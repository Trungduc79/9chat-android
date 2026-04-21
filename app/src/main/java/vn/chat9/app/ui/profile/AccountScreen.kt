package vn.chat9.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import vn.chat9.app.util.UrlUtils

@Composable
fun AccountScreen(onLogout: () -> Unit, onEditProfile: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(container.tokenManager.user) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: return@launch
                inputStream.close()
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("avatar", "avatar.jpg", requestBody)
                val res = withContext(Dispatchers.IO) { container.api.uploadAvatar(filePart) }
                if (res.success && res.data != null) {
                    user = user?.copy(avatar = res.data["avatar"])
                    container.tokenManager.user = user
                }
            } catch (e: Exception) {
                android.util.Log.e("Account", "Avatar upload failed", e)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF0F2F5))
            .verticalScroll(rememberScrollState())
    ) {
        // Search bar
        vn.chat9.app.ui.common.AppSearchBar(
            rightIconRes = vn.chat9.app.R.drawable.ic_settings,
            onRightIconClick = { /* TODO: open settings */ }
        )

        // Profile row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .clickable { onEditProfile() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.clickable { avatarPicker.launch("image/*") }) {
                val avatarUrl = UrlUtils.toFullUrl(user?.avatar)
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = "Avatar",
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            user?.username?.firstOrNull()?.uppercase() ?: "?",
                            color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(user?.username ?: "User", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Xem trang cá nhân", fontSize = 13.sp, color = Color.Gray)
            }

            Icon(Icons.Default.ChevronRight, "arrow", tint = Color.LightGray)
        }

        Spacer(Modifier.height(8.dp))

        // Menu section 1
        Column(modifier = Modifier.background(Color.White)) {
            AccountMenuItem(Icons.Default.Person, Color(0xFF3E1F91), "Hồ sơ cá nhân", "Cập nhật thông tin cá nhân", onEditProfile)
            AccountMenuItem(Icons.Default.QrCode, Color(0xFF3E1F91), "Ví QR", "Lưu trữ và xuất trình các mã QR") { }
        }

        Spacer(Modifier.height(8.dp))

        Column(modifier = Modifier.background(Color.White)) {
            AccountMenuItem(Icons.Default.Shield, Color(0xFF3E1F91), "Tài khoản và bảo mật") { }
            AccountMenuItem(Icons.Default.Lock, Color(0xFF3E1F91), "Quyền riêng tư") { }
        }

        Spacer(Modifier.height(60.dp))

        // Logout centered at bottom
        TextButton(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text("Đăng xuất", color = Color.Red, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun AccountMenuItem(
    icon: ImageVector,
    iconBg: Color = Color(0xFF3E1F91),
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, title, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = Color.Gray)
            }
        }
        Icon(Icons.Default.ChevronRight, "arrow", tint = Color.LightGray, modifier = Modifier.size(20.dp))
    }
}
