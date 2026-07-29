package vn.chat9.app.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Viền sáng "lan tỏa" cho dialog — bản Android của rule global `.n-modal` bên
 * admin.ai.vn (`main.scss`). Cùng màu, cùng độ đậm để hai nền tảng nhìn giống nhau:
 *   web: border 1px rgba(94,106,210,.7) + box-shadow 0 0 24px 3px rgba(...,.3)
 *
 * ĐỊNH NGHĨA MỘT CHỖ DUY NHẤT. Compose không có stylesheet toàn cục nên vẫn phải
 * gắn `Modifier.dialogGlow()` vào từng dialog, nhưng MÀU và ĐỘ LAN chỉ sửa ở đây —
 * đừng chế glow riêng trong từng màn.
 *
 * Lưu ý: bóng đổ CÓ MÀU (ambientColor/spotColor) chỉ có hiệu lực từ API 28.
 * minSdk của app là 24, nên trên máy 24-27 sẽ ra bóng xám mặc định — viền màu
 * vẫn còn nên dialog vẫn nổi, chỉ kém rực. Không đáng đánh đổi bằng việc tự vẽ
 * nhiều lớp glow (tốn frame, dễ vỡ khi bị cha clip).
 */
private val DialogGlowColor = Color(0xFF5E6AD2) // = --primary bên web

/** Bo góc mặc định = shape của AlertDialog Material 3. */
private val DefaultCorner = 28.dp

fun Modifier.dialogGlow(corner: Dp = DefaultCorner): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .shadow(
            elevation = 24.dp,
            shape = shape,
            ambientColor = DialogGlowColor,
            spotColor = DialogGlowColor,
        )
        .border(1.dp, DialogGlowColor.copy(alpha = 0.7f), shape)
}
