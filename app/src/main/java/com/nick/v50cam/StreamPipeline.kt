package com.nick.v50cam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.graphics.ImageFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 -> H.264 or MJPEG -> one-slot TCP hand-off.
 *
 * There is no preview Surface. A second target would force a session rebuild
 * every time the activity goes away, which is how latency and "the stream died"
 * bugs show up. The encoder surface is the only camera output.
 *
 * Nothing in this path queues frames to smooth over a slow laptop. A late
 * client loses a frame and then waits for a fresh IDR.
 */
internal class StreamPipeline(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onStatus(status: StreamStatus)
        fun onFatal(message: String)
    }

    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val fatalSent = AtomicBoolean(false)
    private val genLock = Any()
    private var generation = 0

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var server: TcpServer? = null
    private var encoder: StreamEncoder? = null
    private var encoderSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    @Volatile
    private var stopping = false

    /** Bumped in [stop] so a status posted during teardown cannot land after it. */
    @Volatile
    private var publishGeneration = 0

    @Volatile
    private var ignoringClose = false

    @Volatile
    private var streaming = false

    @Volatile
    private var detail: String = "Stopped."

    private var aeLock = false
    private var afLock = false
    private var afLockPending = false
    private var lockedDistance: Float? = null
    private var manualFocus = false
    private var afModes: IntArray = IntArray(0)
    private var fpsRange: Range<Int>? = null
    private var oisOff = false
    private var videoStabOff = false
    private var aeOn = false
    private var awbAuto = false

    private var runCameraId = ""
    private var runLabel = ""
    private var runWidth = 0
    private var runHeight = 0
    private var runFps = 0
    private var runBitrateMbps = 0
    private var runCodec = "mjpeg"
    private var runQuality = 80
    private var runTemplate = "record"
    private var runEdge = ""
    private var runNr = ""
    private var runMaxExpUs = 0
    private var runJpegHw = false
    private var runMux = "lookahead"
    private var templateId = CameraDevice.TEMPLATE_RECORD
    private var appliedTemplate = "record"
    private var appliedEdge = "default"
    private var appliedNr = "fast"
    private var edgeMode: Int? = null
    private var nrMode: Int? = null
    private var timestampRealtime = false
    private var aeOff = false
    private var sensitivityRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var manualApplied = false
    private var manualExpNs = 0L
    private var manualIso = 0
    private var manualFrameNs = 0L

    private var restarts = 0
    private var restartWindowStart = 0L
    private var restartQueued = false

    private var shownFps = 0.0
    private var shownMbps = 0.0
    private var lastTickMs = 0L
    private var lastFrames = 0L
    private var lastBytes = 0L
    private var sawFrame = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!streaming || stopping) return
            sampleRates()
            noteCameraAccess()
            publish()
            mainHandler.postDelayed(this, 500)
        }
    }

    fun start(request: StreamRequest) {
        stop()
        stopping = false
        // Drop a status that was posted during stop() and captured streaming==true.
        publishGeneration++
        fatalSent.set(false)
        restarts = 0
        restartWindowStart = SystemClock.elapsedRealtime()
        val thread = HandlerThread("V50Cam-camera")
        thread.start()
        cameraThread = thread
        val handler = Handler(thread.looper)
        cameraHandler = handler
        handler.post {
            if (stopping) return@post
            try {
                bringUp(request)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                reportFatal(t.message ?: "Could not start the camera")
            }
        }
    }

    fun stop() {
        val handler = cameraHandler
        val thread = cameraThread
        stopping = true
        publishGeneration++
        bumpGen()
        if (handler != null && thread != null) {
            handler.removeCallbacksAndMessages(null)
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    teardownCameraAndEncoder()
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(4, TimeUnit.SECONDS)) {
                Log.e(TAG, "timed out waiting for the camera to close")
            }
            thread.quitSafely()
        }
        cameraHandler = null
        cameraThread = null
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        streaming = false
        mainHandler.removeCallbacks(ticker)
        shownFps = 0.0
        shownMbps = 0.0
    }

    fun setAeLock(locked: Boolean) {
        val handler = cameraHandler
        if (handler == null) {
            aeLock = locked
            return
        }
        handler.post {
            aeLock = locked
            if (!stopping && captureSession != null) applyRepeating()
        }
    }

    fun setAfLock(locked: Boolean) {
        val handler = cameraHandler ?: return
        handler.post {
            if (stopping) return@post
            afLock = locked
            afLockPending = false
            lockedDistance = null
            if (captureSession == null) return@post
            if (!locked) {
                fireAfCancel()
                applyRepeating()
            } else {
                afLockPending = true
                applyRepeating()
                fireAfTrigger(CaptureRequest.CONTROL_AF_TRIGGER_START)
                scheduleAfTimeout()
            }
        }
    }

    private fun bringUp(request: StreamRequest) {
        val gen = bumpGen()
        val manager = context.getSystemService(CameraManager::class.java)
            ?: throw IllegalStateException("Camera service is missing")
        if (server == null) {
            val mux = normalizeMux(request.mux)
            val codec = normalizeCodec(request.codec)
            server = TcpServer(
                requestSync = { encoder?.requestSync() },
                onClient = {
                    publish()
                },
                lookahead = codec != "h264" && mux == "lookahead"
            ).also { it.start() }
        }
        val all = CameraCatalog.list(context)
        val requestedId = request.cameraId?.takeIf { it.isNotBlank() }
        var cam = if (requestedId == null) {
            CameraCatalog.defaultCamera(all)
        } else {
            all.firstOrNull { it.id == requestedId }
        }
        val notes = ArrayList<String>()
        if (cam == null) {
            val fallback = CameraCatalog.defaultCamera(all)
            if (fallback == null) {
                reportFatal("No camera found.")
                return
            }
            if (requestedId != null) {
                notes.add("Camera $requestedId was not found. Using ${fallback.label}.")
            }
            cam = fallback
        }
        val chosen = CameraCatalog.pick(cam.combos, request.width, request.height, request.fps)
        if (chosen == null || gen != currentGen()) {
            reportFatal(
                "This camera cannot stream 720p or 1080p at a fixed frame rate.\n" +
                    CameraCatalog.supportText(cam.combos)
            )
            return
        }
        CameraCatalog.fallbackNote(cam.combos, request.width, request.height, request.fps, chosen)
            ?.let { notes.add(it) }
        runCameraId = cam.id
        runLabel = cam.label
        runWidth = chosen.width
        runHeight = chosen.height
        runFps = chosen.fps
        runBitrateMbps = request.bitrateMbps.coerceIn(1, 50)
        runCodec = normalizeCodec(request.codec)
        runQuality = request.jpegQuality.coerceIn(1, 100)
        runTemplate = normalizeTemplate(request.template)
        runEdge = request.edgeMode
        runNr = request.nrMode
        runMaxExpUs = request.maxExposureUs.coerceAtLeast(0)
        runJpegHw = runCodec != "h264" && request.jpegMode == "hw"
        runMux = normalizeMux(request.mux)
        if (runCodec == "h264" && request.jpegMode == "hw") {
            Log.i(TAG, "jpeg=hw ignored because codec=h264")
        }
        manualApplied = false
        manualExpNs = 0L
        manualIso = 0
        manualFrameNs = 0L
        aeLock = request.aeLock
        afLock = request.afLock
        lockedDistance = null
        afLockPending = false
        sawFrame = false
        val chars = manager.getCameraCharacteristics(runCameraId)
        loadCaps(chars)
        if (gen != currentGen() || stopping) return
        server?.onEncoderRestart()
        encoderSurface = openEncoder()
        val mode = notes.joinToString(" ")
        detail = buildString {
            if (mode.isNotEmpty()) {
                append(mode)
                append(' ')
            }
            append("${runHeight}p at $runFps fps, ${runBitrateMbps} Mbps. ")
            append(runLabel)
            append(". ")
            append(encoder?.summary.orEmpty())
            append(". Port ")
            append(STREAM_PORT)
            append('.')
        }
        streaming = true
        shownFps = 0.0
        shownMbps = 0.0
        lastTickMs = SystemClock.elapsedRealtime()
        lastFrames = 0L
        lastBytes = 0L
        mainHandler.removeCallbacks(ticker)
        mainHandler.postDelayed(ticker, 500)
        publish()
        if (gen != currentGen() || stopping) return
        openCamera(manager, gen)
    }

    private fun openCamera(manager: CameraManager, gen: Int) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            reportFatal("Camera permission is not granted.")
            return
        }
        // Background camera use is allowed only because StreamService is a
        // foreground service of type camera and was started while MainActivity
        // was visible. Android 12 blocks a background open otherwise.
        // The Quick Settings "Camera access" toggle (API 31) can still refuse
        // the open: black frames, onError(ERROR_CAMERA_DISABLED), or onDisconnected.
        if (SensorPrivacy.cameraBlocked(context) == true) {
            reportFatal(context.getString(R.string.camera_qs_off))
            return
        }
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (stopping || gen != currentGen()) {
                    try {
                        camera.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                cameraDevice = camera
                createSession(camera, gen)
            }

            override fun onDisconnected(camera: CameraDevice) {
                if (ignoringClose || stopping || gen != currentGen()) {
                    try {
                        camera.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                try {
                    camera.close()
                } catch (_: Exception) {
                }
                if (cameraDevice === camera) cameraDevice = null
                if (cameraAccessOff(error = null, disconnected = true)) {
                    reportFatal(context.getString(R.string.camera_qs_off))
                    return
                }
                scheduleRestart("camera disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "camera error $error")
                try {
                    camera.close()
                } catch (_: Exception) {
                }
                if (cameraDevice === camera) cameraDevice = null
                if (ignoringClose || stopping || gen != currentGen()) return
                if (cameraAccessOff(error, disconnected = false)) {
                    reportFatal(context.getString(R.string.camera_qs_off))
                    return
                }
                scheduleRestart(cameraErrorName(error))
            }
        }
        try {
            manager.openCamera(runCameraId, callback, cameraHandler)
        } catch (t: Throwable) {
            reportFatal(openError(t))
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, gen: Int) {
        val surface = encoderSurface
        if (surface == null || stopping || gen != currentGen()) {
            reportFatal("Encoder surface is gone.")
            return
        }
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (stopping || gen != currentGen()) {
                            session.close()
                            return
                        }
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                baseBuilder(camera).build(),
                                captureCallback,
                                cameraHandler
                            )
                            streaming = true
                            if (afLock) {
                                afLockPending = true
                                lockedDistance = null
                                fireAfTrigger(CaptureRequest.CONTROL_AF_TRIGGER_START)
                                scheduleAfTimeout()
                            }
                            encoder?.requestSync()
                            armNoFrameWatch(gen)
                            val exp = if (runMaxExpUs > 0) "maxexp=${runMaxExpUs}us pending" else "ae"
                            logStreaming(exp)
                            publish()
                        } catch (t: Throwable) {
                            if (runJpegHw) {
                                Log.e(TAG, "JPEG repeating target rejected", t)
                            } else {
                                Log.e(TAG, "setRepeatingRequest failed", t)
                            }
                            val why = if (runJpegHw) {
                                "Camera rejected JPEG as a repeating target"
                            } else {
                                "Camera would not start repeating"
                            }
                            reportFatal("$why: ${t.message ?: "unknown error"}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (stopping || gen != currentGen()) return
                        if (runJpegHw) {
                            Log.e(TAG, "JPEG repeating target rejected: session configure failed")
                            reportFatal("Camera rejected JPEG as a repeating target (session configure failed).")
                        } else {
                            reportFatal("Camera session failed to configure.")
                        }
                    }
                },
                cameraHandler
            )
        } catch (t: Throwable) {
            reportFatal("Could not create the camera session: ${t.message ?: "unknown error"}")
        }
    }

    private fun scheduleRestart(reason: String) {
        if (stopping || ignoringClose || restartQueued) return
        val now = SystemClock.elapsedRealtime()
        if (now - restartWindowStart > 30_000) {
            restarts = 0
            restartWindowStart = now
        }
        restarts++
        if (restarts > 3) {
            val blocked = if (Build.VERSION.SDK_INT >= 31) {
                SensorPrivacy.cameraBlocked(context)
            } else {
                false
            }
            val privacy = blocked == true ||
                (blocked == null &&
                    Build.VERSION.SDK_INT >= 31 &&
                    (reason.contains("disconnect") || reason.contains("disabled")))
            reportFatal(
                if (privacy) {
                    context.getString(R.string.camera_qs_off)
                } else {
                    "Camera kept failing ($reason). Stopped."
                }
            )
            return
        }
        restartQueued = true
        val scheduledGen = currentGen()
        detail = "Restarting after $reason."
        publish()
        cameraHandler?.postDelayed({
            restartQueued = false
            if (stopping || scheduledGen != currentGen()) return@postDelayed
            try {
                val manager = context.getSystemService(CameraManager::class.java)
                    ?: throw IllegalStateException("Camera service is missing")
                teardownCameraAndEncoder()
                if (stopping) return@postDelayed
                val gen = bumpGen()
                server?.onEncoderRestart()
                encoderSurface = openEncoder()
                detail = "Restarted after $reason. ${encoder?.summary.orEmpty()}"
                streaming = true
                lastTickMs = SystemClock.elapsedRealtime()
                lastFrames = 0L
                lastBytes = server?.bytesSent?.get() ?: 0L
                shownFps = 0.0
                shownMbps = 0.0
                publish()
                openCamera(manager, gen)
            } catch (t: Throwable) {
                reportFatal("Restart failed: ${t.message ?: reason}")
            }
        }, 400)
    }

    private fun openEncoder(): Surface {
        val enc: StreamEncoder = if (runCodec == "h264") {
            server?.onFrameWritten = null
            H264Encoder(
                width = runWidth,
                height = runHeight,
                fps = runFps,
                bitrateBps = runBitrateMbps * 1_000_000,
                onConfig = { annex -> server?.updateCodecConfig(annex) },
                onFrame = { frame -> server?.offer(frame) },
                onError = { error -> postEncoderError(error) }
            )
        } else {
            val mj = MjpegEncoder(
                width = runWidth,
                height = runHeight,
                fps = runFps,
                quality = runQuality,
                hardwareJpeg = runJpegHw,
                timestampRealtime = timestampRealtime,
                mux = runMux,
                onFrame = { frame -> server?.offer(frame) },
                onError = { error -> postEncoderError(error) }
            )
            server?.onFrameWritten = { ts, writeNs -> mj.onFrameWritten(ts, writeNs) }
            mj
        }
        encoder = enc
        return enc.start()
    }

    private fun postEncoderError(error: Throwable) {
        cameraHandler?.post {
            if (!stopping && !ignoringClose) {
                scheduleRestart("encoder error ${error.message ?: ""}".trim())
            }
        }
    }

    private fun teardownCameraAndEncoder() {
        ignoringClose = true
        val session = captureSession
        captureSession = null
        if (session != null) {
            try {
                session.stopRepeating()
            } catch (_: Exception) {
            }
            try {
                session.abortCaptures()
            } catch (_: Exception) {
            }
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
        val camera = cameraDevice
        cameraDevice = null
        if (camera != null) {
            try {
                camera.close()
            } catch (_: Exception) {
            }
        }
        val enc = encoder
        encoder = null
        encoderSurface = null
        try {
            enc?.stop()
        } catch (_: Exception) {
        }
        ignoringClose = false
    }

    private fun applyRepeating() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            session.setRepeatingRequest(baseBuilder(camera).build(), captureCallback, cameraHandler)
        } catch (t: Throwable) {
            val what = when {
                runJpegHw -> "JPEG repeating target"
                manualApplied -> "manual exposure request"
                else -> "repeating request"
            }
            Log.e(TAG, "updating the $what failed", t)
        }
    }

    private fun baseBuilder(camera: CameraDevice): CaptureRequest.Builder {
        val surface = encoderSurface ?: throw IllegalStateException("Encoder surface is gone")
        val b = newRequest(camera)
        b.addTarget(surface)
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (manualApplied) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExpNs)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            if (manualFrameNs > 0L) b.set(CaptureRequest.SENSOR_FRAME_DURATION, manualFrameNs)
        } else {
            if (aeOn) b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
        }
        if (awbAuto) b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        if (videoStabOff) {
            b.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
        if (oisOff) {
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
        edgeMode?.let { b.set(CaptureRequest.EDGE_MODE, it) }
        nrMode?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
        if (runJpegHw) {
            b.set(CaptureRequest.JPEG_QUALITY, runQuality.coerceIn(1, 100).toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, 0)
            b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, Size(0, 0))
        }
        applyAfMode(b)
        return b
    }

    private fun newRequest(camera: CameraDevice): CaptureRequest.Builder {
        try {
            return camera.createCaptureRequest(templateId)
        } catch (t: Throwable) {
            if (templateId == CameraDevice.TEMPLATE_RECORD) throw t
            Log.w(TAG, "template $appliedTemplate rejected (${t.message}); using record")
            appliedTemplate = "record"
            templateId = CameraDevice.TEMPLATE_RECORD
            return camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        }
    }

    private fun logStreaming(exp: String) {
        val jpeg = if (runJpegHw) "hw" else "sw"
        Log.i(
            TAG,
            "streaming codec=$runCodec mux=$runMux camera=$runCameraId ${runWidth}x$runHeight@$runFps " +
                "q=$runQuality template=$appliedTemplate edge=$appliedEdge nr=$appliedNr " +
                "jpeg=$jpeg exp=$exp aeLock=$aeLock afLock=$afLock range=$fpsRange"
        )
    }

    private fun applyAfMode(b: CaptureRequest.Builder) {
        val distance = lockedDistance
        if (afLock && distance != null && manualFocus && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            return
        }
        if (afLock && afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            return
        }
        val mode = when {
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF) ->
                CaptureRequest.CONTROL_AF_MODE_OFF
            else -> null
        }
        if (mode != null) b.set(CaptureRequest.CONTROL_AF_MODE, mode)
    }

    private fun fireAfTrigger(trigger: Int) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        if (!afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) return
        try {
            val b = baseBuilder(camera)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            session.capture(b.build(), null, cameraHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "AF trigger failed", t)
        }
    }

    private fun fireAfCancel() {
        fireAfTrigger(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
    }

    private fun scheduleAfTimeout() {
        cameraHandler?.postDelayed({
            if (!afLockPending || stopping) return@postDelayed
            afLockPending = false
            Log.w(TAG, "AF did not report locked; holding AF_AUTO")
        }, 1500)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            maybeApplyManualExposure(result)
            if (!afLockPending) return
            val state = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            if (state != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED &&
                state != CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                return
            }
            val distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            afLockPending = false
            if (manualFocus && distance != null && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
                lockedDistance = distance
                Log.i(TAG, "focus locked at $distance diopters")
                cameraHandler?.post { applyRepeating() }
            } else {
                Log.i(TAG, "focus held in AF_AUTO, state=$state")
            }
        }
    }

    private fun loadCaps(chars: CameraCharacteristics) {
        afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        manualFocus = minFocus > 0f && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        fpsRange = ranges?.firstOrNull { it.lower == runFps && it.upper == runFps }
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        oisOff = ois?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true
        val stab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        videoStabOff = stab?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == true
        val ae = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        aeOn = ae?.contains(CaptureRequest.CONTROL_AE_MODE_ON) == true
        aeOff = ae?.contains(CaptureRequest.CONTROL_AE_MODE_OFF) == true
        val awb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        awbAuto = awb?.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO) == true
        sensitivityRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        appliedTemplate = when (runTemplate) {
            "preview" -> "preview"
            "zsl" -> "zsl"
            else -> "record"
        }
        templateId = when (appliedTemplate) {
            "preview" -> CameraDevice.TEMPLATE_PREVIEW
            "zsl" -> CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG
            else -> CameraDevice.TEMPLATE_RECORD
        }
        // H.264 keeps edge unset unless --es edge is passed. MJPEG defaults to fast.
        val edgeReq = when {
            runEdge.isNotEmpty() -> runEdge
            runCodec == "h264" -> "default"
            else -> "fast"
        }
        val edgePick = selectControl(
            edgeReq,
            chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
            CaptureRequest.EDGE_MODE_OFF,
            CaptureRequest.EDGE_MODE_FAST,
            CaptureRequest.EDGE_MODE_HIGH_QUALITY
        )
        edgeMode = edgePick.first
        appliedEdge = edgePick.second
        val nrPick = selectControl(
            runNr.ifEmpty { "fast" },
            chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )
        nrMode = nrPick.first
        appliedNr = nrPick.second
        val ts = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        timestampRealtime = ts == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val tsName = when (ts) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
            null -> "null"
            else -> ts.toString()
        }
        Log.i(TAG, "sensor timestamp source=$tsName")
        if (runMaxExpUs > 0 && !aeOff) {
            Log.w(TAG, "maxexp=${runMaxExpUs}us ignored: AE_MODE_OFF is not available")
            runMaxExpUs = 0
        }
        if (runJpegHw) logJpegTiming(chars)
    }

    private fun selectControl(
        requested: String,
        modes: IntArray?,
        off: Int,
        fast: Int,
        hq: Int
    ): Pair<Int?, String> {
        if (requested == "default") return null to "default"
        val want = when (requested) {
            "off" -> off
            "hq" -> hq
            else -> fast
        }
        val label = when (requested) {
            "off" -> "off"
            "hq" -> "hq"
            else -> "fast"
        }
        return if (modes != null && modes.contains(want)) {
            want to label
        } else {
            null to "$label-unavailable"
        }
    }

    private fun logJpegTiming(chars: CameraCharacteristics) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            Log.w(TAG, "jpeg hw: no stream configuration map")
            return
        }
        val size = Size(runWidth, runHeight)
        try {
            val supported = map.outputFormats.contains(ImageFormat.JPEG)
            val minNs = map.getOutputMinFrameDuration(ImageFormat.JPEG, size)
            val stallNs = map.getOutputStallDuration(ImageFormat.JPEG, size)
            Log.i(
                TAG,
                "jpeg hw ${runWidth}x$runHeight supported=$supported minFrame=${minNs}ns stall=${stallNs}ns"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "jpeg hw ${runWidth}x$runHeight is not in the stream map (${t.message})")
        }
    }

    private fun maybeApplyManualExposure(result: TotalCaptureResult) {
        if (manualApplied || runMaxExpUs <= 0 || !aeOff) return
        val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        if (exp <= 0L || iso <= 0) return
        val targetRaw = runMaxExpUs.toLong() * 1000L
        val expRange = exposureRange
        val target = if (expRange != null) targetRaw.coerceIn(expRange.lower, expRange.upper) else targetRaw
        if (target <= 0L) return
        val rawIsoLong = iso.toLong() * exp / target
        if (rawIsoLong <= 0L) return
        val rawIso = rawIsoLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val isoRange = sensitivityRange
        val newIso = if (isoRange != null) rawIso.coerceIn(isoRange.lower, isoRange.upper) else rawIso.coerceIn(50, 16000)
        val askedFrame = if (runFps > 0) 1_000_000_000L / runFps else target
        val frame = maxOf(askedFrame, target)
        manualExpNs = target
        manualIso = newIso
        manualFrameNs = frame
        manualApplied = true
        Log.i(
            TAG,
            "manual exposure ${target / 1000}us iso=$newIso (from ae ${exp / 1000}us iso=$iso) frame=${frame}ns"
        )
        if (frame != askedFrame) {
            Log.w(TAG, "frame duration raised from ${askedFrame}ns to ${frame}ns to cover exposure")
        }
        cameraHandler?.post {
            if (stopping || captureSession == null) return@post
            logStreaming("manual ${manualExpNs / 1000}us iso=$manualIso")
            applyRepeating()
        }
    }

    private fun sampleRates() {
        val now = SystemClock.elapsedRealtime()
        val frames = encoder?.framesProduced?.get() ?: 0L
        val bytes = server?.bytesSent?.get() ?: 0L
        val dt = now - lastTickMs
        if (lastTickMs != 0L && dt >= 200) {
            shownFps = (frames - lastFrames) * 1000.0 / dt
            shownMbps = (bytes - lastBytes) * 8.0 / dt / 1000.0
            lastTickMs = now
            lastFrames = frames
            lastBytes = bytes
        }
    }

    private fun publish() {
        // TcpServer.stop() notifies the client callback while stop() is still on
        // the main thread. Capturing streaming==true here and posting it would
        // overwrite the Stopped status after stop() returns.
        if (stopping) return
        val generation = publishGeneration
        val live = streaming
        val status = StreamStatus(
            streaming = live,
            clientConnected = server?.clientConnected == true,
            fps = if (live) shownFps else 0.0,
            bitrateMbps = if (live) shownMbps else 0.0,
            droppedFrames = server?.dropped?.get() ?: 0L,
            detail = detail,
            aeLock = aeLock,
            afLock = afLock,
            width = runWidth,
            height = runHeight,
            targetFps = runFps,
            targetBitrateMbps = runBitrateMbps,
            cameraLabel = runLabel,
            codec = runCodec,
            mux = if (runCodec == "mjpeg") runMux else ""
        )
        mainHandler.post {
            // stopping covers a publish that read the new generation after stop()
            // bumped it, then posted a snapshot taken while streaming was still true.
            if (stopping || generation != publishGeneration) return@post
            callback.onStatus(status)
        }
    }

    private fun reportFatal(message: String) {
        if (stopping) return
        if (!fatalSent.compareAndSet(false, true)) return
        Log.e(TAG, message)
        detail = message
        streaming = false
        publish()
        mainHandler.post { callback.onFatal(message) }
    }

    private fun bumpGen(): Int = synchronized(genLock) { ++generation }

    private fun currentGen(): Int = synchronized(genLock) { generation }

    private fun openError(t: Throwable): String {
        return when (t) {
            is SecurityException -> "Camera permission is not granted."
            is IllegalArgumentException ->
                "Camera $runCameraId cannot be opened directly. Pick another lens."
            is CameraAccessException -> when {
                t.reason == CameraAccessException.CAMERA_DISABLED && Build.VERSION.SDK_INT >= 31 ->
                    context.getString(R.string.camera_qs_off)
                SensorPrivacy.cameraBlocked(context) == true ->
                    context.getString(R.string.camera_qs_off)
                else -> "Camera is busy or unavailable (${t.reason})."
            }
            else -> "Could not open camera $runCameraId. ${t.message ?: ""}".trim()
        }
    }

    /**
     * Android 12 Quick Settings "Camera access" off. Prefer SensorPrivacyManager
     * when the platform lets us read it. Otherwise ERROR_CAMERA_DISABLED, a
     * disconnect before any frame, or a session that produces no frames.
     */
    private fun cameraAccessOff(error: Int?, disconnected: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val blocked = SensorPrivacy.cameraBlocked(context)
        if (blocked == true) return true
        if (blocked == false) return false
        if (error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) return true
        // Toggle-off often arrives as onDisconnected, and the session never
        // produces a frame. A disconnect after real frames is a different fault.
        return disconnected && !sawEncoderFrame()
    }

    private fun sawEncoderFrame(): Boolean {
        if (sawFrame) return true
        val produced = encoder?.framesProduced?.get() ?: 0L
        if (produced > 0L) sawFrame = true
        return sawFrame
    }

    private fun noteCameraAccess() {
        if (stopping || !streaming) return
        sawEncoderFrame()
        // Black frames still increment the encoder counter. The privacy toggle
        // is the only way to tell those apart from a real picture.
        if (SensorPrivacy.cameraBlocked(context) == true) {
            reportFatal(context.getString(R.string.camera_qs_off))
        }
    }

    private fun armNoFrameWatch(gen: Int) {
        cameraHandler?.postDelayed({
            if (stopping || gen != currentGen() || fatalSent.get()) return@postDelayed
            if (sawEncoderFrame()) {
                if (SensorPrivacy.cameraBlocked(context) == true) {
                    reportFatal(context.getString(R.string.camera_qs_off))
                }
                return@postDelayed
            }
            // Opened, but the encoder got nothing. On Android 12 that is the
            // Quick Settings camera toggle, unless we know the toggle is on.
            if (SensorPrivacy.cameraBlocked(context) == false) {
                reportFatal(context.getString(R.string.camera_no_frames))
            } else if (Build.VERSION.SDK_INT >= 31) {
                reportFatal(context.getString(R.string.camera_qs_off))
            } else {
                reportFatal(context.getString(R.string.camera_no_frames))
            }
        }, 3_000)
    }

    private fun cameraErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera in use"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "camera disabled by a policy"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera device error"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera service error"
        else -> "camera error $error"
    }
}
