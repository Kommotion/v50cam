package com.nick.v50cam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 encoder fed by its input Surface. The drain thread only copies
 * bytes out and hands them off. It never writes the socket.
 *
 * Settings that keep latency flat: Baseline (no B-frames), CBR when the encoder
 * allows it, I-frame every 1s, KEY_LATENCY=1, realtime priority, no repeated
 * frames. A sync frame can be requested without waiting for that 1s interval.
 */
internal class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (EncodedFrame) -> Unit,
    private val onError: (Throwable) -> Unit
) : StreamEncoder {
    override val framesProduced = AtomicLong(0)

    @Volatile
    override var summary: String = ""
        private set

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null

    @Volatile
    private var drainRunning = false

    @Volatile
    private var syncRequested = false

    private var lastSyncNs = 0L

    private val partial = java.io.ByteArrayOutputStream()
    private var partialFlags = 0

    override fun start(): Surface {
        AnnexB.selfCheck()
        val info = selectEncoder()
        val caps = info.getCapabilitiesForType(MIME)
        val bitrateMode = pickBitrateMode(caps)
        val profileLevel = pickProfileLevel(caps)
        val qcom = info.name.contains("qcom", ignoreCase = true) ||
            info.name.contains("qti", ignoreCase = true)

        val attempts = ArrayList<Pair<String, MediaFormat>>(5)
        fun addAttempt(label: String, format: MediaFormat) {
            if (attempts.any { it.second.toString() == format.toString() }) return
            attempts.add(label to format)
        }
        // Vendors reject different keys. Each step drops the keys that fail
        // configure() on real devices, and the codec from a failed attempt is
        // released before the next one is created.
        addAttempt(
            "full",
            buildFormat(bitrateMode, profileLevel, qcomLowLatency = qcom, lowLatencyKeys = true, realtimeKeys = true)
        )
        addAttempt(
            "no-vendor",
            buildFormat(bitrateMode, profileLevel, qcomLowLatency = false, lowLatencyKeys = true, realtimeKeys = true)
        )
        addAttempt(
            "no-profile",
            buildFormat(bitrateMode, null, qcomLowLatency = false, lowLatencyKeys = true, realtimeKeys = true)
        )
        addAttempt(
            "no-latency-keys",
            buildFormat(bitrateMode, null, qcomLowLatency = false, lowLatencyKeys = false, realtimeKeys = true)
        )
        addAttempt(
            "minimal",
            buildFormat(null, null, qcomLowLatency = false, lowLatencyKeys = false, realtimeKeys = false)
        )
        var lastError: Exception? = null
        var configured: MediaCodec? = null
        var usedFormat: MediaFormat? = null
        var usedLabel = ""
        for ((label, format) in attempts) {
            val candidate = MediaCodec.createByCodecName(info.name)
            try {
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configured = candidate
                usedFormat = format
                usedLabel = label
                if (label != "full") Log.w(TAG, "encoder configure fell back to $label")
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "encoder configure failed ($label)", e)
                try {
                    candidate.release()
                } catch (_: Exception) {
                }
            }
        }
        val encoder = configured ?: throw (lastError ?: IllegalStateException("encoder configure failed"))
        val format = usedFormat ?: throw IllegalStateException("encoder configure failed")
        val input = try {
            encoder.createInputSurface()
        } catch (t: Throwable) {
            try {
                encoder.release()
            } catch (_: Exception) {
            }
            throw t
        }
        try {
            encoder.start()
        } catch (t: Throwable) {
            try {
                input.release()
            } catch (_: Exception) {
            }
            try {
                encoder.release()
            } catch (_: Exception) {
            }
            throw t
        }
        codec = encoder
        surface = input
        val modeLabel = when {
            !format.containsKey(MediaFormat.KEY_BITRATE_MODE) -> "default"
            format.getInteger(MediaFormat.KEY_BITRATE_MODE) == EncoderCapabilities.BITRATE_MODE_CBR -> "CBR"
            format.getInteger(MediaFormat.KEY_BITRATE_MODE) == EncoderCapabilities.BITRATE_MODE_VBR -> "VBR"
            else -> "default"
        }
        val profileLabel = if (format.containsKey(MediaFormat.KEY_PROFILE)) {
            val level = if (format.containsKey(MediaFormat.KEY_LEVEL)) {
                levelName(format.getInteger(MediaFormat.KEY_LEVEL))
            } else {
                "?"
            }
            " profile=${profileName(format.getInteger(MediaFormat.KEY_PROFILE))} level=$level"
        } else {
            " profile=encoder-default"
        }
        val fallback = if (usedLabel == "full") "" else " fallback=$usedLabel"
        summary = "${info.name} $modeLabel$profileLabel ${width}x${height}@$fps ${bitrateBps / 1_000_000}Mbps$fallback"
        Log.i(TAG, "encoder $summary")
        drainRunning = true
        val thread = Thread({
            try {
                drainLoop()
            } catch (t: Throwable) {
                if (drainRunning) fail(t)
            }
        }, "V50Cam-encoder")
        thread.isDaemon = true
        drainThread = thread
        thread.start()
        return input
    }

    /** Coalesced by the drain thread to one request per 200ms. Safe from any thread. */
    override fun requestSync() {
        syncRequested = true
    }

    override fun stop() {
        drainRunning = false
        val thread = drainThread
        drainThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) Log.e(TAG, "encoder drain thread did not exit")
        }
        val current = codec
        codec = null
        if (current != null) {
            try {
                current.stop()
            } catch (_: Exception) {
            }
            try {
                current.release()
            } catch (_: Exception) {
            }
        }
        val input = surface
        surface = null
        if (input != null) {
            try {
                input.release()
            } catch (_: Exception) {
            }
        }
        partial.reset()
        partialFlags = 0
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (drainRunning) {
            val current = codec ?: break
            if (syncRequested) {
                val now = System.nanoTime()
                // Callers can ask on every dropped frame. One IDR per interval is enough;
                // a request that arrives early stays set and fires when the interval ends.
                if (now - lastSyncNs >= SYNC_MIN_INTERVAL_NS) {
                    syncRequested = false
                    lastSyncNs = now
                    try {
                        val bundle = Bundle()
                        bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        current.setParameters(bundle)
                        Log.i(TAG, "requested sync frame")
                    } catch (t: Throwable) {
                        Log.w(TAG, "sync request failed", t)
                    }
                }
            }
            val index = try {
                current.dequeueOutputBuffer(info, 10_000)
            } catch (t: Throwable) {
                if (drainRunning) fail(t)
                break
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    try {
                        cacheFormat(current.outputFormat)
                    } catch (t: Throwable) {
                        Log.w(TAG, "output format", t)
                    }
                }
                index >= 0 -> {
                    try {
                        val buffer = current.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            handle(bytes, info.flags)
                        }
                    } catch (t: Throwable) {
                        if (drainRunning) fail(t)
                        try {
                            current.releaseOutputBuffer(index, false)
                        } catch (_: Exception) {
                        }
                        break
                    }
                    try {
                        current.releaseOutputBuffer(index, false)
                    } catch (t: Throwable) {
                        if (drainRunning) fail(t)
                        break
                    }
                }
            }
        }
    }

    private fun cacheFormat(format: MediaFormat) {
        val annex = AnnexB.fromCodecConfig(format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1"))
        if (annex != null) {
            Log.i(TAG, "cached codec config from output format, ${annex.size} bytes")
            onConfig(annex)
        }
    }

    private fun handle(chunk: ByteArray, flags: Int) {
        if (flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0) {
            partial.write(chunk)
            partialFlags = partialFlags or flags
            return
        }
        val full: ByteArray
        val fullFlags: Int
        if (partial.size() > 0) {
            partial.write(chunk)
            full = partial.toByteArray()
            partial.reset()
            fullFlags = partialFlags or flags
            partialFlags = 0
        } else {
            full = chunk
            fullFlags = flags
        }
        if (fullFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && full.isEmpty()) return
        val annex = if (fullFlags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            AnnexB.fromCodecConfigBytes(full) ?: AnnexB.accessUnit(full)
        } else {
            AnnexB.accessUnit(full)
        }
        val params = AnnexB.parameterSetsOnly(annex)
        if (params != null) {
            onConfig(params)
        } else if (fullFlags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            onConfig(annex)
        }
        val isKey = (fullFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) ||
            AnnexB.containsNalType(annex, 5)
        if ((fullFlags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) && !isKey) return
        if (!isKey && params != null && params.size == annex.size) return
        framesProduced.incrementAndGet()
        onFrame(EncodedFrame(annex, isKey, AnnexB.startsWithSps(annex)))
    }

    private fun fail(t: Throwable) {
        drainRunning = false
        Log.e(TAG, "encoder drain failed", t)
        try {
            onError(t)
        } catch (_: Exception) {
        }
    }

    private fun buildFormat(
        bitrateMode: Int?,
        profileLevel: Pair<Int, Int>?,
        qcomLowLatency: Boolean,
        lowLatencyKeys: Boolean,
        realtimeKeys: Boolean
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        // Seconds, not frames. 1 keeps recovery under a second without an IDR every frame.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (realtimeKeys) {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (bitrateMode != null) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
        }
        if (lowLatencyKeys && Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        if (lowLatencyKeys && Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        if (profileLevel != null) {
            format.setInteger(MediaFormat.KEY_PROFILE, profileLevel.first)
            format.setInteger(MediaFormat.KEY_LEVEL, profileLevel.second)
        }
        // Do not set KEY_REPEAT_PREVIOUS_FRAME_AFTER. Duplicate frames would
        // hide a stall and make the latency look smoother than it is.
        if (qcomLowLatency && lowLatencyKeys) {
            format.setInteger("vendor.qti-ext-enc-low-latency.enable", 1)
        }
        return format
    }

    private fun pickBitrateMode(caps: CodecCapabilities): Int? {
        val enc = caps.encoderCapabilities ?: return null
        return when {
            enc.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR) ->
                EncoderCapabilities.BITRATE_MODE_CBR
            enc.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_VBR) ->
                EncoderCapabilities.BITRATE_MODE_VBR
            else -> null
        }
    }

    private fun pickProfileLevel(caps: CodecCapabilities): Pair<Int, Int>? {
        val wantedLevel = chooseLevel(width, height, fps)
        val levels = caps.profileLevels ?: return null
        val baseline = levels.filter { it.profile == CodecProfileLevel.AVCProfileBaseline }
        if (baseline.any { it.level >= wantedLevel }) {
            return CodecProfileLevel.AVCProfileBaseline to wantedLevel
        }
        // Same value as CodecProfileLevel.AVCProfileConstrainedBaseline (API 27).
        // Compared as an int so API 26 does not reference the newer field.
        val constrained = levels.filter { it.profile == AVC_CONSTRAINED_BASELINE }
        if (constrained.any { it.level >= wantedLevel }) {
            return AVC_CONSTRAINED_BASELINE to wantedLevel
        }
        return null
    }

    private fun selectEncoder(): MediaCodecInfo {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hardware = list.codecInfos.filter { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                !isSoftware(info) &&
                supportsSurface(info)
        }
        val pool = if (hardware.isNotEmpty()) {
            hardware
        } else {
            Log.w(TAG, "no hardware AVC encoder found, falling back")
            list.codecInfos.filter { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                    supportsSurface(info)
            }
        }
        if (pool.isEmpty()) throw IllegalStateException("No H.264 encoder on this device")
        return pool.minBy { score(it.name) }
    }

    private fun supportsSurface(info: MediaCodecInfo): Boolean {
        return try {
            val caps = info.getCapabilitiesForType(MIME)
            caps.colorFormats.contains(CodecCapabilities.COLOR_FormatSurface)
        } catch (_: Exception) {
            false
        }
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean {
        val name = info.name.lowercase()
        if (name.startsWith("omx.google.") || name.startsWith("c2.android.")) return true
        if (Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly) return true
        return false
    }

    private fun score(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("qcom") || n.contains("qti") -> 0
            n.contains("exynos") || n.contains("mtk") || n.contains("intel") -> 1
            else -> 2
        }
    }

    private fun profileName(profile: Int): String = when (profile) {
        CodecProfileLevel.AVCProfileBaseline -> "baseline"
        AVC_CONSTRAINED_BASELINE -> "constrained-baseline"
        else -> profile.toString()
    }

    private fun levelName(level: Int): String = when (level) {
        CodecProfileLevel.AVCLevel31 -> "3.1"
        CodecProfileLevel.AVCLevel4 -> "4"
        CodecProfileLevel.AVCLevel42 -> "4.2"
        CodecProfileLevel.AVCLevel5 -> "5"
        else -> level.toString()
    }

    companion object {
        private const val MIME = "video/avc"

        /** CodecProfileLevel.AVCProfileConstrainedBaseline, added as a field in API 27. */
        private const val AVC_CONSTRAINED_BASELINE = 0x10000

        /** Floor between PARAMETER_KEY_REQUEST_SYNC_FRAME calls. */
        private const val SYNC_MIN_INTERVAL_NS = 200_000_000L

        private fun chooseLevel(width: Int, height: Int, fps: Int): Int {
            val longEdge = maxOf(width, height)
            return when {
                longEdge >= 1920 && fps > 30 -> CodecProfileLevel.AVCLevel42
                longEdge >= 1920 -> CodecProfileLevel.AVCLevel4
                fps > 30 -> CodecProfileLevel.AVCLevel4
                else -> CodecProfileLevel.AVCLevel31
            }
        }
    }
}
