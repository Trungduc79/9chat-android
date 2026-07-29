package vn.chat9.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImageContent
import org.json.JSONObject
import vn.chat9.app.data.model.Message
import vn.chat9.app.util.UrlUtils
import java.text.NumberFormat
import java.util.Locale

private data class BizTheme(val emoji: String, val color: Color, val label: String)

private val BIZ_THEMES = mapOf(
    "order" to BizTheme("🧾", Color(0xFF6D4AFF), "Đơn hàng"),
    "debt" to BizTheme("💰", Color(0xFFB7791F), "Công nợ"),
    "invoice" to BizTheme("📄", Color(0xFF2B6CB0), "Hóa đơn"),
    "product" to BizTheme("📦", Color(0xFF2F855A), "Sản phẩm"),
    "task" to BizTheme("✅", Color(0xFF2C7A7B), "Công việc"),
    "transaction" to BizTheme("💵", Color(0xFF0891B2), "Giao dịch"),
)

/** Dòng badge trạng thái (trái) + meta/tồn (phải). Dùng ở header (title_center) hoặc full-width. */
@Composable
private fun BizMetaRow(statusLabel: String?, statusColorKey: String, meta: String) {
    if (statusLabel == null && meta.isBlank()) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (statusLabel != null) {
            val (fg, bg) = bizStatusColors(statusColorKey)
            Text(
                statusLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
            )
        } else { Spacer(Modifier.width(1.dp)) }
        if (meta.isNotBlank()) {
            Text(meta, fontSize = 12.sp, color = Color(0xFF8593A1))
        }
    }
}

private fun bizStatusColors(c: String): Pair<Color, Color> = when (c) {
    "success" -> Color(0xFF16A34A) to Color(0x1F16A34A)
    "info" -> Color(0xFF2563EB) to Color(0x1F2563EB)
    "warning" -> Color(0xFFA16207) to Color(0x1FA16207)
    "danger" -> Color(0xFFDC2626) to Color(0x1FDC2626)
    else -> Color(0xFF667085) to Color(0x14000000)
}

private val moneyFmt = NumberFormat.getInstance(Locale("vi", "VN"))

/**
 * Thẻ nghiệp vụ — engine CHUNG, theming per entity. Bố cục:
 *   [icon] LABEL · CODE / TITLE
 *   [badge] ................ [meta]
 *   [ảnh vuông nếu có]
 *   AMOUNT ................. Xem chi tiết ›
 * Tap → onOpen(deeplink) — dùng deeplink từ content (dir out/in của invoice...).
 */
@Composable
fun BusinessCardBubble(
    message: Message,
    onOpen: (deeplink: String) -> Unit,
) {
    val json = remember(message.content) {
        try { JSONObject(message.content ?: "{}") } catch (_: Exception) { JSONObject() }
    }
    val entity = json.optString("entity").ifBlank { message.type }
    val theme = BIZ_THEMES[entity] ?: BizTheme("📋", Color(0xFF667085), "")
    val id = json.optInt("id", 0)
    val deeplink = json.optString("deeplink").ifBlank { "9chat://$entity/$id" }
    // Nền icon theo sub-type (mua/nhập → vàng); còn accent bar/amount giữ theme.
    val iconColor = json.optString("color").takeIf { it.isNotBlank() }
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: theme.color
    val label = json.optString("label").ifBlank { theme.label }
    val code = json.optString("code")
    val title = json.optString("title")
    // title_center: tên dài → không đặt ở header (cụt 1 dòng) mà render full-width,
    // căn giữa, wrap ĐẦY ĐỦ ở dưới dòng badge/meta. Dùng cho thẻ sản phẩm.
    val titleCenter = json.optBoolean("title_center", false)
    val meta = json.optString("meta")
    val amount = if (json.has("amount") && !json.isNull("amount")) json.optDouble("amount") else null
    val imageUrl = json.optString("image_url").takeIf { it.isNotBlank() }
    val qtySummary = json.optString("qty_summary").takeIf { it.isNotBlank() }
    val statusObj = json.optJSONObject("status")
    val statusLabel = statusObj?.optString("label")?.takeIf { it.isNotBlank() }
    val statusColorKey = statusObj?.optString("color") ?: "muted"
    val topline = label + (if (code.isNotBlank()) " · $code" else "")

    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.color)          // nền accent; lộ 3dp bên trái
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(12.dp))
            .clickable { onOpen(deeplink) }
            .padding(start = 3.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: icon + (label·code / title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(iconColor),
                    contentAlignment = Alignment.Center,
                ) { Text(theme.emoji, fontSize = 18.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        topline.uppercase(), fontSize = 11.sp, color = Color(0xFF8593A1),
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (!titleCenter && title.isNotBlank()) {
                        Text(
                            title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2C3E50),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // title_center: badge/tồn nằm NGAY dưới topline (cùng cột với icon).
                    if (titleCenter) {
                        Spacer(Modifier.height(4.dp))
                        BizMetaRow(statusLabel, statusColorKey, meta)
                    }
                }
            }

            // Badge + meta (ngày) — dưới header full-width (khi KHÔNG title_center).
            if (!titleCenter) {
                BizMetaRow(statusLabel, statusColorKey, meta)
            }

            // Tên (title_center) — full width, căn giữa, WRAP đầy đủ. Dưới dòng badge/tồn.
            if (titleCenter && title.isNotBlank()) {
                Text(
                    title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2C3E50),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }

            // Ảnh đính kèm (vuông) — CHỈ chiếm chỗ khi tải THÀNH CÔNG. Ảnh
            // null/lỗi/đang tải → không render gì → KHÔNG để khoảng trắng
            // (giảm chiều cao thẻ khi không có ảnh hiển thị).
            if (imageUrl != null) {
                coil.compose.SubcomposeAsyncImage(
                    model = UrlUtils.toFullUrl(imageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (painter.state is coil.compose.AsyncImagePainter.State.Success) {
                        SubcomposeAsyncImageContent(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }

            // Tổng số lượng theo đơn vị bán — full width, căn giữa.
            if (qtySummary != null) {
                Text(
                    "Số lượng: $qtySummary",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2C3E50),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Dòng giữa in hoa (tên đơn vị trên HĐ) — full width, căn giữa.
            val center = json.optString("center").takeIf { it.isNotBlank() }
            if (center != null) {
                Text(
                    center,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(color = Color(0xFFF0F0F0), thickness = 1.dp)

            // Foot: amount + CTA. Chữ "đ" vàng, mảnh, nghiêng.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (amount != null) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = theme.color, fontWeight = FontWeight.Bold)) {
                                append(moneyFmt.format(amount.toLong()))
                            }
                            append(" ")
                            withStyle(SpanStyle(color = Color(0xFFD69E2E), fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic, fontSize = 13.sp)) {
                                append("đ")
                            }
                        },
                        fontSize = 16.sp,
                    )
                } else { Spacer(Modifier.width(1.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Xem chi tiết", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = theme.color)
                    Text(" ›", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = theme.color)
                }
            }
        }
    }
}
