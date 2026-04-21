package vn.chat9.app.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.util.UrlUtils

/**
 * "Thêm bạn" screen. Modeled on Zalo's add-friend screen:
 *  - Blue QR card at top (user's own `9chat://user/{id}` deep link)
 *  - Phone input row with +84 prefix and arrow submit
 *  - Shortcut rows: scan QR, people-you-may-know
 *
 * Phone submit calls `searchUsers(q, type="phone")`. If a single match is
 * found and it's not already a friend, the user-wall is opened for them to
 * send a friend request. If no match, shows a toast.
 */
@Composable
fun AddFriendScreen(
    onBack: () -> Unit,
    onScanQr: () -> Unit,
    onOpenWall: (userId: Int) -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val me = container.tokenManager.user

    var phone by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    fun doSearch() {
        val raw = phone.trim().replace("\\s".toRegex(), "")
        if (raw.length != 10 || !raw.startsWith("0")) {
            android.widget.Toast.makeText(
                context,
                "Số điện thoại phải có 10 chữ số bắt đầu bằng 0",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (isSearching) return
        scope.launch {
            isSearching = true
            val (found, message) = try {
                val res = container.api.searchUsers(raw, "phone")
                val match = res.data?.firstOrNull()
                when {
                    res.success && match != null -> match.id to null
                    !res.success -> null to (res.message?.ifBlank { null } ?: "Không tìm thấy người dùng với số này")
                    else -> null to "Không tìm thấy người dùng với số này"
                }
            } catch (e: Exception) {
                android.util.Log.e("AddFriend", "searchUsers failed", e)
                // Retrofit HttpException (4xx/5xx) carries the server JSON in
                // errorBody — extract {"message":"..."} so the user sees the
                // actual server reason instead of a generic "Lỗi kết nối".
                val serverMsg = (e as? retrofit2.HttpException)?.let { httpE ->
                    try {
                        val body = httpE.response()?.errorBody()?.string() ?: ""
                        org.json.JSONObject(body).optString("message").takeIf { it.isNotBlank() }
                    } catch (_: Exception) { null }
                }
                null to (serverMsg ?: "Lỗi kết nối, thử lại sau")
            }
            isSearching = false
            if (found != null) {
                onOpenWall(found)
            } else if (message != null) {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val submitPhone: () -> Unit = { doSearch() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            // Edge-to-edge compensation: inset by status/nav bars so the top
            // app bar and footer aren't hidden behind system chrome.
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top app bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color(0xFF2C3E50))
            }
            Spacer(Modifier.width(4.dp))
            Text("Thêm bạn", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2C3E50))
        }

        Spacer(Modifier.height(12.dp))

        // Personal QR card — 60% of screen width, centered. QR image inside
        // is 68% of the card width (so it scales proportionally with screen
        // size instead of being pinned to a fixed dp value).
        Box(
            modifier = Modifier
                .fillMaxWidth(0.60f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF3E5B7A), Color(0xFF2C4059))
                    )
                )
                .padding(vertical = 14.dp, horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    me?.username ?: "Bạn",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                val qrPayload = "9chat://user/${me?.id ?: 0}"
                val qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=400x400&margin=6&data=" +
                    java.net.URLEncoder.encode(qrPayload, "UTF-8")
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = qrUrl,
                        contentDescription = "QR cá nhân",
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        contentScale = ContentScale.Fit
                    )
                    val avatarUrl = UrlUtils.toFullUrl(me?.avatar)
                    if (avatarUrl != null) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth(0.22f)
                                .aspectRatio(1f)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color.White)
                                .padding(2.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Quét mã để thêm bạn 9chat với tôi",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Phone input row — all three elements (country code, input, submit)
        // share the same height, rounded-rect shape, and corner radius so they
        // read as a unified input group.
        val inputHeight = 48.dp
        val inputShape = RoundedCornerShape(10.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Country code chip
            Box(
                modifier = Modifier
                    .height(inputHeight)
                    .clip(inputShape)
                    .background(Color(0xFFF5F6F8))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("+84", fontSize = 15.sp, color = Color(0xFF2C3E50))
            }
            Spacer(Modifier.width(8.dp))
            // Input
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(inputHeight)
                    .clip(inputShape)
                    .background(Color(0xFFF5F6F8))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF2C3E50)),
                    modifier = Modifier.fillMaxWidth()
                )
                if (phone.isEmpty()) {
                    Text(
                        "Nhập số điện thoại",
                        color = Color(0xFFBDBDBD),
                        fontSize = 15.sp
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Submit arrow — rectangular to match the other two.
            Box(
                modifier = Modifier
                    .height(inputHeight)
                    .width(inputHeight)
                    .clip(inputShape)
                    .background(
                        if (phone.length >= 10) Color(0xFF2196F3)
                        else Color(0xFFE0E0E0)
                    )
                    .clickable(onClick = submitPhone),
                contentAlignment = Alignment.Center
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        "Tìm",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color(0xFFEEEEEE)
        )

        // Shortcut rows
        ShortcutRow(
            icon = Icons.Default.QrCodeScanner,
            label = "Quét mã QR",
            onClick = onScanQr
        )
        ShortcutRow(
            icon = Icons.Default.People,
            label = "Bạn bè có thể quen",
            onClick = {
                android.widget.Toast.makeText(context, "Đang phát triển", android.widget.Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(Modifier.weight(1f))

        Text(
            "Xem lời mời kết bạn đã gửi tại trang Danh bạ",
            fontSize = 13.sp,
            color = Color(0xFF9E9E9E),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun ShortcutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color(0xFF2196F3), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 15.sp, color = Color(0xFF2C3E50))
    }
}
