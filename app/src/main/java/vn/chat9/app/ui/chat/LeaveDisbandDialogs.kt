package vn.chat9.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import vn.chat9.app.data.model.RoomMember
import vn.chat9.app.util.UrlUtils

/**
 * Bottom sheet "Chọn trưởng nhóm mới trước khi rời" — Zalo-style.
 *
 * Trigger: gọi khi user (admin duy nhất) tap "Rời nhóm". Server đã kiểm
 * tra điều kiện này và trả 409 với `need_transfer`. UI nhận members
 * (đã trừ caller), hiện list với radio, "Chọn và tiếp tục" gọi lại
 * `/rooms/leave.php` với `transfer_to`.
 *
 * @param members danh sách thành viên còn lại sau khi caller rời (đã filter)
 * @param onConfirm gọi với user_id của thành viên được chọn làm admin mới
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferAdminBottomSheet(
    members: List<RoomMember>,
    onDismiss: () -> Unit,
    onConfirm: (newAdminId: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Int?>(null) }

    val filtered = remember(members, search) {
        if (search.isBlank()) members
        else members.filter { it.username.contains(search, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                "Chọn trưởng nhóm mới trước khi rời",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Tìm kiếm") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
            ) {
                items(filtered, key = { it.id }) { m ->
                    val isSel = selected == m.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = m.id }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val url = UrlUtils.toFullUrl(m.avatar)
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
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
                                    color = Color.White, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(m.username, modifier = Modifier.weight(1f), fontSize = 15.sp)

                        // Radio button tròn
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (isSel) Color(0xFF1E88E5) else Color.Transparent)
                                .border(
                                    width = if (isSel) 0.dp else 2.dp,
                                    color = if (isSel) Color.Transparent else Color.LightGray,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSel) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFFEEEEEE))
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "Không có thành viên nào khớp",
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            color = Color.Gray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
            ) {
                Text("Chọn và tiếp tục", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Hủy", color = Color.Black, fontSize = 15.sp) }
        }
    }
}

/**
 * Confirm dialog có title + text căn giữa, 2 nút full-width chia 50/50
 * ở đáy. Dùng Dialog raw (không AlertDialog) để control layout chính xác.
 *
 * Pattern dùng cho mọi destructive confirm trong group (rời, giải tán...).
 *
 * @param destructiveLabel nhãn nút phải (đỏ, in đậm) — vd "Rời nhóm", "Giải tán"
 */
@Composable
fun CenteredConfirmDialog(
    title: String,
    message: String,
    destructiveLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,  // bỏ cap ~280dp mặc định
        ),
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Title
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                )

                // Body
                Text(
                    message,
                    fontSize = 14.sp,
                    color = Color(0xFF555555),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 0.dp),
                )

                Spacer(Modifier.height(24.dp))

                HorizontalDivider(color = Color(0xFFEEEEEE))

                // 2 buttons full-width, chia 50/50, có vertical divider giữa
                Row(modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                    ) {
                        Text("Hủy", fontSize = 15.sp, color = Color(0xFF333333))
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFEEEEEE))
                    )
                    TextButton(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                    ) {
                        Text(
                            destructiveLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE53935),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DisbandConfirmDialog(
    groupName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) = CenteredConfirmDialog(
    title = "Giải tán nhóm?",
    message = "Bạn sắp giải tán \"$groupName\". Tất cả tin nhắn, file và thành viên sẽ bị xoá vĩnh viễn. Hành động này không thể hoàn tác.",
    destructiveLabel = "Giải tán",
    onDismiss = onDismiss,
    onConfirm = onConfirm,
)

@Composable
fun LeaveConfirmDialog(
    groupName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) = CenteredConfirmDialog(
    title = "Rời nhóm?",
    message = "Bạn sẽ không còn nhận tin nhắn từ \"$groupName\". Có thể tham gia lại nếu được mời.",
    destructiveLabel = "Rời nhóm",
    onDismiss = onDismiss,
    onConfirm = onConfirm,
)
