package vn.chat9.app.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.model.Friend
import vn.chat9.app.util.UrlUtils

@Composable
fun ContactsScreen(
    onChat: (Int) -> Unit = {},
    onCall: (userId: Int, isVideo: Boolean) -> Unit = { _, _ -> },
    onOpenWall: (userId: Int) -> Unit = {},
    onAddFriend: () -> Unit = {}
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var selectedSubTab by remember { mutableIntStateOf(0) }
    val subTabs = listOf("Bạn bè", "Lời mời", "Đã gửi")

    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var pending by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var sent by remember { mutableStateOf<List<Friend>>(emptyList()) }
    // Local search query across all three sub-tabs. Filter runs in-memory
    // against username + alias + bio — no API call needed, these lists are
    // always fully loaded for the active tab. Badge counts stay unfiltered
    // so users still see the full pending count while searching.
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    // Long-press on a friend opens a Zalo-style detail dialog; null when closed.
    var detailFor by remember { mutableStateOf<Friend?>(null) }
    // Per-row in-flight tracking for the accept/reject buttons in the pending tab
    // so those rows show a spinner + disabled state instead of being unresponsive.
    var respondingIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    fun parseErrorMessage(e: Exception): String {
        return (e as? retrofit2.HttpException)?.let { httpE ->
            try {
                val body = httpE.response()?.errorBody()?.string() ?: ""
                org.json.JSONObject(body).optString("message").takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
        } ?: "Lỗi kết nối, thử lại sau"
    }

    fun loadData(type: String) {
        scope.launch {
            isLoading = true
            try {
                val res = container.api.getFriends(type)
                if (res.success && res.data != null) {
                    when (type) {
                        "friends" -> friends = res.data
                        "pending" -> pending = res.data
                        "sent" -> sent = res.data
                    }
                }
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    // Load data when tab changes
    LaunchedEffect(selectedSubTab) {
        val type = when (selectedSubTab) {
            0 -> "friends"
            1 -> "pending"
            2 -> "sent"
            else -> "friends"
        }
        loadData(type)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        // Editable gradient search bar — same look as AppSearchBar (which is
        // still used verbatim by the rooms list) but with a live BasicTextField
        // so the user can filter the contact sub-tabs in place. Tap-and-type,
        // X to clear, right-icon (add-friend) preserved.
        ContactsSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onAddFriendClick = onAddFriend,
        )

        // Sub-tabs
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = Color.White,
            contentColor = Color(0xFF3E1F91)
        ) {
            subTabs.forEachIndexed { index, title ->
                val badgeCount = when (index) {
                    1 -> pending.size
                    2 -> sent.size
                    else -> 0
                }
                Tab(
                    selected = selectedSubTab == index,
                    onClick = { selectedSubTab = index },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, fontSize = 14.sp)
                            if (badgeCount > 0) {
                                Spacer(Modifier.width(4.dp))
                                Badge(
                                    containerColor = Color(0xFFFF6F61),
                                    contentColor = Color.White
                                ) {
                                    Text(if (badgeCount > 5) "5+" else "$badgeCount", fontSize = 10.sp, lineHeight = 10.sp, style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                                }
                            }
                        }
                    },
                    selectedContentColor = Color(0xFF3E1F91),
                    unselectedContentColor = Color(0xFF7F8C8D)
                )
            }
        }

        // Derived filtered views per tab. Case-insensitive match against
        // alias / username / bio. When the query is blank these return the
        // original list unchanged.
        val filteredFriends = remember(friends, searchQuery) { filterFriends(friends, searchQuery) }
        val filteredPending = remember(pending, searchQuery) { filterFriends(pending, searchQuery) }
        val filteredSent = remember(sent, searchQuery) { filterFriends(sent, searchQuery) }

        // Content
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF3E1F91), modifier = Modifier.size(32.dp))
            }
        } else if (searchQuery.isNotBlank() && when (selectedSubTab) {
                0 -> filteredFriends
                1 -> filteredPending
                else -> filteredSent
            }.isEmpty()
        ) {
            // Shared empty-state for "no match" across all sub-tabs.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Không tìm thấy “${searchQuery.trim()}”",
                    color = Color(0xFF7F8C8D),
                    fontSize = 14.sp,
                )
            }
        } else {
            when (selectedSubTab) {
                0 -> FriendsList(
                    friends = filteredFriends,
                    onChat = onChat,
                    onCall = onCall,
                    onLongPress = { detailFor = it },
                    onUnfriend = { friendId ->
                        scope.launch {
                            try {
                                val res = container.api.unfriend(mapOf("friend_id" to friendId))
                                if (res.success) {
                                    friends = friends.filter { it.id != friendId }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                )
                1 -> PendingList(
                    pending = filteredPending,
                    respondingIds = respondingIds,
                    onAccept = { requestId, _ ->
                        scope.launch {
                            respondingIds = respondingIds + requestId
                            try {
                                val res = container.api.respondFriendRequest(
                                    mapOf("request_id" to requestId, "action" to "accept")
                                )
                                if (res.success) {
                                    pending = pending.filter { it.request_id != requestId }
                                    loadData("friends")
                                    android.widget.Toast.makeText(
                                        context, "Đã chấp nhận lời mời kết bạn",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        res.message?.ifBlank { null } ?: "Không xử lý được lời mời",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("FriendReq", "Accept failed", e)
                                android.widget.Toast.makeText(
                                    context, parseErrorMessage(e),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            respondingIds = respondingIds - requestId
                        }
                    },
                    onReject = { requestId ->
                        scope.launch {
                            respondingIds = respondingIds + requestId
                            try {
                                val res = container.api.respondFriendRequest(
                                    mapOf("request_id" to requestId, "action" to "reject")
                                )
                                if (res.success) {
                                    pending = pending.filter { it.request_id != requestId }
                                    android.widget.Toast.makeText(
                                        context, "Đã từ chối lời mời",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        res.message?.ifBlank { null } ?: "Không xử lý được lời mời",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("FriendReq", "Reject failed", e)
                                android.widget.Toast.makeText(
                                    context, parseErrorMessage(e),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            respondingIds = respondingIds - requestId
                        }
                    }
                )
                2 -> SentList(filteredSent)
            }
        }
    }

    // Long-press detail dialog
    detailFor?.let { friend ->
        FriendDetailDialog(
            friend = friend,
            onDismiss = { detailFor = null },
            onChat = {
                detailFor = null
                onChat(friend.id)
            },
            onOpenWall = {
                detailFor = null
                onOpenWall(friend.id)
            },
            onUnfriend = {
                detailFor = null
                scope.launch {
                    try {
                        val res = container.api.unfriend(mapOf("friend_id" to friend.id))
                        if (res.success) {
                            friends = friends.filter { it.id != friend.id }
                        }
                    } catch (_: Exception) {}
                }
            },
            onSaveAlias = { newAlias ->
                scope.launch {
                    try {
                        val body = if (newAlias.isBlank())
                            mapOf("friend_id" to friend.id)
                        else
                            mapOf("friend_id" to friend.id, "alias" to newAlias)
                        val res = container.api.setFriendAlias(body)
                        if (res.success) {
                            friends = friends.map {
                                if (it.id == friend.id) it.copy(alias = newAlias.takeIf { it.isNotBlank() }) else it
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        )
    }
}

@Composable
private fun FriendsList(
    friends: List<Friend>,
    onChat: (Int) -> Unit,
    onCall: (userId: Int, isVideo: Boolean) -> Unit,
    onLongPress: (Friend) -> Unit,
    onUnfriend: (Int) -> Unit
) {
    if (friends.isEmpty()) {
        EmptyState("Chưa có bạn bè")
        return
    }

    LazyColumn {
        // Friend count header
        item {
            Text(
                "${friends.size} bạn bè",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                fontSize = 13.sp,
                color = Color(0xFF7F8C8D),
                fontWeight = FontWeight.Medium
            )
        }

        items(friends, key = { it.id }) { friend ->
            FriendItem(
                friend = friend,
                onClick = { onChat(friend.id) },
                onLongClick = { onLongPress(friend) },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        IconButton(
                            onClick = { onCall(friend.id, false) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Call,
                                "Gọi thoại",
                                tint = Color(0xFF3E1F91),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = { onCall(friend.id, true) },
                            modifier = Modifier
                                .size(36.dp)
                                .padding(end = 5.dp)
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                "Gọi video",
                                tint = Color(0xFF3E1F91),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun PendingList(
    pending: List<Friend>,
    respondingIds: Set<Int>,
    onAccept: (Int, Int) -> Unit,
    onReject: (Int) -> Unit
) {
    if (pending.isEmpty()) {
        EmptyState("Không có lời mời nào")
        return
    }

    LazyColumn {
        items(pending, key = { it.request_id ?: it.id }) { request ->
            val inFlight = request.request_id != null && request.request_id in respondingIds
            FriendItem(
                friend = request,
                subtitle = request.message,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Reject
                        OutlinedButton(
                            onClick = { request.request_id?.let { onReject(it) } },
                            enabled = !inFlight,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Từ chối", fontSize = 12.sp)
                        }
                        // Accept
                        Button(
                            onClick = { request.request_id?.let { onAccept(it, request.id) } },
                            enabled = !inFlight,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E1F91))
                        ) {
                            if (inFlight) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Đồng ý", fontSize = 12.sp)
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun SentList(sent: List<Friend>) {
    if (sent.isEmpty()) {
        EmptyState("Chưa gửi lời mời nào")
        return
    }

    LazyColumn {
        items(sent, key = { it.id }) { request ->
            FriendItem(
                friend = request,
                trailing = {
                    Text(
                        "Đã gửi",
                        fontSize = 12.sp,
                        color = Color(0xFF7F8C8D)
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendItem(
    friend: Friend,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    val avatarUrl = UrlUtils.toFullUrl(friend.avatar)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    onClick != null && onLongClick != null ->
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = friend.username,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    friend.username.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Name + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                friend.username,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2C3E50),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = Color(0xFF7F8C8D),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (friend.bio != null) {
                Text(
                    friend.bio,
                    fontSize = 13.sp,
                    color = Color(0xFF7F8C8D),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Trailing action
        trailing()
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(top = 80.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(message, color = Color(0xFF7F8C8D), fontSize = 14.sp)
    }
}

/**
 * Zalo-style friend detail card shown on long-press of a contact item.
 * Avatar + name (alias-aware) + inline alias edit + quick actions + bottom
 * "Xoá bạn" / "Nhắn tin" buttons. Unimplemented rows (chung nhóm, nhật ký
 * chung, bạn thân) show a "Đang phát triển" Toast when tapped.
 */
@Composable
private fun FriendDetailDialog(
    friend: Friend,
    onDismiss: () -> Unit,
    onChat: () -> Unit,
    onOpenWall: () -> Unit,
    onUnfriend: () -> Unit,
    onSaveAlias: (String) -> Unit
) {
    val context = LocalContext.current
    var confirmUnfriend by remember { mutableStateOf(false) }
    var editingAlias by remember { mutableStateOf(false) }
    var aliasDraft by remember { mutableStateOf(friend.alias ?: friend.username) }
    val showDevToast: () -> Unit = {
        android.widget.Toast.makeText(context, "Đang phát triển", android.widget.Toast.LENGTH_SHORT).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 25.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Avatar with gradient ring
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .border(BorderStroke(3.dp, Color(0xFF3E1F91)), CircleShape)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val avatarUrl = UrlUtils.toFullUrl(friend.avatar)
                    if (avatarUrl != null) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = friend.username,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF3E1F91)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                friend.username.firstOrNull()?.uppercase() ?: "?",
                                color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Name area — ONE container that morphs between display and
                // edit modes in place. Both the Text and the BasicTextField
                // sit at the same centered position (same Box, same
                // contentAlignment = Center, same typography), so toggling
                // editingAlias doesn't reflow the layout. The trailing action
                // icon lives at CenterEnd regardless of mode, and only swaps
                // between pencil (edit) and check (save).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val nameStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2C3E50),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (editingAlias) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = aliasDraft,
                            onValueChange = { aliasDraft = it },
                            singleLine = true,
                            textStyle = nameStyle,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF3E1F91)),
                            modifier = Modifier
                                .drawBehind {
                                    val strokeWidthPx = 1.dp.toPx()
                                    val y = size.height - strokeWidthPx / 2
                                    drawLine(
                                        color = androidx.compose.ui.graphics.Color(0xFF3E1F91),
                                        start = androidx.compose.ui.geometry.Offset(0f, y),
                                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                                        strokeWidth = strokeWidthPx
                                    )
                                }
                        )
                    } else {
                        Text(
                            friend.alias ?: friend.username,
                            style = nameStyle
                        )
                    }
                    IconButton(
                        onClick = {
                            if (editingAlias) {
                                onSaveAlias(aliasDraft.trim())
                                editingAlias = false
                            } else {
                                aliasDraft = friend.alias ?: friend.username
                                editingAlias = true
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(32.dp)
                    ) {
                        if (editingAlias) {
                            Icon(
                                Icons.Default.Check, "Lưu",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(
                                    vn.chat9.app.R.drawable.ic_contact_edit
                                ),
                                contentDescription = "Chỉnh sửa",
                                tint = Color(0xFF7F8C8D),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Subtitle — show the real username if an alias is being used
                if (!friend.alias.isNullOrBlank() && friend.alias != friend.username) {
                    Text(
                        "Tên 9chat: ${friend.username}",
                        fontSize = 14.sp,
                        color = Color(0xFF7F8C8D),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else if (!friend.bio.isNullOrBlank()) {
                    Text(
                        friend.bio,
                        fontSize = 14.sp,
                        color = Color(0xFF7F8C8D),
                        modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Two quick-action chips
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailChip(
                        icon = Icons.Default.Person,
                        label = "Xem trang cá nhân",
                        onClick = onOpenWall,
                        modifier = Modifier.weight(1f)
                    )
                    DetailChip(
                        iconRes = vn.chat9.app.R.drawable.ic_contact_block_manage,
                        label = "Quản lý chặn",
                        onClick = showDevToast,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // List rows
                DetailRow(
                    iconRes = vn.chat9.app.R.drawable.ic_contact_friends_since,
                    title = friendSinceText(friend.friends_since)
                )
                DetailDivider()
                DetailRow(
                    iconRes = vn.chat9.app.R.drawable.ic_contact_shared_groups,
                    title = "Xem nhóm chung (0)",
                    showChevron = true,
                    onClick = showDevToast
                )
                DetailDivider()
                DetailRow(
                    iconRes = vn.chat9.app.R.drawable.ic_contact_shared_timeline,
                    title = "Xem nhật ký chung",
                    showChevron = true,
                    onClick = showDevToast
                )
                Spacer(Modifier.height(16.dp))

                // Bottom actions
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { confirmUnfriend = true },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                    ) {
                        Text("Xoá bạn", color = Color(0xFF2C3E50))
                    }
                    Button(
                        onClick = onChat,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                    ) {
                        Text("Nhắn tin", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (confirmUnfriend) {
        AlertDialog(
            onDismissRequest = { confirmUnfriend = false },
            title = { Text("Hủy kết bạn") },
            text = { Text("Bạn có chắc muốn hủy kết bạn với ${friend.alias ?: friend.username}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnfriend = false
                    onUnfriend()
                }) { Text("Hủy kết bạn", color = Color(0xFFFF3B30)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnfriend = false }) { Text("Không") }
            }
        )
    }
}

@Composable
private fun DetailChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    DetailChipBase(label, onClick, modifier) {
        Icon(icon, null, tint = Color(0xFF2196F3), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DetailChip(
    @androidx.annotation.DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    DetailChipBase(label, onClick, modifier) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(iconRes),
            contentDescription = null,
            tint = Color(0xFF2196F3),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun DetailChipBase(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF5F6F8))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.8.dp, vertical = 10.8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = Color(0xFF2C3E50), maxLines = 1)
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    DetailRowBase(title, showChevron, onClick, trailing) {
        Icon(icon, null, tint = Color(0xFF7F8C8D), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DetailRow(
    @androidx.annotation.DrawableRes iconRes: Int,
    title: String,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    DetailRowBase(title, showChevron, onClick, trailing) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(iconRes),
            contentDescription = null,
            tint = Color(0xFF7F8C8D),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun DetailRowBase(
    title: String,
    showChevron: Boolean,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 15.sp, color = Color(0xFF2C3E50), modifier = Modifier.weight(1f))
        when {
            trailing != null -> trailing()
            showChevron -> Icon(
                Icons.Default.ChevronRight, null,
                tint = Color(0xFFBDBDBD), modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DetailDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp, end = 20.dp),
        color = Color(0xFFEEEEEE),
        thickness = 0.5.dp
    )
}

/** Rough "đã kết bạn" text. Returns a generic label if the timestamp can't be parsed. */
private fun friendSinceText(friendsSince: String?): String {
    if (friendsSince.isNullOrBlank()) return "Đã kết bạn"
    return try {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val parsed = java.time.LocalDateTime.parse(friendsSince, formatter)
        val now = java.time.LocalDateTime.now()
        val days = java.time.Duration.between(parsed, now).toDays()
        when {
            days < 1 -> "Đã kết bạn hôm nay"
            days < 30 -> "Đã kết bạn $days ngày trước"
            days < 365 -> "Đã kết bạn ${days / 30} tháng trước"
            else -> "Đã kết bạn ${days / 365} năm trước"
        }
    } catch (_: Exception) {
        "Đã kết bạn"
    }
}

/**
 * In-memory filter for a Friend list against a free-text query. Matches
 * against alias, username, and bio (case-insensitive, substring). Empty /
 * blank query returns the list unchanged so callers can always pass the
 * filtered result into the sub-tab composables without conditional logic.
 */
private fun filterFriends(list: List<Friend>, query: String): List<Friend> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return list
    return list.filter { f ->
        f.alias?.lowercase()?.contains(q) == true ||
            f.username.lowercase().contains(q) ||
            f.bio?.lowercase()?.contains(q) == true
    }
}

/**
 * Editable version of AppSearchBar used inside the Contacts tab. Keeps the
 * same gradient + right-icon shell as the shared component for visual
 * parity with the rooms-list search bar, but swaps the read-only Text for
 * a BasicTextField so the user can type locally to filter contacts.
 * AppSearchBar stays untouched — it's still used as the tap-to-open stub
 * in RoomListScreen.
 */
@androidx.compose.runtime.Composable
private fun ContactsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onAddFriendClick: () -> Unit,
) {
    val gradient = androidx.compose.ui.graphics.Brush.horizontalGradient(
        listOf(
            androidx.compose.ui.graphics.Color(0xFF3E1F91),
            androidx.compose.ui.graphics.Color(0xFF8E44AD),
            androidx.compose.ui.graphics.Color(0xFFFF6F61),
        )
    )
    androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Default.Search,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                modifier = androidx.compose.ui.Modifier.size(22.dp),
            )
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(10.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    color = androidx.compose.ui.graphics.Color.White,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                    androidx.compose.ui.graphics.Color.White
                ),
                modifier = androidx.compose.ui.Modifier.weight(1f),
                decorationBox = { inner ->
                    androidx.compose.foundation.layout.Box(
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            androidx.compose.material3.Text(
                                "Tìm bạn bè",
                                fontSize = 16.sp,
                                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                androidx.compose.material3.IconButton(
                    onClick = { onQueryChange("") },
                    modifier = androidx.compose.ui.Modifier.size(28.dp),
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Xoá",
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = androidx.compose.ui.Modifier.size(18.dp),
                    )
                }
            } else {
                androidx.compose.material3.IconButton(
                    onClick = onAddFriendClick,
                    modifier = androidx.compose.ui.Modifier.size(28.dp),
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(vn.chat9.app.R.drawable.ic_add_friend),
                        contentDescription = "Thêm bạn",
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = androidx.compose.ui.Modifier.size(24.dp),
                    )
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(
            color = androidx.compose.ui.graphics.Color(0xFFE0E6ED),
            thickness = 1.dp,
        )
    }
}
