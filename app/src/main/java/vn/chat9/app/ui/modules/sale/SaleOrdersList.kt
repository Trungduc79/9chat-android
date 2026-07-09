package vn.chat9.app.ui.modules.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.ui.explore.AdminColors
import vn.chat9.app.ui.explore.AdminPullToRefresh
import vn.chat9.app.ui.explore.AdminScrollTopButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * List đơn bán của NV — header search tên KH (70%) + dropdown ngày giao (30%),
 * đồng nhất layout với tab Sản phẩm / Khách hàng (mirror web).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleOrdersList(onTapOrder: (Long) -> Unit = {}, listState: LazyListState = rememberLazyListState()) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val userId = container.tokenManager.user?.id?.toLong()

    var orders by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var startDateMs by remember { mutableStateOf<Long?>(null) }
    var endDateMs by remember { mutableStateOf<Long?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OrderDto?>(null) }   // đơn chờ xác nhận xoá

    fun load() {
        if (userId == null) { error = "Chưa đăng nhập"; loading = false; return }
        scope.launch {
            loading = true; error = null
            try {
                val res = container.vapi.listOrders(status = "", createdByUserId = userId, type = "sale", perPage = 100)
                orders = res.data ?: emptyList()
            } catch (e: Exception) {
                error = "Tải đơn thất bại: ${e.message}"
            } finally { loading = false }
        }
    }
    LaunchedEffect(userId) { load() }

    val dayFmt = SimpleDateFormat("dd/MM", Locale("vi"))
    val keyFmt = SimpleDateFormat("yyyyMMdd", Locale("vi"))
    fun dayKey(ms: Long): Int = keyFmt.format(Date(ms)).toInt()
    val filtered = orders.filter { o ->
        // Khớp tên KH cả khi gõ không dấu (đa số NV search không dấu).
        val q = vnNoAccent(query.trim())
        val matchQ = q.isEmpty() || vnNoAccent(o.partyName).contains(q)
        // Lọc theo KHOẢNG ngày (so theo ngày, bỏ giờ). Chỉ start = từ ngày đó; chỉ end = đến ngày đó.
        val matchDate = if (startDateMs == null && endDateMs == null) true else {
            val d = o.completedAt ?: o.orderedAt
            val om = d?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            if (om == null) false else {
                val ok = dayKey(om)
                (startDateMs?.let { ok >= dayKey(it) } ?: true) && (endDateMs?.let { ok <= dayKey(it) } ?: true)
            }
        }
        matchQ && matchDate
    }.sortedWith(
        // Nháp trên cùng → đã duyệt → đã giao; trong mỗi nhóm theo thời gian giao GIẢM DẦN.
        compareBy<OrderDto> { statusRank(it.status) }.thenByDescending { deliveryTimeMs(it) },
    )

    Column(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        // Header: search 70% + ngày giao 30%
        Row(Modifier.fillMaxWidth().background(AdminColors.Card).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.7f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                    decorationBox = { inner -> if (query.isEmpty()) Text("Tìm đơn theo tên KH", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(0.3f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                    .clickable { datePickerOpen = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                val s = startDateMs; val e = endDateMs
                val rangeLabel = when {
                    s != null && e != null -> "${dayFmt.format(Date(s))}–${dayFmt.format(Date(e))}"
                    s != null -> "Từ ${dayFmt.format(Date(s))}"
                    e != null -> "Đến ${dayFmt.format(Date(e))}"
                    else -> "Khoảng ngày"
                }
                Text(
                    rangeLabel,
                    color = if (s != null || e != null) AdminColors.Text else AdminColors.TextMuted,
                    fontSize = 13.sp, maxLines = 1,
                )
            }
        }

        // Vuốt xuống = reload + reset lọc (khoảng ngày + tên KH). Empty/error state phải
        // scrollable (verticalScroll) thì PullToRefreshBox mới nhận cử chỉ vuốt khi list rỗng.
        Box(Modifier.weight(1f)) {
            AdminPullToRefresh(
                isRefreshing = loading,
                onRefresh = { query = ""; startDateMs = null; endDateMs = null; load() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    loading && orders.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
                    error != null -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), Alignment.Center) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error!!, color = AdminColors.Danger, fontSize = 14.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Chạm để thử lại", color = AdminColors.Primary, fontSize = 13.sp, modifier = Modifier.clickable { load() }.padding(8.dp))
                        }
                    }
                    filtered.isEmpty() -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), Alignment.Center) {
                        Text(if (orders.isEmpty()) "Chưa có đơn nào — nhấn + để tạo" else "Không có đơn khớp lọc", color = AdminColors.TextMuted, fontSize = 14.sp)
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.id }) { o ->
                            OrderRow(o, onClick = { onTapOrder(o.id) }, onDelete = { pendingDelete = o })
                        }
                    }
                }
            }
            // Trên nút + (56 + padding 16 + hở 12 = 84.dp).
            AdminScrollTopButton(listState, bottomPadding = 84.dp)
        }
    }

    if (datePickerOpen) {
        val rangeState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = startDateMs,
            initialSelectedEndDateMillis = endDateMs,
        )
        val headFmt = SimpleDateFormat("dd/MM/yyyy", Locale("vi"))
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text, primary = AdminColors.Primary, onPrimary = Color.White)) {
            DatePickerDialog(
                onDismissRequest = { datePickerOpen = false },
                // OK trải hết chiều ngang (nhấn đáy chỗ nào cũng OK), không có nút Xoá lọc.
                confirmButton = {
                    TextButton(
                        onClick = {
                            startDateMs = rangeState.selectedStartDateMillis
                            endDateMs = rangeState.selectedEndDateMillis
                            datePickerOpen = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("OK", color = AdminColors.Primary) }
                },
                colors = DatePickerDefaults.colors(containerColor = AdminColors.Card),
            ) {
                DateRangePicker(
                    state = rangeState,
                    modifier = Modifier.weight(1f),
                    title = {},                  // bỏ chữ "Chọn ngày"
                    showModeToggle = false,      // bỏ pen icon (toggle nhập tay)
                    headline = {                 // 1 dòng, font nhỏ, no-wrap
                        val s = rangeState.selectedStartDateMillis
                        val e = rangeState.selectedEndDateMillis
                        Text(
                            text = (s?.let { headFmt.format(Date(it)) } ?: "Bắt đầu") + "  –  " + (e?.let { headFmt.format(Date(it)) } ?: "Kết thúc"),
                            color = AdminColors.Text, fontSize = 16.sp, maxLines = 1, softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    },
                )
            }
        }
    }

    // Dialog xác nhận xoá đơn (chỉ nháp/đã duyệt).
    pendingDelete?.let { del ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Xoá đơn", color = AdminColors.Text) },
            text = { Text("Xoá đơn ${del.code} (${del.partyName})? Không hoàn tác được.", color = AdminColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        try {
                            container.vapi.deleteOrder(del.id)
                            orders = orders.filter { it.id != del.id }
                            Toast.makeText(context, "Đã xoá đơn ${del.code}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Xoá đơn thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Xoá", color = AdminColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Huỷ", color = AdminColors.TextMuted) } },
            containerColor = AdminColors.Card,
        )
    }
}

/** Bỏ dấu tiếng Việt + lowercase để search không dấu khớp có dấu. NFD tách dấu thanh/mũ;
 *  đ/Đ KHÔNG bị NFD tách nên thay tay. */
private fun vnNoAccent(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
        .lowercase()

/** Card đơn — layout port từ web SaleOrdersListView: hàng mã+badge, tên KH, dòng "N mặt
 *  hàng · ngày" + tổng tiền. */
@Composable
private fun OrderRow(o: OrderDto, onClick: () -> Unit, onDelete: () -> Unit) {
    val canDelete = o.status == "draft" || o.status == "confirmed"
    val statusColor = when (o.status) {
        "draft" -> AdminColors.TextMuted
        "confirmed" -> AdminColors.Info
        "delivered", "completed" -> AdminColors.Success
        "cancelled" -> AdminColors.Danger
        else -> AdminColors.TextMuted
    }
    val statusLabel = when (o.status) {
        "draft" -> "Nháp"; "confirmed" -> "Đã xác nhận"
        "delivered" -> "Đã giao"; "completed" -> "Hoàn thành"
        "cancelled" -> "Huỷ"; else -> o.status
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AdminColors.Card)
            .border(1.dp, AdminColors.Border, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Hàng 1: ngày (trắng nổi) + mã đơn (xám mờ) — badge trạng thái (phải)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtOrderDate(o.orderedAt ?: o.confirmedAt ?: o.completedAt), color = AdminColors.Text, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(o.code, color = AdminColors.TextMuted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(statusLabel, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
            // Xoá đơn (chỉ nháp/đã duyệt) — icon cạnh phải badge trạng thái
            if (canDelete) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Delete, contentDescription = "Xoá đơn", tint = AdminColors.TextMuted,
                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable { onDelete() })
            }
        }
        Spacer(Modifier.height(2.dp))
        // Hàng 2: tên khách hàng/đối tác
        Text(o.partyName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        // Hàng 3: N mặt hàng · tổng SL (trái) — số tiền TRẮNG, chỉ "đ" vàng gold nghiêng mảnh (phải)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${o.items.size} mặt hàng · ${qtySummary(o)}",
                color = AdminColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(moneyGoldDong(o.totalAmount ?: 0.0), color = AdminColors.Text, fontSize = 14.sp)
        }
    }
}

/** Thứ tự nhóm trạng thái: nháp → đã duyệt → đã giao/hoàn thành → (huỷ/khác). */
private fun statusRank(s: String): Int = when (s) {
    "draft" -> 0
    "confirmed" -> 1
    "delivered", "completed", "received" -> 2
    else -> 3
}
/** Thời gian giao (proxy): hoàn tất → ngày đặt → ngày tạo → 0. */
private fun deliveryTimeMs(o: OrderDto): Long {
    val d = o.completedAt ?: o.orderedAt ?: o.createdAt ?: return 0L
    return runCatching { java.time.Instant.parse(d).toEpochMilli() }.getOrNull() ?: 0L
}

private val moneyFmt = java.text.NumberFormat.getInstance(Locale("vi"))
private fun fmtMoney(n: Double): String = moneyFmt.format(n.toLong())

/** Số tiền màu trắng (mặc định) + chữ "đ" vàng gold, in nghiêng, nét mảnh. */
private fun moneyGoldDong(n: Double) = buildAnnotatedString {
    append(fmtMoney(n))
    withStyle(SpanStyle(color = Color(0xFFD4AF37), fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light)) {
        append(" đ")
    }
}

/** Tổng SL gộp theo đơn vị: "5 Thùng + 3 Gói" (đơn vị 2 nếu có). */
private fun qtySummary(o: OrderDto): String {
    val map = LinkedHashMap<String, Double>()
    for (it in o.items) {
        val unit = it.unitName.ifBlank { "đv" }
        map[unit] = (map[unit] ?: 0.0) + it.qtyUnit
    }
    val parts = map.entries.map { "${trimZeros(it.value)} ${it.key}" }
    return if (parts.isNotEmpty()) parts.joinToString(" + ") else "—"
}
private fun trimZeros(n: Double): String =
    if (n == kotlin.math.floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()

private val orderDayFmt = SimpleDateFormat("dd/MM/yyyy", Locale("vi"))
private fun fmtOrderDate(iso: String?): String =
    iso?.let { runCatching { orderDayFmt.format(Date(java.time.Instant.parse(it).toEpochMilli())) }.getOrNull() } ?: "—"
