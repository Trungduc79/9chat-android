package vn.chat9.app.ui.chat

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import vn.chat9.app.data.model.Room
import vn.chat9.app.data.model.RoomMember
import vn.chat9.app.ui.common.GroupAvatar
import vn.chat9.app.ui.common.GroupAvatarMember
import vn.chat9.app.util.UrlUtils

/**
 * Onboarding card hiện cho admin của group mới tạo — trông như Zalo:
 *
 *   ┌──────────────────────────┐
 *   │       [📷 avatar]        │   ← tap → image picker → upload
 *   │   Đặt tên nhóm  >        │   ← tap → rename dialog
 *   │   Bạn vừa tạo nhóm       │
 *   │  [👥][👥][👥]  [+]       │   ← thêm thành viên
 *   │   👋 Vẫy tay chào         │   ← gửi "👋" vào chat
 *   │   Xem mã QR tham gia nhóm │   ← TODO: chưa có group invite link
 *   └──────────────────────────┘
 *
 * Trigger: render trong ChatScreen khi `room.isGroup && room.isAdmin`. Card
 * tự ẩn khi:
 *   - Admin đặt tên nhóm thủ công (server clear name_is_default → recompose)
 *   - Admin tap nút "x" dismiss (lưu local prefs theo room id)
 *
 * Tất cả action đụng REST đều best-effort; lỗi mạng → giữ card, anh thử lại.
 *
 * @param onChanged callback khi room state đổi (avatar/name/members) để
 *                  ChatScreen reload room detail và update header.
 */
@Composable
fun GroupOnboardingCard(
    room: Room,
    currentUserId: Int,
    onSendMessage: (text: String) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var members by remember(room.id) { mutableStateOf<List<RoomMember>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var renameOpen by remember { mutableStateOf(false) }
    var addMemberOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploadGroupAvatar(
                container = container,
                context = context,
                roomId = room.id,
                uri = uri,
                onError = { error = it },
                onDone = onChanged,
            )
        }
    }

    LaunchedEffect(room.id) {
        try {
            val res = container.api.getRoomMembers(room.id)
            if (res.success && res.data != null) members = res.data
        } catch (_: Exception) {}
        loading = false
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            // Dismiss button góc phải trên
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
            ) {
                Icon(Icons.Default.Close, "Đóng", tint = Color.Gray, modifier = Modifier.size(18.dp))
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Avatar nhóm — nếu có room.avatar thì hiện thẳng, ngược lại
                // tự ghép mosaic 3 ảnh đầu (Zalo style). Tap chọn ảnh tuỳ chỉnh.
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clickable { avatarPicker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    if (!room.avatar.isNullOrBlank() || members.isNotEmpty()) {
                        GroupAvatar(
                            avatarUrl = room.avatar,
                            members = members.map {
                                GroupAvatarMember(
                                    avatarUrl = it.avatar,
                                    initial = it.username.firstOrNull()?.toString() ?: "?",
                                )
                            },
                            memberCount = room.member_count ?: members.size,
                            size = 78.dp,
                        )
                    } else {
                        // Chưa có avatar + chưa load member → placeholder
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF666666).copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.PhotoCamera, "Chọn ảnh đại diện",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Tên nhóm — tap mở rename dialog
                Row(
                    modifier = Modifier.clickable { renameOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (room.nameIsDefault) "Đặt tên nhóm" else (room.name ?: "Nhóm chat"),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (room.nameIsDefault) Color(0xFF1E88E5) else Color.Black,
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        "edit",
                        tint = if (room.nameIsDefault) Color(0xFF1E88E5) else Color.Gray,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    "Bạn vừa tạo nhóm",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(Modifier.height(16.dp))

                // Hàng avatar thành viên + nút thêm
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    members.take(6).forEach { m ->
                        val url = UrlUtils.toFullUrl(m.avatar)
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (url != null) {
                                AsyncImage(
                                    model = url, contentDescription = m.username,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text(
                                    m.username.firstOrNull()?.uppercase() ?: "?",
                                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    // Nút thêm thành viên
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE3F2FD))
                            .clickable { addMemberOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PersonAddAlt1, "Thêm",
                            tint = Color(0xFF1E88E5),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Nút Vẫy tay chào — gửi "👋" vào chat
                Surface(
                    modifier = Modifier.clickable {
                        onSendMessage("👋")
                        // Sau wave thì onboarding card không tự ẩn — admin tự dismiss
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFF5F5F5),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("👋", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Vẫy tay chào", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // QR mời — TODO (chưa có feature group invite link)
                TextButton(
                    onClick = { /* TODO: group invite QR */ },
                    enabled = false,
                ) {
                    Text(
                        "Xem mã QR tham gia nhóm",
                        color = Color.Gray,
                        fontSize = 13.sp,
                    )
                }

                error?.let {
                    Text(it, color = Color.Red, fontSize = 12.sp,
                         modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    if (renameOpen) {
        RenameGroupDialog(
            initial = if (room.nameIsDefault) "" else (room.name ?: ""),
            onDismiss = { renameOpen = false },
            onConfirm = { newName ->
                renameOpen = false
                scope.launch {
                    try {
                        val res = container.api.updateRoomSettings(
                            mapOf("room_id" to room.id, "name" to newName)
                        )
                        if (res.success) onChanged() else error = res.message ?: "Lỗi đổi tên"
                    } catch (e: Exception) {
                        error = "Lỗi: ${e.message}"
                    }
                }
            },
        )
    }

    if (addMemberOpen) {
        AddMemberDialog(
            existingIds = members.map { it.id }.toSet() + currentUserId,
            onDismiss = { addMemberOpen = false },
            onPick = { friendIds ->
                addMemberOpen = false
                scope.launch {
                    try {
                        // members.php nhận từng user_id, không có batch — loop.
                        var added = 0
                        for (fid in friendIds) {
                            val res = container.api.addRoomMember(
                                mapOf("room_id" to room.id, "action" to "add", "user_id" to fid)
                            )
                            if (res.success) added++
                        }
                        if (added > 0) onChanged()
                    } catch (e: Exception) {
                        error = "Lỗi: ${e.message}"
                    }
                }
            }
        )
    }
}

@Composable
private fun RenameGroupDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đặt tên nhóm") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Tên nhóm") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim().isNotEmpty(),
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}

@Composable
private fun AddMemberDialog(
    existingIds: Set<Int>,
    onDismiss: () -> Unit,
    onPick: (Set<Int>) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val res = container.api.getFriends("friends")
                if (res.success && res.data != null) {
                    friends = res.data.filter { it.id !in existingIds }
                }
            } catch (_: Exception) {}
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm thành viên") },
        text = {
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (friends.isEmpty()) {
                Text("Không còn bạn nào để thêm vào nhóm.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(friends, key = { it.id }) { f ->
                        val checked = f.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - f.id else selected + f.id
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val url = UrlUtils.toFullUrl(f.avatar)
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (url != null) {
                                    AsyncImage(
                                        model = url, contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Text(
                                        f.username.firstOrNull()?.uppercase() ?: "?",
                                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(f.alias ?: f.username, modifier = Modifier.weight(1f))
                            if (checked) Icon(Icons.Default.Check, null, tint = Color(0xFF1E88E5))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(selected) },
                enabled = selected.isNotEmpty(),
            ) { Text("Thêm (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}

private suspend fun uploadGroupAvatar(
    container: vn.chat9.app.di.AppContainer,
    context: android.content.Context,
    roomId: Int,
    uri: Uri,
    onError: (String) -> Unit,
    onDone: () -> Unit,
) {
    try {
        val resolver: ContentResolver = context.contentResolver
        val bytes = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", "avatar.jpg", body)
        val roomIdBody = roomId.toString().toRequestBody("text/plain".toMediaTypeOrNull())

        val uploadRes = withContext(Dispatchers.IO) {
            container.api.uploadFile(filePart, roomIdBody)
        }
        val fileUrl = uploadRes.data?.file_url
        if (uploadRes.success && fileUrl != null) {
            container.api.updateRoomSettings(mapOf("room_id" to roomId, "avatar" to fileUrl))
            onDone()
        } else {
            onError(uploadRes.message ?: "Upload avatar thất bại")
        }
    } catch (e: Exception) {
        onError("Lỗi: ${e.message}")
    }
}
