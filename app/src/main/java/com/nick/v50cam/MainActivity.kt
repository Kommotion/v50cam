package com.nick.v50cam

import android.Manifest
import android.app.Activity
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.ToggleButton
import java.util.Locale

/**
 * Controls only. The stream lives in [StreamService].
 * `am start` extras: --ei res 1080 --ei fps 60 --ei bitrate 10 --es camera <id>
 * --es codec mjpeg|h264 --ei quality 80 --es mux lookahead|raw|mpjpeg --ez autostart true
 * --es template record|preview|zsl --es edge off|fast|hq|default
 * --es nr off|fast|hq|default --ei maxexp 0 --es jpeg sw|hw
 * codec defaults to mjpeg. quality is the JPEG quality, 1 to 100.
 * mux defaults to lookahead: raw mjpeg bytes, SOI shifted so FFmpeg emits each
 * frame immediately. OBS Input Format is mjpeg, with framerate set.
 * mpjpeg is multipart. OBS timestamps it at a fixed 25 fps, so lag grows.
 * It is not recommended. raw is concatenated JPEGs.
 * template defaults to record. edge and nr default to fast when the camera lists it.
 * maxexp is microseconds; 0 leaves auto exposure. jpeg sw is the YUV path; hw is camera JPEG.
 *
 * Android 12 only allows startForegroundService while this activity is resumed
 * and visible. The autostart bat uses `am start`, which brings this activity to
 * the foreground, and the service is started from here. It is never started
 * from a broadcast receiver or from the background.
 */
class MainActivity : Activity() {
    private lateinit var spinnerCamera: Spinner
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFps: Spinner
    private lateinit var spinnerBitrate: Spinner
    private lateinit var textSupport: TextView
    private lateinit var buttonStart: Button
    private lateinit var toggleAe: ToggleButton
    private lateinit var toggleAf: ToggleButton
    private lateinit var toggleCodec: ToggleButton
    private lateinit var textStatus: TextView

    private var cameras: List<CamInfo> = emptyList()
    private var resChoices: List<Pair<Int, Int>> = emptyList()
    private var fpsChoices: List<Int> = emptyList()
    private var updating = false
    private var camerasLoaded = false
    private var streaming = false

    private var wantCamera: String? = null
    private var wantRes = 1080
    private var wantFps = 60
    private var wantBitrate = 10
    private var wantMjpeg = true
    private var wantQuality = 80
    private var wantTemplate: String? = null
    private var wantEdge: String? = null
    private var wantNr: String? = null
    private var wantMaxExp: Int? = null
    private var wantJpeg: String? = null
    private var wantMux = "lookahead"
    private var startWhenReady = false
    private var askedCamera = false
    private var askedNotifications = false
    private var resumed = false
    private var permissionRequestInFlight = false
    private var stickyStatus: String? = null

    private var service: StreamService? = null
    private var bound = false

    private val listener = object : StreamService.Listener {
        override fun onStatus(status: StreamStatus) {
            render(status)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? StreamService.LocalBinder ?: return
            val boundService = local.service()
            service = boundService
            boundService.addListener(listener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        spinnerCamera = findViewById(R.id.spinnerCamera)
        spinnerResolution = findViewById(R.id.spinnerResolution)
        spinnerFps = findViewById(R.id.spinnerFps)
        spinnerBitrate = findViewById(R.id.spinnerBitrate)
        textSupport = findViewById(R.id.textSupport)
        buttonStart = findViewById(R.id.buttonStart)
        toggleAe = findViewById(R.id.toggleAe)
        toggleAf = findViewById(R.id.toggleAf)
        toggleCodec = findViewById(R.id.toggleCodec)
        textStatus = findViewById(R.id.textStatus)

        setupBitrateSpinner()
        wireControls()
        readIntent(intent)
        textSupport.text = getString(R.string.loading_cameras)
        buttonStart.isEnabled = false
        loadCameras()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        if (camerasLoaded) {
            applyWantedSelection()
            tryStart()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            // Bind only. This does not start the foreground service.
            bindService(Intent(this, StreamService::class.java), connection, BIND_AUTO_CREATE)
            bound = true
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Autostart arrives as an activity intent from `am start`, so this runs
        // while the activity is in the foreground. Start the service from here.
        if (!permissionRequestInFlight) tryStart()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        if (bound) {
            service?.removeListener(listener)
            service = null
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMS) return
        permissionRequestInFlight = false
        val askedForCamera = permissions.any { it == Manifest.permission.CAMERA }
        if (askedForCamera && !cameraGranted()) {
            // We just asked. Rationale false means "don't ask again".
            startWhenReady = false
            showCameraDenied(permanent = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
            return
        }
        if (askedForCamera) {
            camerasLoaded = false
            loadCameras()
            return
        }
        // POST_NOTIFICATIONS only. Denial is non-fatal; the stream still starts.
        if (startWhenReady && resumed) tryStart()
    }

    private fun readIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra("res")) {
            wantRes = when (intent.getIntExtra("res", 1080)) {
                720, 1280 -> 720
                else -> 1080
            }
        }
        if (intent.hasExtra("fps")) {
            val fps = intent.getIntExtra("fps", 60)
            wantFps = if (fps >= 45) 60 else 30
        }
        if (intent.hasExtra("bitrate")) {
            wantBitrate = intent.getIntExtra("bitrate", 10)
        }
        if (intent.hasExtra("camera")) {
            wantCamera = intent.getStringExtra("camera")
        }
        if (intent.hasExtra("codec")) {
            wantMjpeg = normalizeCodec(intent.getStringExtra("codec")) != "h264"
        }
        if (intent.hasExtra("quality")) {
            wantQuality = intent.getIntExtra("quality", 80).coerceIn(1, 100)
        }
        if (intent.hasExtra("template")) {
            wantTemplate = intent.getStringExtra("template")
        }
        if (intent.hasExtra("edge")) {
            wantEdge = intent.getStringExtra("edge")
        }
        if (intent.hasExtra("nr")) {
            wantNr = intent.getStringExtra("nr")
        }
        if (intent.hasExtra("maxexp")) {
            wantMaxExp = intent.getIntExtra("maxexp", 0).coerceAtLeast(0)
        }
        if (intent.hasExtra("jpeg")) {
            wantJpeg = intent.getStringExtra("jpeg")
        }
        if (intent.hasExtra("mux")) {
            wantMux = normalizeMux(intent.getStringExtra("mux"))
        }
        if (::toggleCodec.isInitialized) {
            updating = true
            toggleCodec.isChecked = wantMjpeg
            updating = false
        }
        if (intent.getBooleanExtra("autostart", false)) {
            startWhenReady = true
        }
        intent.removeExtra("autostart")
    }

    private fun loadCameras() {
        Thread {
            val found = try {
                CameraCatalog.list(this)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "camera list failed", t)
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                cameras = found
                camerasLoaded = true
                if (found.isEmpty()) {
                    textSupport.text = getString(
                        if (cameraGranted()) R.string.no_camera else R.string.need_camera_permission
                    )
                    buttonStart.isEnabled = true
                    if (startWhenReady) tryStart()
                    return@runOnUiThread
                }
                applyWantedSelection()
                buttonStart.isEnabled = true
                tryStart()
            }
        }.start()
    }

    private fun applyWantedSelection() {
        if (cameras.isEmpty()) return
        updating = true
        val labels = cameras.map { it.label }
        spinnerCamera.adapter = adapter(labels)
        val wantedIndex = cameras.indexOfFirst { it.id == wantCamera }
        val index = when {
            wantedIndex >= 0 -> wantedIndex
            else -> {
                val def = CameraCatalog.defaultCamera(cameras)
                if (def == null) 0 else cameras.indexOf(def).coerceAtLeast(0)
            }
        }
        spinnerCamera.setSelection(index)
        rebuildResAndFps()
        val bitrateIndex = BITRATES.indexOf(wantBitrate).let { if (it >= 0) it else 1 }
        spinnerBitrate.setSelection(bitrateIndex)
        updating = false
        updateSupportText()
    }

    private fun rebuildResAndFps() {
        val cam = cameras.getOrNull(spinnerCamera.selectedItemPosition) ?: return
        val supported = cam.combos.filter { it.supported }
        val resolutions = ArrayList<Pair<Int, Int>>()
        for (combo in supported) {
            val pair = combo.width to combo.height
            if (!resolutions.contains(pair)) resolutions.add(pair)
        }
        resChoices = resolutions
        spinnerResolution.adapter = adapter(resolutions.map { "${it.second}p (${it.first}x${it.second})" })
        val wantPair = if (wantRes >= 1080) 1920 to 1080 else 1280 to 720
        val resIndex = resolutions.indexOf(wantPair).let { if (it >= 0) it else 0 }
        if (resolutions.isNotEmpty()) spinnerResolution.setSelection(resIndex)
        rebuildFps()
    }

    private fun rebuildFps() {
        val cam = cameras.getOrNull(spinnerCamera.selectedItemPosition)
        val res = resChoices.getOrNull(spinnerResolution.selectedItemPosition)
        if (cam == null || res == null) {
            fpsChoices = emptyList()
            spinnerFps.adapter = adapter(emptyList())
            return
        }
        val fps = cam.combos
            .filter { it.supported && it.width == res.first && it.height == res.second }
            .map { it.fps }
            .distinct()
        fpsChoices = fps
        spinnerFps.adapter = adapter(fps.map { "$it fps" })
        val fpsIndex = fps.indexOf(wantFps).let { if (it >= 0) it else 0 }
        if (fps.isNotEmpty()) spinnerFps.setSelection(fpsIndex)
    }

    private fun updateSupportText() {
        val cam = cameras.getOrNull(spinnerCamera.selectedItemPosition)
        if (cam == null) {
            textSupport.text = ""
            return
        }
        val lines = ArrayList<String>()
        if (!wantCamera.isNullOrBlank() && cam.id != wantCamera) {
            lines.add("Camera $wantCamera was not found. Showing ${cam.label}.")
        }
        lines.add(CameraCatalog.supportText(cam.combos))
        val res = resChoices.getOrNull(spinnerResolution.selectedItemPosition)
        val fps = fpsChoices.getOrNull(spinnerFps.selectedItemPosition)
        if (res != null && fps != null) {
            val chosen = Combo(res.first, res.second, fps, true, "")
            val wantW = if (wantRes >= 1080) 1920 else 1280
            val wantH = if (wantRes >= 1080) 1080 else 720
            CameraCatalog.fallbackNote(cam.combos, wantW, wantH, wantFps, chosen)?.let { lines.add(it) }
        }
        textSupport.text = lines.joinToString("\n")
    }

    private fun wireControls() {
        val selection = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updating) return
                if (parent === spinnerCamera) {
                    val cam = cameras.getOrNull(position)
                    wantCamera = cam?.id
                    updating = true
                    rebuildResAndFps()
                    updating = false
                } else if (parent === spinnerResolution) {
                    val res = resChoices.getOrNull(position)
                    if (res != null) wantRes = res.second
                    updating = true
                    rebuildFps()
                    updating = false
                } else if (parent === spinnerFps) {
                    fpsChoices.getOrNull(position)?.let { wantFps = it }
                } else if (parent === spinnerBitrate) {
                    wantBitrate = BITRATES.getOrElse(position) { 10 }
                }
                updateSupportText()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinnerCamera.onItemSelectedListener = selection
        spinnerResolution.onItemSelectedListener = selection
        spinnerFps.onItemSelectedListener = selection
        spinnerBitrate.onItemSelectedListener = selection

        buttonStart.setOnClickListener {
            if (streaming) {
                startWhenReady = false
                // Stop command to a service this activity already started. The
                // activity is visible. This is not a background foreground-service start.
                startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            } else {
                stickyStatus = null
                startWhenReady = true
                tryStart()
            }
        }
        toggleAe.setOnCheckedChangeListener { _, checked ->
            if (!updating && streaming) service?.setAeLock(checked)
        }
        toggleAf.setOnCheckedChangeListener { _, checked ->
            if (!updating && streaming) service?.setAfLock(checked)
        }
        toggleCodec.setOnCheckedChangeListener { _, checked ->
            if (!updating) wantMjpeg = checked
        }
    }

    private fun tryStart() {
        if (!startWhenReady || permissionRequestInFlight) return
        // Android 12 background-start restriction: only while resumed and visible.
        if (!resumed) return
        if (!cameraGranted()) {
            if (askedCamera && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                startWhenReady = false
                showCameraDenied(permanent = true)
                return
            }
            val need = ArrayList<String>()
            need.add(Manifest.permission.CAMERA)
            askedCamera = true
            if (notificationsMissing() && !askedNotifications) {
                need.add(Manifest.permission.POST_NOTIFICATIONS)
                askedNotifications = true
            }
            permissionRequestInFlight = true
            requestPermissions(need.toTypedArray(), REQUEST_PERMS)
            return
        }
        if (!camerasLoaded) return
        // POST_NOTIFICATIONS is API 33+. Denial must not block the stream.
        // Do not call startForegroundService in the same turn as the permission
        // dialog. The dialog pauses this activity, and Android 12 then treats the
        // service start as a background start. Denial still continues below, from
        // onRequestPermissionsResult or the next onResume.
        if (notificationsMissing() && !askedNotifications) {
            askedNotifications = true
            permissionRequestInFlight = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERMS)
            return
        }
        startWhenReady = false
        beginStream()
    }

    private fun beginStream() {
        if (!resumed) {
            startWhenReady = true
            return
        }
        val cam = cameras.getOrNull(spinnerCamera.selectedItemPosition)
        val res = resChoices.getOrNull(spinnerResolution.selectedItemPosition)
        val fps = fpsChoices.getOrNull(spinnerFps.selectedItemPosition)
        if (cam == null || res == null || fps == null) {
            textStatus.text = getString(R.string.need_camera_permission)
            if (textSupport.text.isNotBlank()) {
                textStatus.text = textSupport.text
            }
            startWhenReady = false
            return
        }
        val mbps = BITRATES.getOrElse(spinnerBitrate.selectedItemPosition) { 10 }
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_CAMERA, cam.id)
            putExtra(StreamService.EXTRA_WIDTH, res.first)
            putExtra(StreamService.EXTRA_HEIGHT, res.second)
            putExtra(StreamService.EXTRA_FPS, fps)
            putExtra(StreamService.EXTRA_BITRATE, mbps)
            putExtra(StreamService.EXTRA_AE, toggleAe.isChecked)
            putExtra(StreamService.EXTRA_AF, toggleAf.isChecked)
            putExtra(StreamService.EXTRA_CODEC, if (toggleCodec.isChecked) "mjpeg" else "h264")
            putExtra(StreamService.EXTRA_QUALITY, wantQuality)
            wantTemplate?.let { putExtra(StreamService.EXTRA_TEMPLATE, it) }
            wantEdge?.let { putExtra(StreamService.EXTRA_EDGE, it) }
            wantNr?.let { putExtra(StreamService.EXTRA_NR, it) }
            wantMaxExp?.let { putExtra(StreamService.EXTRA_MAXEXP, it) }
            wantJpeg?.let { putExtra(StreamService.EXTRA_JPEG, it) }
            putExtra(StreamService.EXTRA_MUX, wantMux)
        }
        try {
            // Only from this visible activity. `am start` has already resumed it.
            startForegroundService(intent)
        } catch (t: RuntimeException) {
            if (Build.VERSION.SDK_INT >= 31) {
                if (t is ForegroundServiceStartNotAllowedException) {
                    startWhenReady = false
                    showSticky(getString(R.string.fgs_not_allowed))
                    Log.e(TAG, "foreground service start blocked", t)
                    return
                }
            }
            throw t
        }
    }

    private fun cameraGranted(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationsMissing(): Boolean {
        return Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    private fun showCameraDenied(permanent: Boolean) {
        showSticky(
            getString(if (permanent) R.string.camera_denied_forever else R.string.camera_denied)
        )
    }

    private fun showSticky(message: String) {
        stickyStatus = message
        buttonStart.isEnabled = true
        textStatus.text = message
    }

    private fun render(status: StreamStatus) {
        streaming = status.streaming
        buttonStart.text = getString(if (streaming) R.string.action_stop else R.string.action_start)
        if (status.streaming) stickyStatus = null
        val sticky = stickyStatus
        textStatus.text = if (sticky != null && !status.streaming) sticky else formatStatus(status)
        val enabled = !streaming && camerasLoaded
        spinnerCamera.isEnabled = enabled
        spinnerResolution.isEnabled = enabled
        spinnerFps.isEnabled = enabled
        spinnerBitrate.isEnabled = enabled
        toggleCodec.isEnabled = enabled
        if (streaming) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updating = true
            toggleAe.isChecked = status.aeLock
            toggleAf.isChecked = status.afLock
            updating = false
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun formatStatus(status: StreamStatus): String {
        val client = if (status.clientConnected) "connected" else "not connected"
        val fps = String.format(Locale.US, "%.1f", status.fps)
        val mbps = String.format(Locale.US, "%.1f", status.bitrateMbps)
        val codec = when (status.codec) {
            "mjpeg" -> {
                val container = if (status.mux.isNotEmpty()) " ${status.mux}" else ""
                "Codec: MJPEG$container\n"
            }
            "h264" -> "Codec: H.264\n"
            else -> ""
        }
        return status.detail + "\n" +
            codec +
            "Client: $client\n" +
            "FPS: $fps\n" +
            "Bitrate: $mbps Mbps\n" +
            "Dropped: ${status.droppedFrames}"
    }

    private fun setupBitrateSpinner() {
        spinnerBitrate.adapter = adapter(BITRATES.map { "$it Mbps" })
        spinnerBitrate.setSelection(1)
    }

    private fun adapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    companion object {
        private val BITRATES = listOf(8, 10, 12)
        private const val REQUEST_PERMS = 42
    }
}
