package vn.chat9.app.ui.modules.warehouse

import vn.chat9.app.data.vapi.dto.OrderDto
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** BE lưu UTC (offset +00:00) hoặc +07:00 tuỳ field — luôn hiển thị theo giờ VN. */
private val VN_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Parse ISO (mọi offset: Z, +00:00, +07:00…) → giờ VN. null nếu không parse được. */
private fun parseVn(s: String?): ZonedDateTime? {
    if (s.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(s).atZoneSameInstant(VN_ZONE) }
        .recoverCatching { Instant.parse(s).atZone(VN_ZONE) }
        .getOrNull()
}

/** Bỏ số 0 thập phân thừa: 5.0 -> "5", 2.50 -> "2.5". */
fun trimZeros(n: Double): String =
    if (n == Math.floor(n) && !n.isInfinite()) n.toLong().toString()
    else n.toString().trimEnd('0').trimEnd('.')

/** ISO -> "20/05/2026" theo giờ VN (chuyển timezone, không cắt chuỗi thô). */
fun fmtDate(s: String?): String {
    val z = parseVn(s)
    if (z != null) return DATE_FMT.format(z)
    // Fallback: chuỗi chỉ có ngày ("2026-05-20") — cắt thô.
    if (s != null && s.length >= 10) {
        val p = s.substring(0, 10).split("-")
        if (p.size == 3) return "${p[2]}/${p[1]}/${p[0]}"
    }
    return "—"
}

/** ISO -> "20/05/2026 09:30" theo GIỜ VN (đổi offset đúng). Fallback fmtDate nếu chỉ có ngày. */
fun fmtDateTime(s: String?): String {
    val z = parseVn(s) ?: return fmtDate(s)
    return "${DATE_FMT.format(z)} ${TIME_FMT.format(z)}"
}

/** completed_at có phải hôm nay (so theo ngày chuỗi yyyy-MM-dd). */
fun isToday(s: String?): Boolean {
    if (s == null || s.length < 10) return false
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return s.substring(0, 10) == today
}

/** Tổng SL gộp theo đơn vị: "5 thùng + 2 lốc". */
fun qtySummary(o: OrderDto): String {
    val map = LinkedHashMap<String, Double>()
    for (item in o.items) {
        val u = item.unitName.ifBlank { "đv" }
        map[u] = (map[u] ?: 0.0) + item.qtyUnit
    }
    return if (map.isEmpty()) "—" else map.entries.joinToString(" + ") { "${trimZeros(it.value)} ${it.key}" }
}

fun isPurchaseOrder(o: OrderDto): Boolean = o.type == "purchase" || o.type == "supplier_return"

/** Thời điểm gửi kho (FIFO cũ trước). */
fun sentToWhTime(o: OrderDto): String = o.confirmedAt ?: o.orderedAt ?: ""
