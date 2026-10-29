package com.nick.v50cam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.os.Build
import android.util.Size
import java.util.Locale

internal data class Combo(
    val width: Int,
    val height: Int,
    val fps: Int,
    val supported: Boolean,
    val reason: String
)

internal data class CamInfo(
    val id: String,
    val label: String,
    val facing: Int,
    val sensorArea: Float,
    val inCameraIdList: Boolean,
    val combos: List<Combo>
)

internal object CameraCatalog {
    val CANDIDATES = listOf(
        Triple(1920, 1080, 60),
        Triple(1920, 1080, 30),
        Triple(1280, 720, 60),
        Triple(1280, 720, 30)
    )

    fun list(context: Context): List<CamInfo> {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return emptyList()
        val openable = try {
            manager.cameraIdList.toList()
        } catch (_: Exception) {
            emptyList()
        }
        val out = ArrayList<CamInfo>()
        val seen = HashSet<String>()
        for (id in openable) {
            val info = describe(manager, id, inList = true, physical = false) ?: continue
            out.add(info)
            seen.add(id)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            for (id in openable) {
                val chars = try {
                    manager.getCameraCharacteristics(id)
                } catch (_: Exception) {
                    continue
                }
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?: continue
                val logical = caps.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                )
                if (!logical) continue
                val physical = try {
                    chars.physicalCameraIds
                } catch (_: Exception) {
                    emptySet()
                }
                for (pid in physical) {
                    if (!seen.add(pid)) continue
                    val info = describe(manager, pid, inList = false, physical = true) ?: continue
                    out.add(info)
                }
            }
        }
        return out.sortedWith(
            compareBy<CamInfo> { facingOrder(it.facing) }
                .thenByDescending { it.sensorArea }
                .thenBy { it.id }
        )
    }

    fun defaultCamera(cameras: List<CamInfo>): CamInfo? {
        return cameras.firstOrNull { it.inCameraIdList && it.facing == CameraMetadata.LENS_FACING_BACK }
            ?: cameras.firstOrNull { it.inCameraIdList }
            ?: cameras.firstOrNull()
    }

    fun pick(combos: List<Combo>, wantW: Int, wantH: Int, wantFps: Int): Combo? {
        val supported = combos.filter { it.supported }
        if (supported.isEmpty()) return null
        supported.firstOrNull { it.width == wantW && it.height == wantH && it.fps == wantFps }
            ?.let { return it }
        supported.firstOrNull { it.width == wantW && it.height == wantH && it.fps == 30 }
            ?.let { return it }
        supported.firstOrNull { it.width == wantW && it.height == wantH }?.let { return it }
        supported.firstOrNull { it.fps == 30 && it.width == 1280 }?.let { return it }
        supported.firstOrNull { it.fps == wantFps }?.let { return it }
        return supported.first()
    }

    fun fallbackNote(combos: List<Combo>, wantW: Int, wantH: Int, wantFps: Int, chosen: Combo): String? {
        if (chosen.width == wantW && chosen.height == wantH && chosen.fps == wantFps) return null
        val why = combos.firstOrNull {
            it.width == wantW && it.height == wantH && it.fps == wantFps
        }?.reason ?: "that mode is not available"
        return "Asked for ${wantH}p at $wantFps fps. $why Using ${chosen.height}p at ${chosen.fps} fps instead."
    }

    fun supportText(combos: List<Combo>): String {
        val missing = combos.filter { !it.supported }
        if (missing.isEmpty()) {
            return "720p and 1080p at 30 and 60 fps are available on this camera."
        }
        return missing.joinToString("\n") { c ->
            "${c.height}p at ${c.fps} fps: ${c.reason}"
        }
    }

    fun evaluate(chars: CameraCharacteristics): List<Combo> {
        return CANDIDATES.map { (w, h, fps) -> evaluateOne(chars, w, h, fps) }
    }

    private fun describe(
        manager: CameraManager,
        id: String,
        inList: Boolean,
        physical: Boolean
    ): CamInfo? {
        val chars = try {
            manager.getCameraCharacteristics(id)
        } catch (_: Exception) {
            return null
        }
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
            ?: CameraMetadata.LENS_FACING_EXTERNAL
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalText = if (focals == null || focals.isEmpty()) {
            "focal n/a"
        } else {
            focals.joinToString(", ") { String.format(Locale.US, "%.1f mm", it) }
        }
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val area = if (size != null) size.width * size.height else 0f
        val facingText = when (facing) {
            CameraMetadata.LENS_FACING_BACK -> "back"
            CameraMetadata.LENS_FACING_FRONT -> "front"
            CameraMetadata.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        val physicalText = if (physical && !inList) " · physical" else ""
        val label = "id $id · $facingText · $focalText$physicalText"
        return CamInfo(
            id = id,
            label = label,
            facing = facing,
            sensorArea = area,
            inCameraIdList = inList,
            combos = evaluate(chars)
        )
    }

    private fun evaluateOne(chars: CameraCharacteristics, width: Int, height: Int, fps: Int): Combo {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Combo(width, height, fps, false, "This camera has no stream configuration.")
        if (!sizeSupported(map, width, height)) {
            return Combo(
                width, height, fps, false,
                "${width}x${height} is not a camera output size."
            )
        }
        val minNs = minFrameDurationNs(map, width, height)
        if (minNs != null && minNs > 0L) {
            val maxFps = 1_000_000_000.0 / minNs.toDouble()
            if (maxFps + 0.5 < fps) {
                val ms = minNs / 1_000_000.0
                return Combo(
                    width, height, fps, false,
                    String.format(
                        Locale.US,
                        "%dx%d cannot do %d fps (shortest frame is %.1f ms, about %d fps).",
                        width, height, fps, ms, maxFps.toInt()
                    )
                )
            }
        }
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val hasFixed = ranges?.any { it.lower == fps && it.upper == fps } == true
        if (!hasFixed) {
            val shown = if (ranges.isNullOrEmpty()) {
                "none"
            } else {
                ranges.joinToString(", ") { "[${it.lower},${it.upper}]" }
            }
            return Combo(
                width, height, fps, false,
                "No fixed exposure range [$fps,$fps]. This camera reports $shown. " +
                    "Constrained high-speed mode is not used."
            )
        }
        return Combo(width, height, fps, true, "")
    }

    private fun sizeSupported(map: android.hardware.camera2.params.StreamConfigurationMap, w: Int, h: Int): Boolean {
        fun has(sizes: Array<Size>?): Boolean = sizes?.any { it.width == w && it.height == h } == true
        try {
            if (has(map.getOutputSizes(MediaCodec::class.java))) return true
        } catch (_: Exception) {
        }
        try {
            if (has(map.getOutputSizes(SurfaceTexture::class.java))) return true
        } catch (_: Exception) {
        }
        try {
            if (has(map.getOutputSizes(ImageFormat.PRIVATE))) return true
        } catch (_: Exception) {
        }
        return false
    }

    private fun minFrameDurationNs(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        w: Int,
        h: Int
    ): Long? {
        val size = Size(w, h)
        var best: Long? = null
        fun consider(value: Long) {
            if (value > 0L && (best == null || value < best!!)) best = value
        }
        try {
            consider(map.getOutputMinFrameDuration(MediaCodec::class.java, size))
        } catch (_: Exception) {
        }
        try {
            consider(map.getOutputMinFrameDuration(SurfaceTexture::class.java, size))
        } catch (_: Exception) {
        }
        try {
            consider(map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size))
        } catch (_: Exception) {
        }
        return best
    }

    private fun facingOrder(facing: Int): Int = when (facing) {
        CameraMetadata.LENS_FACING_BACK -> 0
        CameraMetadata.LENS_FACING_FRONT -> 1
        else -> 2
    }
}
