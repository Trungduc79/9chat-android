package vn.chat9.app.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import vn.chat9.app.BuildConfig

/**
 * Kiểm tra bản cập nhật khi app mở và hiện hộp thoại nếu có bản mới.
 * Đặt 1 lần ở gốc setContent. Im lặng khi không có mạng / không có bản mới.
 *
 * `mandatory=true` (trong version.json) → ẩn nút "Để sau", không cho tắt.
 */
@Composable
fun UpdateGate() {
    // Build tay (Android Studio, không -PverCode) không kiểm tra cập nhật —
    // versionCode fallback=1 luôn nhỏ hơn bản trên VPS nên sẽ nhắc update nhầm.
    if (!BuildConfig.AUTO_UPDATE_ENABLED) return

    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val latest = UpdateManager.fetchLatest()
        if (latest != null && UpdateManager.hasUpdate(latest)) info = latest
    }

    val u = info ?: return
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { if (!u.mandatory) dismissed = true },
        title = { Text("Đã có bản cập nhật ${u.versionName}") },
        text = {
            Text(if (u.notes.isNotBlank()) u.notes else "Phiên bản mới của 9chat đã sẵn sàng.")
        },
        confirmButton = {
            TextButton(onClick = {
                UpdateManager.startInstall(context, u)
                if (!u.mandatory) dismissed = true
            }) { Text("Cập nhật") }
        },
        dismissButton = if (u.mandatory) null else {
            { TextButton(onClick = { dismissed = true }) { Text("Để sau") } }
        },
    )
}
