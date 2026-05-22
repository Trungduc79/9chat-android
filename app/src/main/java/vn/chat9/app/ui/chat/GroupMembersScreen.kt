package vn.chat9.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import vn.chat9.app.App
import vn.chat9.app.data.model.RoomMember
import vn.chat9.app.util.UrlUtils

/**
 * Xem thành viên nhóm — list đơn giản với avatar, tên, badge "Trưởng nhóm"
 * cho admin. Tap user → mở Wall (profile).
 *
 * Endpoint: GET /rooms/members.php?room_id=
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMembersScreen(
    roomId: Int,
    onBack: () -> Unit,
    onUserWall: (Int) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container

    var members by remember(roomId) { mutableStateOf<List<RoomMember>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    // Lookup alias cho member là bạn bè (FriendAliasStore global cache).
    val aliasMap by container.friendAliases.state.collectAsState()

    LaunchedEffect(roomId) {
        try {
            val res = container.api.getRoomMembers(roomId)
            if (res.success && res.data != null) members = res.data
        } catch (_: Exception) {}
        loading = false
    }

    fun displayNameOf(m: RoomMember): String = aliasMap[m.id] ?: m.username

    val filtered = remember(members, search, aliasMap) {
        if (search.isBlank()) members
        else members.filter {
            it.username.contains(search, ignoreCase = true) ||
                (aliasMap[it.id]?.contains(search, ignoreCase = true) == true)
        }
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Thành viên (${members.size})",
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3E1F91),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Tìm thành viên") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (search.isBlank()) "Nhóm chưa có thành viên"
                        else "Không tìm thấy",
                        color = Color.Gray,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { m ->
                        MemberRow(
                            member = m,
                            displayName = displayNameOf(m),
                            onClick = { onUserWall(m.id) },
                        )
                        HorizontalDivider(color = Color(0xFFEEEEEE))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: RoomMember, displayName: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val url = UrlUtils.toFullUrl(member.avatar)
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) {
                AsyncImage(
                    model = url, contentDescription = displayName,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    displayName.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Alias > username; nếu khác username thì hiện nguyên tên 9chat
            // dưới dòng phụ để admin nhận diện
            Text(displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (displayName != member.username) {
                Text("(${member.username})", fontSize = 12.sp, color = Color(0xFF7F8C8D))
            } else if (member.is_online == true) {
                Text("Đang hoạt động", fontSize = 12.sp, color = Color(0xFF00C853))
            }
        }
        if (member.role == "admin") {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFFF3E0))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Trưởng nhóm", fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Medium)
            }
        }
    }
}
