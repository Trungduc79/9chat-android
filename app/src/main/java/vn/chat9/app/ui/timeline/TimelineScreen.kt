package vn.chat9.app.ui.timeline

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import vn.chat9.app.App
import vn.chat9.app.data.model.Story
import vn.chat9.app.util.DateUtils
import vn.chat9.app.util.UrlUtils

@Composable
fun TimelineScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val currentUserId = container.tokenManager.user?.id

    var stories by remember { mutableStateOf<List<Story>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    fun loadStories() {
        scope.launch {
            isLoading = true
            try {
                val res = container.api.getStories("feed")
                if (res.success && res.data != null) {
                    stories = res.data
                }
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadStories()
        // Listen for new stories from friends
        container.socket.on("new_story") {
            loadStories()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        // Search bar
        vn.chat9.app.ui.common.AppSearchBar(
            rightIconRes = vn.chat9.app.R.drawable.ic_add_photo,
            onRightIconClick = { showCreateDialog = true }
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF3E1F91), modifier = Modifier.size(32.dp))
            }
        } else if (stories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 80.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text("Chưa có nhật ký nào", color = Color(0xFF7F8C8D), fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(stories, key = { it.id }) { story ->
                    StoryItem(
                        story = story,
                        isOwn = story.user_id == currentUserId,
                        onView = {
                            if (story.viewed == 0 && story.user_id != currentUserId) {
                                scope.launch {
                                    try {
                                        container.api.viewStory(mapOf("story_id" to story.id))
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                try {
                                    val res = container.api.deleteStory(mapOf("story_id" to story.id))
                                    if (res.success) {
                                        stories = stories.filter { it.id != story.id }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateStoryDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { content, imageUris ->
                showCreateDialog = false
                scope.launch {
                    try {
                        val contentBody = content?.toRequestBody("text/plain".toMediaTypeOrNull())
                        if (imageUris.size <= 1) {
                            var imagePart: MultipartBody.Part? = null
                            if (imageUris.isNotEmpty()) {
                                val stream = context.contentResolver.openInputStream(imageUris[0])
                                val bytes = stream?.readBytes() ?: return@launch
                                stream.close()
                                val body = bytes.toRequestBody("image/*".toMediaTypeOrNull())
                                imagePart = MultipartBody.Part.createFormData("image", "story.jpg", body)
                            }
                            container.api.createStory(imagePart, contentBody)
                        } else {
                            val parts = imageUris.mapIndexed { i, uri ->
                                val stream = context.contentResolver.openInputStream(uri)
                                val bytes = stream?.readBytes() ?: return@launch
                                stream.close()
                                val body = bytes.toRequestBody("image/*".toMediaTypeOrNull())
                                MultipartBody.Part.createFormData("images[]", "story_$i.jpg", body)
                            }
                            container.api.createStoryMultiImage(parts, contentBody)
                        }
                        loadStories()
                    } catch (_: Exception) {}
                }
            }
        )
    }
}

@Composable
private fun StoryItem(
    story: Story,
    isOwn: Boolean,
    onView: () -> Unit,
    onDelete: () -> Unit
) {
    val avatarUrl = UrlUtils.toFullUrl(story.avatar)
    // Parse images — supports JSON array or single string
    val images: List<String> = remember(story.image_url) {
        if (story.image_url == null) emptyList()
        else try {
            val parsed = org.json.JSONArray(story.image_url)
            (0 until parsed.length()).mapNotNull { UrlUtils.toFullUrl(parsed.getString(it)) }
        } catch (_: Exception) {
            listOfNotNull(UrlUtils.toFullUrl(story.image_url))
        }
    }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Mark as viewed when rendered
    LaunchedEffect(story.id) { onView() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // User header
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            story.username?.firstOrNull()?.uppercase() ?: "?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        story.username ?: "",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2C3E50)
                    )
                    Text(
                        DateUtils.timeAgo(story.created_at ?: ""),
                        fontSize = 12.sp,
                        color = Color(0xFF7F8C8D)
                    )
                }

                if (isOwn) {
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, "Menu", tint = Color(0xFF7F8C8D), modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Xóa") },
                                onClick = { showMenu = false; showDeleteConfirm = true },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFFF3B30)) }
                            )
                        }
                    }
                }
            }

            // Content (3-line clamp, tap to expand)
            if (!story.content.isNullOrBlank()) {
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                Text(
                    story.content,
                    fontSize = 15.sp,
                    color = Color(0xFF2C3E50),
                    lineHeight = 22.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }

            // Images
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                StoryImageGrid(images)
            }

            // Footer: views count
            if (isOwn && (story.views_count ?: 0) > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, null, modifier = Modifier.size(16.dp), tint = Color(0xFF7F8C8D))
                    Spacer(Modifier.width(4.dp))
                    Text("${story.views_count} lượt xem", fontSize = 12.sp, color = Color(0xFF7F8C8D))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Xóa nhật ký") },
            text = { Text("Bạn có chắc muốn xóa nhật ký này?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Xóa", color = Color(0xFFFF3B30))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Không")
                }
            }
        )
    }
}

@Composable
private fun CreateStoryDialog(
    onDismiss: () -> Unit,
    onCreate: (String?, List<Uri>) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val remaining = 8 - imageUris.size
        imageUris = (imageUris + uris.take(remaining)).take(8)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tạo nhật ký", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text("Bạn đang nghĩ gì?") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    maxLines = 5
                )

                // Image previews
                if (imageUris.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        imageUris.forEachIndexed { index, uri ->
                            Box(modifier = Modifier.size(56.dp)) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = { imageUris = imageUris.filterIndexed { i, _ -> i != index } },
                                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { imagePicker.launch("image/*") },
                    shape = RoundedCornerShape(8.dp),
                    enabled = imageUris.size < 8
                ) {
                    Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (imageUris.isEmpty()) "Thêm ảnh (tối đa 8)"
                        else "${imageUris.size}/8 ảnh",
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (content.isNotBlank() || imageUris.isNotEmpty()) {
                        onCreate(content.ifBlank { null }, imageUris)
                    }
                },
                enabled = content.isNotBlank() || imageUris.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E1F91))
            ) {
                Text("Đăng")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}

@Composable
private fun StoryImageGrid(images: List<String>) {
    val shape = RoundedCornerShape(8.dp)
    when (images.size) {
        1 -> {
            AsyncImage(
                model = images[0], contentDescription = null,
                modifier = Modifier.fillMaxWidth().clip(shape),
                contentScale = ContentScale.FillWidth
            )
        }
        2 -> {
            Row(modifier = Modifier.fillMaxWidth().height(200.dp).clip(shape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                images.forEach { url ->
                    AsyncImage(model = url, contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight().clip(shape), contentScale = ContentScale.Crop)
                }
            }
        }
        3 -> {
            Row(modifier = Modifier.fillMaxWidth().height(280.dp).clip(shape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AsyncImage(model = images[0], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight().clip(shape), contentScale = ContentScale.Crop)
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AsyncImage(model = images[1], contentDescription = null, modifier = Modifier.weight(1f).fillMaxWidth().clip(shape), contentScale = ContentScale.Crop)
                    AsyncImage(model = images[2], contentDescription = null, modifier = Modifier.weight(1f).fillMaxWidth().clip(shape), contentScale = ContentScale.Crop)
                }
            }
        }
        4 -> {
            Column(modifier = Modifier.fillMaxWidth().clip(shape), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(modifier = Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    AsyncImage(model = images[0], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight().clip(shape), contentScale = ContentScale.Crop)
                    AsyncImage(model = images[1], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight().clip(shape), contentScale = ContentScale.Crop)
                }
                Row(modifier = Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    AsyncImage(model = images[2], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight().clip(shape), contentScale = ContentScale.Crop)
                    AsyncImage(model = images[3], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight().clip(shape), contentScale = ContentScale.Crop)
                }
            }
        }
        else -> {
            // 5+ images: main image on top row spanning 2 cols, rest below, last cell shows +N
            val showCount = minOf(images.size, 5)
            val remaining = images.size - 5
            Column(modifier = Modifier.fillMaxWidth().clip(shape), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(modifier = Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    AsyncImage(model = images[0], contentDescription = null, modifier = Modifier.weight(2f).fillMaxHeight().clip(shape), contentScale = ContentScale.Crop)
                    AsyncImage(model = images[1], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight().clip(shape), contentScale = ContentScale.Crop)
                }
                Row(modifier = Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (i in 2 until showCount) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().clip(shape)) {
                            AsyncImage(model = images[i], contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (i == 4 && remaining > 0) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color(0x80000000)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("+$remaining", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
