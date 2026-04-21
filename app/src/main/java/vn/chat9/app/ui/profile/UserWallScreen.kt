package vn.chat9.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import vn.chat9.app.App
import vn.chat9.app.data.model.Story
import vn.chat9.app.data.model.WallData
import vn.chat9.app.util.DateUtils
import vn.chat9.app.util.UrlUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserWallScreen(
    userId: Int,
    onBack: () -> Unit,
    onChat: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var wallData by remember { mutableStateOf<WallData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var sendingRequest by remember { mutableStateOf(false) }
    var requestSent by remember { mutableStateOf(false) }

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

    // Extracts server's JSON `message` from a retrofit error body so the user
    // sees the real reason (e.g. "Hai bạn đã là bạn bè rồi" on 400) instead
    // of a generic "Lỗi kết nối".
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
                        android.widget.Toast.makeText(context, "Đã gửi lời mời kết bạn", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            res.message?.ifBlank { null } ?: "Không gửi được lời mời",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FriendReq", "Send failed", e)
                    android.widget.Toast.makeText(context, parseErrorMessage(e), android.widget.Toast.LENGTH_SHORT).show()
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
                        android.widget.Toast.makeText(
                            context,
                            if (accept) "Đã chấp nhận lời mời" else "Đã từ chối lời mời",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        // Refresh wall so UI reflects new relationship state
                        val fresh = container.api.getUserWall(userId)
                        if (fresh.success && fresh.data != null) wallData = fresh.data
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            res.message?.ifBlank { null } ?: "Không xử lý được lời mời",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FriendReq", "Respond failed", e)
                    android.widget.Toast.makeText(context, parseErrorMessage(e), android.widget.Toast.LENGTH_SHORT).show()
                }
                sendingRequest = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        wallData?.friend_alias ?: wallData?.user?.username ?: "",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF0F2F5)
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF3E1F91))
            }
        } else if (wallData != null) {
            val data = wallData!!
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                // Profile header
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(bottom = 16.dp)
                    ) {
                        // Cover photo
                        val coverUrl = UrlUtils.toFullUrl(data.user.cover_photo)
                        if (coverUrl != null) {
                            AsyncImage(
                                model = coverUrl, contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .background(
                                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                                            listOf(Color(0xFF3E1F91), Color(0xFF8E44AD), Color(0xFFFF6F61))
                                        )
                                    )
                            )
                        }

                        // Avatar + info
                        Column(
                            modifier = Modifier.fillMaxWidth().offset(y = (-40).dp).padding(horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val avatarUrl = UrlUtils.toFullUrl(data.user.avatar)
                            if (avatarUrl != null) {
                                AsyncImage(
                                    model = avatarUrl, contentDescription = null,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .padding(3.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        data.user.username.firstOrNull()?.uppercase() ?: "?",
                                        color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Name + pencil (friends can rename via alias API —
                            // non-friends see name only, pencil disabled)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    data.friend_alias ?: data.user.username,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2C3E50)
                                )
                                if (data.is_friend) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Edit,
                                        "Chỉnh sửa",
                                        tint = Color(0xFF7F8C8D),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            if (data.friend_alias != null) {
                                Text(data.user.username, fontSize = 14.sp, color = Color(0xFF7F8C8D))
                            }

                            if (!data.user.bio.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(data.user.bio, fontSize = 14.sp, color = Color(0xFF7F8C8D), maxLines = 3)
                            }

                            // Stranger / pending action block. Three sub-states
                            // drive the UI under the profile header:
                            //   • null / (any): Nhắn tin + add-friend circle
                            //   • pending_sent: Nhắn tin + disabled check
                            //   • pending_received: Chấp nhận + Từ chối pill
                            if (!data.is_self && !data.is_friend) {
                                Spacer(Modifier.height(14.dp))
                                val subtitle = when (data.friend_status) {
                                    "pending_sent" ->
                                        "Đã gửi lời mời. Bạn chưa thể xem nhật ký của ${data.friend_alias ?: data.user.username} khi chưa là bạn bè"
                                    "pending_received" ->
                                        "${data.friend_alias ?: data.user.username} đã gửi lời mời kết bạn cho bạn"
                                    else ->
                                        "Bạn chưa thể xem nhật ký của ${data.friend_alias ?: data.user.username} khi chưa là bạn bè"
                                }
                                Text(
                                    subtitle,
                                    fontSize = 14.sp,
                                    color = Color(0xFF7F8C8D),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                                Spacer(Modifier.height(14.dp))

                                if (data.friend_status == "pending_received") {
                                    // Accept + Reject
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                                .background(Color(0xFFE0E0E0))
                                                .clickable(enabled = !sendingRequest) { respondRequest(false) },
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Từ chối", color = Color(0xFF2C3E50), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                        }
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                                .background(Color(0xFF2196F3))
                                                .clickable(enabled = !sendingRequest) { respondRequest(true) },
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (sendingRequest) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    color = Color.White,
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Text("Chấp nhận", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                            }
                                        }
                                    }
                                } else {
                                    // Default / pending_sent — Nhắn tin + icon
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                                .background(Color(0xFFE7F3FF))
                                                .clickable { onChat(data.user.id) },
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Chat,
                                                null,
                                                tint = Color(0xFF2196F3),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Nhắn tin",
                                                color = Color(0xFF2196F3),
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 15.sp
                                            )
                                        }
                                        val alreadySent = data.friend_status == "pending_sent" || requestSent
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .border(1.dp, Color(0xFFE0E0E0), CircleShape)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                                .clickable(enabled = !alreadySent && !sendingRequest) {
                                                    sendFriendRequest()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            when {
                                                sendingRequest -> CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    color = Color(0xFF2196F3),
                                                    strokeWidth = 2.dp
                                                )
                                                alreadySent -> Icon(
                                                    Icons.Default.Check,
                                                    "Đã gửi",
                                                    tint = Color(0xFF4CAF50),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                else -> Icon(
                                                    Icons.Default.PersonAddAlt1,
                                                    "Kết bạn",
                                                    tint = Color(0xFF2196F3),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Stories section — only when it's the user themselves or a friend
                if (!data.is_self && !data.is_friend) {
                    // No stories for strangers; the action block above already
                    // explains the restriction.
                } else if (data.stories.isNotEmpty()) {
                    item {
                        Text(
                            "Nhật ký",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2C3E50)
                        )
                    }

                    items(data.stories, key = { it.id }) { story ->
                        WallStoryItem(story = story)
                    }
                } else {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Chưa có nhật ký nào", color = Color(0xFF7F8C8D), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WallStoryItem(story: Story) {
    val imageUrl = UrlUtils.toFullUrl(story.image_url)
    // Parse multi-image
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
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Time
            Text(
                DateUtils.timeAgo(story.created_at ?: ""),
                fontSize = 12.sp, color = Color(0xFF7F8C8D)
            )

            // Content (3-line clamp)
            if (!story.content.isNullOrBlank()) {
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(6.dp))
                Text(
                    story.content,
                    fontSize = 15.sp, color = Color(0xFF2C3E50), lineHeight = 22.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                )
            }

            // Images
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                if (images.size == 1) {
                    AsyncImage(
                        model = images[0], contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                } else {
                    // Simple grid for multiple images
                    val rows = images.chunked(2)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                row.forEach { url ->
                                    AsyncImage(
                                        model = url, contentDescription = null,
                                        modifier = Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Views
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
