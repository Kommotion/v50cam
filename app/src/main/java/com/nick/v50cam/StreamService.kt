package com.nick.v50cam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the camera, encoder, and loopback server so streaming continues when the
 * activity is in the background. The activity is only the controls.
 *
 * Android 12 allows that background camera use only because this service is a
 * foreground service of type camera, and [MainActivity] starts it with
 * startForegroundService while the activity is resumed and visible. That
 * includes the autostart path: `am start` brings the activity to the foreground
 * first. Do not start this service from a [android.content.BroadcastReceiver]
 * or any other background context.
 *
 * [onStartCommand] calls [startForeground] before the camera is opened, well
 * inside the few seconds Android 12 allows after startForegroundService.
 */
class StreamService : Service() {
    internal interface Listener {
        fun onStatus(status: StreamStatus)
    }

    inner class LocalBinder : Binder() {
        fun service(): StreamService = this@StreamService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private lateinit var pipeline: StreamPipeline
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    @Volatile
    internal var status: StreamStatus = StreamStatus.stopped()
        private set

    private val pipelineCallback = object : StreamPipeline.Callback {
        override fun onStatus(status: StreamStatus) {
            this@StreamService.status = status
            for (listener in listeners) {
                try {
                    listener.onStatus(status)
                } catch (t: Throwable) {
                    Log.w(TAG, "listener", t)
                }
            }
        }

        override fun onFatal(message: String) {
            try {
                pipeline.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "stop after failure", t)
            }
            releaseWakeLock()
            val text = message.lineSequence().firstOrNull() ?: "Stopped"
            showFinalNotification(text)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        pipeline = StreamPipeline(applicationContext, pipelineCallback)
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                return START_NOT_STICKY
            }
            else -> {
                val request = readRequest(intent)
                // Promote before opening the camera. Android 12 (API 31) throws
                // ForegroundServiceDidNotStartInTimeException if this is late.
                enterForeground(startingText(request))
                acquireWakeLock()
                pipeline.start(request)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        listeners.clear()
        try {
            pipeline.stop()
        } catch (_: Exception) {
        }
        releaseWakeLock()
        super.onDestroy()
    }

    internal fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStatus(status)
    }

    internal fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    internal fun setAeLock(locked: Boolean) {
        pipeline.setAeLock(locked)
    }

    internal fun setAfLock(locked: Boolean) {
        pipeline.setAfLock(locked)
    }

    private fun stopStreaming() {
        try {
            pipeline.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "stop", t)
        }
        releaseWakeLock()
        status = status.copy(
            streaming = false,
            clientConnected = false,
            fps = 0.0,
            bitrateMbps = 0.0,
            detail = "Stopped."
        )
        for (listener in listeners) {
            try {
                listener.onStatus(status)
            } catch (_: Throwable) {
            }
        }
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    private fun readRequest(intent: Intent?): StreamRequest {
        val width: Int
        val height: Int
        if (intent != null && intent.hasExtra(EXTRA_WIDTH)) {
            width = intent.getIntExtra(EXTRA_WIDTH, 1920)
            height = intent.getIntExtra(EXTRA_HEIGHT, 1080)
        } else {
            val res = intent?.getIntExtra(EXTRA_RES, 1080) ?: 1080
            if (res == 720 || res == 1280) {
                width = 1280
                height = 720
            } else {
                width = 1920
                height = 1080
            }
        }
        val fps = intent?.getIntExtra(EXTRA_FPS, 60) ?: 60
        val bitrate = intent?.getIntExtra(EXTRA_BITRATE, 10) ?: 10
        val camera = intent?.getStringExtra(EXTRA_CAMERA)
        val ae = intent?.getBooleanExtra(EXTRA_AE, false) ?: false
        val af = intent?.getBooleanExtra(EXTRA_AF, false) ?: false
        val codec = normalizeCodec(intent?.getStringExtra(EXTRA_CODEC))
        val quality = (intent?.getIntExtra(EXTRA_QUALITY, 80) ?: 80).coerceIn(1, 100)
        val template = if (intent != null && intent.hasExtra(EXTRA_TEMPLATE)) {
            normalizeTemplate(intent.getStringExtra(EXTRA_TEMPLATE))
        } else {
            "record"
        }
        // Empty means the extra was omitted: pipeline applies the fast default.
        val edge = if (intent != null && intent.hasExtra(EXTRA_EDGE)) {
            normalizeEdgeNr(intent.getStringExtra(EXTRA_EDGE))
        } else {
            ""
        }
        val nr = if (intent != null && intent.hasExtra(EXTRA_NR)) {
            normalizeEdgeNr(intent.getStringExtra(EXTRA_NR))
        } else {
            ""
        }
        val maxExp = if (intent != null && intent.hasExtra(EXTRA_MAXEXP)) {
            intent.getIntExtra(EXTRA_MAXEXP, 0).coerceAtLeast(0)
        } else {
            0
        }
        val jpeg = if (intent != null && intent.hasExtra(EXTRA_JPEG)) {
            normalizeJpeg(intent.getStringExtra(EXTRA_JPEG))
        } else {
            "sw"
        }
        val mux = if (intent != null && intent.hasExtra(EXTRA_MUX)) {
            normalizeMux(intent.getStringExtra(EXTRA_MUX))
        } else {
            "lookahead"
        }
        return StreamRequest(
            cameraId = camera,
            width = width,
            height = height,
            fps = fps,
            bitrateMbps = bitrate,
            aeLock = ae,
            afLock = af,
            codec = codec,
            jpegQuality = quality,
            template = template,
            edgeMode = edge,
            nrMode = nr,
            maxExposureUs = maxExp,
            jpegMode = jpeg,
            mux = mux
        )
    }

    private fun startingText(request: StreamRequest): String {
        val res = if (request.height > 0) "${request.height}p" else "camera"
        val codec = if (request.codec == "h264") "H.264" else "MJPEG ${request.mux}"
        return "Starting $codec $res at ${request.fps} fps"
    }

    private fun enterForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            // The 3-arg overload exists on API 29. FOREGROUND_SERVICE_TYPE_CAMERA
            // exists on API 30. Passing that bit on API 29 throws, because it is
            // not in that release's allowed mask. API 31 (this phone) requires it.
            val type = if (Build.VERSION.SDK_INT >= 30) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun showFinalNotification(text: String) {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notif)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID + 1, notification)
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        // API 31+ throws if a PendingIntent does not set FLAG_IMMUTABLE or FLAG_MUTABLE.
        // Neither of these intents is mutated after it is created.
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            immutable
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            immutable
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_notif)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(icon, getString(R.string.notif_stop), stopPi).build())
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // Released in stopStreaming / onDestroy. Not a timed lock: the stream decides the lifetime.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "v50cam:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock != null && lock.isHeld) {
            try {
                lock.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val ACTION_START = "com.nick.v50cam.START"
        const val ACTION_STOP = "com.nick.v50cam.STOP"
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_RES = "res"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_AE = "ae"
        const val EXTRA_AF = "af"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_TEMPLATE = "template"
        const val EXTRA_EDGE = "edge"
        const val EXTRA_NR = "nr"
        const val EXTRA_MAXEXP = "maxexp"
        const val EXTRA_JPEG = "jpeg"
        const val EXTRA_MUX = "mux"
        private const val CHANNEL_ID = "v50cam"
        private const val NOTIFICATION_ID = 1
    }
}
