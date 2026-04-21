package vn.chat9.app.service

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.MainActivity
import vn.chat9.app.R

class FCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        // v2: added setAllowBubbles(true) — required for Android 11+ Bubbles
        // API. v1 channel "messages" cannot be retrofitted (Android freezes
        // channel properties after user first sees them), so we delete it and
        // create a fresh channel id; same pattern used for calls_v2 → calls_v3.
        private const val CHANNEL_MESSAGE = "messages_v2"
        // v3: silent sound, but channel-level 3-burst vibration enabled — reliable even when
        // the direct Vibrator.vibrate() from background is gated on Android 13+.
        private const val CHANNEL_CALL = "calls_v3"
    }

    override fun onNewToken(token: String) {
        sendTokenToServer(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "9chat"
        val body = data["body"] ?: message.notification?.body ?: "Tin nhắn mới"
        val type = data["type"] ?: "message"
        val roomId = data["room_id"]

        // Per-room mute: skip message notifications if user muted this room
        if (type != "call") {
            val mutedRoomId = data["room_id"]?.toIntOrNull() ?: 0
            if (mutedRoomId > 0) {
                val prefs = getSharedPreferences(MessageActionReceiver.MUTE_PREFS, MODE_PRIVATE)
                val until = prefs.getLong("room_$mutedRoomId", 0)
                if (until > System.currentTimeMillis()) return
            }
        }

        if (type == "call") {
            val callEvent = data["call_event"]
            // Call ended / cancelled / dismissed elsewhere → clear the INCOMING
            // notification only. DO NOT touch cancelAll — that would also kill
            // CallForegroundService's ongoing-call notification (id 99001),
            // which Android 12+ treats as "foreground service lost its notif →
            // kill the process". That was the cause of the "tap Accept → call
            // auto-ends" bug: server sends dismiss FCM to the same account's
            // tokens after call_accept, including the accepting device; old
            // cancelAll killed our own FG service → ICE failed → auto-end.
            if (callEvent == "ended" || callEvent == "cancelled" || callEvent == "dismiss") {
                val cid = data["call_id"] ?: ""
                if (cid.isNotEmpty()) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(cid.hashCode())  // stable id per call, keyed off call_id
                }
                // Only stop the ringtone / re-enter IDLE if we're still in the
                // ringing phase. V2 internally guards too, but a separate
                // check here keeps us from racing the accept flow.
                val state = vn.chat9.app.call.CallManager.state.value
                if (state == vn.chat9.app.call.model.CallState.RINGING) {
                    vn.chat9.app.call.audio.CallSoundPlayer.stopIncomingRingtone()
                    val reason = data["reason"] ?: "ended"
                    if (cid.isNotEmpty()) {
                        vn.chat9.app.call.CallManager.handleRemoteDismiss(cid, reason)
                    }
                }
                return
            }
            // Missed call — Zalo-style (red circle + white phone-missed icon + name)
            if (callEvent == "missed") {
                showMissedCallNotification(title, body, roomId, data)
                return
            }
            // Incoming call while app is foreground: socket event handles UI, skip notification
            if (callEvent == "incoming" && isAppInForeground()) return
            showCallNotification(title, body, roomId, callEvent, data)
            // Hand off to V2 so PeerConnection prewarm + buffered offer
            // handling starts in parallel with the user seeing the incoming UI.
            // handleIncomingFromFcm internally posts to Main — safe from FCM's
            // background worker thread (foot-gun #17).
            if (callEvent == "incoming") {
                try {
                    vn.chat9.app.call.CallManager.handleIncomingFromFcm(data)
                } catch (e: Exception) {
                    android.util.Log.e("FCMService", "V2 handleIncomingFromFcm failed", e)
                }
            }
        } else {
            showMessageNotification(title, body, roomId, data)
        }
    }

    private fun showMissedCallNotification(title: String, body: String, roomId: String?, data: Map<String, String>) {
        createNotificationChannels()

        // Dismiss the (still-ringing) incoming-call notification — by ID, not
        // cancelAll (which would also nuke CallForegroundService's 99001 and
        // trigger an Android 12+ FG-service kill). Same reason as the dismiss
        // branch above.
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        data["call_id"]?.let { manager.cancel(it.hashCode()) }
        // Stop V2's ringtone in case the socket call_ended didn't arrive first
        // (e.g. FCM-only delivery to a killed app). Belt-and-suspenders.
        vn.chat9.app.call.audio.CallSoundPlayer.stopIncomingRingtone()

        val replyIntent = buildMissedCallActionIntent(roomId, data, action = null)
        val callbackIntent = buildMissedCallActionIntent(roomId, data, action = "callback")
        val isVideo = data["call_type"] == "video"
        val iconRes = if (isVideo) R.drawable.ic_call_video_missed else R.drawable.ic_call_missed

        // Caller avatar as large icon (circular crop)
        val avatarBitmap = data["caller_avatar"]?.takeIf { it.isNotBlank() }?.let { path ->
            loadBitmapFromUrl(vn.chat9.app.util.UrlUtils.toFullUrl(path))?.let { toCircularBitmap(it) }
        }

        val displayName = data["caller_alias"]?.takeIf { it.isNotBlank() }
            ?: data["caller_name"]?.takeIf { it.isNotBlank() }
            ?: body

        val builder = NotificationCompat.Builder(this, CHANNEL_MESSAGE)
            .setSmallIcon(iconRes)
            .setColor(0xFFF44336.toInt())
            .setContentTitle(title)
            .setContentText(displayName)
            .setContentIntent(replyIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .addAction(
                NotificationCompat.Action.Builder(
                    iconRes, "Trả lời", replyIntent
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    iconRes, "Gọi lại", callbackIntent
                ).build()
            )
        if (avatarBitmap != null) builder.setLargeIcon(avatarBitmap)

        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun buildMissedCallActionIntent(roomId: String?, data: Map<String, String>, action: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            roomId?.let { putExtra("room_id", it) }
            data["caller_id"]?.let { putExtra("caller_id", it) }
            data["caller_name"]?.let { putExtra("caller_name", it) }
            data["call_type"]?.let { putExtra("call_type", it) }
            if (action == "callback") putExtra("call_action", "auto_callback")
        }
        val requestCode = when (action) {
            "callback" -> 3001
            else -> 3000
        } + (data["caller_id"]?.hashCode() ?: 0)
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun loadBitmapFromUrl(url: String?): android.graphics.Bitmap? {
        if (url.isNullOrBlank()) return null
        // FCMService.onMessageReceived has a hard 10s budget before Android
        // kills the worker. We're called on that thread and a slow VPS could
        // pin us for ~6s under the previous 3+3s timeouts — long enough to
        // delay the incoming-call ringtone past the point users give up.
        // 1.5+1.5s is plenty for 3G/4G in Vietnam; if we blow that budget,
        // the notification still posts without an avatar.
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                doInput = true
                connect()
            }
            android.graphics.BitmapFactory.decodeStream(conn.inputStream)
        } catch (t: Throwable) {
            // Catch Throwable, not Exception — BitmapFactory.decodeStream can
            // throw OutOfMemoryError on huge images, which would otherwise
            // crash the FCM worker.
            Log.w(TAG, "Failed to load avatar: ${t.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun toCircularBitmap(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val size = minOf(src.width, src.height)
        val output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint().apply { isAntiAlias = true }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val srcRect = android.graphics.Rect(
            (src.width - size) / 2,
            (src.height - size) / 2,
            (src.width + size) / 2,
            (src.height + size) / 2
        )
        val dstRect = android.graphics.Rect(0, 0, size, size)
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return output
    }

    private fun showMessageNotification(title: String, body: String, roomId: String?, data: Map<String, String>) {
        createNotificationChannels()
        val rid = roomId?.toIntOrNull() ?: 0
        val notificationId = if (rid > 0) rid else System.currentTimeMillis().toInt()
        val pendingIntent = buildOpenAppIntent(roomId)

        // Sender display name — prefer alias, fallback to username, fallback to title
        val displayName = data["sender_alias"]?.takeIf { it.isNotBlank() }
            ?: data["sender_name"]?.takeIf { it.isNotBlank() }
            ?: title

        // Sender avatar as large icon (circular crop)
        val avatarBitmap = data["sender_avatar"]?.takeIf { it.isNotBlank() }?.let { path ->
            val url = vn.chat9.app.util.UrlUtils.toFullUrl(path)
            loadBitmapFromUrl(url)?.let { toCircularBitmap(it) }
        }

        // Inline reply action
        val replyLabel = "Trả lời"
        val remoteInput = androidx.core.app.RemoteInput.Builder(MessageActionReceiver.REPLY_KEY)
            .setLabel(replyLabel).build()
        val replyIntent = Intent(this, MessageActionReceiver::class.java).apply {
            action = MessageActionReceiver.ACTION_REPLY
            putExtra("room_id", rid)
            putExtra("notification_id", notificationId)
        }
        val replyPending = PendingIntent.getBroadcast(
            this, 4000 + rid, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_notification, replyLabel, replyPending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // Mute 1 hour action
        val muteIntent = Intent(this, MessageActionReceiver::class.java).apply {
            action = MessageActionReceiver.ACTION_MUTE_ROOM
            putExtra("room_id", rid)
            putExtra("notification_id", notificationId)
        }
        val mutePending = PendingIntent.getBroadcast(
            this, 4001 + rid, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteAction = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Tắt t.báo 1 giờ", mutePending).build()

        // Accumulate recent messages for this room (Zalo-style stacked lines).
        // Cache stores up to 8 — Android shows ~4 collapsed, up to 8 expanded.
        val recent = NotificationMessageCache.add(this, rid, body, displayName)

        // Build MessagingStyle so Android renders multiple lines per conversation.
        val meUser = androidx.core.app.Person.Builder().setName("Tôi").build()
        val senderBuilder = androidx.core.app.Person.Builder()
            .setName(displayName)
            .setKey("sender_${data["sender_id"] ?: rid}")
        if (avatarBitmap != null) senderBuilder.setIcon(
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(avatarBitmap)
        )
        val sender = senderBuilder.build()
        val style = NotificationCompat.MessagingStyle(meUser)
        recent.forEach { e -> style.addMessage(e.text, e.time, sender) }

        // Publish a long-lived shortcut so Android (11+) treats this as a conversation
        // — hides the app-name header and uses the enhanced "Bố yêu 22:26" layout.
        val shortcutId = "room_$rid"
        if (rid > 0) {
            try {
                val openChatIntent = Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("room_id", rid.toString())
                }
                val shortcutBuilder = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, shortcutId)
                    .setLongLived(true)
                    .setShortLabel(displayName)
                    .setIntent(openChatIntent)
                    .setPerson(sender)
                    .setCategories(setOf("msg"))
                if (avatarBitmap != null) shortcutBuilder.setIcon(
                    androidx.core.graphics.drawable.IconCompat.createWithBitmap(avatarBitmap)
                )
                androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(this, shortcutBuilder.build())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push conversation shortcut: ${e.message}")
            }
        }

        // Delete intent → clear cache when user swipes the notification away
        val deleteIntent = Intent(this, MessageActionReceiver::class.java).apply {
            action = MessageActionReceiver.ACTION_CLEAR_CACHE
            putExtra("room_id", rid)
        }
        val deletePending = PendingIntent.getBroadcast(
            this, 4002 + rid, deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF3E1F91.toInt()) // 9chat brand purple — colors the badge overlay on avatar
            .setContentTitle(displayName)
            .setContentText(body)
            .setStyle(style)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .addAction(replyAction)
            .addAction(muteAction)
        if (rid > 0) builder.setShortcutId(shortcutId)
        if (avatarBitmap != null) builder.setLargeIcon(avatarBitmap)

        // ── Bubbles API (Android 11+) ────────────────────────────────────
        // Promote this notification to a Conversation Bubble (giống Zalo
        // floating chat bubble). Yêu cầu đã có sẵn ở trên: MessagingStyle +
        // long-lived ShortcutInfoCompat. Thiếu nốt 2 thứ:
        //   - setBubbleMetadata: trỏ tới BubbleActivity với room_id
        //   - setLocusId khớp shortcutId — Android dùng để link bubble với
        //     conversation đang chạy.
        // User phải bật "Bong bóng" trong cài đặt notification 1 lần đầu;
        // Android tự hỏi khi notification đầu tiên có bubble metadata đến.
        if (rid > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bubbleIntent = Intent(this, vn.chat9.app.ui.bubble.BubbleActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                putExtra("room_id", rid.toString())
            }
            val bubblePending = PendingIntent.getActivity(
                this, 5000 + rid, bubbleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            // Bubble icon hierarchy: bubble metadata icon wins over Person /
            // Shortcut icons. avatarBitmap is already circular (toCircularBitmap),
            // so use createWithBitmap to render as-is — createWithAdaptiveBitmap
            // expects 108dp adaptive icon format (foreground/background layers
            // with safe zone) which a pre-cropped circular bitmap doesn't satisfy
            // → Android fell back to default and showed the 9chat logo.
            val bubbleIcon = avatarBitmap?.let {
                androidx.core.graphics.drawable.IconCompat.createWithBitmap(it)
            } ?: androidx.core.graphics.drawable.IconCompat.createWithResource(
                this, R.drawable.ic_notification
            )
            val bubbleMeta = NotificationCompat.BubbleMetadata.Builder(bubblePending, bubbleIcon)
                .setDesiredHeight(384)             // int dp variant
                .setDesiredHeightResId(R.dimen.bubble_desired_height) // Samsung-friendly
                .setAutoExpandBubble(false)        // Không tự bung — user phải tap
                .setSuppressNotification(false)    // Vẫn giữ noti trong tray song song bubble
                .build()
            builder.setBubbleMetadata(bubbleMeta)
            builder.setLocusId(androidx.core.content.LocusIdCompat(shortcutId))
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
    }

    private fun showCallNotification(title: String, body: String, roomId: String?, callEvent: String?, data: Map<String, String>) {
        createNotificationChannels()
        val pendingIntent = buildCallIntent(roomId, data, extra = null)
        val isIncoming = callEvent == "incoming"
        val isVideo = data["call_type"] == "video"

        // Prefer alias over username for title
        val displayName = data["caller_alias"]?.takeIf { it.isNotBlank() }
            ?: data["caller_name"]?.takeIf { it.isNotBlank() }
            ?: title
        val displayBody = if (isIncoming) "Đang gọi đến" else body

        // Caller avatar as large icon (circular crop)
        val avatarBitmap = data["caller_avatar"]?.takeIf { it.isNotBlank() }?.let { path ->
            val url = vn.chat9.app.util.UrlUtils.toFullUrl(path)
            loadBitmapFromUrl(url)?.let { toCircularBitmap(it) }
        }

        val smallIconRes = if (isIncoming) {
            if (isVideo) R.drawable.ic_call_video else R.drawable.ic_call_incoming
        } else R.drawable.ic_notification

        val builder = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setSmallIcon(smallIconRes)
            .setColor(0xFF2196F3.toInt()) // blue accent like Zalo incoming
            .setContentTitle(displayName)
            .setContentText(displayBody)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (avatarBitmap != null) builder.setLargeIcon(avatarBitmap)

        if (isIncoming) {
            val acceptIntent = buildCallIntent(roomId, data, extra = "auto_accept")
            val rejectIntent = buildRejectBroadcast(data["call_id"])
            // Ringtone is owned by V2's CallSoundPlayer (via handleIncomingFromFcm
            // right after this, posting onto Main). Starting V1's RingtonePlayer
            // here AS WELL caused two independent ringtones playing simultaneously
            // ("reo 2 lần chuông") and — worse — V2's stop-paths never touched V1,
            // so if the user accepted or the call ended via server echo, V1's
            // ringtone kept playing forever ("kêu mãi mặc dù đã ngắt cuộc gọi").
            builder.setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(pendingIntent, true)
                .addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_call_rejected, "Từ chối", rejectIntent
                    ).build()
                )
                .addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_call_incoming, "Trả lời", acceptIntent
                    ).build()
                )
        } else {
            builder.setAutoCancel(true)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // Stable notification ID keyed off the call_id so the dismiss path can
        // cancel THIS notification specifically (not via cancelAll, which
        // would also kill CallForegroundService's ongoing-call notif).
        val stableId = data["call_id"]?.hashCode() ?: System.currentTimeMillis().toInt()
        manager.notify(stableId, builder.build())
    }

    private fun buildOpenAppIntent(roomId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            roomId?.let { putExtra("room_id", it) }
        }
        val requestCode = System.currentTimeMillis().toInt()
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildRejectBroadcast(callId: String?): PendingIntent {
        val intent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_REJECT_CALL
            callId?.let { putExtra("call_id", it) }
        }
        val requestCode = 2000 + (callId?.hashCode() ?: 0)
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildCallIntent(roomId: String?, data: Map<String, String>, extra: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            roomId?.let { putExtra("room_id", it) }
            data["call_event"]?.let { putExtra("call_event", it) }
            data["call_id"]?.let { putExtra("call_id", it) }
            data["caller_name"]?.let { putExtra("caller_name", it) }
            data["caller_id"]?.let { putExtra("caller_id", it) }
            data["caller_alias"]?.takeIf { it.isNotBlank() }?.let { putExtra("caller_alias", it) }
            data["caller_avatar"]?.takeIf { it.isNotBlank() }?.let { putExtra("caller_avatar", it) }
            data["call_type"]?.let { putExtra("call_type", it) }
            if (extra != null) putExtra("call_action", extra)
        }
        // Different request codes so PendingIntents are distinct
        val requestCode = when (extra) {
            "auto_accept" -> 1001
            "auto_reject" -> 1002
            else -> 1000
        } + (data["call_id"]?.hashCode() ?: 0)
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val processes = am.runningAppProcesses ?: return false
        return processes.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                it.processName == packageName
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Clean up legacy message channel (lacked setAllowBubbles → Android
        // never promoted notifications to bubbles regardless of system bubble
        // permission). Must be deleted before creating the v2 channel below.
        try { manager.deleteNotificationChannel("messages") } catch (_: Exception) {}

        if (manager.getNotificationChannel(CHANNEL_MESSAGE) == null) {
            val messageChannel = NotificationChannel(
                CHANNEL_MESSAGE, "Tin nhắn",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo tin nhắn mới"
                // Required for Android 11+ Bubbles API to render conversation
                // bubbles. Channel-level toggle in addition to per-app and
                // per-conversation bubble permissions.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowBubbles(true)
                }
            }
            manager.createNotificationChannel(messageChannel)
        }

        // Clean up legacy call channels (different vibration config each generation)
        try { manager.deleteNotificationChannel("calls") } catch (_: Exception) {}
        try { manager.deleteNotificationChannel("calls_v2") } catch (_: Exception) {}

        if (manager.getNotificationChannel(CHANNEL_CALL) == null) {
            val callChannel = NotificationChannel(
                CHANNEL_CALL, "Cuộc gọi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo cuộc gọi đến"
                setSound(null, null) // Silent — RingtonePlayer handles sound via MediaPlayer
                // 3-burst channel vibration. Acts as a backup in case the direct
                // Vibrator.vibrate() from RingtonePlayer is gated by the OS when the
                // caller is a background FirebaseMessagingService on Android 13+.
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 450, 500, 450, 500, 450)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(callChannel)
        }
    }

    private fun sendTokenToServer(token: String) {
        try {
            val app = application as? App ?: return
            if (!app.container.tokenManager.isLoggedIn) return

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.container.api.subscribePush(
                        vn.chat9.app.data.model.PushSubscribeRequest(
                            endpoint = token,
                            keys = vn.chat9.app.data.model.PushKeys("fcm", "fcm")
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send token", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending token", e)
        }
    }
}
