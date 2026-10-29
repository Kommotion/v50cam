package com.nick.v50cam

import android.content.Context
import android.hardware.SensorPrivacyManager
import android.os.Build
import android.util.Log

/**
 * Android 12 Quick Settings "Camera access" toggle.
 *
 * The public [SensorPrivacyManager] API only says whether that toggle exists.
 * Whether it is currently off is the hidden `isSensorPrivacyEnabled` method,
 * which requires OBSERVE_SENSOR_PRIVACY. A normal app cannot hold that
 * permission, so the call is attempted and any refusal is treated as unknown.
 * Callers then fall back to [android.hardware.camera2.CameraDevice.StateCallback.onError],
 * [android.hardware.camera2.CameraDevice.StateCallback.onDisconnected], and a
 * no-frame watchdog. While the toggle is off the camera may still open and
 * deliver black frames, or those callbacks may fire.
 */
internal object SensorPrivacy {
    /** null until the first attempt. False means stop calling the hidden method. */
    private var readable: Boolean? = null

    /** null until known. False means this device has no camera privacy toggle. */
    private var toggleSupported: Boolean? = null

    /**
     * @return true when camera access is blocked, false when it is allowed or
     * the device has no camera toggle, null when the state cannot be read.
     * A successful read is not cached, so a mid-stream toggle change is seen.
     */
    fun cameraBlocked(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 31) return null
        if (readable == false) return null
        val manager = context.getSystemService(SensorPrivacyManager::class.java) ?: return null
        val sensor = SensorPrivacyManager.Sensors.CAMERA
        if (toggleSupported == null) {
            toggleSupported = try {
                manager.supportsSensorToggle(sensor)
            } catch (t: Throwable) {
                Log.w(TAG, "supportsSensorToggle failed", t)
                true
            }
        }
        if (toggleSupported == false) return false
        return try {
            val blocked = queryEnabled(manager, sensor)
            readable = true
            blocked
        } catch (t: Throwable) {
            readable = false
            Log.i(TAG, "SensorPrivacyManager state is not readable by this app", t)
            null
        }
    }

    private fun queryEnabled(manager: SensorPrivacyManager, sensor: Int): Boolean {
        val cls = manager.javaClass
        val intType = Integer.TYPE
        val oneArg = try {
            cls.getMethod("isSensorPrivacyEnabled", intType)
        } catch (_: Throwable) {
            null
        }
        if (oneArg != null) {
            val result = oneArg.invoke(manager, sensor)
            if (result is Boolean) return result
        }
        // The two-arg form is (toggleType, sensor). Software toggle is 1.
        // TOGGLE_TYPE_SOFTWARE itself is an API 33 constant, so the raw value is used.
        val twoArg = cls.getMethod("isSensorPrivacyEnabled", intType, intType)
        val result = twoArg.invoke(manager, 1, sensor)
        if (result is Boolean) return result
        throw NoSuchMethodException("isSensorPrivacyEnabled")
    }
}
