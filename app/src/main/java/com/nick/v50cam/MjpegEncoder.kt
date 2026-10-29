package com.nick.v50cam

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Camera2 ImageReader (YUV_420_888) to JPEG on a background thread.
 *
 * Each frame is one JPEG, SOI through EOI, with no EXIF and no rotation.
 * lookahead (the default) and mpjpeg pack each JPEG into one wire unit before it
 * is offered, so [TcpServer] replaces and writes that whole unit as one frame.
 * raw offers the JPEG bytes unchanged.
 * Frames are marked as key frames so [TcpServer] keeps the newest one.
 * The listener only grabs the latest image. Compression runs on its own thread
 * and drops a frame when it is still behind.
 */
internal class MjpegEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val quality: Int,
    private val hardwareJpeg: Boolean,
    private val timestampRealtime: Boolean,
    private val mux: String,
    private val onFrame: (EncodedFrame) -> Unit,
    private val onError: (Throwable) -> Unit
) : StreamEncoder {
    override val framesProduced = AtomicLong(0)

    @Volatile
    override var summary: String = ""
        private set

    private val lock = Object()
    private val jpegQuality = quality.coerceIn(1, 100)
    private val jpegOut = ByteArrayOutputStream(256 * 1024)

    private class Held(val image: Image, val sensorTs: Long, val availAgeNs: Long)

    private var reader: ImageReader? = null
    private var listenerThread: HandlerThread? = null
    private var compressThread: Thread? = null
    private var pending: Held? = null
    private var nv21 = ByteArray(0)
    private var uRow = ByteArray(0)
    private var vRow = ByteArray(0)

    @Volatile
    private var running = false

    private val failed = AtomicBoolean(false)
    private val statLock = Any()

    private var statWindowStart = 0L
    private var statFrames = 0
    private var statBytes = 0L
    private var statCompressNs = 0L
    private var statConvertNs = 0L
    private var statJpegNs = 0L
    private var statAvailNs = 0L
    private var statAvailFrames = 0
    private var statEncNs = 0L
    private var statEncFrames = 0
    private var statSentNs = 0L
    private var statSentFrames = 0
    private var statWriteNs = 0L
    private var statWriteFrames = 0
    private var slowWindows = 0
    private var loggedSlow = false
    private var loggedPath = false

    override fun start(): Surface {
        val thread = HandlerThread("V50Cam-jpeg")
        thread.start()
        listenerThread = thread
        val handler = Handler(thread.looper)
        val format = if (hardwareJpeg) ImageFormat.JPEG else ImageFormat.YUV_420_888
        val images = try {
            ImageReader.newInstance(width, height, format, 3)
        } catch (t: Throwable) {
            thread.quitSafely()
            listenerThread = null
            val kind = if (hardwareJpeg) "JPEG" else "YUV"
            throw IllegalStateException("$kind ImageReader rejected ${width}x$height", t)
        }
        images.setOnImageAvailableListener({ source -> onImage(source) }, handler)
        reader = images
        running = true
        statWindowStart = SystemClock.elapsedRealtime()
        val worker = Thread({
            try {
                compressLoop()
            } catch (t: Throwable) {
                if (running) fail(t)
            }
        }, "V50Cam-jpeg-compress")
        worker.isDaemon = true
        compressThread = worker
        worker.start()
        val path = if (hardwareJpeg) "hw-jpeg" else "sw"
        summary = "MJPEG $path q=$jpegQuality ${width}x${height}@$fps"
        Log.i(TAG, "encoder $summary")
        if (hardwareJpeg) Log.i(TAG, "mjpeg yuv path=hw-jpeg ${width}x$height")
        return images.surface
    }

    override fun requestSync() {
        // Every JPEG is a keyframe. There is no IDR to request.
    }

    override fun stop() {
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
        val images = reader
        reader = null
        try {
            images?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }
        val worker = compressThread
        compressThread = null
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) Log.e(TAG, "jpeg thread did not exit")
        }
        synchronized(lock) {
            pending?.image?.closeQuiet()
            pending = null
        }
        try {
            images?.close()
        } catch (_: Exception) {
        }
        val thread = listenerThread
        listenerThread = null
        thread?.quitSafely()
    }

    private fun onImage(source: ImageReader) {
        if (!running) {
            try {
                source.acquireLatestImage()?.closeQuiet()
            } catch (_: Exception) {
            }
            return
        }
        val image = try {
            source.acquireLatestImage()
        } catch (t: Throwable) {
            if (running) fail(t)
            return
        } ?: return
        val sensorTs = image.timestamp
        val availAgeNs = if (timestampRealtime && sensorTs > 0L) {
            val age = SystemClock.elapsedRealtimeNanos() - sensorTs
            if (age >= 0L) age else -1L
        } else {
            -1L
        }
        val held = Held(image, sensorTs, availAgeNs)
        val drop = synchronized(lock) {
            if (!running) {
                true
            } else {
                val previous = pending
                pending = held
                lock.notifyAll()
                previous?.image?.closeQuiet()
                false
            }
        }
        if (drop) image.closeQuiet()
    }

    private fun takeHeld(): Held? {
        synchronized(lock) {
            while (running && pending == null) {
                try {
                    lock.wait(200)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            if (!running) return null
            val held = pending
            pending = null
            return held
        }
    }

    private fun compressLoop() {
        while (running) {
            val held = takeHeld() ?: continue
            val image = held.image
            try {
                if (!running) continue
                val jpeg: ByteArray
                val convertNs: Long
                val jpegNs: Long
                if (hardwareJpeg) {
                    val t0 = System.nanoTime()
                    jpeg = extractJpeg(image)
                    convertNs = 0L
                    jpegNs = System.nanoTime() - t0
                } else {
                    val t0 = System.nanoTime()
                    val nv = yuv420ToNv21(image)
                    val t1 = System.nanoTime()
                    jpeg = compressNv21(nv, image.width, image.height)
                    val t2 = System.nanoTime()
                    convertNs = t1 - t0
                    jpegNs = t2 - t1
                }
                val encAgeNs = if (timestampRealtime && held.sensorTs > 0L) {
                    val age = SystemClock.elapsedRealtimeNanos() - held.sensorTs
                    if (age >= 0L) age else -1L
                } else {
                    -1L
                }
                val wired = wireFrame(jpeg) ?: continue
                framesProduced.incrementAndGet()
                onFrame(
                    EncodedFrame(
                        data = wired,
                        isKey = true,
                        startsWithSps = false,
                        sensorTimestampNs = held.sensorTs
                    )
                )
                noteEncode(jpeg.size, convertNs, jpegNs, held.availAgeNs, encAgeNs)
            } catch (t: Throwable) {
                if (running) {
                    running = false
                    fail(t)
                }
                return
            } finally {
                image.closeQuiet()
            }
        }
    }

    /**
     * One frame for the socket slot. raw is the JPEG. mpjpeg is the multipart part.
     * lookahead drops the SOI: the stream already ends in [JPEG_LOOKAHEAD] (FF D8 FF FE),
     * this unit is `00 04 00 00` plus the JPEG after its SOI, then another [JPEG_LOOKAHEAD].
     * The decoder therefore sees FF D8 FF FE 00 04 00 00 and the original JPEG tail.
     * Returns null when lookahead sees a buffer that does not start with FF D8.
     */
    private fun wireFrame(jpeg: ByteArray): ByteArray? {
        if (mux == "raw") return jpeg
        if (mux == "mpjpeg") return mpjpegPart(jpeg)
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            Log.w(TAG, "mjpeg lookahead skip: no SOI (${jpeg.size} bytes)")
            return null
        }
        val rest = jpeg.size - 2
        val out = ByteArray(4 + rest + JPEG_LOOKAHEAD.size)
        out[0] = 0x00
        out[1] = 0x04
        out[2] = 0x00
        out[3] = 0x00
        System.arraycopy(jpeg, 2, out, 4, rest)
        System.arraycopy(JPEG_LOOKAHEAD, 0, out, 4 + rest, JPEG_LOOKAHEAD.size)
        return out
    }

    private fun mpjpegPart(jpeg: ByteArray): ByteArray {
        val header = (
            "--v50cam\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val out = ByteArray(header.size + jpeg.size + 2)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(jpeg, 0, out, header.size, jpeg.size)
        out[out.size - 2] = '\r'.code.toByte()
        out[out.size - 1] = '\n'.code.toByte()
        return out
    }

    /** Send thread reports a finished socket write. [writeDurationNs] is the write itself. */
    fun onFrameWritten(sensorTimestampNs: Long, writeDurationNs: Long) {
        val sentAge = if (timestampRealtime && sensorTimestampNs > 0L) {
            val age = SystemClock.elapsedRealtimeNanos() - sensorTimestampNs
            if (age >= 0L) age else -1L
        } else {
            -1L
        }
        synchronized(statLock) {
            statWriteFrames++
            statWriteNs += writeDurationNs
            if (sentAge >= 0L) {
                statSentFrames++
                statSentNs += sentAge
            }
        }
    }

    private fun noteEncode(size: Int, convertNs: Long, jpegNs: Long, availAgeNs: Long, encAgeNs: Long) {
        val line: String? = synchronized(statLock) {
            statFrames++
            statBytes += size.toLong()
            statConvertNs += convertNs
            statJpegNs += jpegNs
            statCompressNs += convertNs + jpegNs
            if (availAgeNs >= 0L) {
                statAvailFrames++
                statAvailNs += availAgeNs
            }
            if (encAgeNs >= 0L) {
                statEncFrames++
                statEncNs += encAgeNs
            }
            val now = SystemClock.elapsedRealtime()
            val window = now - statWindowStart
            if (window < 1000L) return@synchronized null
            val frames = statFrames
            val seconds = window / 1000.0
            val fpsSent = frames / seconds
            val avgKb = if (frames > 0) (statBytes.toDouble() / frames) / 1024.0 else 0.0
            val compressMs = ms(statCompressNs, frames)
            val convertMs = ms(statConvertNs, frames)
            val jpegMs = ms(statJpegNs, frames)
            val availMs = ms(statAvailNs, statAvailFrames)
            val encMs = ms(statEncNs, statEncFrames)
            val sentMs = ms(statSentNs, statSentFrames)
            val writeMs = ms(statWriteNs, statWriteFrames)
            statFrames = 0
            statBytes = 0L
            statCompressNs = 0L
            statConvertNs = 0L
            statJpegNs = 0L
            statAvailNs = 0L
            statAvailFrames = 0
            statEncNs = 0L
            statEncFrames = 0
            statSentNs = 0L
            statSentFrames = 0
            statWriteNs = 0L
            statWriteFrames = 0
            statWindowStart = now
            if (hardwareJpeg) noteHwFps(fpsSent)
            formatStats(fpsSent, avgKb, compressMs, convertMs, jpegMs, availMs, encMs, sentMs, writeMs)
        }
        if (line != null) Log.i(TAG, line)
    }

    private fun ms(ns: Long, n: Int): Double {
        return if (n > 0) (ns.toDouble() / n) / 1_000_000.0 else -1.0
    }

    private fun formatStats(
        fpsSent: Double,
        avgKb: Double,
        compressMs: Double,
        convertMs: Double,
        jpegMs: Double,
        availMs: Double,
        encMs: Double,
        sentMs: Double,
        writeMs: Double
    ): String {
        val base = "mjpeg fps=%.1f avg=%.1fKB compress=%.1fms convert=%.1fms jpeg=%.1fms"
        return if (timestampRealtime) {
            String.format(
                java.util.Locale.US,
                "$base avail=%.1fms enc=%.1fms sent=%.1fms write=%.1fms",
                fpsSent, avgKb, compressMs, convertMs, jpegMs, availMs, encMs, sentMs, writeMs
            )
        } else {
            String.format(
                java.util.Locale.US,
                "$base write=%.1fms",
                fpsSent, avgKb, compressMs, convertMs, jpegMs, writeMs
            )
        }
    }

    private fun noteHwFps(fpsSent: Double) {
        if (fpsSent < fps * 0.5) slowWindows++ else slowWindows = 0
        if (!loggedSlow && slowWindows >= 2) {
            loggedSlow = true
            Log.w(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "jpeg hw fps dropped: %.1f vs target %d (repeating JPEG may be stalling)",
                    fpsSent,
                    fps
                )
            )
        }
    }

    private fun compressNv21(nv: ByteArray, w: Int, h: Int): ByteArray {
        jpegOut.reset()
        val yuv = YuvImage(nv, ImageFormat.NV21, w, h, null)
        val ok = yuv.compressToJpeg(Rect(0, 0, w, h), jpegQuality, jpegOut)
        if (!ok) throw IllegalStateException("compressToJpeg failed")
        return jpegOut.toByteArray()
    }

    /** Camera JPEG bytes, trimmed to the EOI marker when the buffer is padded. */
    private fun extractJpeg(image: Image): ByteArray {
        val buf = image.planes[0].buffer.duplicate()
        val n = buf.remaining()
        if (n <= 0) throw IllegalStateException("empty jpeg")
        val bytes = ByteArray(n)
        buf.get(bytes)
        var i = n - 2
        while (i >= 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) {
                val end = i + 2
                return if (end == n) bytes else bytes.copyOf(end)
            }
            i--
        }
        return bytes
    }

    /** NV21, no rotation. Handles rowStride and pixelStride, including tight semi-planar copies. */
    private fun yuv420ToNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val needed = w * h * 3 / 2
        if (nv21.size != needed) nv21 = ByteArray(needed)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        copyLuma(y, nv21, w, h)
        copyChroma(u, v, nv21, w * h, w / 2, h / 2)
        if (!loggedPath) {
            loggedPath = true
            Log.i(
                TAG,
                "mjpeg yuv path=bulk yStride=${y.rowStride}/${y.pixelStride} " +
                    "uStride=${u.rowStride}/${u.pixelStride} vStride=${v.rowStride}/${v.pixelStride}"
            )
        }
        return nv21
    }

    private fun copyLuma(plane: Image.Plane, out: ByteArray, width: Int, height: Int) {
        val buf = plane.buffer.duplicate()
        val start = buf.position()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == width) {
            buf.get(out, 0, width * height)
            return
        }
        if (pixelStride == 1) {
            var dst = 0
            for (row in 0 until height) {
                buf.position(start + row * rowStride)
                buf.get(out, dst, width)
                dst += width
            }
            return
        }
        for (row in 0 until height) {
            val rowStart = start + row * rowStride
            for (col in 0 until width) {
                out[row * width + col] = buf.get(rowStart + col * pixelStride)
            }
        }
    }

    /**
     * Bulk-copies each chroma row, then interleaves V,U into NV21.
     * The last row is often shorter than rowStride: a pixelStride of 2 ends at width-1 bytes.
     */
    private fun copyChroma(
        u: Image.Plane,
        v: Image.Plane,
        out: ByteArray,
        outPos: Int,
        cols: Int,
        rows: Int
    ) {
        if (cols <= 0 || rows <= 0) return
        val uBuf = u.buffer.duplicate()
        val vBuf = v.buffer.duplicate()
        val uBase = uBuf.position()
        val vBase = vBuf.position()
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()
        val uPix = u.pixelStride.coerceAtLeast(1)
        val vPix = v.pixelStride.coerceAtLeast(1)
        val uStride = u.rowStride
        val vStride = v.rowStride
        val uNeed = (cols - 1) * uPix + 1
        val vNeed = (cols - 1) * vPix + 1
        if (uRow.size < uNeed) uRow = ByteArray(uNeed)
        if (vRow.size < vNeed) vRow = ByteArray(vNeed)
        var dst = outPos
        for (row in 0 until rows) {
            val uSrc = uBase + row * uStride
            val vSrc = vBase + row * vStride
            val uRead = (uLimit - uSrc).coerceAtLeast(0).coerceAtMost(uNeed)
            val vRead = (vLimit - vSrc).coerceAtLeast(0).coerceAtMost(vNeed)
            if (uRead > 0) {
                uBuf.position(uSrc)
                uBuf.get(uRow, 0, uRead)
            }
            if (vRead > 0) {
                vBuf.position(vSrc)
                vBuf.get(vRow, 0, vRead)
            }
            var col = 0
            while (col < cols) {
                val ui = col * uPix
                val vi = col * vPix
                out[dst++] = if (vi < vRead) vRow[vi] else 0
                out[dst++] = if (ui < uRead) uRow[ui] else 0
                col++
            }
        }
    }

    private fun fail(t: Throwable) {
        if (!failed.compareAndSet(false, true)) return
        Log.e(TAG, "mjpeg encode failed", t)
        try {
            onError(t)
        } catch (_: Throwable) {
        }
    }

    private fun Image.closeQuiet() {
        try {
            close()
        } catch (_: Exception) {
        }
    }
}
