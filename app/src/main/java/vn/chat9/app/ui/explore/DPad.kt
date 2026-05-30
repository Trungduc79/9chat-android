package vn.chat9.app.ui.explore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

enum class DpadDir { UP, DOWN, LEFT, RIGHT }

/**
 * Nút điều hướng 4 chiều (D-pad) kiểu gamepad — dựng theo DPad_Kotlin_Guide.html + ảnh mẫu:
 * vòng tròn ngoài radial gradient + viền tối, chữ thập kim loại bo góc, 4 mũi tên tam giác
 * trắng, vòng ○ trung tâm, nhãn UP/LÊN · XUỐNG/DOWN · LEFT/TRÁI · RIGHT/PHẢI quanh nút.
 *
 * - Tap 1 trong 4 nhánh (theo trục dài hơn) → [onDirection]. Vùng tâm ○ = dead-zone.
 * - Kéo (ngang + dọc) → [onDrag] (dx, dy px) để caller dịch nút.
 */
@Composable
fun DPad(
    onDirection: (DpadDir) -> Unit,
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 246.dp,   // +30% +15% +10%
) {
    Canvas(
        modifier
            .size(size)
            .alpha(0.5f)   // giảm opacity 50%
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val vs = min(this.size.width, this.size.height).toFloat()
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val dx = o.x - cx
                    val dy = o.y - cy
                    if (sqrt(dx * dx + dy * dy) < vs * 0.10f) return@detectTapGestures   // tâm ○: bỏ qua
                    onDirection(
                        if (abs(dx) > abs(dy)) { if (dx > 0) DpadDir.RIGHT else DpadDir.LEFT }
                        else { if (dy > 0) DpadDir.DOWN else DpadDir.UP }
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount -> change.consume(); onDrag(dragAmount.x, dragAmount.y) }
            },
    ) {
        val vs = min(this.size.width, this.size.height)
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val center = Offset(cx, cy)

        val outerR = vs * 0.356f          // padding (lề trong) giảm 20% → nút to hơn
        val armL = outerR * 0.9f          // đầu + sát đường tròn (90% đường kính)
        val armW = outerR * 0.47f
        val centerR = outerR * 0.234f

        // 1) Vòng tròn ngoài (radial gradient sáng→tối) + viền tối
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF697582), Color(0xFF2D3641)),
                center = center, radius = outerR,
            ),
            radius = outerR, center = center,
        )
        drawCircle(color = Color(0xFF1B2026), radius = outerR, center = center, style = Stroke(width = vs * 0.025f))

        // 2) Chữ thập kim loại (2 thanh bo góc) — vertical gradient
        val cross = Path().apply {
            val rc = CornerRadius(vs * 0.05f, vs * 0.05f)
            addRoundRect(RoundRect(cx - armW / 2, cy - armL, cx + armW / 2, cy + armL, rc))
            addRoundRect(RoundRect(cx - armL, cy - armW / 2, cx + armL, cy + armW / 2, rc))
        }
        drawPath(
            cross,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF7C8793), Color(0xFF3A434D)),
                startY = cy - armL, endY = cy + armL,
            ),
        )

        // 3) 4 mũi tên tam giác trắng gần đầu nhánh
        val a = outerR * 0.156f
        val tip = armL * 0.92f   // mũi tên sát đầu ngoài nhánh
        val arrow = Color(0xF2FFFFFF)
        fun tri(p1: Offset, p2: Offset, p3: Offset) =
            drawPath(Path().apply { moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close() }, arrow)
        tri(Offset(cx, cy - tip), Offset(cx - a, cy - tip + a), Offset(cx + a, cy - tip + a))   // lên
        tri(Offset(cx, cy + tip), Offset(cx - a, cy + tip - a), Offset(cx + a, cy + tip - a))   // xuống
        tri(Offset(cx - tip, cy), Offset(cx - tip + a, cy - a), Offset(cx - tip + a, cy + a))   // trái
        tri(Offset(cx + tip, cy), Offset(cx + tip - a, cy - a), Offset(cx + tip - a, cy + a))   // phải

        // 4) Tâm: đĩa gradient + 2 vòng ○
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFF505A66), Color(0xFF232A33)), center = center, radius = centerR),
            radius = centerR, center = center,
        )
        drawCircle(color = Color(0xCCFFFFFF), radius = centerR * 0.55f, center = center, style = Stroke(width = vs * 0.02f))
        drawCircle(color = Color(0xCCFFFFFF), radius = centerR * 0.20f, center = center)
    }
}
