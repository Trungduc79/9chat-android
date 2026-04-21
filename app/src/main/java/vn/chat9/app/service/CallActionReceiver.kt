package vn.chat9.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import vn.chat9.app.App

class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REJECT_CALL = "vn.chat9.app.ACTION_REJECT_CALL"
        private const val TAG = "CallActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REJECT_CALL) return

        val callId = intent.getStringExtra("call_id")

        // Dismiss ONLY the matching call notification (keyed off call_id
        // hashCode, same stable ID FCMService uses). cancelAll() here
        // would also kill CallForegroundService's 99001 notif, which on
        // Android 12+ takes the process with it.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        callId?.let { nm.cancel(it.hashCode()) }
        // V2's CallSoundPlayer owns the ringtone now (V1 RingtonePlayer
        // deleted as part of the V2 cleanup pass).
        vn.chat9.app.call.audio.CallSoundPlayer.stopIncomingRingtone()

        if (callId.isNullOrBlank()) return
        val app = context.applicationContext as? App ?: return
        if (!app.container.tokenManager.isLoggedIn) return

        // Fire-and-forget server notification so caller gets immediate "rejected" signal
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.api.rejectCall(mapOf("call_id" to callId))
            } catch (e: Exception) {
                Log.e(TAG, "Reject API failed for $callId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
