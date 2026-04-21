package vn.chat9.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(container.tokenManager.user) }

    var isEditing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(user?.username ?: "") }
    var editEmail by remember { mutableStateOf(user?.email ?: "") }
    var editPhone by remember { mutableStateOf(user?.phone ?: "") }
    var editBio by remember { mutableStateOf(user?.bio ?: "") }
    var editGender by remember { mutableStateOf(user?.gender ?: "") }
    var isSaving by remember { mutableStateOf(false) }

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
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thông tin cá nhân", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF3E1F91))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.White)
        ) {
            // Avatar
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.clickable { avatarPicker.launch("image/*") }) {
                    val avatarUrl = UrlUtils.toFullUrl(user?.avatar)
                    if (avatarUrl != null) {
                        AsyncImage(
                            model = avatarUrl, contentDescription = "Avatar",
                            modifier = Modifier.size(100.dp).clip(CircleShape).border(2.dp, Color(0xFFE0E0E0), CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(user?.username?.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFF0F2F5))

            // Fields - always same layout, editable or not
            InfoEditRow(Icons.Default.Person, "Tên", if (isEditing) editName else (user?.username ?: ""), isEditing) { editName = it }
            InfoEditRow(Icons.Default.Email, "Email", if (isEditing) editEmail else (user?.email ?: "Chưa cập nhật"), isEditing) { editEmail = it }
            InfoEditRow(Icons.Default.Phone, "Số điện thoại", if (isEditing) editPhone else (user?.phone ?: "Chưa cập nhật"), isEditing, isPhone = true) { editPhone = it }

            // Gender row
            GenderRow(
                label = "Giới tính",
                value = if (isEditing) editGender else (user?.gender ?: ""),
                isEditing = isEditing,
                onValueChange = { editGender = it }
            )

            Spacer(Modifier.height(20.dp))

            // Edit / Save button
            Button(
                onClick = {
                    if (isEditing) {
                        // Save
                        scope.launch {
                            isSaving = true
                            try {
                                val body = mapOf(
                                    "username" to editName.trim(),
                                    "email" to editEmail.trim().ifEmpty { null },
                                    "phone" to editPhone.trim().ifEmpty { null },
                                    "gender" to editGender.ifEmpty { null },
                                    "bio" to editBio.trim().ifEmpty { null }
                                )
                                val res = container.api.updateProfile(body)
                                if (res.success && res.data != null) {
                                    container.tokenManager.user = res.data
                                    user = res.data
                                    isEditing = false
                                }
                            } catch (_: Exception) {}
                            isSaving = false
                        }
                    } else {
                        // Switch to edit mode
                        editName = user?.username ?: ""
                        editEmail = user?.email ?: ""
                        editPhone = user?.phone ?: ""
                        editGender = user?.gender ?: ""
                        editBio = user?.bio ?: ""
                        isEditing = true
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = if (isEditing)
                    ButtonDefaults.buttonColors(containerColor = Color(0xFF3E1F91))
                else
                    ButtonDefaults.buttonColors(containerColor = Color.White),
                border = if (!isEditing) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)) else null,
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(Modifier.size(20.dp), Color.White, strokeWidth = 2.dp)
                } else if (isEditing) {
                    Icon(Icons.Default.Check, "save", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lưu", color = Color.White, fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.Edit, "edit", tint = Color(0xFF2C3E50), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Chỉnh sửa", color = Color(0xFF2C3E50), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoEditRow(icon: ImageVector, label: String, value: String, isEditing: Boolean, isPhone: Boolean = false, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, tint = Color.Gray, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        if (isEditing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF2C3E50),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                ),
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = if (isPhone) androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                ) else androidx.compose.foundation.text.KeyboardOptions.Default,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        innerTextField()
                    }
                }
            )
        } else {
            Text(
                value, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = if (value == "Chưa cập nhật") Color.LightGray else Color(0xFF2C3E50)
            )
        }
    }
}

@Composable
private fun GenderRow(label: String, value: String, isEditing: Boolean, onValueChange: (String) -> Unit) {
    val genderMap = mapOf("male" to "Nam", "female" to "Nữ", "other" to "Khác")
    val displayValue = genderMap[value] ?: "Chưa cập nhật"

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Person, label, tint = Color.Gray, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = Color.Gray, modifier = Modifier.weight(1f))

        if (isEditing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("male" to "Nam", "female" to "Nữ", "other" to "Khác").forEach { (key, text) ->
                    FilterChip(
                        selected = value == key,
                        onClick = { onValueChange(key) },
                        label = { Text(text, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF3E1F91),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        } else {
            Text(
                displayValue, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = if (value.isEmpty()) Color.LightGray else Color(0xFF2C3E50)
            )
        }
    }
}
