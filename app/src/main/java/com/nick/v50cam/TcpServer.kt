package com.nick.v50cam

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One TCP client on 127.0.0.1:27183. H.264 bytes are raw Annex-B.
 * An MJPEG frame is already one wire unit: a lookahead unit, a raw JPEG, or a
 * multipart part that starts with "--v50cam". A new client drops the queued unit.
 * In lookahead mode the first bytes it reads are [JPEG_LOOKAHEAD], then a unit.
 *
 * The hand-off is a single slot. If the client is slow the slot stays full and
 * non-key frames are dropped. After a drop, later P-frames are skipped until the
 * next IDR, because those P-frames would reference the frame we threw away.
 * A new client gets cached SPS/PPS immediately and then nothing until the next IDR.
 * MJPEG frames are marked as key frames, so a slow client gets the newest JPEG
 * and the IDR wait does not apply. H.264 still drops through to the next IDR.
 */
internal class TcpServer(
    private val requestSync: () -> Unit,
    private val onClient: (Boolean) -> Unit,
    private val lookahead: Boolean = false
) {
    val bytesSent = AtomicLong(0)
    val dropped = AtomicLong(0)

    @Volatile
    var clientConnected: Boolean = false
        private set

    private val lock = Object()
    private val client = AtomicReference<Socket?>(null)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var sendThread: Thread? = null

    @Volatile
    private var running = false

    /**
     * MJPEG latency hook. Invoked on the send thread after a frame's bytes are flushed.
     * [writeDurationNs] is the socket write itself, not the time spent queued.
     */
    @Volatile
    var onFrameWritten: ((sensorTimestampNs: Long, writeDurationNs: Long) -> Unit)? = null

    @Volatile
    private var cachedSpsPps: ByteArray? = null

    private var slot: EncodedFrame? = null
    private var headerPending: ByteArray? = null
    private var needIdr = true
    private var syncArmed = false
    /** True after this client has been sent a lookahead prefix. Not used for H.264. */
    private var lookaheadSent = false

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.soTimeout = 500
        ss.bind(InetSocketAddress(InetAddress.getByName(STREAM_HOST), STREAM_PORT), 1)
        server = ss
        running = true
        val accept = Thread({
            try {
                acceptLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "accept thread", t)
            }
        }, "V50Cam-accept")
        val send = Thread({
            try {
                sendLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "send thread", t)
            }
        }, "V50Cam-send")
        accept.isDaemon = true
        send.isDaemon = true
        acceptThread = accept
        sendThread = send
        accept.start()
        send.start()
        Log.i(TAG, "listening on $STREAM_HOST:$STREAM_PORT")
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        // Hold the lock across the gate clear so a new client cannot be published
        // after this returns and before the socket is closed. acceptLoop checks
        // running under the same lock. Closing before join lets a blocked write
        // throw and the send thread exit.
        synchronized(lock) {
            slot = null
            headerPending = null
            lock.notifyAll()
        }
        closeClient()
        acceptThread?.joinQuiet()
        sendThread?.joinQuiet()
        acceptThread = null
        sendThread = null
        closeClient()
        synchronized(lock) {
            slot = null
            headerPending = null
            needIdr = true
            syncArmed = false
            lock.notifyAll()
        }
        if (clientConnected) {
            clientConnected = false
            try {
                onClient(false)
            } catch (_: Exception) {
            }
        }
    }

    private fun closeClient() {
        val current = client.getAndSet(null)
        if (current != null) {
            try {
                current.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Called when a new encoder starts so the next client waits for that encoder's SPS and IDR. */
    fun onEncoderRestart() {
        synchronized(lock) {
            cachedSpsPps = null
            slot = null
            // A connect that has not flushed its prefix yet must keep it. Sending
            // another prefix after a frame unit would open a second, empty JPEG.
            if (lookahead && client.get() != null && !lookaheadSent) {
                headerPending = JPEG_LOOKAHEAD
            } else {
                headerPending = null
            }
            needIdr = true
            syncArmed = false
            lock.notifyAll()
        }
        requestSync()
    }

    fun updateCodecConfig(annexB: ByteArray) {
        synchronized(lock) {
            val first = cachedSpsPps == null
            cachedSpsPps = annexB
            if (first && client.get() != null) {
                headerPending = annexB
                lock.notifyAll()
            }
        }
    }

    /**
     * Called on the encoder thread. Must return immediately.
     * The bytes in [frame] are already a private copy.
     */
    fun offer(frame: EncodedFrame) {
        var askSync = false
        synchronized(lock) {
            if (client.get() == null) return
            if (needIdr && !frame.isKey) {
                val n = dropped.incrementAndGet()
                if (n == 1L || n % 30L == 0L) {
                    Log.i(TAG, "skipping P-frame until IDR, dropped=$n")
                }
                if (!syncArmed) {
                    syncArmed = true
                    askSync = true
                }
                return@synchronized
            }
            val queued = slot
            if (queued != null) {
                if (!frame.isKey) {
                    // One sync request per stall. Asking on every dropped frame makes
                    // the encoder emit an IDR for each one, which is larger, blocks
                    // the socket longer, and drops even more frames.
                    dropped.incrementAndGet()
                    needIdr = true
                    if (!syncArmed) {
                        syncArmed = true
                        askSync = true
                    }
                    return@synchronized
                }
                dropped.incrementAndGet()
                slot = frame
                needIdr = false
                syncArmed = false
                lock.notifyAll()
                return@synchronized
            }
            slot = frame
            if (frame.isKey) {
                needIdr = false
                syncArmed = false
            }
            lock.notifyAll()
        }
        if (askSync) requestSync()
    }

    private fun acceptLoop() {
        val ss = server ?: return
        while (running) {
            val socket = try {
                ss.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed", e)
                break
            }
            try {
                if (!adoptClient(socket)) {
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                    break
                }
            } catch (t: Throwable) {
                Log.e(TAG, "accept client", t)
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                if (!running) break
            }
        }
    }

    /**
     * Publishes [socket] only together with the IDR gate. Doing the swap outside
     * the lock lets a P-frame that was already queued be written to the new client
     * before SPS and before the next IDR.
     *
     * @return false when the server is stopping and the caller must drop [socket].
     */
    private fun adoptClient(socket: Socket): Boolean {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            // Fixed 64KB. The kernel queue is a constant delay, not a growing one.
            // The send thread blocks when it fills, and offer() then drops to an IDR.
            socket.sendBufferSize = 64 * 1024
        } catch (e: Exception) {
            Log.w(TAG, "socket options", e)
        }
        val previous: Socket? = synchronized(lock) {
            if (!running) return false
            val old = client.getAndSet(socket)
            slot = null
            needIdr = true
            syncArmed = true
            lookaheadSent = false
            // Lookahead: 4 bytes before any frame unit. H.264 still queues SPS.
            headerPending = if (lookahead) JPEG_LOOKAHEAD else cachedSpsPps
            lock.notifyAll()
            old
        }
        if (previous != null) {
            try {
                previous.close()
            } catch (_: Exception) {
            }
        }
        clientConnected = true
        Log.i(TAG, "client connected, sps cached=${cachedSpsPps != null}")
        try {
            onClient(true)
        } catch (_: Exception) {
        }
        requestSync()
        return true
    }

    private fun sendLoop() {
        while (running) {
            var socket: Socket?
            var header: ByteArray? = null
            var frame: EncodedFrame? = null
            var sps: ByteArray? = null
            synchronized(lock) {
                while (running && (client.get() == null || (headerPending == null && slot == null))) {
                    try {
                        lock.wait(200)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (!running) return
                socket = client.get()
                if (socket != null) {
                    header = headerPending
                    headerPending = null
                    frame = slot
                    slot = null
                    sps = cachedSpsPps
                    // Mark before the write so a restart cannot queue a second prefix.
                    if (lookahead && (header != null || frame != null)) lookaheadSent = true
                }
            }
            val current = socket ?: continue
            if (header == null && frame == null) continue
            try {
                writePacket(current, header, frame, sps)
            } catch (e: IOException) {
                Log.i(TAG, "client write failed: ${e.message}")
                disconnectIfCurrent(current)
            } catch (t: Throwable) {
                Log.e(TAG, "send failed", t)
                disconnectIfCurrent(current)
            }
        }
    }

    private fun writePacket(
        socket: Socket,
        header: ByteArray?,
        frame: EncodedFrame?,
        sps: ByteArray?
    ) {
        if (header == null && frame == null) return
        val parts = ArrayList<ByteArray>(3)
        if (header != null) parts.add(header)
        if (frame != null) {
            if (!lookahead && frame.isKey && !frame.startsWithSps && sps != null) {
                if (header == null || !header.contentEquals(sps)) parts.add(sps)
            }
            parts.add(frame.data)
        }
        var total = 0
        for (part in parts) total += part.size
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        val started = System.nanoTime()
        val stream = socket.getOutputStream()
        stream.write(out)
        stream.flush()
        val writeNs = System.nanoTime() - started
        bytesSent.addAndGet(out.size.toLong())
        val tookMs = writeNs / 1_000_000L
        if (tookMs > 80) {
            Log.w(TAG, "slow client write ${tookMs}ms (${out.size} bytes)")
        }
        if (frame != null) {
            try {
                onFrameWritten?.invoke(frame.sensorTimestampNs, writeNs)
            } catch (t: Throwable) {
                Log.w(TAG, "frame written callback", t)
            }
        }
    }

    private fun disconnectIfCurrent(socket: Socket) {
        if (!client.compareAndSet(socket, null)) return
        try {
            socket.close()
        } catch (_: Exception) {
        }
        synchronized(lock) {
            slot = null
            needIdr = true
            syncArmed = false
            headerPending = null
            lookaheadSent = false
            lock.notifyAll()
        }
        clientConnected = false
        Log.i(TAG, "client disconnected, still listening")
        try {
            onClient(false)
        } catch (_: Exception) {
        }
    }

    private fun Thread.joinQuiet() {
        try {
            join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
