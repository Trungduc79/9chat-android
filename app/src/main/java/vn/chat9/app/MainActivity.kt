package vn.chat9.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import vn.chat9.app.call.CallManager as CallManagerV2
import vn.chat9.app.call.model.CallState as CallStateV2
import vn.chat9.app.call.model.CallType as CallTypeV2
import vn.chat9.app.data.model.Room
import vn.chat9.app.ui.auth.LoginScreen
import vn.chat9.app.ui.auth.RegisterScreen
import vn.chat9.app.ui.call.CallScreenHost
import vn.chat9.app.ui.chat.ChatScreen
import vn.chat9.app.ui.main.HomeScreen
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import vn.chat9.app.ui.theme._9chatTheme

class MainActivity : ComponentActivity() {

    private var _pendingRoomId: androidx.compose.runtime.MutableState<Int?>? = null
    private var _pendingCallIntent: androidx.compose.runtime.MutableState<android.content.Intent?>? = null
    private var _pendingCallback: androidx.compose.runtime.MutableState<Pair<Int, Boolean>?>? = null
    // Destination produced by DeepLinkRouter.fromIntent for intents arriving
    // through onNewIntent (URI deep links while the activity is already alive).
    private var _pendingDestination: androidx.compose.runtime.MutableState<vn.chat9.app.navigation.AppDestination?>? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("call_event") == "incoming") {
            // Activity already alive (singleTop) → onCreate's lockscreen flags
            // didn't run for this intent. Apply them again here so the call UI
            // pierces the keyguard and wakes the screen. Without this, an
            // incoming call arriving while the app was backgrounded under a
            // locked screen renders silently behind the lockscreen.
            applyIncomingCallWindowFlags()
            _pendingCallIntent?.value = intent
            dismissNotificationsForIntent(intent)
            return
        }
        if (intent.getStringExtra("call_action") == "auto_callback") {
            val rid = intent.getStringExtra("room_id")?.toIntOrNull()
            val isVideo = intent.getStringExtra("call_type") == "video"
            if (rid != null) _pendingCallback?.value = rid to isVideo
            dismissNotificationsForIntent(intent)
            return
        }
        val roomId = intent.getStringExtra("room_id")?.toIntOrNull()
        if (roomId != null) {
            _pendingRoomId?.value = roomId
            dismissNotificationsForIntent(intent)
            return
        }
        // Fall through to generic deep-link parsing (URI-based intents).
        val dest = vn.chat9.app.navigation.DeepLinkRouter.fromIntent(intent)
        if (dest != null) _pendingDestination?.value = dest
    }

    /**
     * Apply window flags so an incoming-call intent renders ON TOP of the
     * keyguard / lockscreen and wakes the screen. Must be called from BOTH
     * onCreate (fresh launch) and onNewIntent (singleTop re-use) — otherwise
     * a call arriving while MainActivity is already alive lands behind the
     * lockscreen with the screen still off.
     *
     * Deliberately does NOT use FLAG_DISMISS_KEYGUARD or
     * KeyguardManager.requestDismissKeyguard — those would prompt the user
     * for PIN/biometric and block the incoming-call UI behind an auth dialog.
     * The combo here is enough to render over keyguard and accept tap input.
     */
    private fun applyIncomingCallWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "9chat:incomingCall"
            )
            wakeLock.acquire(10_000L)
        } catch (e: Exception) {
            Log.e("Call", "incoming-call wakeLock failed", e)
        }
    }

    /**
     * Android 14+ (API 34, UPSIDE_DOWN_CAKE) revokes USE_FULL_SCREEN_INTENT by
     * default for non-call-category apps. Without it, incoming-call notifications
     * are demoted to a status-bar peek — the user won't see the call UI when
     * the screen is locked. Critical for the customer-support use case.
     *
     * Strategy: explain in Vietnamese first, THEN deep-link to settings (the
     * platform doesn't expose a runtime dialog for this permission). Throttle
     * to once per 24h so we don't spam users who said "Để sau".
     */
    private fun ensureFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return
        if (nm.canUseFullScreenIntent()) return

        val prefs = getSharedPreferences("perm_prompt", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastAsk = prefs.getLong("fsi_last_ask", 0L)
        if (now - lastAsk < 24 * 60 * 60 * 1000L) return
        prefs.edit().putLong("fsi_last_ask", now).apply()

        android.app.AlertDialog.Builder(this)
            .setTitle("Cho phép hiển thị cuộc gọi đến")
            .setMessage(
                "Để khách hàng có thể gọi tới và bạn nhìn thấy ngay cả khi máy đang khóa, " +
                "9chat cần quyền \"Hiển thị thông báo toàn màn hình\". Vui lòng bật trong cài đặt."
            )
            .setPositiveButton("Mở cài đặt") { _, _ ->
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:$packageName")
                    ))
                } catch (e: Exception) {
                    Log.e("FSI", "Can't open full-screen settings", e)
                }
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    /**
     * Cancel ONLY the notification that triggered this Activity launch — never
     * the whole tray. Calling NotificationManager.cancelAll() here would also
     * kill CallForegroundService's ongoing-call notification (id 99001), which
     * Android 12+ treats as "foreground service lost its notif → kill the
     * process" — that was the cause of mid-call drops when an unrelated
     * notification arrived during a call.
     *
     * Notification id contract (must mirror FCMService):
     *   - Incoming / missed / dismiss call notif → id = call_id.hashCode()
     *   - Per-room message notif                 → id = room_id (positive int)
     *   - CallForegroundService                  → id = 99001 (UNTOUCHABLE)
     */
    private fun dismissNotificationsForIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return
        intent.getStringExtra("call_id")?.takeIf { it.isNotBlank() }?.let { cid ->
            nm.cancel(cid.hashCode())
        }
        intent.getStringExtra("room_id")?.toIntOrNull()?.takeIf { it > 0 }?.let { rid ->
            nm.cancel(rid)
            // Also flush the per-room MessagingStyle cache so reopening the
            // room and getting new pushes starts from a clean slate (fixes
            // stale "old messages re-appear in notification" UX bug).
            vn.chat9.app.service.NotificationMessageCache.clear(this, rid)
        }
    }

    /**
     * Fetches room + waits for socket, then invokes [onReady] on the main thread
     * to kick off the call. Runs outside Compose so state changes inside [onReady]
     * can't cancel this coroutine via LeftCompositionCancellationException.
     */
    private suspend fun runAutoCallback(
        roomId: Int,
        isVideo: Boolean,
        onReady: (vn.chat9.app.data.model.Room, Boolean) -> Unit,
        onFail: () -> Unit,
    ) {
        val container = (application as App).container
        try {
            if (!container.socket.isConnected) container.socket.connect()
            val res = container.api.getRooms()
            if (!res.success || res.data == null) {
                android.util.Log.w("Callback", "getRooms failed: ${res.message}")
                runOnUiThread { android.widget.Toast.makeText(this, "Không tải được danh sách phòng", android.widget.Toast.LENGTH_SHORT).show() }
                onFail(); return
            }
            val room = res.data.find { it.id == roomId }
            if (room == null) {
                android.util.Log.w("Callback", "Room $roomId not found (got ${res.data.size})")
                runOnUiThread { android.widget.Toast.makeText(this, "Không tìm thấy phòng chat", android.widget.Toast.LENGTH_SHORT).show() }
                onFail(); return
            }
            var wait = 0
            while (!container.socket.isConnected && wait < 150) {
                kotlinx.coroutines.delay(100); wait++
            }
            if (!container.socket.isConnected) {
                android.util.Log.e("Callback", "Socket never connected after 15s")
                runOnUiThread { android.widget.Toast.makeText(this, "Không kết nối được server, thử lại", android.widget.Toast.LENGTH_LONG).show() }
                onFail(); return
            }
            kotlinx.coroutines.delay(300)
            onReady(room, isVideo)
        } catch (e: Exception) {
            android.util.Log.e("Callback", "auto_callback failed", e)
            runOnUiThread { android.widget.Toast.makeText(this, "Lỗi gọi lại: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            onFail()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SoundPool warm-up is owned by V2 — CallManager.init() (called from
        // App.onCreate before this Activity) preloads CallSoundPlayer tones.

        // Always run edge-to-edge. ChatScreen + HomeScreen already pad for the
        // system bars (statusBarsPadding / systemBarsPadding / navigationBarsPadding),
        // so this is the one source of truth. Without this, the CallScreen toggling
        // decor-fits on/off would leave the window in an inconsistent state when
        // control returns (e.g. bottom tab bar hidden under the nav bar).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // When launched from call notification, show the CallScreen DIRECTLY
        // OVER the lockscreen without forcing the user to unlock.
        //
        // Deliberately does NOT call KeyguardManager.requestDismissKeyguard —
        // that API asks the system to prompt for PIN/biometric, which was
        // what was blocking the incoming-call UI behind an auth prompt. The
        // combination of setShowWhenLocked + setTurnScreenOn is enough to
        // render the activity on top of keyguard and accept Accept/Reject
        // taps; if the user wants to keep using the app after hanging up,
        // they unlock the device then as usual.
        //
        // Keep the legacy flags for API < 27 where setShowWhenLocked doesn't
        // exist — same semantics via FLAG_SHOW_WHEN_LOCKED + FLAG_TURN_SCREEN_ON.
        // DO NOT add FLAG_DISMISS_KEYGUARD — same reason as above.
        val isCallLaunch = intent?.getStringExtra("call_event") == "incoming"
        if (isCallLaunch) {
            applyIncomingCallWindowFlags()
            // Cancel the call notification since we're showing the UI now.
            // Keep ringtone playing if user just tapped the body — they're seeing the
            // incoming UI and need to pick accept/reject. Only stop ringtone on an
            // explicit accept/reject action button.
            dismissNotificationsForIntent(intent)
            val action = intent?.getStringExtra("call_action")
            if (action == "auto_accept" || action == "auto_reject") {
                // V2's acceptCall / rejectCall stop the ringtone via
                // CallSoundPlayer themselves — no explicit stop needed here.
            }
        }

        // Always dismiss any notification that triggered this Activity launch
        // (incoming call / missed call / message / callback action) — by id, not
        // tray-wide. See dismissNotificationsForIntent for the id contract.
        if (intent?.hasExtra("call_event") == true
            || intent?.hasExtra("call_action") == true
            || intent?.hasExtra("room_id") == true
        ) {
            dismissNotificationsForIntent(intent)
        }

        val container = (application as App).container
        // True if this Activity instance was launched specifically for a call flow
        // (incoming call, "Gọi lại" from missed-call notification, or direct
        // call dispatch from the bubble — direct_call=true). When the call
        // ends, we finishAndRemoveTask the Activity so the user returns to
        // whatever they were doing before. For the bubble case, finishing this
        // task lets the system resume the bubble's separate task — bubble UI
        // returns to view, never auto-removed.
        val launchedFromCall = isCallLaunch
            || intent?.getStringExtra("call_action") == "auto_callback"
            || intent?.getBooleanExtra("direct_call", false) == true

        setContent {
            _9chatTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    val scope = rememberCoroutineScope()
                    var screen by remember {
                        mutableStateOf(if (container.tokenManager.isLoggedIn) "home" else "login")
                    }
                    var selectedRoom by remember { mutableStateOf<Room?>(null) }
                    var selectedTab by remember { mutableIntStateOf(0) }
                    var roomRefreshKey by remember { mutableIntStateOf(0) }
                    var scrollToMessageId by remember { mutableStateOf<Int?>(null) }
                    var wallUserId by remember { mutableStateOf<Int?>(null) }
                    var openChatSearch by remember { mutableStateOf(false) }
                    // Payloads from an incoming ACTION_SEND / SEND_MULTIPLE
                    // intent. Set by navigateTo(ShareIncoming) + consumed by
                    // the share compose screen below.
                    var sharePayloads by remember {
                        mutableStateOf<List<vn.chat9.app.navigation.SharePayload>>(emptyList())
                    }
                    // Pending auto-call action after a Chat destination navigation.
                    // Used by DeepLinkRouter when the entry intent has call_action=auto_callback.
                    var pendingAutoCall by remember {
                        mutableStateOf<vn.chat9.app.navigation.CallAction?>(null)
                    }
                    // Remembers the screen that opened Wall so back returns there
                    // (e.g. AddFriend → Wall → back → AddFriend, not home).
                    var wallReturnTo by remember { mutableStateOf<String?>(null) }
                    // pendingRoomIdState is still exposed for onNewIntent handling
                    // (notifications arriving while activity is already alive).
                    val pendingRoomIdState = remember { mutableStateOf<Int?>(null) }
                    var pendingRoomId by pendingRoomIdState
                    _pendingRoomId = pendingRoomIdState
                    val pendingDestinationState = remember {
                        mutableStateOf<vn.chat9.app.navigation.AppDestination?>(null)
                    }
                    _pendingDestination = pendingDestinationState

                    // -----------------------------------------------------------
                    // UNIFIED NAVIGATION ENTRY POINT.
                    //
                    // Every place that needs to move the user to a different
                    // screen (room list tap, contacts tap, FCM intent, QR deep
                    // link, web URL, search result...) must go through this
                    // single lambda. For Chat destinations the room is always
                    // fetched via `rooms/detail.php` → `getForUser`, producing
                    // an identical payload shape regardless of entry point.
                    // This is the contract that prevents chat layout from
                    // varying based on where the user came from.
                    //
                    // See: navigation/AppDestination.kt, navigation/DeepLinkRouter.kt
                    // -----------------------------------------------------------
                    val navigateTo: (vn.chat9.app.navigation.AppDestination) -> Unit = { dest ->
                        when (dest) {
                            is vn.chat9.app.navigation.AppDestination.Home -> {
                                selectedTab = dest.tab.index
                                selectedRoom = null
                                wallUserId = null
                                screen = "home"
                            }
                            is vn.chat9.app.navigation.AppDestination.Chat -> {
                                scope.launch {
                                    try {
                                        val res = container.api.getRoomDetail(dest.roomId)
                                        if (res.success && res.data != null) {
                                            selectedRoom = res.data
                                            scrollToMessageId = dest.scrollToMessageId
                                            pendingAutoCall = dest.autoCall
                                            screen = "chat"
                                            vn.chat9.app.service.NotificationMessageCache
                                                .clear(this@MainActivity, dest.roomId)
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                            is vn.chat9.app.navigation.AppDestination.Wall -> {
                                // Remember where we came from so back goes back
                                // to that exact screen (AddFriend, Chat, etc.).
                                wallReturnTo = screen.takeIf { it != "wall" }
                                wallUserId = dest.userId
                                screen = "wall"
                            }
                            is vn.chat9.app.navigation.AppDestination.Search -> {
                                screen = "search"
                            }
                            vn.chat9.app.navigation.AppDestination.ProfileEdit -> {
                                screen = "profile"
                            }
                            vn.chat9.app.navigation.AppDestination.QrScanner -> {
                                screen = "qr"
                            }
                            vn.chat9.app.navigation.AppDestination.AddFriend -> {
                                screen = "add_friend"
                            }
                            vn.chat9.app.navigation.AppDestination.Timeline -> {
                                selectedTab = vn.chat9.app.navigation.HomeTab.TIMELINE.index
                                selectedRoom = null
                                screen = "home"
                            }
                            is vn.chat9.app.navigation.AppDestination.ShareIncoming -> {
                                sharePayloads = dest.payloads
                                screen = "share"
                            }
                        }
                    }

                    // Parse launch intent through the deep-link router — handles
                    // FCM message taps, missed-call "Gọi lại", 9chat:// URIs,
                    // and https://9chat.vn web fallbacks in a single codepath.
                    LaunchedEffect(Unit) {
                        if (!container.tokenManager.isLoggedIn) return@LaunchedEffect
                        val dest = vn.chat9.app.navigation.DeepLinkRouter.fromIntent(intent)
                        if (dest != null) navigateTo(dest)
                    }

                    // Deep-link arriving via onNewIntent (activity already alive).
                    LaunchedEffect(pendingDestinationState.value) {
                        val dest = pendingDestinationState.value ?: return@LaunchedEffect
                        pendingDestinationState.value = null
                        if (container.tokenManager.isLoggedIn) navigateTo(dest)
                    }

                    // Handle session expired (401 from API)
                    LaunchedEffect(Unit) {
                        container.sessionExpired.collect {
                            container.tokenManager.clear()
                            try { container.socket.disconnect() } catch (_: Exception) {}
                            selectedRoom = null
                            wallUserId = null
                            pendingRoomId = null
                            pendingAutoCall = null
                            screen = "login"
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    // onNewIntent → sets pendingRoomId for existing activity.
                    // Route it through navigateTo for consistency.
                    LaunchedEffect(pendingRoomId) {
                        val rid = pendingRoomId ?: return@LaunchedEffect
                        pendingRoomId = null
                        navigateTo(vn.chat9.app.navigation.AppDestination.Chat(rid))
                    }

                    // Friend alias map for resolving incoming caller display name
                    var friendAliases by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
                    var friendAvatars by remember { mutableStateOf<Map<Int, String?>>(emptyMap()) }
                    LaunchedEffect(Unit) {
                        if (container.tokenManager.isLoggedIn) {
                            try {
                                val res = container.api.getFriends("friends")
                                if (res.success && res.data != null) {
                                    friendAliases = res.data.mapNotNull { f ->
                                        val a = f.alias
                                        if (!a.isNullOrBlank()) f.id to a else null
                                    }.toMap()
                                    friendAvatars = res.data.associate { it.id to it.avatar }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    // ── V2 Call state (observed from CallManager singleton) ──
                    // All per-call state (active?, mic muted?, speaker on?, etc.) now
                    // lives in vn.chat9.app.call.CallManager and flows back through
                    // StateFlows. MainActivity is no longer the state owner — it
                    // observes and hands off intents.
                    val callState by CallManagerV2.state.collectAsState()
                    val currentCall by CallManagerV2.currentCall.collectAsState()
                    val callActive = callState != CallStateV2.IDLE
                    val callIsVideo = currentCall?.type == CallTypeV2.VIDEO

                    // When a call becomes active while user is typing in chat (or any input),
                    // hide keyboard + drop focus so the CallScreen overlay fully covers the UI.
                    // Does NOT clear chat input text — ChatScreen's remembered state stays intact.
                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    LaunchedEffect(callActive) {
                        if (callActive) {
                            keyboardController?.hide()
                            focusManager.clearFocus(force = true)
                        }
                    }

                    // Pending launch intent from call notification (incoming / auto_accept / auto_reject)
                    val pendingCallIntentState = remember { mutableStateOf<android.content.Intent?>(null) }
                    var pendingAutoAction by remember { mutableStateOf<String?>(null) }
                    _pendingCallIntent = pendingCallIntentState
                    LaunchedEffect(Unit) {
                        if (intent?.getStringExtra("call_event") == "incoming") {
                            pendingCallIntentState.value = intent
                        }
                    }

                    // Route pending call intents into V2. V2 handles all state (peer name,
                    // avatar, video vs voice, ringing UI) internally — MainActivity just
                    // hands off and lets the state flow drive the UI.
                    LaunchedEffect(pendingCallIntentState.value) {
                        val ci = pendingCallIntentState.value ?: return@LaunchedEffect
                        if (!container.tokenManager.isLoggedIn) {
                            pendingCallIntentState.value = null
                            return@LaunchedEffect
                        }
                        val action = ci.getStringExtra("call_action")
                        fun String?.cleanOrNull(): String? {
                            val s = this?.trim() ?: return null
                            return if (s.isBlank() || s.equals("null", true) || s.equals("undefined", true)) null else s
                        }
                        val cidFromIntent = ci.getStringExtra("caller_id")?.toIntOrNull() ?: 0
                        val rawName = ci.getStringExtra("caller_name").cleanOrNull()
                        val rawAlias = ci.getStringExtra("caller_alias").cleanOrNull()
                        val rawAvatar = ci.getStringExtra("caller_avatar").cleanOrNull()
                        val resolvedName = rawAlias
                            ?: friendAliases[cidFromIntent]
                            ?: rawName
                            ?: "Cuộc gọi đến"
                        val resolvedAvatar = rawAvatar ?: friendAvatars[cidFromIntent]
                        val type = if (ci.getStringExtra("call_type") == "video")
                            CallTypeV2.VIDEO else CallTypeV2.AUDIO
                        val cid = ci.getStringExtra("call_id") ?: ""
                        val roomIdFromIntent = ci.getStringExtra("room_id")?.toIntOrNull() ?: 0

                        if (!container.socket.isConnected) container.socket.connect()
                        CallManagerV2.handleIncomingFromIntent(
                            callId = cid,
                            callerId = cidFromIntent,
                            callerName = resolvedName,
                            callerAvatar = resolvedAvatar,
                            roomId = roomIdFromIntent,
                            type = type,
                        )
                        pendingAutoAction = action
                        pendingCallIntentState.value = null
                    }


                    // Instantly finish the Activity when the call ends, if it was
                    // launched specifically for this incoming call. This avoids a
                    // visible flash of the underlying app between CallScreen dismissal
                    // and Activity teardown.
                    val closeAppIfCallLaunch: () -> Unit = {
                        if (launchedFromCall) {
                            finishAndRemoveTask()
                            @Suppress("DEPRECATION")
                            overridePendingTransition(0, 0)
                        }
                    }

                    // Call duration timer is now owned by CallViewModel — CallScreenHost reads it.

                    // Proximity sensor → screen off while phone is at the ear.
                    // Voice calls only — video needs the screen on. Driven by V2 state.
                    DisposableEffect(callActive, callIsVideo) {
                        if (callActive && !callIsVideo) {
                            vn.chat9.app.service.ProximityWakeLock.acquire(applicationContext)
                        } else {
                            vn.chat9.app.service.ProximityWakeLock.release()
                        }
                        onDispose { vn.chat9.app.service.ProximityWakeLock.release() }
                    }

                    // Instantly finish the Activity when a call-launch call ends so the
                    // user returns to the underlying app instead of seeing home. Only
                    // fires AFTER we've observed callActive=true at least once —
                    // otherwise the initial callActive=false would finish the activity
                    // before the call even starts.
                    val sawActiveCall = remember { mutableStateOf(false) }
                    LaunchedEffect(callActive) {
                        if (callActive) sawActiveCall.value = true
                        if (launchedFromCall && !callActive && sawActiveCall.value) {
                            kotlinx.coroutines.delay(200)  // let V2 terminal state render briefly
                            finishAndRemoveTask()
                            @Suppress("DEPRECATION")
                            overridePendingTransition(0, 0)
                        }
                    }

                    // Initialize V2 CallManager singleton — idempotent. Hooks up socket
                    // listeners (call_signaling / call_incoming / call_ringing / call_accepted
                    // / call_ended / call_dismiss / call_signal / call_error) internally.
                    LaunchedEffect(Unit) {
                        CallManagerV2.init(applicationContext, container.socket)
                    }

                    // Surface end-of-call reasons as transient toasts. The
                    // call overlay dismisses immediately on terminal state,
                    // so without this the user just sees the screen vanish
                    // with no explanation (rejected vs busy vs no-answer).
                    LaunchedEffect(Unit) {
                        CallManagerV2.endEvents.collect { reason ->
                            val msg = when (reason) {
                                vn.chat9.app.call.model.CallEndReason.REJECTED -> "Người nhận từ chối cuộc gọi"
                                vn.chat9.app.call.model.CallEndReason.BUSY -> "Người dùng bận"
                                vn.chat9.app.call.model.CallEndReason.NO_ANSWER -> "Không trả lời"
                                vn.chat9.app.call.model.CallEndReason.NETWORK -> "Mất kết nối"
                                vn.chat9.app.call.model.CallEndReason.ANSWERED_ELSEWHERE ->
                                    "Cuộc gọi đã trả lời trên thiết bị khác"
                                else -> null
                            }
                            if (msg != null) {
                                android.widget.Toast.makeText(
                                    this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }


                    fun hasCallPermissions(isVideo: Boolean): Boolean {
                        val hasAudio = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val hasCamera = if (isVideo) ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED else true
                        // Dormant V1 bug — `return hasAudio` ignored hasCamera, so a
                        // first video call with CAMERA ungranted passed this check,
                        // then V2's stricter hasRequiredPermissions silently rejected
                        // (test 2 regression — "nhấn gọi video không có phản hồi").
                        return hasAudio && hasCamera
                    }

                    fun requestCallPermissions(isVideo: Boolean) {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (isVideo) perms.add(Manifest.permission.CAMERA)
                        ActivityCompat.requestPermissions(this@MainActivity, perms.toTypedArray(), 100)
                    }

                    // Execute pending auto-action (auto_accept / auto_reject) from
                    // notification action tap. V2 handles the actual logic — we just
                    // invoke the right method once the call state has settled into
                    // RINGING (V2's handleIncomingFromIntent drives this).
                    LaunchedEffect(pendingAutoAction, callState) {
                        val action = pendingAutoAction ?: return@LaunchedEffect
                        if (callState != CallStateV2.RINGING) return@LaunchedEffect
                        when (action) {
                            "auto_accept" -> {
                                if (!hasCallPermissions(callIsVideo)) requestCallPermissions(callIsVideo)
                                CallManagerV2.acceptCall()
                            }
                            "auto_reject" -> {
                                var wait = 0
                                while (!container.socket.isConnected && wait < 50) {
                                    kotlinx.coroutines.delay(100); wait++
                                }
                                CallManagerV2.rejectCall()
                            }
                        }
                        pendingAutoAction = null
                    }

                    fun initiateCall(room: Room, isVideo: Boolean) {
                        if (callActive) {
                            android.util.Log.d("Call", "initiateCall ignored — call already active")
                            return
                        }
                        if (CallManagerV2.isBusy()) {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Đang giải phóng cuộc gọi trước, thử lại sau giây lát",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return
                        }
                        if (!hasCallPermissions(isVideo)) {
                            requestCallPermissions(isVideo)
                            return
                        }
                        val peer = room.other_user ?: return
                        CallManagerV2.initiateCall(
                            roomId = room.id,
                            calleeId = peer.id,
                            calleeName = peer.displayName,
                            calleeAvatar = peer.avatar,
                            type = if (isVideo) CallTypeV2.VIDEO else CallTypeV2.AUDIO,
                        )
                    }

                    // pendingCallback channel kept for onNewIntent handling of
                    // "Gọi lại" while the activity is already alive (the launch
                    // intent is handled via DeepLinkRouter above).
                    val pendingCallbackState = remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
                    _pendingCallback = pendingCallbackState
                    LaunchedEffect(pendingCallbackState.value) {
                        val cb = pendingCallbackState.value ?: return@LaunchedEffect
                        pendingCallbackState.value = null
                        navigateTo(vn.chat9.app.navigation.AppDestination.Chat(
                            roomId = cb.first,
                            autoCall = if (cb.second) vn.chat9.app.navigation.CallAction.VIDEO
                                       else vn.chat9.app.navigation.CallAction.VOICE
                        ))
                    }

                    // Execute auto-call once a Chat destination has opened and
                    // selectedRoom is populated. Keys are intentionally
                    // (selectedRoom?.id, screen) — NOT pendingAutoCall. If we
                    // keyed on pendingAutoCall, clearing it inside the effect
                    // would cancel this coroutine before delay() finished,
                    // so initiateCall never ran (the bug where both call
                    // buttons just opened the room without dialing).
                    LaunchedEffect(selectedRoom?.id, screen) {
                        if (screen != "chat") return@LaunchedEffect
                        val room = selectedRoom ?: return@LaunchedEffect
                        val action = pendingAutoCall ?: return@LaunchedEffect
                        pendingAutoCall = null
                        kotlinx.coroutines.delay(250)
                        initiateCall(room, action == vn.chat9.app.navigation.CallAction.VIDEO)
                    }

                    @Composable
                    fun SwipeBack(onBack: () -> Unit, content: @Composable () -> Unit) {
                        var offsetX by remember { mutableFloatStateOf(0f) }
                        val animatedOffset by animateFloatAsState(offsetX, label = "swipeBack")
                        val threshold = 308f

                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val down = awaitPointerEvent(PointerEventPass.Final)
                                            val startX = down.changes.firstOrNull()?.position?.x ?: continue
                                            // Skip if already consumed by children (e.g. left swipe on bubble)
                                            if (down.changes.any { it.isConsumed }) continue

                                            var totalDrag = 0f
                                            var tracking = false

                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Final)
                                                val change = event.changes.firstOrNull() ?: break

                                                if (!change.pressed) {
                                                    if (totalDrag > threshold) onBack()
                                                    offsetX = 0f
                                                    break
                                                }

                                                // Skip if consumed by children
                                                if (change.isConsumed) {
                                                    offsetX = 0f
                                                    tracking = false
                                                    continue
                                                }

                                                val dragX = change.position.x - startX
                                                if (!tracking && dragX > 15f) {
                                                    tracking = true
                                                }
                                                if (tracking) {
                                                    totalDrag = dragX
                                                    offsetX = dragX.coerceAtLeast(0f)
                                                    change.consume()
                                                } else if (dragX < -10f) {
                                                    // Left swipe — don't track
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                                .graphicsLayer { translationX = animatedOffset }
                        ) {
                            content()
                        }
                    }

                    when (screen) {
                        "login" -> LoginScreen(
                            onLoginSuccess = {
                                container.socket.connect()
                                screen = "home"
                            },
                            onNavigateToRegister = { screen = "register" }
                        )
                        "register" -> RegisterScreen(
                            onRegisterSuccess = {
                                container.socket.connect()
                                screen = "home"
                            },
                            onNavigateToLogin = { screen = "login" }
                        )
                        "home" -> {
                            // Connect socket + register FCM
                            LaunchedEffect(Unit) {
                                if (!container.socket.isConnected) {
                                    container.socket.connect()
                                }
                                // Register FCM token
                                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            container.api.subscribePush(
                                                vn.chat9.app.data.model.PushSubscribeRequest(
                                                    endpoint = token,
                                                    keys = vn.chat9.app.data.model.PushKeys("fcm", "fcm")
                                                )
                                            )
                                        } catch (e: Exception) {
                                            Log.e("FCM", "Failed to register token", e)
                                        }
                                    }
                                }
                                // Request notification permission (Android 13+)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                                    }
                                }
                                // Full-screen intent permission (Android 14+) — needed for
                                // incoming call UI to render over the lockscreen. Helper
                                // shows a Vietnamese explanation dialog before deep-linking
                                // to settings, throttled to once / 24h.
                                ensureFullScreenIntentPermission()
                            }
                            HomeScreen(
                                onLogout = {
                                    container.tokenManager.clear()
                                    container.socket.disconnect()
                                    screen = "login"
                                },
                                onRoomClick = { room ->
                                    // Route through navigateTo so the room is
                                    // re-fetched via detail.php — keeps payload
                                    // shape identical to every other entry point.
                                    navigateTo(vn.chat9.app.navigation.AppDestination.Chat(room.id))
                                },
                                onRoomCall = { room, isVideo ->
                                    navigateTo(vn.chat9.app.navigation.AppDestination.Chat(
                                        roomId = room.id,
                                        autoCall = if (isVideo) vn.chat9.app.navigation.CallAction.VIDEO
                                                   else vn.chat9.app.navigation.CallAction.VOICE
                                    ))
                                },
                                onOpenWall = { uid ->
                                    navigateTo(vn.chat9.app.navigation.AppDestination.Wall(uid))
                                },
                                onAddFriend = {
                                    navigateTo(vn.chat9.app.navigation.AppDestination.AddFriend)
                                },
                                onEditProfile = {
                                    navigateTo(vn.chat9.app.navigation.AppDestination.ProfileEdit)
                                },
                                onSearchClick = {
                                    navigateTo(vn.chat9.app.navigation.AppDestination.Search())
                                },
                                selectedTab = selectedTab,
                                onTabChange = { selectedTab = it },
                                roomRefreshKey = roomRefreshKey
                            )
                        }
                        "profile" -> SwipeBack(onBack = { screen = "home" }) {
                            vn.chat9.app.ui.profile.ProfileEditScreen(
                                onBack = { screen = "home" }
                            )
                        }
                        "search" -> SwipeBack(onBack = { screen = "home" }) {
                            vn.chat9.app.ui.search.SearchScreen(
                                onBack = { screen = "home" },
                                onChat = { userId ->
                                    // TODO: start private chat with userId
                                    screen = "home"
                                },
                                onOpenRoom = { roomId, messageId ->
                                    navigateTo(vn.chat9.app.navigation.AppDestination.Chat(
                                        roomId = roomId,
                                        scrollToMessageId = if (messageId > 0) messageId else null
                                    ))
                                },
                                onSendFriendRequest = { /* handled inside SearchScreen */ }
                            )
                        }
                        "chat" -> SwipeBack(onBack = {
                            openChatSearch = false
                            container.socket.switchRoom(0)
                            selectedRoom = null
                            scrollToMessageId = null
                            roomRefreshKey++
                            screen = "home"
                        }) {
                            selectedRoom?.let { room ->
                                ChatScreen(
                                    room = room,
                                    scrollToMessageId = scrollToMessageId,
                                    startWithSearch = openChatSearch,
                                    onBack = {
                                        openChatSearch = false
                                        container.socket.switchRoom(0)
                                        selectedRoom = null
                                        scrollToMessageId = null
                                        roomRefreshKey++
                                        screen = "home"
                                    },
                                    onVoiceCall = { initiateCall(room, false) },
                                    onVideoCall = { initiateCall(room, true) },
                                    onUserWall = { uid ->
                                        navigateTo(vn.chat9.app.navigation.AppDestination.Wall(uid))
                                    },
                                    onChatOptions = { screen = "chat_options" }
                                )
                            }
                        }
                        "chat_options" -> SwipeBack(onBack = { screen = "chat" }) {
                            selectedRoom?.let { room ->
                                vn.chat9.app.ui.chat.ChatOptionsScreen(
                                    room = room,
                                    onBack = { screen = "chat" },
                                    onSearchMessages = { openChatSearch = true; screen = "chat" },
                                    onUserWall = { uid ->
                                        navigateTo(vn.chat9.app.navigation.AppDestination.Wall(uid))
                                    }
                                )
                            }
                        }
                        "wall" -> {
                            // Back from Wall: prefer the remembered return
                            // screen (AddFriend, Chat, etc.), otherwise fall
                            // back to chat if a room is selected, else home.
                            val backFromWall: () -> Unit = {
                                val target = wallReturnTo
                                    ?: if (selectedRoom != null) "chat" else "home"
                                wallReturnTo = null
                                wallUserId = null
                                screen = target
                            }
                            SwipeBack(onBack = backFromWall) {
                                wallUserId?.let { uid ->
                                    vn.chat9.app.ui.profile.UserWallScreen(
                                        userId = uid,
                                        onBack = backFromWall,
                                        onChat = { targetUserId ->
                                            // Create or find the private room,
                                            // then navigate through AppDestination
                                            // — same code path as tapping a friend
                                            // in the contact list.
                                            scope.launch {
                                                try {
                                                    val res = container.api.createRoom(
                                                        mapOf("type" to "private", "friend_id" to targetUserId)
                                                    )
                                                    if (res.success && res.data != null) {
                                                        navigateTo(vn.chat9.app.navigation.AppDestination.Chat(res.data.id))
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        "add_friend" -> SwipeBack(onBack = { screen = "home" }) {
                            vn.chat9.app.ui.contacts.AddFriendScreen(
                                onBack = { screen = "home" },
                                onScanQr = {
                                    navigateTo(vn.chat9.app.navigation.AppDestination.QrScanner)
                                },
                                onOpenWall = { uid ->
                                    navigateTo(vn.chat9.app.navigation.AppDestination.Wall(uid))
                                }
                            )
                        }
                        "share" -> {
                            vn.chat9.app.ui.share.ShareComposeScreen(
                                payloads = sharePayloads,
                                onDismiss = {
                                    sharePayloads = emptyList()
                                    screen = "home"
                                },
                                onOpenRoom = { roomId ->
                                    sharePayloads = emptyList()
                                    navigateTo(vn.chat9.app.navigation.AppDestination.Chat(roomId))
                                },
                            )
                        }
                    }

                    // Call overlay — V2. CallScreenHost reads state from the
                    // CallManager singleton via StateFlow and routes every user
                    // action back through CallViewModel. MainActivity no longer
                    // owns any per-call state.
                    if (callActive) {
                        CallScreenHost()
                    }
                }
            }
        }
    }
}
