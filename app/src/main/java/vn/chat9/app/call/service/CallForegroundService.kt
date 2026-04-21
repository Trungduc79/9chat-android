package vn.chat9.app.call.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import vn.chat9.app.MainActivity
import vn.chat9.app.R

/**
 * Keeps the app's process alive while a call is in progress.
 *
 * Android 12+ (API 31+) aggressively kills background processes after
 * ~60 seconds when the app is swiped away. Without a foreground service,
 * calls silently die mid-conversation if the user navigates away from
 * the CallScreen or backgrounds the app.
 *
 * Started from CallManager at state transition to INIT (outgoing) or
 * ACCEPTED (incoming after the user taps Nhận). Notification text is
 * updated on each state change via [updateState]. Stopped when the call
 * reaches any terminal state.
 *
 * Notification channel "calls_active" is separate from "calls_v2"
 * (incoming ringtone channel) so users can silence one without the other.
 */
class CallForegroundService : Service() {

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "Cuộc gọi"
        val stateText = intent?.getStringExtra(EXTRA_STATE_TEXT) ?: "Đang gọi…"
        val isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) ?: false
        val roomId = intent?.getIntExtra(EXTRA_ROOM_ID, 0) ?: 0

        val notif = buildNotification(peerName, stateText, isVideo, roomId)
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: Must declare either phoneCall or microphone|camera type.
            // phoneCall requires matching foregroundServiceType in manifest.
            if (isVideo) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fgType != 0) {
            startForeground(NOTIFICATION_ID, notif, fgType)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        peerName: String,
        stateText: String,
        isVideo: Boolean,
        roomId: Int,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("room_id", roomId)
            putExtra("open_call", true)
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (isVideo) "Cuộc gọi video với $peerName" else "Cuộc gọi với $peerName"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(stateText)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Cuộc gọi đang diễn ra",
                    NotificationManager.IMPORTANCE_LOW,  // no sound (ringtone handled elsewhere)
                ).apply {
                    description = "Hiển thị khi cuộc gọi đang hoạt động"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                notificationManager.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        private const val TAG = "CallFgService"
        private const val CHANNEL_ID = "calls_active"
        private const val NOTIFICATION_ID = 99001

        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_STATE_TEXT = "state_text"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_ROOM_ID = "room_id"

        /**
         * Start or update the foreground service. Safe to call repeatedly —
         * each call refreshes the notification text in place.
         */
        fun start(
            context: Context,
            peerName: String,
            stateText: String,
            isVideo: Boolean,
            roomId: Int,
        ) {
            try {
                val intent = Intent(context, CallForegroundService::class.java).apply {
                    putExtra(EXTRA_PEER_NAME, peerName)
                    putExtra(EXTRA_STATE_TEXT, stateText)
                    putExtra(EXTRA_IS_VIDEO, isVideo)
                    putExtra(EXTRA_ROOM_ID, roomId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CallForegroundService::class.java))
            } catch (_: Exception) {}
        }
    }
}
