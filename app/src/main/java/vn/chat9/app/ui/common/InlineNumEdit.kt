package vn.chat9.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import vn.chat9.app.ui.explore.AdminColors

/**
 * Bong bóng hint số nổi phía trên ô inline-edit SL/đơn giá (mirror tab Công nợ chưa chốt).
 * Hiện khi ô đang được focus; [text] = giá trị lớn để đọc (vd số đã bung theo nghìn). null/rỗng → ẩn.
 */
@Composable
fun NumEditHint(show: Boolean, text: String?) {
    if (!show || text.isNullOrEmpty()) return
    val offY = with(LocalDensity.current) { -84 - 10.dp.roundToPx() }
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, offY)) {
        Text(
            text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .background(AdminColors.Primary.copy(alpha = 0.75f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
