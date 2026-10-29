package com.nick.v50cam

import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

internal const val TAG = "V50Cam"
internal const val STREAM_PORT = 27183
internal const val STREAM_HOST = "127.0.0.1"

/** What the activity or an `am start` intent asked for. The pipeline may fall back. */
internal data class StreamRequest(
    val cameraId: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateMbps: Int,
    val aeLock: Boolean,
    val afLock: Boolean,
    val codec: String,
    val jpegQuality: Int,
    /** `record`, `preview`, or `zsl`. */
    val template: String = "record",
    /** `off`, `fast`, `hq`, `default`, or empty when the extra was omitted. */
    val edgeMode: String = "",
    /** `off`, `fast`, `hq`, `default`, or empty when the extra was omitted. */
    val nrMode: String = "",
    /** Microseconds. 0 keeps auto exposure. */
    val maxExposureUs: Int = 0,
    /** `sw` (YUV to JPEG) or `hw` (camera JPEG). */
    val jpegMode: String = "sw",
    /** `lookahead` (default), `raw`, or `mpjpeg`. */
    val mux: String = "lookahead"
)

internal data class StreamStatus(
    val streaming: Boolean,
    val clientConnected: Boolean,
    val fps: Double,
    val bitrateMbps: Double,
    val droppedFrames: Long,
    val detail: String,
    val aeLock: Boolean,
    val afLock: Boolean,
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val targetBitrateMbps: Int,
    val cameraLabel: String,
    val codec: String,
    /** `lookahead`, `mpjpeg`, `raw`, or empty when stopped or not MJPEG. */
    val mux: String = ""
) {
    companion object {
        fun stopped(detail: String = "Stopped."): StreamStatus = StreamStatus(
            streaming = false,
            clientConnected = false,
            fps = 0.0,
            bitrateMbps = 0.0,
            droppedFrames = 0,
            detail = detail,
            aeLock = false,
            afLock = false,
            width = 0,
            height = 0,
            targetFps = 0,
            targetBitrateMbps = 0,
            cameraLabel = "",
            codec = ""
        )
    }
}

/**
 * One encoded access unit, already copied out of the codec buffer.
 * [sensorTimestampNs] is [android.media.Image.getTimestamp] for MJPEG and 0 for H.264.
 */
internal class EncodedFrame(
    val data: ByteArray,
    val isKey: Boolean,
    val startsWithSps: Boolean,
    val sensorTimestampNs: Long = 0L
)

/** H.264 and MJPEG both hand a [Surface] to Camera2 and frames to [TcpServer]. */
internal interface StreamEncoder {
    val framesProduced: AtomicLong
    val summary: String
    fun start(): Surface
    fun stop()
    fun requestSync()
}

/** `h264` or `mjpeg`. Anything else, including a missing extra, is MJPEG. */
internal fun normalizeCodec(raw: String?): String {
    return if (raw?.trim()?.equals("h264", ignoreCase = true) == true) "h264" else "mjpeg"
}

/** `preview`, `zsl`, or `record` (anything else, including a missing extra). */
internal fun normalizeTemplate(raw: String?): String {
    return when (raw?.trim()?.lowercase(java.util.Locale.US)) {
        "preview" -> "preview"
        "zsl" -> "zsl"
        else -> "record"
    }
}

/** `off`, `fast`, `hq`, or `default`. Anything else is `fast`. */
internal fun normalizeEdgeNr(raw: String?): String {
    return when (raw?.trim()?.lowercase(java.util.Locale.US)) {
        "off" -> "off"
        "hq", "high", "highquality" -> "hq"
        "default", "unset" -> "default"
        else -> "fast"
    }
}

/** `hw` or `sw`. Anything else, including a missing extra, is `sw`. */
internal fun normalizeJpeg(raw: String?): String {
    return if (raw?.trim()?.equals("hw", ignoreCase = true) == true) "hw" else "sw"
}

/**
 * SOI plus the first two bytes of a COM marker: FF D8 FF FE.
 * Lookahead mode sends this once when a client connects, and again at the end of every frame unit.
 */
internal val JPEG_LOOKAHEAD = byteArrayOf(
    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFE.toByte()
)

/** `lookahead`, `raw`, or `mpjpeg`. Anything else, including a missing extra, is `lookahead`. */
internal fun normalizeMux(raw: String?): String {
    return when (raw?.trim()?.lowercase(java.util.Locale.US)) {
        "raw" -> "raw"
        "mpjpeg" -> "mpjpeg"
        else -> "lookahead"
    }
}
