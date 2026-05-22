package vn.chat9.app.ui.rooms

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import vn.chat9.app.App
import vn.chat9.app.data.model.Friend
import vn.chat9.app.util.UrlUtils

/**
 * Tạo nhóm chat — Zalo-like UX.
 *
 * Flow tổng:
 *   1. User chọn ảnh đại diện (camera icon) — optional
 *   2. Đặt tên nhóm + emoji (text field hỗ trợ emoji từ system keyboard +
 *      nút smiley để insert nhanh từ inline picker)
 *   3. Search + multi-select bạn bè
 *   4. Bấm "Tạo nhóm (N)":
 *        a. POST /rooms/create.php → roomId (caller auto-admin)
 *        b. Nếu có avatar URI: POST /files/upload.php (multipart, room_id)
 *           → file_url → PUT /rooms/settings.php { avatar: file_url }
 *        c. navigateTo(Chat(roomId))
 *
 * Window insets: bottomBar được wrap bằng `navigationBarsPadding()` để nút
 * không bị che bởi thanh điều hướng Android (edge-to-edge đã bật trong
 * MainActivity, từng screen tự pad).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onCreated: (roomId: Int) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var nameField by remember { mutableStateOf(TextFieldValue("")) }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }
    var search by remember { mutableStateOf("") }
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var emojiOpen by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) avatarUri = uri }

    LaunchedEffect(Unit) {
        try {
            val res = container.api.getFriends("friends")
            if (res.success && res.data != null) friends = res.data
        } catch (e: Exception) {
            error = "Không tải được danh sách bạn bè: ${e.message}"
        }
        loading = false
    }

    val filtered = remember(friends, search) {
        if (search.isBlank()) friends
        else friends.filter {
            it.username.contains(search, ignoreCase = true)
                || (it.alias?.contains(search, ignoreCase = true) == true)
        }
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nhóm mới", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        if (selectedIds.isNotEmpty()) {
                            Text("Đã chọn: ${selectedIds.size}", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                    }
                }
            )
        },
        bottomBar = {
            // imePadding đẩy thanh thành viên đã chọn lên trên bàn phím khi user
            // gõ tên nhóm. navigationBarsPadding cho 3-button nav khi không có IME.
            Surface(
                shadowElevation = 8.dp,
                modifier = Modifier.imePadding().navigationBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    // Hàng avatar người đã chọn (chips tròn)
                    if (selectedIds.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            friends.filter { it.id in selectedIds }.forEach { f ->
                                Box(
                                    modifier = Modifier.padding(end = 8.dp),
                                    contentAlignment = Alignment.TopEnd,
                                ) {
                                    FriendAvatar(friend = f, size = 44.dp)
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF666666))
                                            .clickable { selectedIds = selectedIds - f.id },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Close, "Bỏ chọn",
                                            tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            // Nút "Tạo nhóm" hình tròn — sát phải, Zalo style
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (creating) Color.Gray else Color(0xFF1E88E5))
                                    .clickable(enabled = !creating) {
                                        startCreate(
                                            scope = scope,
                                            container = container,
                                            context = context,
                                            nameRaw = nameField.text,
                                            friends = friends,
                                            selectedIds = selectedIds,
                                            avatarUri = avatarUri,
                                            onError = { error = it },
                                            onCreating = { creating = it },
                                            onCreated = onCreated,
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (creating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White,
                                    )
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Tạo nhóm",
                                        tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    } else {
                        Button(
                            enabled = false,
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Chọn ít nhất 1 thành viên") }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // Hàng avatar nhóm + tên nhóm
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Camera icon — chọn ảnh đại diện. Bg hình tròn opacity 50%.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (avatarUri != null) Color.Transparent
                            else Color(0xFF666666).copy(alpha = 0.5f)
                        )
                        .clickable { avatarPicker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarUri != null) {
                        AsyncImage(
                            model = avatarUri,
                            contentDescription = "Avatar nhóm",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Default.PhotoCamera,
                            "Chọn ảnh đại diện",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 18.sp, color = Color.Black),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (nameField.text.isEmpty()) {
                            Text("Đặt tên nhóm", color = Color.Gray, fontSize = 18.sp)
                        }
                        inner()
                    }
                )
                IconButton(onClick = { emojiOpen = !emojiOpen }) {
                    Icon(
                        if (emojiOpen) Icons.Default.Close else Icons.Default.EmojiEmotions,
                        "Emoji",
                        tint = if (emojiOpen) Color(0xFFE53935) else Color(0xFFFFB300),
                    )
                }
            }
            HorizontalDivider(color = Color(0xFFEEEEEE))

            // Inline emoji picker — chỉ hiện khi user tap nút smiley
            if (emojiOpen) {
                EmojiQuickPicker(
                    onPick = { emoji ->
                        val cur = nameField
                        val sel = cur.selection
                        val newText = cur.text.substring(0, sel.start) + emoji + cur.text.substring(sel.end)
                        nameField = TextFieldValue(
                            text = newText,
                            selection = androidx.compose.ui.text.TextRange(sel.start + emoji.length)
                        )
                    }
                )
                HorizontalDivider(color = Color(0xFFEEEEEE))
            }

            // Search box
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Tìm tên hoặc số điện thoại") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )

            error?.let {
                Text(it, color = Color.Red, modifier = Modifier.padding(horizontal = 12.dp))
            }

            // Friend list
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (search.isBlank()) "Chưa có bạn bè"
                        else "Không tìm thấy bạn nào khớp \"$search\"",
                        color = Color.Gray,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { friend ->
                        val checked = friend.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (checked) selectedIds - friend.id
                                                  else selectedIds + friend.id
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FriendAvatar(friend = friend, size = 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    friend.alias ?: friend.username,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                friend.last_seen?.let {
                                    Text(it, fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                            // Checkbox tròn — Zalo style
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (checked) Color(0xFF1E88E5) else Color.Transparent)
                                    .border(
                                        width = if (checked) 0.dp else 2.dp,
                                        color = if (checked) Color.Transparent else Color.LightGray,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (checked) {
                                    Icon(Icons.Default.Check, "selected",
                                         tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0xFFEEEEEE))
                    }
                }
            }
        }
    }
}

/**
 * Pick một emoji từ bộ phổ biến — không cần keyboard system. Cursor được
 * set lại ngay sau khi insert để dễ gõ tiếp.
 */
@Composable
private fun EmojiQuickPicker(onPick: (String) -> Unit) {
    val emojis = remember {
        listOf(
            "😀","😁","😂","🤣","😊","😍","🥰","😎","🤔","😅",
            "😴","🙃","😘","🤗","🤩","😇","🥳","😜","🤤","🤯",
            "👍","👏","🙏","💪","✨","🔥","⭐","❤️","💕","🎉",
            "🎊","🎁","🎂","🌹","🌟","💯","✅","📌","📢","☕",
            "🍀","🌈","🍰","🍔","🚀","💼","📦","🛍️","🏠","💰",
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .background(Color(0xFFFAFAFA))
            .padding(8.dp),
    ) {
        gridItems(emojis) { e ->
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(36.dp)
                    .clickable { onPick(e) },
                contentAlignment = Alignment.Center,
            ) {
                Text(e, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun FriendAvatar(friend: Friend, size: androidx.compose.ui.unit.Dp) {
    val url = UrlUtils.toFullUrl(friend.avatar)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = friend.username,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(Color(0xFF3E1F91)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                friend.username.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Tạo room → upload avatar (nếu có) → set avatar → navigate.
 *
 * Tách ra hàm rời để bottomBar chip-style và button-fallback gọi cùng logic.
 * Avatar upload dùng files/upload.php (đã có) → trả file_url, sau đó PUT
 * rooms/settings.php { avatar: file_url } để cập nhật. Caller là creator
 * = admin của room nên qua Phase 2.3 gate (room.rename + isAdmin).
 */
private fun startCreate(
    scope: kotlinx.coroutines.CoroutineScope,
    container: vn.chat9.app.di.AppContainer,
    context: android.content.Context,
    nameRaw: String,
    friends: List<Friend>,
    selectedIds: Set<Int>,
    avatarUri: Uri?,
    onError: (String) -> Unit,
    onCreating: (Boolean) -> Unit,
    onCreated: (Int) -> Unit,
) {
    if (selectedIds.isEmpty()) return
    val name = nameRaw.trim().ifBlank {
        friends.filter { it.id in selectedIds }
            .joinToString(", ") { it.alias ?: it.username }
            .take(80)
    }
    scope.launch {
        onCreating(true)
        try {
            // 1. Tạo room. Nếu user để tên trống → ta auto-sinh từ alias/username
            // và báo server (name_is_default=true) để banner onboarding hiện cho
            // admin đến khi anh đặt tên thủ công.
            val nameWasBlank = nameRaw.trim().isBlank()
            val createRes = container.api.createRoom(
                mapOf(
                    "type" to "group",
                    "name" to name,
                    "name_is_default" to nameWasBlank,
                    "member_ids" to selectedIds.toList(),
                )
            )
            if (!createRes.success || createRes.data == null) {
                onError(createRes.message ?: "Tạo nhóm thất bại")
                return@launch
            }
            val roomId = createRes.data.id

            // 2. Upload avatar (best-effort — lỗi cũng vẫn vào room)
            if (avatarUri != null) {
                try {
                    val resolver: ContentResolver = context.contentResolver
                    val bytes = withContext(Dispatchers.IO) {
                        resolver.openInputStream(avatarUri)?.use { it.readBytes() }
                    } ?: ByteArray(0)
                    if (bytes.isNotEmpty()) {
                        val mime = resolver.getType(avatarUri) ?: "image/jpeg"
                        val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
                        val filePart = MultipartBody.Part.createFormData("file", "avatar.jpg", body)
                        val roomIdBody = roomId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                        // files/upload.php expects @Part file + @Part room_id
                        val uploadRes = withContext(Dispatchers.IO) {
                            container.api.uploadFile(filePart, roomIdBody)
                        }
                        val fileUrl = uploadRes.data?.file_url
                        if (uploadRes.success && fileUrl != null) {
                            container.api.updateRoomSettings(
                                mapOf("room_id" to roomId, "avatar" to fileUrl)
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CreateGroup", "Avatar upload failed (room đã tạo)", e)
                }
            }

            onCreated(roomId)
        } catch (e: Exception) {
            onError("Lỗi: ${e.message}")
        }
        onCreating(false)
    }
}
