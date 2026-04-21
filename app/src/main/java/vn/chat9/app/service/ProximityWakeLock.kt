package vn.chat9.app.service

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Proximity-triggered screen-off wake lock for voice calls.
 * When the user holds the phone to their ear, the proximity sensor fires and the
 * screen turns off (saves battery + blocks accidental cheek taps). When the phone
 * moves away, the screen wakes back up.
 *
 * Used only for audio calls — video calls need the screen on.
 */
object ProximityWakeLock {
    private const val TAG = "ProximityWakeLock"
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                Log.w(TAG, "PROXIMITY_SCREEN_OFF_WAKE_LOCK not supported on this device")
                return
            }
            wakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "9chat:proximity"
            ).also { it.acquire() }
        } catch (e: Exception) {
            Log.e(TAG, "acquire failed", e)
        }
    }

    @Synchronized
    fun release() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    // ON_AFTER_RELEASE ensures screen turns back on even if the sensor
                    // is still covered when we release (otherwise it can stay black
                    // until the user moves the phone).
                    it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                }
            }
        } catch (_: Exception) {}
        wakeLock = null
    }
}
