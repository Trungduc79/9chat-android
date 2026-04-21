package vn.chat9.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.chat9.app.App

class MessageActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "vn.chat9.app.ACTION_MESSAGE_REPLY"
        const val ACTION_MUTE_ROOM = "vn.chat9.app.ACTION_MUTE_ROOM"
        const val ACTION_CLEAR_CACHE = "vn.chat9.app.ACTION_CLEAR_NOTIF_CACHE"
        const val REPLY_KEY = "reply_text"
        const val MUTE_PREFS = "room_mute"
        const val MUTE_DURATION_MS = 60 * 60 * 1000L // 1 hour
        private const val TAG = "MessageAction"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = intent.getIntExtra("notification_id", 0)
        val roomId = intent.getIntExtra("room_id", 0)

        when (intent.action) {
            ACTION_CLEAR_CACHE -> {
                if (roomId > 0) NotificationMessageCache.clear(context, roomId)
            }

            ACTION_MUTE_ROOM -> {
                if (roomId > 0) {
                    val prefs = context.getSharedPreferences(MUTE_PREFS, Context.MODE_PRIVATE)
                    val until = System.currentTimeMillis() + MUTE_DURATION_MS
                    prefs.edit().putLong("room_$roomId", until).apply()
                }
                // Cancel by specific id only — never cancelAll(), which would
                // also kill CallForegroundService's id 99001 and trigger an
                // Android 12+ FG-service-loss process kill mid-call. Per
                // FCMService:216, message notif id == room_id, so roomId is a
                // safe fallback when the action intent lacks notification_id.
                when {
                    notificationId != 0 -> nm.cancel(notificationId)
                    roomId > 0 -> nm.cancel(roomId)
                }
            }

            ACTION_REPLY -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REPLY_KEY)?.toString()?.trim()
                if (replyText.isNullOrBlank() || roomId == 0) {
                    if (notificationId != 0) nm.cancel(notificationId)
                    return
                }
                val app = context.applicationContext as? App ?: return
                if (!app.container.tokenManager.isLoggedIn) return

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (!app.container.socket.isConnected) app.container.socket.connect()
                        var wait = 0
                        while (!app.container.socket.isConnected && wait < 50) {
                            delay(100); wait++
                        }
                        if (!app.container.socket.isConnected) {
                            Log.w(TAG, "Socket never connected — reply dropped")
                            return@launch
                        }
                        app.container.socket.sendMessage(
                            roomId = roomId,
                            type = "text",
                            content = replyText
                        )
                        NotificationMessageCache.clear(context, roomId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Reply failed", e)
                    } finally {
                        if (notificationId != 0) nm.cancel(notificationId)
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
