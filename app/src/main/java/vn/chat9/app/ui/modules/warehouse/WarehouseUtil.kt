package vn.chat9.app.ui.modules.warehouse

import vn.chat9.app.data.vapi.dto.OrderDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bỏ số 0 thập phân thừa: 5.0 -> "5", 2.50 -> "2.5". */
fun trimZeros(n: Double): String =
    if (n == Math.floor(n) && !n.isInfinite()) n.toLong().toString()
    else n.toString().trimEnd('0').trimEnd('.')

/** ISO "2026-05-20T..." -> "20/05/2026". */
fun fmtDate(s: String?): String {
    if (s == null || s.length < 10) return "—"
    val p = s.substring(0, 10).split("-")
    return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else "—"
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
