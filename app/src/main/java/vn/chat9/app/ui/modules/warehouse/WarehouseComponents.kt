package vn.chat9.app.ui.modules.warehouse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Badge trạng thái kho (Xuất kho / Nhập kho / Đã giao...). Chiều cao tối giản:
 * padding dọc 1dp + lineHeight sát + includeFontPadding=false (giảm ~35% so với
 * mặc định). Dùng chung cho card list + chi tiết đơn.
 */
@Composable
fun WhBadge(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        lineHeight = 12.sp,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/**
 * Checkbox tùy biến (vẽ Canvas) để khớp chuẩn nhẹ của web:
 * kích thước ~19dp (−5%), nét 1dp (−50% so với ~2dp mặc định), opacity 0.65 (−35%).
 */
@Composable
fun WhCheckbox(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkedColor: Color,
    borderColor: Color,
) {
    Canvas(
        modifier = Modifier
            .size(17.dp)
            .alpha(if (enabled) 0.21f else 0.1f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        val s = size.minDimension
        val radius = CornerRadius(s * 0.18f, s * 0.18f)
        val sw = 1.dp.toPx()
        if (checked) {
            drawRoundRect(color = checkedColor, cornerRadius = radius)
            val check = Path().apply {
                moveTo(s * 0.27f, s * 0.52f)
                lineTo(s * 0.43f, s * 0.69f)
                lineTo(s * 0.74f, s * 0.31f)
            }
            drawPath(check, color = Color.White, style = Stroke(width = sw * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawRoundRect(color = borderColor, cornerRadius = radius, style = Stroke(width = sw))
        }
    }
}
