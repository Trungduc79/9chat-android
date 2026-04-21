package vn.chat9.app.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ripple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import vn.chat9.app.R
import vn.chat9.app.App
import vn.chat9.app.ui.contacts.ContactsScreen
import vn.chat9.app.ui.profile.AccountScreen
import vn.chat9.app.ui.rooms.RoomListScreen
import vn.chat9.app.ui.timeline.TimelineScreen

data class BottomNavItem(val label: String, val iconRes: Int)

@Composable
fun HomeScreen(
    onLogout: () -> Unit,
    onRoomClick: (vn.chat9.app.data.model.Room) -> Unit = {},
    onRoomCall: (room: vn.chat9.app.data.model.Room, isVideo: Boolean) -> Unit = { _, _ -> },
    onOpenWall: (userId: Int) -> Unit = {},
    onAddFriend: () -> Unit = {},
    onEditProfile: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    selectedTab: Int = 0,
    onTabChange: (Int) -> Unit = {},
    roomRefreshKey: Int = 0
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    // Total unread count
    var totalUnread by remember { mutableIntStateOf(0) }

    // Reload from API on roomRefreshKey (after returning from chat) and selectedTab changes
    LaunchedEffect(roomRefreshKey, selectedTab) {
        try {
            val res = container.api.getRooms()
            if (res.success && res.data != null) {
                totalUnread = res.data.sumOf { it.unread_count ?: 0 }
            }
        } catch (_: Exception) {}
    }

    // Real-time update when new message arrives (any tab)
    DisposableEffect(Unit) {
        val listener: (Array<Any>) -> Unit = {
            totalUnread++
        }
        container.socket.on("new_message_notification", listener)
        onDispose {
            container.socket.off("new_message_notification", listener)
        }
    }

    val tabs = listOf(
        BottomNavItem("Tin nhắn", R.drawable.ic_tab_messages),
        BottomNavItem("Danh bạ", R.drawable.ic_tab_contacts),
        BottomNavItem("Khám phá", R.drawable.ic_tab_discover),
        BottomNavItem("Nhật ký", R.drawable.ic_tab_stories),
        BottomNavItem("Cá nhân", R.drawable.ic_tab_personal),
    )

    Scaffold(
        bottomBar = {
            // navigationBarsPadding() keeps the tab bar above the device's gesture
            // / 3-button nav bar in edge-to-edge mode (MainActivity runs decorFits=false).
            Surface(
                shadowElevation = 4.dp,
                color = Color.White,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, item ->
                        val isSelected = selectedTab == index
                        val baseSize = if (index == 2) 32f else 28f
                        val iconSize = if (isSelected) (baseSize * 1.05f).dp else baseSize.dp
                        val iconColor = if (isSelected) Color(0xFF3E1F91) else Color(0xFF7F8C8D)

                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = false, radius = 56.dp, color = Color(0x403E1F91))
                                ) {
                                    onTabChange(index)
                                }
                                .padding(top = 4.dp, bottom = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (index == 0 && totalUnread > 0) {
                                BadgedBox(badge = {
                                    Badge(containerColor = Color(0xFFFF6F61)) {
                                        Text(
                                            if (totalUnread > 5) "5+" else "$totalUnread",
                                            fontSize = 10.sp,
                                            lineHeight = 10.sp,
                                            style = androidx.compose.ui.text.TextStyle(
                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                            )
                                        )
                                    }
                                }) {
                                    Icon(
                                        painterResource(item.iconRes),
                                        contentDescription = item.label,
                                        modifier = Modifier.size(iconSize),
                                        tint = iconColor
                                    )
                                }
                            } else {
                                Icon(
                                    painterResource(item.iconRes),
                                    contentDescription = item.label,
                                    modifier = Modifier.size(iconSize),
                                    tint = iconColor
                                )
                            }
                            if (isSelected) {
                                Text(item.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3E1F91), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> RoomListScreen(onRoomClick = onRoomClick, refreshKey = roomRefreshKey, onSearchClick = onSearchClick)
                1 -> {
                    val context = LocalContext.current
                    val container = (context.applicationContext as App).container
                    val contactsScope = rememberCoroutineScope()
                    ContactsScreen(
                        onChat = { userId ->
                            contactsScope.launch {
                                try {
                                    val res = container.api.createRoom(
                                        mapOf("type" to "private", "friend_id" to userId)
                                    )
                                    if (res.success && res.data != null) {
                                        onRoomClick(res.data)
                                    }
                                } catch (_: Exception) {}
                            }
                        },
                        onCall = { userId, isVideo ->
                            // Find-or-create the private room, then open chat
                            // with autoCall via AppDestination — same code path
                            // as "Gọi lại" from a missed-call notification.
                            contactsScope.launch {
                                try {
                                    val res = container.api.createRoom(
                                        mapOf("type" to "private", "friend_id" to userId)
                                    )
                                    if (res.success && res.data != null) {
                                        onRoomCall(res.data, isVideo)
                                    }
                                } catch (_: Exception) {}
                            }
                        },
                        onOpenWall = onOpenWall,
                        onAddFriend = onAddFriend
                    )
                }
                2 -> PlaceholderTab("Khám phá")
                3 -> TimelineScreen()
                4 -> AccountScreen(onLogout = onLogout, onEditProfile = onEditProfile)
            }
        }
    }
}

@Composable
fun RoomListPlaceholder() {
    PlaceholderTab("Tin nhắn — Đang phát triển")
}

@Composable
fun AccountTab(onLogout: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val user = container.tokenManager.user

    Column(
        modifier = Modifier.fillMaxSize().padding(androidx.compose.ui.unit.Dp(24f)),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = user?.username ?: "User",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(androidx.compose.ui.unit.Dp(8f)))
        Text(text = user?.phone ?: "", color = Color.Gray)
        Spacer(modifier = Modifier.height(androidx.compose.ui.unit.Dp(32f)))
        Button(
            onClick = {
                container.tokenManager.clear()
                container.socket.disconnect()
                onLogout()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) {
            Text("Đăng xuất")
        }
    }
}

@Composable
fun PlaceholderTab(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(title, color = Color.Gray)
    }
}
