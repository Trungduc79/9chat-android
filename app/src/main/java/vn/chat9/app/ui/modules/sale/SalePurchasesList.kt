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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * List đơn nhập của NV — clone [SaleOrdersList] cho type=purchase: party = NCC,
 * search theo tên NCC, badge trạng thái thêm "received" → "Đã nhận" (mirror web
 * SalePurchasesListView). FAB tạo đơn nhập do [SaleScreen] quản lý.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalePurchasesList(onTapOrder: (Long) -> Unit = {}, listState: LazyListState = rememberLazyListState()) {
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

    fun load() {
        if (userId == null) { error = "Chưa đăng nhập"; loading = false; return }
        scope.launch {
            loading = true; error = null
            try {
                // Đơn nhập: hiện TẤT CẢ (mọi NV), giống web — không lọc theo createdByUserId.
                val res = container.vapi.listOrders(status = "", createdByUserId = null, type = "purchase", perPage = 100)
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
        // Khớp tên NCC cả khi gõ không dấu.
        val q = vnNoAccentPurchase(query.trim())
        val matchQ = q.isEmpty() || vnNoAccentPurchase(o.partyName).contains(q)
        val matchDate = if (startDateMs == null && endDateMs == null) true else {
            val d = o.completedAt ?: o.orderedAt
            val om = d?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            if (om == null) false else {
                val ok = dayKey(om)
                (startDateMs?.let { ok >= dayKey(it) } ?: true) && (endDateMs?.let { ok <= dayKey(it) } ?: true)
            }
        }
        matchQ && matchDate
    }

    Column(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        // Header: search 70% + khoảng ngày 30%
        Row(Modifier.fillMaxWidth().background(AdminColors.Card).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.7f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                    decorationBox = { inner -> if (query.isEmpty()) Text("Tìm đơn theo tên NCC", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
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
                        Text(if (orders.isEmpty()) "Chưa có đơn nhập nào — nhấn + để tạo" else "Không có đơn khớp lọc", color = AdminColors.TextMuted, fontSize = 14.sp)
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.id }) { o ->
                            PurchaseOrderRow(o, onClick = { onTapOrder(o.id) })
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
                    title = {},
                    showModeToggle = false,
                    headline = {
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
}

private fun vnNoAccentPurchase(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
        .lowercase()

/** Card đơn nhập — layout giống OrderRow đơn bán, badge thêm "received" → "Đã nhận". */
@Composable
private fun PurchaseOrderRow(o: OrderDto, onClick: () -> Unit) {
    val statusColor = when (o.status) {
        "draft" -> AdminColors.TextMuted
        "confirmed" -> AdminColors.Info
        "received", "completed" -> AdminColors.Success
        "cancelled" -> AdminColors.Danger
        else -> AdminColors.TextMuted
    }
    val statusLabel = when (o.status) {
        "draft" -> "Nháp"; "confirmed" -> "Đã xác nhận"
        "received" -> "Đã nhận"; "completed" -> "Hoàn thành"
        "cancelled" -> "Huỷ"; else -> o.status
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AdminColors.Card)
            .border(1.dp, AdminColors.Border, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(o.code, color = AdminColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(statusLabel, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(6.dp))
        // Tên NCC (OrderDto.partyName ưu tiên short_name cho đơn nhập)
        Text(o.partyName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${o.items.size} mặt hàng · ${fmtPurchaseDate(o.orderedAt ?: o.confirmedAt ?: o.completedAt)}",
                color = AdminColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("${fmtPurchaseMoney(o.totalAmount ?: 0.0)} đ", color = AdminColors.Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private val purchaseMoneyFmt = java.text.NumberFormat.getInstance(Locale("vi"))
private fun fmtPurchaseMoney(n: Double): String = purchaseMoneyFmt.format(n.toLong())

private val purchaseDayFmt = SimpleDateFormat("dd/MM/yyyy", Locale("vi"))
private fun fmtPurchaseDate(iso: String?): String =
    iso?.let { runCatching { purchaseDayFmt.format(Date(java.time.Instant.parse(it).toEpochMilli())) }.getOrNull() } ?: "—"
