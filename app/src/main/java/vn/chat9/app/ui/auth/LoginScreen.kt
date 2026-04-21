package vn.chat9.app.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.model.LoginRequest

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo
        Text(
            text = "9chat",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF3E1F91)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Đăng nhập để tiếp tục",
            fontSize = 14.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Error
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // Username field
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = null },
            label = { Text("Số điện thoại / Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Password field
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Mật khẩu") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (username.isNotBlank() && password.isNotBlank()) {
                        scope.launch {
                            isLoading = true
                            try {
                                val res = container.api.login(LoginRequest(username.trim(), password))
                                if (res.success && res.data != null) {
                                    container.tokenManager.saveAuth(
                                        res.data.access_token,
                                        res.data.refresh_token,
                                        res.data.user
                                    )
                                    onLoginSuccess()
                                } else {
                                    error = res.message ?: "Đăng nhập thất bại"
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Login", "Error", e)
                                error = "Lỗi: ${e.message}"
                            }
                            isLoading = false
                        }
                    }
                }
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Login button
        Button(
            onClick = {
                if (username.isBlank() || password.isBlank()) {
                    error = "Vui lòng nhập đầy đủ thông tin"
                    return@Button
                }
                scope.launch {
                    isLoading = true
                    error = null
                    try {
                        val res = container.api.login(LoginRequest(username.trim(), password))
                        if (res.success && res.data != null) {
                            container.tokenManager.saveAuth(
                                res.data.access_token,
                                res.data.refresh_token,
                                res.data.user
                            )
                            onLoginSuccess()
                        } else {
                            error = res.message ?: "Đăng nhập thất bại"
                        }
                    } catch (e: Exception) {
                        error = "Lỗi kết nối server"
                    }
                    isLoading = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E1F91)),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Đăng nhập", fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Register link
        TextButton(onClick = onNavigateToRegister) {
            Text("Chưa có tài khoản? Đăng ký", color = Color(0xFF3E1F91))
        }
    }
}
