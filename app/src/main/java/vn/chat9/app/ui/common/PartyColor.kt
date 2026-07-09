package vn.chat9.app.ui.common

import androidx.compose.ui.graphics.Color
import vn.chat9.app.data.vapi.dto.PartyDto
import kotlin.math.abs

/**
 * Màu định danh partner (KH/NCC) — khớp web/backend.
 * Ưu tiên `display_color` BE trả; thiếu → tự tính golden-angle theo id (S=60% L=68%).
 */
private val HEX_RE = Regex("^#[0-9a-fA-F]{6}$")

fun partyColor(party: PartyDto?): Color = partyColor(party?.id ?: 0, party?.displayColor)

/** display_color hex (nếu hợp lệ) ?: auto theo id. Dùng cho DTO không phải PartyDto. */
fun partyColor(id: Long, displayColor: String?): Color {
    if (displayColor != null && HEX_RE.matches(displayColor)) {
        return Color(android.graphics.Color.parseColor(displayColor))
    }
    return autoPartyColor(id)
}

fun autoPartyColor(id: Long): Color {
    if (id <= 0) return Color(0xFF9AA0A6)
    val hue = ((id * 137.508) % 360.0).toFloat()
    return hslToColor(hue, 0.60f, 0.68f)
}

private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs((h / 60f) % 2 - 1))
    val m = l - c / 2
    val (r, g, b) = when ((h / 60f).toInt() % 6) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}
