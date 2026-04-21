package vn.chat9.app.ui.bubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import vn.chat9.app.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.gson.Gson
import kotlinx.coroutines.launch
import org.json.JSONObject
import vn.chat9.app.App
import vn.chat9.app.data.model.Message
import vn.chat9.app.data.model.Room
import vn.chat9.app.ui.chat.MessageBubble
import vn.chat9.app.ui.theme._9chatTheme
import vn.chat9.app.util.DateUtils
import vn.chat9.app.util.TimeDisplayConfig
import vn.chat9.app.util.TimeDisplayProcessor
import vn.chat9.app.util.TimePosition
import vn.chat9.app.util.TimeStyle
import vn.chat9.app.util.UrlUtils

/**
 * Bubble Activity — embedded in the Android Bubbles expanded container
 * (see manifest attrs: allowEmbedded, resizeableActivity, documentLaunchMode).
 *
 * Phase 2A: Header + read-only message list from REST API.
 * Phase 2B (current): Realtime socket listener + inline send (text only).
 * Phase 2C: Polish (call/video buttons, presence text, settings footer).
 * Phase 2D: Rich content (image, file, contact, location, call, reactions).
 */
class BubbleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getStringExtra("room_id")?.toIntOrNull()
        if (roomId == null || roomId <= 0) {
            finish()
            return
        }
        val container = (application as App).container
        val currentUserId = container.tokenManager.user?.id
        if (!container.tokenManager.isLoggedIn || currentUserId == null) {
            finish()
            return
        }
        // Required for Compose's WindowInsets (imePadding, ime.getBottom)
        // to track the soft keyboard reliably inside the bubble container.
        // Without this, Samsung One UI bubble windows don't fire IME insets
        // updates → message list never re-scrolls when the keyboard opens.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            _9chatTheme {
                BubbleChatScreen(roomId = roomId, currentUserId = currentUserId)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BubbleChatScreen(roomId: Int, currentUserId: Int) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var room by remember { mutableStateOf<Room?>(null) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Initial load: room detail + last 50 messages.
    LaunchedEffect(roomId) {
        scope.launch {
            try {
                val roomRes = container.api.getRoomDetail(roomId)
                if (roomRes.success && roomRes.data != null) {
                    room = roomRes.data
                } else {
                    error = roomRes.message ?: "Không tải được thông tin phòng"
                    loading = false
                    return@launch
                }
                val msgRes = container.api.getMessages(roomId, limit = 50)
                if (msgRes.success && msgRes.data != null) {
                    // Backend returns ORDER BY id DESC. Sort ASC so oldest is at
                    // top, newest at bottom — Zalo-style. Sorting by id (not by
                    // created_at string) is robust to identical-second timestamps
                    // and avoids parsing dates on every list rebuild.
                    messages = msgRes.data.messages.sortedBy { it.id }
                } else {
                    error = msgRes.message ?: "Không tải được tin nhắn"
                }
            } catch (e: Exception) {
                error = "Lỗi: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    // Realtime: connect socket (idempotent — singleton in container) + listen
    // for new messages in this room.
    DisposableEffect(roomId) {
        if (!container.socket.isConnected) container.socket.connect()
        val gson = Gson()
        val listener: (Array<Any>) -> Unit = { args ->
            try {
                val raw = args[0] as JSONObject
                val incomingRoomId = raw.optInt("room_id", -1)
                if (incomingRoomId == roomId) {
                    val msg = gson.fromJson(raw.toString(), Message::class.java)
                    messages = (messages.filter { it.id != msg.id } + msg).sortedBy { it.id }
                }
            } catch (_: Exception) { /* ignore malformed payload */ }
        }
        container.socket.on("message", listener)
        onDispose {
            container.socket.off("message", listener)
        }
    }

    // Track viewing status across the bubble's lifecycle, not just on
    // composition. Bubble collapse (tap outside the expanded view) only
    // PAUSES BubbleActivity — it stays alive in memory ready to re-expand.
    // Without lifecycle observers, switchRoom(0) would never fire on
    // collapse, server keeps `currentRoom = roomId`, and isUserViewingRoom
    // suppresses every subsequent push for this room — exactly the
    // "không hiện thông báo nữa" symptom.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(roomId, lifecycleOwner) {
        container.socket.switchRoom(roomId)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    container.socket.switchRoom(roomId)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    container.socket.switchRoom(0)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            container.socket.switchRoom(0)
        }
    }

    // Sender for the input bar (defined once, reused below).
    val sendText: (String) -> Unit = { text ->
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            container.socket.sendMessage(roomId = roomId, type = "text", content = trimmed)
        }
    }

    // Lifted state so the InputBar focus listener can scroll the list.
    val listState = rememberLazyListState()

    // Scroll to the newest message when the input gets focus (user about to
    // type). With reverseLayout=true, index 0 is the newest (visual bottom).
    val scrollToBottom: suspend () -> Unit = {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // Re-scroll continuously while the soft keyboard is animating up. The
    // simple isImeVisible boolean only flips once at the start, but Android
    // animates the IME in over ~150-200ms — during that animation the
    // viewport keeps shrinking and LazyColumn keeps the absolute scroll
    // position so the last message slides under the keyboard.
    //
    // Watching ime.getBottom() returns the live insets pixel value, which
    // changes throughout the animation → LaunchedEffect re-fires on every
    // frame → we keep the last item pinned to the bottom edge.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom, messages.size) {
        if (imeBottom > 0 && messages.isNotEmpty()) {
            listState.scrollToItem(0) // reverseLayout → 0 = newest = bottom
        }
    }

    // Open the full app in this room (used by header icon + name tap).
    val launchInApp: () -> Unit = {
        val intent = android.content.Intent(context, vn.chat9.app.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("room_id", roomId.toString())
        }
        context.startActivity(intent)
    }

    // Start a call directly. We bypass MainActivity.runAutoCallback (which
    // fetches the room + opens ChatScreen first → user briefly sees the chat
    // before the call overlay appears). Instead:
    //   1. Trigger CallManager.initiateCall right here — singleton, no Activity
    //      needed for state. CallState flips out of IDLE immediately.
    //   2. Launch MainActivity with direct_call=true so it skips the chat
    //      navigation and just hosts CallScreenHost overlay (which auto-renders
    //      because state != IDLE). User sees only the call UI, no chat flash.
    //   3. Bubble Activity stays alive in its separate task (taskAffinity=""),
    //      so when MainActivity finishAndRemoveTask after the call, the system
    //      resumes the bubble task — bubble is NOT removed.
    val launchCall: (Boolean) -> Unit = { isVideo ->
        val r = room
        val peer = r?.other_user
        if (peer == null) {
            android.widget.Toast.makeText(context, "Chưa tải xong thông tin phòng", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val type = if (isVideo) vn.chat9.app.call.model.CallType.VIDEO
                else vn.chat9.app.call.model.CallType.AUDIO
            val started = vn.chat9.app.call.CallManager.initiateCall(
                roomId = roomId,
                calleeId = peer.id,
                calleeName = peer.displayName,
                calleeAvatar = peer.avatar,
                type = type,
            )
            if (!started) {
                android.widget.Toast.makeText(
                    context,
                    "Không thể gọi (đang bận hoặc thiếu quyền)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                val intent = android.content.Intent(context, vn.chat9.app.MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("direct_call", true)
                }
                context.startActivity(intent)
            }
        }
    }

    Surface(
        // imePadding() reserves space for the soft keyboard so the input bar
        // and the latest messages stay visible while typing. Pairs with the
        // Activity's windowSoftInputMode="adjustResize".
        modifier = Modifier.fillMaxSize().imePadding(),
        color = Color(0xFFEAF4FB) // Zalo-like pale blue chat background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BubbleHeader(
                room = room,
                onOpenInApp = launchInApp,
                onAudioCall = { launchCall(false) },
                onVideoCall = { launchCall(true) }
            )

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                error != null -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }

                else -> MessageList(
                    messages = messages,
                    currentUserId = currentUserId,
                    listState = listState,
                    modifier = Modifier.weight(1f)
                )
            }

            InputBar(
                onSend = sendText,
                onFocused = {
                    scope.launch { scrollToBottom() }
                }
            )
        }
    }
}

@Composable
private fun BubbleHeader(
    room: Room?,
    onOpenInApp: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
) {
    val displayName = room?.other_user?.displayName ?: room?.name ?: "Đang tải..."
    val isOnline = room?.other_user?.is_online == true
    val presenceText = if (isOnline) "Đang online" else "Vừa truy cập"
    var menuOpen by remember { mutableStateOf(false) }

    // Compact header tuned to ~46dp (was ~40dp; +15% from user feedback —
    // IconButtons 41dp + outer padding 6dp). Text line-height collapsed to
    // font-size to remove ~6sp of unused leading per line.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF4FC3F7), Color(0xFF29B6F6))
                )
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Chat icon + name share one click target → open the room in
            // the full app. Use wrapContentWidth (not weight) so the clickable
            // area hugs only the icon + name; otherwise the row stretches to
            // touch the Call button and a near-edge tap on Call mis-fires the
            // open-in-app navigation instead.
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenInApp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Custom thin-weight chat icon (res/drawable/ic_bubble_chat.xml).
                // +30% horizontal padding (vs the tight default box) for
                // breathing room around the slim line strokes.
                Icon(
                    painter = painterResource(R.drawable.ic_bubble_chat),
                    contentDescription = "Mở trong ứng dụng",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 4.dp) // ~30% extra width
                        .size(24.dp)
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        displayName,
                        color = Color.White,
                        fontSize = 16.sp,    // 14 → 16 (+15%)
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        presenceText,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 9.sp,     // 10 → 9 (giảm)
                        lineHeight = 9.sp,
                    )
                }
            }
            // Flex spacer pushes the call buttons to the far right and is
            // NOT clickable — taps on this empty area do nothing rather than
            // mis-triggering open-in-app.
            Spacer(Modifier.weight(1f))
            // Audio call — 27dp (-5% from 28 per user feedback).
            IconButton(onClick = onAudioCall, modifier = Modifier.size(41.dp)) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = "Gọi thoại",
                    tint = Color.White,
                    modifier = Modifier.size(27.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            // Video call — kept at 28dp per user feedback.
            IconButton(onClick = onVideoCall, modifier = Modifier.size(41.dp)) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = "Gọi video",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            // 3-dots menu — 25dp (+5% from 24 per user feedback).
            // Per project memory rule, TODO buttons stay visible but disabled.
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(41.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Tuỳ chọn",
                        tint = Color.White,
                        modifier = Modifier.size(25.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DisabledMenuItem("Tìm tin nhắn (sắp ra mắt)")
                    DisabledMenuItem("Tắt thông báo (sắp ra mắt)")
                    DisabledMenuItem("Cài đặt phòng (sắp ra mắt)")
                }
            }
        }
    }
}

@Composable
private fun DisabledMenuItem(label: String) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 14.sp) },
        onClick = { /* TODO Phase 2D */ },
        enabled = false,
        modifier = Modifier.alpha(0.55f)
    )
}


@Composable
private fun MessageList(
    messages: List<Message>,
    currentUserId: Int,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    if (messages.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Chưa có tin nhắn", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    // Mirror ChatScreen rendering so the bubble and the full chat look
    // identical in one conversation:
    //   - reverseLayout=true (newest at visual bottom, oldest at top)
    //   - pass messages.reversed() so index 0 = newest message
    //   - precompute date separator + time config per message
    //   - reuse vn.chat9.app.ui.chat.MessageBubble — same colors, shapes,
    //     avatars, image/file/contact/location/call/recalled renderers
    val messageDisplayInfo = remember(messages) {
        val info = mutableMapOf<Int, Pair<Boolean, TimeDisplayConfig>>()
        for (i in messages.indices) {
            val msg = messages[i]
            val prev = if (i > 0) messages[i - 1] else null
            val next = if (i < messages.size - 1) messages[i + 1] else null
            val showDateSep = prev == null || DateUtils.isDifferentDay(prev.created_at, msg.created_at)
            val timeConfig = TimeDisplayProcessor.getConfig(
                type = msg.type,
                createdAt = msg.created_at,
                userId = msg.user_id,
                nextUserId = next?.user_id,
                nextCreatedAt = next?.created_at,
            )
            info[msg.id] = Pair(showDateSep, timeConfig)
        }
        info
    }

    // "Silent" initial positioning — list invisible (alpha 0), jump to newest
    // (index 0 because reverseLayout=true), then flip to alpha 1. User never
    // sees the scroll.
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(messages.isNotEmpty()) {
        if (messages.isNotEmpty() && !positioned) {
            listState.scrollToItem(0)
            positioned = true
        }
    }

    // Realtime append — animate to newest (index 0) only if user is already
    // near the bottom. In reverseLayout, firstVisibleItemIndex <= 1 means
    // the user's viewport covers the newest messages.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (positioned && messages.isNotEmpty()) {
            if (listState.firstVisibleItemIndex <= 1) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val reversedMessages = messages.reversed()
    val lastMsgId = messages.lastOrNull()?.id

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .alpha(if (positioned) 1f else 0f),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(reversedMessages, key = { it.id }) { msg ->
            val isMine = msg.user_id == currentUserId
            val status = if (isMine && msg.id == lastMsgId) "received" else null
            val (showDateSep, timeConfig) = messageDisplayInfo[msg.id]
                ?: Pair(false, TimeDisplayConfig(true, TimePosition.INSIDE_BUBBLE, TimeStyle.NORMAL))

            Column {
                if (showDateSep) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).height(0.5.dp).background(Color(0xFFD0D0D0)))
                        Text(
                            text = DateUtils.formatDateSeparator(msg.created_at),
                            fontSize = 12.sp,
                            color = Color(0xFFAAAAAA),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        Box(modifier = Modifier.weight(1f).height(0.5.dp).background(Color(0xFFD0D0D0)))
                    }
                }
                // Reuse the full-chat MessageBubble. Phase 2C leaves all the
                // interaction callbacks null (reply, swipe, long-press, quick
                // react, pill click) — those land in Phase 2D. Visual output
                // is already 1:1 with the full chat.
                MessageBubble(
                    message = msg,
                    isSent = isMine,
                    currentUserId = currentUserId,
                    displayName = msg.username ?: "",
                    deliveryStatus = status,
                    timeConfig = timeConfig,
                )
            }
        }
    }
}

@Composable
private fun InputBar(onSend: (String) -> Unit, onFocused: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val canSend = draft.trim().isNotEmpty()

    // Track focus on the text field so the parent can scroll the message list
    // to the bottom the instant the keyboard opens (Zalo-style).
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isFocused) {
        if (isFocused) onFocused()
    }

    // Mirrors ChatScreen.kt:1087-1156 — emoji button (placeholder, disabled in
    // Phase 2C; Phase 2D will wire the picker), OutlinedTextField with all
    // borders/containers transparent so the rounded chip blends into the white
    // bar, send button on the right that appears only when there's content.
    Surface(color = Color.White, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji button — disabled in Phase 2C per the project rule
            // "TODO buttons: keep visible, render mờ".
            IconButton(
                onClick = { /* TODO Phase 2D: emoji picker */ },
                enabled = false,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_emoji_add),
                    contentDescription = "Emoji",
                    tint = Color.Gray,
                    modifier = Modifier.size(29.dp).alpha(0.45f) // +20% from 24dp
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Nhập tin nhắn...", fontSize = 18.sp) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp, max = 120.dp),
                textStyle = TextStyle(fontSize = 18.sp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                ),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (canSend) {
                        onSend(draft)
                        draft = ""
                    }
                }),
                interactionSource = interactionSource,
            )

            // Send button only appears when there's text — same UX as ChatScreen.
            if (canSend) {
                IconButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Gửi",
                        tint = Color(0xFF3E1F91)
                    )
                }
            }
        }
    }
}
