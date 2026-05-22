package vn.chat9.app.ui.explore

import androidx.compose.ui.graphics.Color

/**
 * Bảng màu DARK cho khu quản trị (tab Khám phá + các module) — khớp design
 * token của admin.ai.vn/kho. Chỉ áp cho khu quản trị; phần chat 9chat vẫn sáng.
 */
object AdminColors {
    val Bg = Color(0xFF0A0A0A)          // nền trang
    val Card = Color(0xFF141414)        // nền card
    val Surface = Color(0xFF1F1F1F)
    val Hover = Color(0xFF1A1A1A)
    val Border = Color(0xFF2A2A2A)
    val BorderBright = Color(0xFF3A3A3A)
    val Divider = Color(0xFF555555)
    val Text = Color(0xFFEDEDED)        // chữ chính
    val TextSecondary = Color(0xFFBBBBBB)
    val TextMuted = Color(0xFF888888)
    val Primary = Color(0xFF5E6AD2)
    val Success = Color(0xFF4CB782)
    val Warning = Color(0xFFF2994A)
    val Danger = Color(0xFFEB5757)
    val Info = Color(0xFF9B87F5)
    val White = Color(0xFFFFFFFF)
}
