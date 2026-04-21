package vn.chat9.app.ui.share

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import vn.chat9.app.App
import vn.chat9.app.data.model.Room
import vn.chat9.app.navigation.SharePayload
import vn.chat9.app.util.UrlUtils

/**
 * External share target — visually identical to the in-app forward dialog
 * ([vn.chat9.app.ui.chat.ForwardDialog]). Same header, same search bar,
 * same "Gần đây" room list, same preview card, same bottom-row BasicTextField
 * + inline circular Send button in brand purple. The only thing that
 * changes is the preview content (incoming attachments vs. an existing
 * message) and the send pipeline (upload files first vs. forward message
 * content directly). Users see a single familiar UI for "send to someone
 * else" regardless of whether the source is an internal forward or an
 * ACTION_SEND intent from another app.
 */
@Composable
fun ShareComposeScreen(
    payloads: List<SharePayload>,
    onDismiss: () -> Unit,
    onOpenRoom: (Int) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var rooms by remember { mutableStateOf<List<Room>>(emptyList()) }
    var selectedRoomIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var note by remember {
        mutableStateOf(
            (payloads.firstOrNull { it is SharePayload.Text } as? SharePayload.Text)?.content
                ?: ""
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }

    val filePayloads = remember(payloads) { payloads.filterIsInstance<SharePayload.File>() }
    val hasText = payloads.any { it is SharePayload.Text }

    LaunchedEffect(Unit) {
        try {
            val res = container.api.getRooms()
            if (res.success && res.data != null) rooms = res.data
        } catch (_: Exception) {}
        isLoading = false
    }

    val filteredRooms = if (searchQuery.isBlank()) rooms else rooms.filter { room ->
        val name = if (room.type == "private") room.other_user?.displayName ?: "" else room.name ?: ""
        name.contains(searchQuery, ignoreCase = true)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
            containerColor = Color.White,
            topBar = {
                Surface(shadowElevation = 2.dp, color = Color.White) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Chia sẻ",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2C3E50),
                                )
                                Text(
                                    "Đã chọn: ${selectedRoomIds.size}",
                                    fontSize = 13.sp,
                                    color = Color(0xFF7F8C8D),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF0F2F5))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Search, null, tint = Color(0xFF7F8C8D), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF2C3E50)),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    Box {
                                        if (searchQuery.isEmpty()) Text("Tìm kiếm", color = Color(0xFF7F8C8D), fontSize = 15.sp)
                                        inner()
                                    }
                                },
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, "Clear", tint = Color(0xFF7F8C8D), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            },
            bottomBar = {
                Surface(shadowElevation = 8.dp, color = Color.White) {
                    Column {
                        // Preview card — mirrors ForwardDialog exactly:
                        // 12dp horizontal / 8dp vertical outer padding, 8dp
                        // inner, 8dp corner radius, 0xFFF5F5F5 background.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF5F5F5))
                                .padding(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SharePreviewThumbnail(filePayloads)
                                if (filePayloads.isNotEmpty()) Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        sharePreviewTitle(filePayloads, hasText, payloads),
                                        fontSize = 14.sp,
                                        color = Color(0xFF2C3E50),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        // Bottom row — same as ForwardDialog: note input +
                        // inline circular Send IconButton in brand purple.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                value = note,
                                onValueChange = { note = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF2C3E50)),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    Box {
                                        if (note.isEmpty()) {
                                            Text(
                                                if (filePayloads.isEmpty() && hasText) "Nội dung tin nhắn"
                                                else "Nhập tin nhắn",
                                                color = Color(0xFF7F8C8D),
                                                fontSize = 15.sp,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            val canSend = selectedRoomIds.isNotEmpty() && !isSending
                            IconButton(
                                onClick = {
                                    if (!canSend) return@IconButton
                                    scope.launch {
                                        isSending = true
                                        val picked = rooms.filter { it.id in selectedRoomIds }
                                        val noteText = note.trim()
                                        val success = sendShareToRooms(
                                            context = context,
                                            container = container,
                                            rooms = picked,
                                            payloads = payloads,
                                            note = noteText,
                                        )
                                        isSending = false
                                        if (success) {
                                            Toast.makeText(context, "Đã chia sẻ", Toast.LENGTH_SHORT).show()
                                            if (picked.size == 1) onOpenRoom(picked.first().id)
                                            else onDismiss()
                                        } else {
                                            Toast.makeText(context, "Một số mục chia sẻ không thành công", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = canSend,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (canSend) Color(0xFF3E1F91) else Color(0xFFD0D0D0)),
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        "Send",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            if (isLoading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF3E1F91), modifier = Modifier.size(32.dp))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    item {
                        Text(
                            "Gần đây",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF7F8C8D),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    items(filteredRooms, key = { it.id }) { room ->
                        val roomName = if (room.type == "private") room.other_user?.displayName ?: "" else room.name ?: ""
                        val avatarUrl = if (room.type == "private") UrlUtils.toFullUrl(room.other_user?.avatar) else null
                        val isSelected = room.id in selectedRoomIds
                        ShareRoomRow(
                            name = roomName,
                            avatarUrl = avatarUrl,
                            selected = isSelected,
                            onToggle = {
                                selectedRoomIds = if (isSelected) selectedRoomIds - room.id
                                else selectedRoomIds + room.id
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareRoomRow(name: String, avatarUrl: String?, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar — 48dp brand-purple fallback with uppercase initial.
        // Matches ForwardDialog exactly.
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                modifier = Modifier.size(48.dp).clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Text(
            name,
            fontSize = 16.sp,
            color = Color(0xFF2C3E50),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(12.dp))

        // Radio-style selection circle on the right (same as ForwardDialog):
        //  - Selected: 24dp purple-filled circle with white check.
        //  - Unselected: 24dp white circle with 2dp #E0E6ED border.
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(Color(0xFF3E1F91))
                    else Modifier.background(Color.White).border(2.dp, Color(0xFFE0E6ED), CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Default.Check, "Đã chọn", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Thumbnail for the preview card — image preview for image payloads, icon
 * for other file kinds, hidden when only text is being shared (the text
 * already shows in the title row).
 */
@Composable
private fun SharePreviewThumbnail(files: List<SharePayload.File>) {
    if (files.isEmpty()) return
    val first = files.first()
    val isImage = first.mime?.startsWith("image/") == true
    Box(
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE0E0E0)),
        contentAlignment = Alignment.Center,
    ) {
        if (isImage) {
            AsyncImage(
                model = first.uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Default.Description, null, tint = Color(0xFF7F8C8D))
        }
    }
}

/**
 * One-line summary shown next to the preview thumbnail — matches
 * ForwardDialog's labelling style (`"Hình ảnh"` / filename / content).
 */
private fun sharePreviewTitle(
    files: List<SharePayload.File>,
    hasText: Boolean,
    payloads: List<SharePayload>,
): String = when {
    files.size == 1 -> {
        val f = files.first()
        when {
            f.mime?.startsWith("image/") == true -> "Hình ảnh"
            f.mime?.startsWith("video/") == true -> "Video"
            f.mime?.startsWith("audio/") == true -> "Âm thanh"
            !f.displayName.isNullOrBlank() -> f.displayName
            else -> "Tệp đính kèm"
        }
    }
    files.size > 1 -> "${files.size} tệp đính kèm"
    hasText -> (payloads.firstOrNull { it is SharePayload.Text } as? SharePayload.Text)?.content ?: ""
    else -> ""
}

// ─────────────────────────────────────────────────────────────────
//  SEND PIPELINE (unchanged)
// ─────────────────────────────────────────────────────────────────
private suspend fun sendShareToRooms(
    context: android.content.Context,
    container: vn.chat9.app.di.AppContainer,
    rooms: List<Room>,
    payloads: List<SharePayload>,
    note: String,
): Boolean {
    if (rooms.isEmpty()) return false
    val files = payloads.filterIsInstance<SharePayload.File>()
    val texts = payloads.filterIsInstance<SharePayload.Text>()
    val plainText = note.takeIf { it.isNotBlank() } ?: texts.firstOrNull()?.content

    var allOk = true
    rooms.forEach { room ->
        val hasFiles = files.isNotEmpty()
        val shouldSendText = plainText != null && plainText.isNotBlank() &&
            (hasFiles || texts.isNotEmpty())

        if (shouldSendText && hasFiles) {
            try {
                container.socket.sendMessage(roomId = room.id, type = "text", content = plainText!!)
            } catch (_: Exception) { allOk = false }
        }
        files.forEach { f ->
            if (!uploadAndSendFile(context, container, room.id, f)) allOk = false
        }
        if (!hasFiles && plainText != null && plainText.isNotBlank()) {
            try {
                container.socket.sendMessage(roomId = room.id, type = "text", content = plainText)
            } catch (_: Exception) { allOk = false }
        }
    }
    return allOk
}

private suspend fun uploadAndSendFile(
    context: android.content.Context,
    container: vn.chat9.app.di.AppContainer,
    roomId: Int,
    payload: SharePayload.File,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val uri = Uri.parse(payload.uri)
        val resolver = context.contentResolver
        val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        if (size > 50L * 1024 * 1024) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Tệp quá lớn (tối đa 50MB)", Toast.LENGTH_SHORT).show()
            }
            return@withContext false
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext false
        val mime = payload.mime ?: resolver.getType(uri) ?: "application/octet-stream"
        val displayName = payload.displayName
            ?: resolveDisplayName(resolver, uri)
            ?: defaultNameForMime(mime)

        val requestBody = bytes.toRequestBody(mime.toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", displayName, requestBody)
        val roomIdBody = roomId.toString().toRequestBody("text/plain".toMediaTypeOrNull())

        val res = container.api.uploadFile(filePart, roomIdBody)
        if (!res.success || res.data == null) return@withContext false

        val msgType = when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            else -> "file"
        }
        container.socket.sendMessage(
            roomId = roomId,
            type = msgType,
            content = "",
            fileUrl = res.data.file_url,
            fileName = res.data.file_name,
            fileSize = res.data.file_size,
        )
        true
    } catch (e: Exception) {
        android.util.Log.e("Share", "uploadAndSendFile failed", e)
        false
    }
}

private fun resolveDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
    return try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).takeIf { !it.isNullOrBlank() } else null
        }
    } catch (_: Exception) { null }
}

private fun defaultNameForMime(mime: String): String {
    val ts = System.currentTimeMillis()
    val ext = when {
        mime.startsWith("image/jpeg") -> "jpg"
        mime.startsWith("image/png") -> "png"
        mime.startsWith("image/gif") -> "gif"
        mime.startsWith("image/webp") -> "webp"
        mime.startsWith("image/") -> "img"
        mime.startsWith("video/mp4") -> "mp4"
        mime.startsWith("video/") -> "mp4"
        mime.startsWith("audio/mpeg") -> "mp3"
        mime.startsWith("audio/") -> "m4a"
        mime == "application/pdf" -> "pdf"
        else -> "bin"
    }
    return "share_$ts.$ext"
}
