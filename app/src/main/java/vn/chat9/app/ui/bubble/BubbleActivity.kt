package vn.chat9.app.ui.bubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chat9.app.ui.theme._9chatTheme

/**
 * Activity dành riêng cho **Android Bubble expanded container** (API 30+).
 * Hệ thống tự embed Activity này vào bubble window khi user tap bubble peek.
 *
 * Manifest yêu cầu:
 *   - allowEmbedded=true     (system embed vào bubble container)
 *   - resizeableActivity=true (bubble dùng window mode khác full-screen)
 *   - documentLaunchMode=always (mỗi room có 1 task/bubble riêng,
 *     cho phép nhiều bubble đồng thời)
 *   - taskAffinity=""        (không gộp vào MainActivity stack)
 *   - exported=true          (system process launch được)
 *
 * Phase 1 (hiện tại): chỉ là skeleton để verify bubble flow hoạt động —
 * tap bubble peek phải mở được Activity này trong expanded container.
 *
 * Phase 2: thay nội dung bằng mini chat UI đầy đủ (header avatar + name,
 * danh sách message gọn, input bar gửi tin nhắn inline).
 */
class BubbleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getStringExtra("room_id")?.toIntOrNull()
        if (roomId == null || roomId <= 0) {
            finish()
            return
        }
        setContent {
            _9chatTheme {
                BubblePlaceholder(roomId = roomId)
            }
        }
    }
}

@Composable
private fun BubblePlaceholder(roomId: Int) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bubble — phòng #$roomId",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Mini chat UI sẽ được hoàn thiện ở Phase 2.\n" +
                    "Hiện tại chỉ verify bubble peek + expand đang chạy đúng.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
