package vn.chat9.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.model.InviteCheck

/**
 * Phase 2.4b — Accept invite link.
 *
 * Flow:
 *   1. Deep link `9chat://invite/{token}` → AppDestination.AcceptInvite(token)
 *   2. Màn hình check token (PUBLIC, không cần auth) → preview role
 *   3. Nếu user chưa login → hiện nút "Đăng nhập / Đăng ký để tham gia"
 *   4. Nếu user đã login → nút "Tham gia với vai trò X"
 *   5. POST /invites/accept → permission cache refresh ngay → onSuccess về home
 *
 * Edge case:
 *   - Token invalid (expired/revoked/used_up/not_found) → hiện lý do, có nút back
 *   - Mạng lỗi khi check → retry button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcceptInviteScreen(
    token: String,
    onBack: () -> Unit,
    onAccepted: () -> Unit,
    onNeedLogin: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var check by remember { mutableStateOf<InviteCheck?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var accepting by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }

    val isLoggedIn = container.tokenManager.isLoggedIn

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                val res = container.api.checkInvite(token)
                if (res.success && res.data != null) {
                    check = res.data
                } else {
                    error = res.message ?: "Không xác minh được link mời"
                }
            } catch (e: Exception) {
                error = "Lỗi kết nối: ${e.message}"
            }
            loading = false
        }
    }

    LaunchedEffect(token) { load() }

    Scaffold(
        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Lời mời tham gia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 64.dp))
                }
                error != null -> {
                    ErrorBox(message = error!!, onRetry = { load() })
                }
                check?.valid == false -> {
                    InvalidInviteBox(reason = check?.reason ?: "unknown", onBack = onBack)
                }
                check?.valid == true -> {
                    val c = check!!
                    Spacer(Modifier.height(40.dp))
                    Icon(
                        Icons.Default.Mail,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = Color(0xFF3E1F91)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "${c.inviter?.username ?: "Ai đó"} mời bạn",
                        fontSize = 16.sp,
                        color = Color.Gray,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        c.role?.display_name ?: "—",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    c.role?.description?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 13.sp, color = Color.Gray)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Hết hạn: ${c.expires_at}", fontSize = 12.sp, color = Color.Gray)
                    c.uses_remaining?.let {
                        Text("Còn $it lượt", fontSize = 12.sp, color = Color.Gray)
                    }

                    Spacer(Modifier.height(40.dp))

                    if (accepted) {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(0xFFE8F5E9), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Đã tham gia thành công", color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onAccepted, modifier = Modifier.fillMaxWidth()) {
                            Text("Về trang chính")
                        }
                    } else if (!isLoggedIn) {
                        Button(onClick = onNeedLogin, modifier = Modifier.fillMaxWidth()) {
                            Text("Đăng nhập / Đăng ký để tham gia")
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    accepting = true
                                    try {
                                        val res = container.api.acceptInvite(mapOf("token" to token))
                                        if (res.success) {
                                            // Refresh cache ngay để UI khác lên perm mới
                                            container.permissions.refresh(force = true)
                                            accepted = true
                                        } else {
                                            error = res.message ?: "Lỗi khi accept"
                                        }
                                    } catch (e: Exception) {
                                        error = "Lỗi: ${e.message}"
                                    }
                                    accepting = false
                                }
                            },
                            enabled = !accepting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (accepting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text("Tham gia với vai trò ${c.role?.display_name ?: ""}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Spacer(Modifier.height(40.dp))
    Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(48.dp), tint = Color(0xFFE53935))
    Spacer(Modifier.height(12.dp))
    Text(message, color = Color(0xFFE53935))
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onRetry) { Text("Thử lại") }
}

@Composable
private fun InvalidInviteBox(reason: String, onBack: () -> Unit) {
    val message = when (reason) {
        "expired" -> "Link mời đã hết hạn"
        "revoked" -> "Link mời đã bị thu hồi"
        "used_up" -> "Link mời đã hết lượt sử dụng"
        "not_found" -> "Link mời không tồn tại"
        else -> "Link mời không hợp lệ"
    }
    Spacer(Modifier.height(40.dp))
    Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(48.dp), tint = Color.Gray)
    Spacer(Modifier.height(12.dp))
    Text(message, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onBack) { Text("Quay lại") }
}
