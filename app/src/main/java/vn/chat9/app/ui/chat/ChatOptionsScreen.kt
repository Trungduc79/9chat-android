package vn.chat9.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import vn.chat9.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import vn.chat9.app.App
import vn.chat9.app.data.model.Room
import vn.chat9.app.util.UrlUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatOptionsScreen(
    room: Room,
    onBack: () -> Unit,
    onSearchMessages: () -> Unit = {},
    onUserWall: (Int) -> Unit = {},
    onViewMedia: () -> Unit = {}
) {
    val otherUser = room.other_user
    val displayName = otherUser?.displayName ?: room.name ?: "Chat"
    val avatarUrl = if (room.type == "private") UrlUtils.toFullUrl(otherUser?.avatar) else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tùy chọn", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3E1F91),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFFF0F2F5)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Profile header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Avatar
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl, contentDescription = null,
                        modifier = Modifier.size(80.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(displayName.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(displayName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))

                Spacer(Modifier.height(20.dp))

                // Quick action buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickAction(Icons.Default.Search, "Tìm\ntin nhắn", onClick = onSearchMessages)
                    QuickAction(Icons.Default.Person, "Trang\ncá nhân", onClick = {
                        if (room.type == "private" && otherUser != null) onUserWall(otherUser.id)
                    })
                    QuickAction(Icons.Default.Wallpaper, "Đổi\nhình nền", onClick = { /* TODO */ })
                    QuickAction(Icons.Default.NotificationsOff, "Tắt\nthông báo", onClick = { /* TODO */ })
                }
            }

            Spacer(Modifier.height(8.dp))

            // Options list
            Surface(color = Color.White) {
                Column {
                    OptionItemPainter(painterResource(R.drawable.ic_edit_alias), "Đổi tên gợi nhớ") { /* TODO: edit alias */ }
                    HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(start = 56.dp))
                    OptionItemPainter(painterResource(R.drawable.ic_best_friend), "Đánh dấu bạn thân") { /* TODO */ }
                    HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(start = 56.dp))
                    OptionItem(Icons.Default.Schedule, "Nhật ký chung") {
                        if (room.type == "private" && otherUser != null) onUserWall(otherUser.id)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Media section
            Surface(color = Color.White) {
                Column {
                    OptionItemPainter(painterResource(R.drawable.ic_media_search), "Ảnh, file, link") { onViewMedia() }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Danger zone
            Surface(color = Color.White) {
                Column {
                    OptionItem(Icons.Default.DeleteOutline, "Xóa lịch sử trò chuyện", color = Color(0xFFFF3B30)) { /* TODO */ }
                    HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(start = 56.dp))
                    OptionItem(Icons.Default.Block, "Chặn", color = Color(0xFFFF3B30)) { /* TODO */ }
                    HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(start = 56.dp))
                    OptionItem(Icons.Default.Report, "Báo xấu", color = Color(0xFFFF3B30)) { /* TODO */ }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFFF0F2F5)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color(0xFF2C3E50), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Color(0xFF2C3E50), textAlign = TextAlign.Center, lineHeight = 15.sp)
    }
}

@Composable
private fun OptionItem(
    icon: ImageVector,
    label: String,
    color: Color = Color(0xFF2C3E50),
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 15.sp, color = color)
    }
}

@Composable
private fun OptionItemPainter(
    icon: Painter,
    label: String,
    color: Color = Color(0xFF2C3E50),
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 15.sp, color = color)
    }
}
