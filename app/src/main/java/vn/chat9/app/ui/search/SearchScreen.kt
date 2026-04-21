package vn.chat9.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.model.User
import vn.chat9.app.util.UrlUtils

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onChat: (Int) -> Unit,
    onOpenRoom: (Int, Int) -> Unit = { _, _ -> },
    onSendFriendRequest: (Int) -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    var roomResults by remember { mutableStateOf<List<vn.chat9.app.data.model.Room>>(emptyList()) }
    var userResults by remember { mutableStateOf<List<User>>(emptyList()) }
    var messageResults by remember { mutableStateOf<List<vn.chat9.app.data.model.MessageSearchResult>>(emptyList()) }
    var hasMoreMessages by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var allRooms by remember { mutableStateOf<List<vn.chat9.app.data.model.Room>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var sentRequests by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Load rooms once for local contact search
    LaunchedEffect(Unit) {
        try {
            val res = container.api.getRooms()
            if (res.success && res.data != null) allRooms = res.data
        } catch (_: Exception) {}
    }

    fun doSearch(q: String) {
        if (q.isBlank()) { roomResults = emptyList(); userResults = emptyList(); messageResults = emptyList(); hasSearched = false; return }
        scope.launch {
            isSearching = true
            hasSearched = true
            try {
                // Search contacts/rooms locally by alias or username (max 5)
                val lq = q.trim().lowercase()
                roomResults = allRooms.filter { room ->
                    if (room.type == "private" && room.other_user != null) {
                        val displayName = room.other_user.displayName.lowercase()
                        val username = room.other_user.username.lowercase()
                        displayName.contains(lq) || username.contains(lq)
                    } else {
                        room.name?.lowercase()?.contains(lq) == true
                    }
                }.take(5)

                // Search messages globally (includes stranger messages)
                val msgRes = container.api.searchMessages(q.trim(), 11) // Request 11 to detect if more exist
                val msgs = if (msgRes.success && msgRes.data != null) msgRes.data else emptyList()
                hasMoreMessages = msgs.size > 10
                messageResults = msgs.take(10)
            } catch (_: Exception) {
                roomResults = emptyList()
                messageResults = emptyList()
                hasMoreMessages = false
            }
            isSearching = false
        }
    }

    // Auto-search with debounce
    LaunchedEffect(query) {
        if (query.length >= 2) {
            delay(500)
            doSearch(query)
        } else if (query.isEmpty()) {
            roomResults = emptyList()
            userResults = emptyList()
            messageResults = emptyList()
            hasSearched = false
        }
    }

    // Auto focus search field
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // App runs edge-to-edge (WindowCompat.setDecorFitsSystemWindows=false in
    // MainActivity), so every root screen has to pad for system bars itself.
    // Without these the gradient search bar slid under the status bar and the
    // result list got covered by the on-screen keyboard ("thanh tìm kiếm bị
    // lấp bởi status bar, menu tab bị lấp bởi bàn phím").
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Search bar with gradient background (same as AppSearchBar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color(0xFF3E1F91), Color(0xFF8E44AD), Color(0xFFFF6F61))
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White,
                modifier = Modifier.size(22.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(10.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = TextStyle(fontSize = 16.sp, color = Color.White, lineHeight = 20.sp, platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                    singleLine = true,
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch(query) }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Tìm kiếm", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, lineHeight = 20.sp, style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                            innerTextField()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { query = ""; roomResults = emptyList(); userResults = emptyList(); messageResults = emptyList(); hasSearched = false },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(Icons.Default.Close, "Clear", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFFE0E6ED))

        // Results
        if (isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF3E1F91), modifier = Modifier.size(32.dp))
            }
        } else if (hasSearched && (roomResults.isNotEmpty() || messageResults.isNotEmpty())) {
            LazyColumn {
                // 1. Contact/Room results (top, max 5)
                if (roomResults.isNotEmpty()) {
                    item {
                        Text(
                            "Liên hệ (${roomResults.size})",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            fontSize = 13.sp, color = Color(0xFF7F8C8D), fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(roomResults, key = { "room_${it.id}" }) { room ->
                        val roomName = if (room.type == "private" && room.other_user != null) room.other_user.displayName else room.name ?: ""
                        val avatarUrl = if (room.type == "private") UrlUtils.toFullUrl(room.other_user?.avatar) else null
                        val lastMsg = room.last_message
                        val preview = lastMsg?.let { msg ->
                            when (msg.type) {
                                "image" -> "Hình ảnh"
                                "file" -> "File"
                                "video" -> "Video"
                                "audio" -> "Audio"
                                "recalled" -> "Đã thu hồi tin nhắn"
                                else -> msg.content ?: ""
                            }
                        } ?: ""
                        val time = lastMsg?.created_at?.let { vn.chat9.app.util.DateUtils.formatTime(it) } ?: ""

                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenRoom(room.id, 0) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (avatarUrl != null) {
                                    AsyncImage(
                                        model = avatarUrl, contentDescription = null,
                                        modifier = Modifier.size(48.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(roomName.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    HighlightedText(text = roomName, highlight = query, fontSize = 15.sp)
                                    if (preview.isNotEmpty()) {
                                        Text(preview, fontSize = 13.sp, color = Color(0xFF7F8C8D), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                }
                                if (time.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(time, fontSize = 12.sp, color = Color(0xFF7F8C8D))
                                }
                            }
                            HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(start = 76.dp))
                        }
                    }
                }

                // 2. Message results
                if (messageResults.isNotEmpty()) {
                    item {
                        Text(
                            "Tin nhắn (${messageResults.size})",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            fontSize = 13.sp, color = Color(0xFF7F8C8D), fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(messageResults, key = { "msg_${it.id}" }) { msg ->
                        MessageSearchItem(
                            result = msg,
                            query = query,
                            onClick = { onOpenRoom(msg.room_id, msg.id) }
                        )
                    }

                    // "Xem thêm" button
                    if (hasMoreMessages) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!isLoadingMore) {
                                            scope.launch {
                                                isLoadingMore = true
                                                try {
                                                    val lastId = messageResults.lastOrNull()?.id
                                                    val moreRes = container.api.searchMessages(query.trim(), 11, lastId)
                                                    if (moreRes.success && moreRes.data != null && moreRes.data.isNotEmpty()) {
                                                        hasMoreMessages = moreRes.data.size > 10
                                                        messageResults = messageResults + moreRes.data.take(10)
                                                    } else {
                                                        hasMoreMessages = false
                                                    }
                                                } catch (_: Exception) {
                                                    hasMoreMessages = false
                                                }
                                                isLoadingMore = false
                                            }
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(color = Color(0xFF3E1F91), modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Xem thêm", color = Color(0xFF3E1F91), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        } else if (hasSearched) {
            Box(Modifier.fillMaxSize().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
                Text("Không tìm thấy kết quả", color = Color.Gray, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun MessageSearchItem(
    result: vn.chat9.app.data.model.MessageSearchResult,
    query: String,
    onClick: () -> Unit
) {
    val avatarUrl = UrlUtils.toFullUrl(result.other_avatar)

    Column {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Room avatar
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                contentAlignment = Alignment.Center
            ) {
                Text(result.roomDisplayName.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Room name
            Text(
                result.roomDisplayName,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2C3E50),
                maxLines = 1
            )
            // Message preview with highlighted query
            HighlightedText(
                text = result.content ?: "",
                highlight = query,
                fontSize = 14.sp
            )
        }

        Spacer(Modifier.width(8.dp))

        // Time
        Text(
            vn.chat9.app.util.DateUtils.formatTime(result.created_at),
            fontSize = 12.sp,
            color = Color(0xFF7F8C8D)
        )
    }
    HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(start = 76.dp))
    }
}

@Composable
fun HighlightedText(
    text: String,
    highlight: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp
) {
    if (highlight.isBlank() || !text.contains(highlight, ignoreCase = true)) {
        Text(text, fontSize = fontSize, color = Color(0xFF7F8C8D), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        return
    }

    val annotated = buildAnnotatedString {
        var start = 0
        val lowerText = text.lowercase()
        val lowerQuery = highlight.lowercase()
        while (start < text.length) {
            val idx = lowerText.indexOf(lowerQuery, start)
            if (idx < 0) {
                withStyle(SpanStyle(color = Color(0xFF7F8C8D))) { append(text.substring(start)) }
                break
            }
            if (idx > start) {
                withStyle(SpanStyle(color = Color(0xFF7F8C8D))) { append(text.substring(start, idx)) }
            }
            withStyle(SpanStyle(color = Color(0xFF3E1F91), fontWeight = FontWeight.Bold, background = Color(0x203E1F91))) {
                append(text.substring(idx, idx + highlight.length))
            }
            start = idx + highlight.length
        }
    }
    Text(annotated, fontSize = fontSize, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

@Composable
fun SearchResultItem(
    user: User,
    query: String,
    isSent: Boolean,
    onChat: () -> Unit,
    onAddFriend: () -> Unit
) {
    val avatarUrl = UrlUtils.toFullUrl(user.avatar)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChat)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = user.username,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                contentAlignment = Alignment.Center
            ) {
                Text(user.username.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }

        Spacer(Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(user.username, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            user.phone?.let {
                Text(
                    "Số điện thoại: $it",
                    fontSize = 13.sp,
                    color = Color(0xFF3E1F91)
                )
            }
        }

        // Action button
        if (isSent) {
            Text("Đã gửi", fontSize = 12.sp, color = Color.Gray)
        } else {
            OutlinedButton(
                onClick = onAddFriend,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF3E1F91))
            ) {
                Text("Kết bạn", fontSize = 13.sp)
            }
        }
    }
}
