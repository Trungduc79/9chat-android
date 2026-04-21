package vn.chat9.app.ui.rooms

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.model.Room
import vn.chat9.app.util.DateUtils
import vn.chat9.app.util.UrlUtils

@Composable
fun RoomListScreen(onRoomClick: (Room) -> Unit, refreshKey: Int = 0, onSearchClick: () -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var rooms by remember { mutableStateOf<List<Room>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var friendIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var pinnedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var mutedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedRoomIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }

    // Load pin + mute state from SharedPrefs (and refresh on each composition)
    LaunchedEffect(Unit) {
        val pinPrefs = context.getSharedPreferences("room_pin", android.content.Context.MODE_PRIVATE)
        pinnedIds = pinPrefs.all
            .filterValues { it == true }
            .keys.mapNotNull { it.removePrefix("room_").toIntOrNull() }
            .toSet()
        val mutePrefs = context.getSharedPreferences("room_mute", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        mutedIds = mutePrefs.all
            .filterValues { (it as? Long ?: 0L) > now }
            .keys.mapNotNull { it.removePrefix("room_").toIntOrNull() }
            .toSet()
    }

    // Display order: pinned first (top), rest keep server order
    val displayedRooms = run {
        val (pinned, unpinned) = rooms.partition { pinnedIds.contains(it.id) }
        pinned + unpinned
    }

    // Load rooms + friend IDs
    LaunchedEffect(refreshKey) {
        try {
            val friendRes = container.api.getFriends("friends")
            if (friendRes.success && friendRes.data != null) friendIds = friendRes.data.map { it.id }.toSet()
        } catch (_: Exception) {}
        try {
            val res = container.api.getRooms()
            if (res.success && res.data != null) {
                rooms = res.data
            }
        } catch (e: Exception) {
            android.util.Log.e("RoomList", "Error loading rooms", e)
        }
        isLoading = false
    }

    // Listen for new messages to update room list in real-time
    // Use DisposableEffect to clean up listener when leaving composition
    val notificationListener = remember<(Array<Any>) -> Unit> {{ args ->
        try {
            val data = args[0] as org.json.JSONObject
            val roomId = data.getInt("room_id")
            val msgJson = data.getJSONObject("message")

            val newMsg = vn.chat9.app.data.model.Message(
                id = msgJson.getInt("id"),
                room_id = roomId,
                user_id = msgJson.getInt("user_id"),
                type = msgJson.optString("type", "text"),
                content = msgJson.optString("content", null),
                file_url = msgJson.optString("file_url", null),
                file_name = msgJson.optString("file_name", null),
                file_size = msgJson.optLong("file_size", 0),
                reply_to = null,
                created_at = msgJson.optString("created_at", "").ifEmpty {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).apply {
                        timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
                    }.format(java.util.Date())
                },
                username = msgJson.optString("username", null),
                avatar = msgJson.optString("avatar", null)
            )

            val updated = rooms.toMutableList()
            val idx = updated.indexOfFirst { it.id == roomId }
            if (idx >= 0) {
                val room = updated[idx]
                updated[idx] = room.copy(
                    last_message = newMsg,
                    unread_count = (room.unread_count ?: 0) + 1
                )
                val moved = updated.removeAt(idx)
                updated.add(0, moved)
                rooms = updated
            } else {
                scope.launch {
                    try {
                        val res = container.api.getRooms()
                        if (res.success && res.data != null) rooms = res.data
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RoomList", "Error updating room", e)
        }
    }}

    DisposableEffect(Unit) {
        container.socket.on("new_message_notification", notificationListener)
        onDispose {
            container.socket.off("new_message_notification", notificationListener)
        }
    }

    // State lifted so overlay can live outside the Column, at Box root level
    var confirmDelete by remember { mutableStateOf<Room?>(null) }
    var menuRoom by remember { mutableStateOf<Room?>(null) }
    var menuRoomBounds by remember { mutableStateOf<Rect?>(null) }
    val roomBounds = remember { mutableStateMapOf<Int, Rect>() }
    val currentUserId = container.tokenManager.user?.id ?: 0

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (multiSelectMode) {
            // Multi-select top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    multiSelectMode = false
                    selectedRoomIds = emptySet()
                }) {
                    Icon(Icons.Default.Close, "Đóng", tint = Color(0xFF2C3E50))
                }
                Text(
                    text = "${selectedRoomIds.size}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2C3E50)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Chọn tất cả",
                    fontSize = 15.sp,
                    color = Color(0xFF2196F3),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable {
                            selectedRoomIds = if (selectedRoomIds.size == rooms.size) {
                                emptySet()
                            } else {
                                rooms.map { it.id }.toSet()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            HorizontalDivider(color = Color(0x14000000), thickness = 1.dp)
        } else {
            // Search bar
            vn.chat9.app.ui.common.AppSearchBar(
                onSearchClick = onSearchClick,
                rightIconRes = vn.chat9.app.R.drawable.ic_add,
                onRightIconClick = { /* TODO */ }
            )
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF3E1F91))
            }
        } else if (rooms.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Chưa có cuộc trò chuyện", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(displayedRooms, key = { it.id }) { room ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                try { roomBounds[room.id] = coords.boundsInRoot() } catch (_: Exception) {}
                            }
                    ) {
                        RoomItem(
                            room = room,
                            currentUserId = currentUserId,
                            friendIds = friendIds,
                            isPinned = pinnedIds.contains(room.id),
                            isMuted = mutedIds.contains(room.id),
                            isHighlighted = menuRoom?.id == room.id,
                            multiSelect = multiSelectMode,
                            isSelected = selectedRoomIds.contains(room.id),
                            onClick = {
                                if (multiSelectMode) {
                                    selectedRoomIds = if (selectedRoomIds.contains(room.id)) {
                                        selectedRoomIds - room.id
                                    } else {
                                        selectedRoomIds + room.id
                                    }
                                } else {
                                    onRoomClick(room)
                                }
                            },
                            onLongPress = if (multiSelectMode) null else {
                                {
                                    menuRoomBounds = roomBounds[room.id]
                                    menuRoom = room
                                }
                            }
                        )
                    }
                }
            }

        }
    }

    // Overlay + menu rendered OUTSIDE Column so it covers everything and shares
    // the same coordinate system as the items (makes bounds positioning exact).
    menuRoom?.let { target ->
        RoomContextMenu(
            room = target,
            currentUserId = currentUserId,
            friendIds = friendIds,
            isPinned = pinnedIds.contains(target.id),
            isMuted = mutedIds.contains(target.id),
            anchorBounds = menuRoomBounds,
            onDismiss = { menuRoom = null; menuRoomBounds = null },
            onMarkUnread = {
                // Optimistic: show unread dot immediately. Server call rewinds the
                // user's last-read pointer by 1 so the latest message becomes unread
                // and persists across refresh.
                val previousCount = target.unread_count ?: 0
                rooms = rooms.map { if (it.id == target.id) it.copy(unread_count = 1) else it }
                menuRoom = null
                scope.launch {
                    try {
                        val res = container.api.markRoomUnread(mapOf("room_id" to target.id))
                        if (!res.success) {
                            // Rollback
                            rooms = rooms.map { if (it.id == target.id) it.copy(unread_count = previousCount) else it }
                            android.widget.Toast.makeText(
                                context,
                                res.message ?: "Không đánh dấu được",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        rooms = rooms.map { if (it.id == target.id) it.copy(unread_count = previousCount) else it }
                        android.widget.Toast.makeText(context, "Lỗi: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onPin = {
                val prefs = context.getSharedPreferences("room_pin", android.content.Context.MODE_PRIVATE)
                val key = "room_${target.id}"
                val currentlyPinned = prefs.getBoolean(key, false)
                prefs.edit().putBoolean(key, !currentlyPinned).apply()
                pinnedIds = if (currentlyPinned) pinnedIds - target.id else pinnedIds + target.id
                android.widget.Toast.makeText(
                    context,
                    if (currentlyPinned) "Đã bỏ ghim" else "Đã ghim",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                menuRoom = null
            },
            onMute = {
                val prefs = context.getSharedPreferences("room_mute", android.content.Context.MODE_PRIVATE)
                val key = "room_${target.id}"
                val now = System.currentTimeMillis()
                val until = prefs.getLong(key, 0)
                if (until > now) {
                    prefs.edit().remove(key).apply()
                    mutedIds = mutedIds - target.id
                    android.widget.Toast.makeText(context, "Đã bật thông báo", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // Permanent mute — Long.MAX_VALUE ensures it never expires by time
                    prefs.edit().putLong(key, Long.MAX_VALUE).apply()
                    mutedIds = mutedIds + target.id
                    android.widget.Toast.makeText(context, "Đã tắt thông báo", android.widget.Toast.LENGTH_SHORT).show()
                }
                menuRoom = null
            },
            onHide = {
                android.widget.Toast.makeText(context, "Đang phát triển", android.widget.Toast.LENGTH_SHORT).show()
                menuRoom = null
            },
            onDelete = {
                menuRoom = null
                confirmDelete = target
            },
            onSelectMultiple = {
                multiSelectMode = true
                selectedRoomIds = setOf(target.id)
                menuRoom = null
            }
        )
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Xoá cuộc trò chuyện") },
            text = { Text("Ẩn cuộc trò chuyện này khỏi danh sách của bạn? Các thành viên khác không bị ảnh hưởng.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    confirmDelete = null
                    scope.launch {
                        try {
                            val res = container.api.deleteRoom(mapOf("room_id" to id))
                            if (res.success) {
                                rooms = rooms.filter { it.id != id }
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    res.message ?: "Xoá thất bại",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                "Lỗi: ${e.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) { Text("Xoá", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Huỷ") }
            }
        )
    }

    // Bottom action bar in multi-select mode
    if (multiSelectMode) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            color = Color.White,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Đánh dấu đã đọc
                Column(
                    modifier = Modifier
                        .clickable(enabled = selectedRoomIds.isNotEmpty()) {
                            val ids = selectedRoomIds.toList()
                            scope.launch {
                                try {
                                    ids.forEach { id -> container.api.markRoomRead(mapOf("room_id" to id)) }
                                    rooms = rooms.map { if (it.id in ids) it.copy(unread_count = 0) else it }
                                    android.widget.Toast.makeText(context, "Đã đánh dấu đọc", android.widget.Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Lỗi: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            multiSelectMode = false
                            selectedRoomIds = emptySet()
                        }
                        .padding(vertical = 8.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painterResource(vn.chat9.app.R.drawable.ic_mark_unread),
                        contentDescription = "Đánh dấu đã đọc",
                        tint = if (selectedRoomIds.isNotEmpty()) Color(0xFF2C3E50) else Color.Gray,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Đánh dấu đã đọc", fontSize = 13.sp,
                        color = if (selectedRoomIds.isNotEmpty()) Color(0xFF2C3E50) else Color.Gray)
                }
                // Xoá
                Column(
                    modifier = Modifier
                        .clickable(enabled = selectedRoomIds.isNotEmpty()) {
                            confirmBulkDelete = true
                        }
                        .padding(vertical = 8.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Xoá",
                        tint = if (selectedRoomIds.isNotEmpty()) Color(0xFFE53935) else Color.Gray,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Xoá", fontSize = 13.sp,
                        color = if (selectedRoomIds.isNotEmpty()) Color(0xFFE53935) else Color.Gray)
                }
            }
        }
    }

    // Bulk delete confirm dialog
    if (confirmBulkDelete) {
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Xoá ${selectedRoomIds.size} cuộc trò chuyện") },
            text = { Text("Ẩn ${selectedRoomIds.size} cuộc trò chuyện đã chọn khỏi danh sách của bạn? Các thành viên khác không bị ảnh hưởng.") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedRoomIds.toList()
                    confirmBulkDelete = false
                    multiSelectMode = false
                    selectedRoomIds = emptySet()
                    scope.launch {
                        val succeeded = mutableSetOf<Int>()
                        val firstError = StringBuilder()
                        ids.forEach { id ->
                            try {
                                val res = container.api.deleteRoom(mapOf("room_id" to id))
                                if (res.success) {
                                    succeeded.add(id)
                                } else if (firstError.isEmpty()) {
                                    firstError.append(res.message ?: "Xoá thất bại")
                                }
                            } catch (e: Exception) {
                                if (firstError.isEmpty()) firstError.append(e.message ?: e.javaClass.simpleName)
                            }
                        }
                        // Only remove rooms that actually deleted on the server
                        rooms = rooms.filter { it.id !in succeeded }
                        val failCount = ids.size - succeeded.size
                        if (failCount > 0) {
                            android.widget.Toast.makeText(
                                context,
                                "Xoá thất bại $failCount/${ids.size}: $firstError",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }) { Text("Xoá", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) { Text("Huỷ") }
            }
        )
    }
    } // end Box
}

@Composable
private fun RoomContextMenu(
    room: Room,
    currentUserId: Int,
    friendIds: Set<Int>,
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    anchorBounds: Rect?,
    onDismiss: () -> Unit,
    onMarkUnread: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    onSelectMultiple: () -> Unit,
) {
    val density = LocalDensity.current
    val horizontalPadPx = with(density) { 16.dp.toPx() }
    val gapPx = with(density) { 14.dp.toPx() }
    val estimatedMenuHeightPx = with(density) { 340.dp.toPx() }

    androidx.activity.compose.BackHandler(enabled = true, onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        if (anchorBounds == null) {
                // Fallback: center layout when we somehow don't have the anchor bounds
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MenuCard(onMarkUnread, onPin, onMute, onHide, onDelete, onSelectMultiple)
                    Spacer(Modifier.height(14.dp))
                    RoomPreviewCard(room, currentUserId, friendIds)
                }
            } else {
                // Position preview at the item's original Y; menu above (or below if no room).
                val previewTop = anchorBounds.top
                val previewHeight = anchorBounds.height
                val menuAboveY = previewTop - estimatedMenuHeightPx - gapPx
                val placeAbove = menuAboveY > with(density) { 48.dp.toPx() } // leave room for status bar
                val menuY = if (placeAbove) menuAboveY
                            else previewTop + previewHeight + gapPx
                val menuXPx = horizontalPadPx

                // Room preview — at original position
                Box(
                    modifier = Modifier
                        .absoluteOffset { IntOffset(0, previewTop.toInt()) }
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                onClick = {}
                            ),
                        color = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp
                    ) {
                        RoomItem(
                            room = room,
                            currentUserId = currentUserId,
                            friendIds = friendIds,
                            isHighlighted = false,
                            onClick = {},
                            onLongPress = null
                        )
                    }
                }

                // Menu — above (or below) the preview, aligned to left padding
                Surface(
                    modifier = Modifier
                        .absoluteOffset { IntOffset(menuXPx.toInt(), menuY.toInt()) }
                        .width(260.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            onClick = {}
                        ),
                    color = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        ContextMenuItem(iconRes = vn.chat9.app.R.drawable.ic_mark_unread, label = "Đánh dấu chưa đọc", onClick = onMarkUnread)
                        ContextMenuItem(
                            Icons.Default.PushPin,
                            if (isPinned) "Bỏ ghim" else "Ghim",
                            onClick = onPin
                        )
                        ContextMenuItem(
                            if (isMuted) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                            if (isMuted) "Bật thông báo" else "Tắt thông báo",
                            onClick = onMute
                        )
                        ContextMenuItem(Icons.Default.VisibilityOff, "Ẩn", onClick = onHide)
                        ContextMenuItem(Icons.Default.Delete, "Xoá", textColor = Color(0xFFE53935), iconTint = Color(0xFFE53935), onClick = onDelete)
                        HorizontalDivider(color = Color(0x14000000), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                        ContextMenuItem(iconRes = vn.chat9.app.R.drawable.ic_select_multiple, label = "Chọn nhiều", onClick = onSelectMultiple)
                    }
                }
            }
        }
    }

@Composable
private fun MenuCard(
    onMarkUnread: () -> Unit, onPin: () -> Unit, onMute: () -> Unit,
    onHide: () -> Unit, onDelete: () -> Unit, onSelectMultiple: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(260.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = {}
            ),
        color = Color.White,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            ContextMenuItem(iconRes = vn.chat9.app.R.drawable.ic_mark_unread, label = "Đánh dấu chưa đọc", onClick = onMarkUnread)
            ContextMenuItem(Icons.Default.PushPin, "Ghim", onClick = onPin)
            ContextMenuItem(Icons.Default.NotificationsOff, "Tắt thông báo", onClick = onMute)
            ContextMenuItem(Icons.Default.VisibilityOff, "Ẩn", onClick = onHide)
            ContextMenuItem(Icons.Default.Delete, "Xoá", textColor = Color(0xFFE53935), iconTint = Color(0xFFE53935), onClick = onDelete)
            HorizontalDivider(color = Color(0x14000000), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
            ContextMenuItem(iconRes = vn.chat9.app.R.drawable.ic_select_multiple, label = "Chọn nhiều", onClick = onSelectMultiple)
        }
    }
}

@Composable
private fun RoomPreviewCard(room: Room, currentUserId: Int, friendIds: Set<Int>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = {}
            ),
        color = Color.White,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        RoomItem(
            room = room,
            currentUserId = currentUserId,
            friendIds = friendIds,
            isHighlighted = false,
            onClick = {},
            onLongPress = null
        )
    }
}

@Composable
private fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    textColor: Color = Color(0xFF2C3E50),
    iconTint: Color = Color(0xFF666666),
    onClick: () -> Unit,
) {
    ContextMenuRow(label, textColor, onClick) {
        Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ContextMenuItem(
    iconRes: Int,
    label: String,
    textColor: Color = Color(0xFF2C3E50),
    iconTint: Color = Color(0xFF666666),
    onClick: () -> Unit,
) {
    ContextMenuRow(label, textColor, onClick) {
        Icon(painterResource(iconRes), contentDescription = label, tint = iconTint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ContextMenuRow(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 15.sp, color = textColor)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoomItem(
    room: Room,
    currentUserId: Int,
    friendIds: Set<Int> = emptySet(),
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    isHighlighted: Boolean = false,
    multiSelect: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val name = if (room.type == "private" && room.other_user != null) {
        room.other_user.displayName
    } else {
        room.name ?: "Nhóm chat"
    }

    val avatarUrl = if (room.type == "private" && room.other_user != null) {
        UrlUtils.toFullUrl(room.other_user.avatar)
    } else null

    val isOnline = room.type == "private" && room.other_user?.is_online == true
    val isStranger = room.type == "private" && room.other_user != null && room.other_user.id != currentUserId && !friendIds.contains(room.other_user.id)
    val unread = room.unread_count ?: 0

    val preview = room.last_message?.let { msg ->
        val sender = if (msg.user_id == currentUserId) "Bạn"
            else if (room.type == "private" && room.other_user != null) room.other_user.displayName
            else (msg.username ?: "")
        when (msg.type) {
            "image" -> "$sender: Hình ảnh"
            "file" -> "$sender: File"
            "video" -> "$sender: Video"
            "audio" -> "$sender: Audio"
            "contact" -> "$sender: Danh thiếp"
            "location" -> "$sender: Vị trí"
            "recalled" -> "$sender: Đã thu hồi tin nhắn"
            "call" -> "$sender: Cuộc gọi"
            else -> "$sender: ${msg.content ?: ""}"
        }
    } ?: "Chưa có tin nhắn"

    val time = room.last_message?.created_at?.let { DateUtils.formatTime(it) } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isHighlighted) Color(0xFFEEF2FF) else Color.White)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = Color(0x14000000),
                    start = androidx.compose.ui.geometry.Offset(0f, size.height - strokeWidth),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height - strokeWidth),
                    strokeWidth = strokeWidth
                )
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress?.let { lp -> { lp() } }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkbox in multi-select mode
        if (multiSelect) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF2196F3) else Color.Transparent)
                    .then(
                        if (!isSelected)
                            Modifier.drawBehind {
                                drawCircle(
                                    color = Color(0xFFBDBDBD),
                                    radius = size.minDimension / 2 - 1.dp.toPx(),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                )
                            }
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Đã chọn",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        // Avatar
        Box {
            var showFallback by remember { mutableStateOf(avatarUrl == null) }
            if (avatarUrl != null && !showFallback) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = name,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    onError = { showFallback = true }
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3E1F91)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
            }
            // Online dot
            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .align(Alignment.BottomEnd)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00C853))
                            .align(Alignment.Center)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isStranger) {
                        Text(
                            "Người lạ",
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                            modifier = Modifier
                                .background(Color(0xFFFF9800), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = name,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Đã ghim",
                            tint = Color(0xFF3E1F91),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (isMuted) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Đã tắt thông báo",
                            tint = Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = time,
                        fontSize = 12.sp,
                        color = if (unread > 0 && !isMuted) Color(0xFF3E1F91) else Color.Gray
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preview,
                    fontSize = 14.sp,
                    color = if (unread > 0) Color.DarkGray else Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unread > 0) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (unread > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isMuted) {
                        // Silent unread — small red dot only
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF6F61))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF6F61)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (unread > 5) "5+" else unread.toString(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 10.sp,
                                style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
            }
        }
    }
}
