package vn.chat9.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import vn.chat9.app.App
import vn.chat9.app.data.model.Story
import vn.chat9.app.data.model.WallData
import vn.chat9.app.util.DateUtils
import vn.chat9.app.util.UrlUtils

/**
 * User wall — Zalo-style:
 *  - Cover photo full-bleed kéo lên status bar
 *  - Avatar 104dp (tăng 30% so với 80dp cũ) overlap đáy cover
 *  - Floating back button + 3-dot menu (chỉ với friend) trên cover
 *  - Tên + pencil → tap để edit alias inline (chỉ friend)
 *  - 3 nút action: Nhắn tin / Gọi thoại / Gọi video (chỉ friend)
 *  - Non-friend: giữ flow add-friend cũ
 *  - Stories ở dưới (chỉ self/friend)
 *
 * @param onCall (userId, isVideo) — caller (MainActivity) tự lo tạo/find
 *               room private rồi navigate Chat(autoCall=VOICE|VIDEO).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserWallScreen(
    userId: Int,
    onBack: () -> Unit,
    onChat: (Int) -> Unit = {},
    onCall: (Int, isVideo: Boolean) -> Unit = { _, _ -> },
    /** Gọi sau khi alias đổi thành công — caller (MainActivity) reload
     *  selectedRoom + friendAliases để header chat + room list đồng bộ. */
    onUserDataChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var wallData by remember { mutableStateOf<WallData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var sendingRequest by remember { mutableStateOf(false) }
    var requestSent by remember { mutableStateOf(false) }
    var aliasEditOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(userId) {
        try {
            val res = container.api.getUserWall(userId)
            if (res.success && res.data != null) {
                wallData = res.data
                requestSent = res.data.friend_status == "sent"
            }
        } catch (_: Exception) {}
        isLoading = false
    }

    fun parseErrorMessage(e: Exception): String {
        return (e as? retrofit2.HttpException)?.let { httpE ->
            try {
                val body = httpE.response()?.errorBody()?.string() ?: ""
                org.json.JSONObject(body).optString("message").takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
        } ?: "Lỗi kết nối, thử lại sau"
    }

    val sendFriendRequest: () -> Unit = {
        if (!sendingRequest && !requestSent) {
            scope.launch {
                sendingRequest = true
                try {
                    val res = container.api.sendFriendRequest(mapOf("friend_id" to userId))
                    if (res.success) {
                        requestSent = true
                        android.widget.Toast.makeText(context, "Đã gửi lời mời kết bạn",
                            android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(context,
                            res.message?.ifBlank { null } ?: "Không gửi được lời mời",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, parseErrorMessage(e),
                        android.widget.Toast.LENGTH_SHORT).show()
                }
                sendingRequest = false
            }
        }
    }

    val respondRequest: (accept: Boolean) -> Unit = { accept ->
        val rid = wallData?.friend_request_id
        if (rid != null && !sendingRequest) {
            scope.launch {
                sendingRequest = true
                try {
                    val res = container.api.respondFriendRequest(
                        mapOf("request_id" to rid, "action" to if (accept) "accept" else "reject")
                    )
                    if (res.success) {
                        android.widget.Toast.makeText(context,
                            if (accept) "Đã chấp nhận lời mời" else "Đã từ chối lời mời",
                            android.widget.Toast.LENGTH_SHORT).show()
                        val fresh = container.api.getUserWall(userId)
                        if (fresh.success && fresh.data != null) wallData = fresh.data
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, parseErrorMessage(e),
                        android.widget.Toast.LENGTH_SHORT).show()
                }
                sendingRequest = false
            }
        }
    }

    val saveAlias: (String) -> Unit = { newAlias ->
        scope.launch {
            try {
                // Server bỏ qua "alias" khi key absent (PHP isset()=false → xóa).
                // Gson default skip null values trong Map → alias trống không
                // gửi key "alias", PHP nhận đúng signal xóa. friend_id luôn gửi.
                val body = mutableMapOf<String, Any>("friend_id" to userId)
                if (newAlias.isNotBlank()) body["alias"] = newAlias
                val res = container.api.setFriendAlias(body.toMap())
                if (res.success) {
                    // Update FriendAliasStore TRƯỚC khi reload UI để mọi
                    // Composable observing store thấy alias mới ngay (chat
                    // header, group sender, room list...) — không cần đợi
                    // API roundtrip.
                    container.friendAliases.setLocal(
                        userId,
                        newAlias.takeIf { it.isNotBlank() },
                    )
                    val fresh = container.api.getUserWall(userId)
                    if (fresh.success && fresh.data != null) wallData = fresh.data
                    onUserDataChanged()  // Báo MainActivity reload selectedRoom
                    android.widget.Toast.makeText(context,
                        if (newAlias.isBlank()) "Đã xóa tên gợi nhớ" else "Đã đặt tên gợi nhớ",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context,
                        res.message?.ifBlank { null } ?: "Không lưu được tên gợi nhớ",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("UserWall", "setFriendAlias failed", e)
                android.widget.Toast.makeText(context, parseErrorMessage(e),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF0F2F5))) {

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF3E1F91))
            }
        } else if (wallData != null) {
            val data = wallData!!
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    WallHeader(
                        data = data,
                        sendingRequest = sendingRequest,
                        requestSent = requestSent,
                        onChat = onChat,
                        onCall = onCall,
                        onSendFriendRequest = sendFriendRequest,
                        onRespond = respondRequest,
                        onEditAlias = { aliasEditOpen = true },
                    )
                }

                if (data.is_self || data.is_friend) {
                    if (data.stories.isNotEmpty()) {
                        item {
                            Text(
                                "Nhật ký",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF2C3E50),
                            )
                        }
                        items(data.stories, key = { it.id }) { story -> WallStoryItem(story) }
                    } else {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Chưa có nhật ký nào", color = Color(0xFF7F8C8D), fontSize = 14.sp)
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        // Floating back button — đè lên cover photo
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onBack)
                .align(Alignment.TopStart),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White,
                 modifier = Modifier.size(22.dp))
        }

        // 3-dot menu cho friend (KHÔNG self) — TODO mờ
        val data = wallData
        if (data != null && data.is_friend && !data.is_self) {
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp)
                    .align(Alignment.TopEnd),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MoreVert, "Menu", tint = Color.White,
                         modifier = Modifier.size(22.dp))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Cài đặt cá nhân", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Settings, null, tint = Color.Gray) },
                        enabled = false,
                        onClick = {},
                    )
                    DropdownMenuItem(
                        text = { Text("Thông báo", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.NotificationsOff, null, tint = Color.Gray) },
                        enabled = false,
                        onClick = {},
                    )
                    DropdownMenuItem(
                        text = { Text("Chặn", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Block, null, tint = Color.Gray) },
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
    }

    if (aliasEditOpen && wallData?.is_friend == true) {
        EditAliasDialog(
            initial = wallData?.friend_alias ?: "",
            originalName = wallData?.user?.username ?: "",
            onDismiss = { aliasEditOpen = false },
            onSave = { newAlias ->
                aliasEditOpen = false
                saveAlias(newAlias)
            },
        )
    }
}

@Composable
private fun WallHeader(
    data: WallData,
    sendingRequest: Boolean,
    requestSent: Boolean,
    onChat: (Int) -> Unit,
    onCall: (Int, isVideo: Boolean) -> Unit,
    onSendFriendRequest: () -> Unit,
    onRespond: (Boolean) -> Unit,
    onEditAlias: () -> Unit,
) {
    // Layout 2 layer:
    //   1. Column (cover full 240dp + white area dưới)
    //   2. Avatar overlay (vẽ sau, z-order trên cùng) — offset y=188 để
    //      top half (52dp) đè cover, bottom half đè white. White area
    //      reserve 56dp top-padding cho avatar bottom half + ~4dp gap.
    // Khu trắng giờ cao đúng = top-padding + content + bottom-padding,
    // không có "dead space" như approach cũ (offset trick để lại 52dp).
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Cover photo full-bleed 240dp
            Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                val coverUrl = UrlUtils.toFullUrl(data.user.cover_photo)
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(Color(0xFF3E1F91), Color(0xFF8E44AD), Color(0xFFFF6F61))
                            )
                        ),
                    )
                }
            }

            // Khu trắng — top padding 56dp = avatar bottom half (52dp) +
            // ~4dp gap để text bắt đầu ngay dưới avatar.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Tên + pencil clickable (chỉ friend mới sửa được alias)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (data.is_friend && !data.is_self)
                    Modifier.clickable(onClick = onEditAlias) else Modifier,
            ) {
                Text(
                    data.friend_alias ?: data.user.username,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50),
                )
                if (data.is_friend && !data.is_self) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Edit,
                        "Chỉnh sửa tên gợi nhớ",
                        tint = Color(0xFF7F8C8D),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (data.friend_alias != null && data.friend_alias != data.user.username) {
                Text(data.user.username, fontSize = 14.sp, color = Color(0xFF7F8C8D))
            }

            if (!data.user.bio.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    data.user.bio,
                    fontSize = 14.sp,
                    color = Color(0xFF7F8C8D),
                    maxLines = 3,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Action row
            when {
                data.is_self -> Unit  // Trang của mình → không có action

                data.is_friend -> {
                    // 3 nút: Nhắn tin | Gọi thoại | Gọi video
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        FriendAction(Icons.AutoMirrored.Filled.Chat, "Nhắn tin") { onChat(data.user.id) }
                        FriendAction(Icons.Default.Call, "Gọi thoại") { onCall(data.user.id, false) }
                        FriendAction(Icons.Default.Videocam, "Gọi video") { onCall(data.user.id, true) }
                    }
                }

                data.friend_status == "pending_received" -> {
                    Text(
                        "${data.friend_alias ?: data.user.username} đã gửi lời mời kết bạn cho bạn",
                        fontSize = 14.sp,
                        color = Color(0xFF7F8C8D),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PillButton("Từ chối", Color(0xFFE0E0E0), Color(0xFF2C3E50),
                                   modifier = Modifier.weight(1f),
                                   enabled = !sendingRequest) { onRespond(false) }
                        PillButton("Chấp nhận", Color(0xFF2196F3), Color.White,
                                   modifier = Modifier.weight(1f),
                                   enabled = !sendingRequest, loading = sendingRequest) { onRespond(true) }
                    }
                }

                else -> {
                    val subtitle = if (data.friend_status == "pending_sent")
                        "Đã gửi lời mời. Chưa thể xem nhật ký khi chưa là bạn bè"
                    else
                        "Bạn chưa thể xem nhật ký của ${data.friend_alias ?: data.user.username} khi chưa là bạn bè"
                    Text(
                        subtitle,
                        fontSize = 14.sp,
                        color = Color(0xFF7F8C8D),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xFFE7F3FF))
                                .clickable { onChat(data.user.id) },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, null, tint = Color(0xFF2196F3),
                                 modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Nhắn tin", color = Color(0xFF2196F3), fontWeight = FontWeight.SemiBold,
                                 fontSize = 15.sp)
                        }
                        val alreadySent = data.friend_status == "pending_sent" || requestSent
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .border(1.dp, Color(0xFFE0E0E0), CircleShape)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable(enabled = !alreadySent && !sendingRequest, onClick = onSendFriendRequest),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                sendingRequest -> CircularProgressIndicator(modifier = Modifier.size(18.dp),
                                                  color = Color(0xFF2196F3), strokeWidth = 2.dp)
                                alreadySent -> Icon(Icons.Default.Check, "Đã gửi", tint = Color(0xFF4CAF50),
                                                    modifier = Modifier.size(20.dp))
                                else -> Icon(Icons.Default.PersonAddAlt1, "Kết bạn",
                                             tint = Color(0xFF2196F3), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
        }

        // Avatar absolute — đè lên ranh giới cover/white. Top y = 188dp,
        // size 104dp → top half (52dp) trên cover, bottom half (52dp)
        // trên khu trắng. Vẽ SAU Column outer nên z-order trên đầu (Compose
        // Box stack children theo thứ tự khai báo). Sibling của Column
        // outer trong Box parent → BoxScope cho .align().
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 188.dp)
                .size(104.dp)
                .clip(CircleShape)
                .background(Color.White)
                .padding(4.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val avatarUrl = UrlUtils.toFullUrl(data.user.avatar)
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF3E1F91)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        data.user.username.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    // Tăng 20% so với base: bg 48→58dp, icon 22→26dp, label 12→14sp,
    // padding 14/8 → 17/10
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier.size(58.dp).clip(CircleShape).background(Color(0xFFE7F3FF)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color(0xFF2196F3), modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 14.sp, color = Color(0xFF2C3E50), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PillButton(
    label: String,
    bg: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = textColor, strokeWidth = 2.dp)
        } else {
            Text(label, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun EditAliasDialog(
    initial: String,
    originalName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,  // bỏ cap ~280dp mặc định
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth(0.95f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Title
                Text(
                    "Tên gợi nhớ",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                )

                // Description
                Text(
                    "Đặt tên dễ nhớ cho $originalName. Để trống để xóa.",
                    fontSize = 13.sp,
                    color = Color(0xFF7F8C8D),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )

                Spacer(Modifier.height(12.dp))

                // Input — text căn giữa, chỉ có border bottom (Zalo style).
                // Dùng BasicTextField + Box decoration với HorizontalDivider
                // dưới để vẽ duy nhất 1 đường gạch chân.
                val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                var focused by remember { mutableStateOf(false) }
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= 50) text = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 16.sp,
                        color = Color(0xFF2C3E50),
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E88E5)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text.trim()) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { innerTextField ->
                        Column {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (text.isEmpty()) {
                                    Text(
                                        originalName,
                                        color = Color.Gray.copy(alpha = 0.5f),
                                        fontSize = 16.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                                innerTextField()
                            }
                            HorizontalDivider(
                                color = if (focused) Color(0xFF1E88E5) else Color(0xFFBDBDBD),
                                thickness = if (focused) 2.dp else 1.dp,
                            )
                        }
                    },
                )

                Spacer(Modifier.height(20.dp))

                HorizontalDivider(color = Color(0xFFEEEEEE))

                // 2 nút full-width 50/50, vertical divider giữa
                Row(modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                    ) { Text("Hủy", fontSize = 15.sp, color = Color(0xFF333333)) }
                    Box(
                        modifier = Modifier.width(1.dp).fillMaxHeight().background(Color(0xFFEEEEEE))
                    )
                    TextButton(
                        onClick = { onSave(text.trim()) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                    ) {
                        Text(
                            "Lưu",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E88E5),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WallStoryItem(story: Story) {
    val images: List<String> = remember(story.image_url) {
        if (story.image_url == null) emptyList()
        else try {
            val parsed = org.json.JSONArray(story.image_url)
            (0 until parsed.length()).mapNotNull { UrlUtils.toFullUrl(parsed.getString(it)) }
        } catch (_: Exception) {
            listOfNotNull(UrlUtils.toFullUrl(story.image_url))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                DateUtils.timeAgo(story.created_at ?: ""),
                fontSize = 12.sp, color = Color(0xFF7F8C8D),
            )
            if (!story.content.isNullOrBlank()) {
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(6.dp))
                Text(
                    story.content,
                    fontSize = 15.sp, color = Color(0xFF2C3E50), lineHeight = 22.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                if (images.size == 1) {
                    AsyncImage(
                        model = images[0], contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    val rows = images.chunked(2)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                row.forEach { url ->
                                    AsyncImage(
                                        model = url, contentDescription = null,
                                        modifier = Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if ((story.views_count ?: 0) > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, null, modifier = Modifier.size(16.dp), tint = Color(0xFF7F8C8D))
                    Spacer(Modifier.width(4.dp))
                    Text("${story.views_count} lượt xem", fontSize = 12.sp, color = Color(0xFF7F8C8D))
                }
            }
        }
    }
}
