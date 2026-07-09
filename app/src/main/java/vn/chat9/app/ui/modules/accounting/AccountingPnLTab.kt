package vn.chat9.app.ui.modules.accounting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.*
import vn.chat9.app.ui.explore.AdminColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val vnNum = NumberFormat.getIntegerInstance(Locale("vi"))
private fun fmtMoney(n: Double): String = vnNum.format(Math.round(n))
private fun trimNum(n: Double): String =
    if (n == Math.floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()
private fun pct(n: Double): String = trimNum(n) + "%"
private fun fmtDate(s: String?): String {
    if (s.isNullOrBlank()) return "—"
    return try { LocalDate.parse(s.substring(0, 10)).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) } catch (_: Exception) { s }
}
private fun profitColor(n: Double): Color = if (n >= 0) AdminColors.Success else AdminColors.Danger

/**
 * Tab "Lãi Lỗ" (mặc định) app Kế toán Android — mirror web AccountingPnLView.
 * Dùng chung API reports/pnl*; kỳ preset Tháng/Quý/Năm (dropdown) + cơ sở Dồn tích/Tiền thực.
 * 3 sub-tab: Chung (KPI + waterfall + chi phí danh mục + lãi ròng/tháng + top SP) ·
 * Theo đơn (Ngày·Khách·Lãi ròng, tap → chi tiết đơn) · Theo khách (Khách·Số đơn·Lãi ròng).
 * DEFER (advanced desktop): truy vết nguồn giá vốn FIFO, sửa lô, split-view — không cần trên mobile.
 */
@Composable
fun AccountingPnLTab() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    val today = remember { LocalDate.now() }
    var periodMode by remember { mutableStateOf("month") } // month|quarter|year
    var year by remember { mutableStateOf(today.year) }
    var month by remember { mutableStateOf(today.monthValue) }
    var quarter by remember { mutableStateOf((today.monthValue - 1) / 3 + 1) }
    var basis by remember { mutableStateOf("accrual") } // accrual|cash
    var compare by remember { mutableStateOf(false) }
    var subTab by remember { mutableStateOf("general") } // general|orders|customers

    var loading by remember { mutableStateOf(false) }
    var stmt by remember { mutableStateOf<PnLStatementDto?>(null) }
    var prev by remember { mutableStateOf<PnLStatementDto?>(null) }
    var timeline by remember { mutableStateOf<List<PnLTimelineRowDto>>(emptyList()) }
    var categories by remember { mutableStateOf<List<PnLCategoryRowDto>>(emptyList()) }
    var topProducts by remember { mutableStateOf<List<PnLTopProductDto>>(emptyList()) }
    var orders by remember { mutableStateOf<PnLOrdersReportDto?>(null) }
    var customers by remember { mutableStateOf<PnLCustomersReportDto?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var detailOrder by remember { mutableStateOf<PnLOrderDetailDto?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailOpen by remember { mutableStateOf(false) }
    var custDetail by remember { mutableStateOf<PnLCustomerRowDto?>(null) }

    fun curRange(): Pair<String, String> = when (periodMode) {
        "month" -> { val ym = YearMonth.of(year, month); ym.atDay(1).toString() to ym.atEndOfMonth().toString() }
        "quarter" -> { val sm = (quarter - 1) * 3 + 1; LocalDate.of(year, sm, 1).toString() to YearMonth.of(year, sm).plusMonths(2).atEndOfMonth().toString() }
        else -> LocalDate.of(year, 1, 1).toString() to LocalDate.of(year, 12, 31).toString()
    }
    fun prevRange(): Pair<String, String> = when (periodMode) {
        "month" -> { val ym = YearMonth.of(year, month).minusMonths(1); ym.atDay(1).toString() to ym.atEndOfMonth().toString() }
        "quarter" -> { val sm = (quarter - 1) * 3 + 1; val s = LocalDate.of(year, sm, 1).minusMonths(3); val ym = YearMonth.from(s); s.toString() to ym.plusMonths(2).atEndOfMonth().toString() }
        else -> LocalDate.of(year - 1, 1, 1).toString() to LocalDate.of(year - 1, 12, 31).toString()
    }

    LaunchedEffect(periodMode, year, month, quarter, basis, compare) {
        loading = true; errorMsg = null
        try {
            val (from, to) = curRange()
            stmt = container.vapi.pnlStatement(from, to, basis).data
            timeline = container.vapi.pnlTimeline("$year-01-01", "$year-12-31", "month", basis).data ?: emptyList()
            categories = container.vapi.pnlByCategory(from, to, basis).data ?: emptyList()
            topProducts = container.vapi.pnlTopProducts(from, to, 10).data ?: emptyList()
            prev = if (compare) { val (pf, pt) = prevRange(); container.vapi.pnlStatement(pf, pt, basis).data } else null
            if (basis == "accrual") {
                orders = container.vapi.pnlOrders(from, to).data
                customers = container.vapi.pnlCustomers(from, to).data
            } else { orders = null; customers = null }
        } catch (e: Exception) {
            errorMsg = e.message ?: "Không tải được báo cáo lãi lỗ"
            stmt = null
        } finally { loading = false }
    }

    fun openOrder(id: Long) {
        detailOpen = true; detailLoading = true; detailOrder = null
        scope.launch {
            try { detailOrder = container.vapi.pnlOrder(id).data }
            catch (_: Exception) { detailOpen = false }
            finally { detailLoading = false }
        }
    }

    val monthOpts = (1..12).map { "Tháng $it" to it.toString() }
    val quarterOpts = (1..4).map { "Quý $it" to it.toString() }
    val yearOpts = (0..4).map { val y = today.year - it; y.toString() to y.toString() }

    Box(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            // ===== Toolbar =====
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PnlSelect(
                        selected = when (periodMode) { "month" -> "Theo tháng"; "quarter" -> "Theo quý"; else -> "Theo năm" },
                        options = listOf("Theo tháng" to "month", "Theo quý" to "quarter", "Theo năm" to "year"),
                        modifier = Modifier.weight(1f),
                    ) { periodMode = it }
                    if (periodMode == "month") PnlSelect("Tháng $month", monthOpts, Modifier.weight(1f)) { month = it.toInt() }
                    if (periodMode == "quarter") PnlSelect("Quý $quarter", quarterOpts, Modifier.weight(1f)) { quarter = it.toInt() }
                    PnlSelect(year.toString(), yearOpts, Modifier.width(84.dp)) { year = it.toInt() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PnlSelect(
                        selected = if (basis == "accrual") "Dồn tích" else "Tiền thực",
                        options = listOf("Dồn tích" to "accrual", "Tiền thực" to "cash"),
                        modifier = Modifier.weight(1f),
                    ) { basis = it }
                    Spacer(Modifier.width(4.dp))
                    Text("So kỳ trước", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Switch(
                        checked = compare, onCheckedChange = { compare = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AdminColors.White, checkedTrackColor = AdminColors.Primary),
                    )
                }
            }

            if (basis == "cash") {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cơ sở \"Tiền thực\" đang hoàn thiện: mới lọc chi phí đã chi; doanh thu/giá vốn vẫn theo ngày đơn.",
                    color = AdminColors.Warning, fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(10.dp))

            if (loading && stmt == null) {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
            } else if (errorMsg != null && stmt == null) {
                Text(errorMsg!!, color = AdminColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 40.dp))
            } else if (stmt != null) {
                val s = stmt!!
                // ===== KPI 2x2 =====
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpiCard("Doanh thu thuần", fmtMoney(s.revenue.netRevenue), Modifier.weight(1f),
                        delta = deltaPct(compare, s.revenue.netRevenue, prev?.revenue?.netRevenue))
                    KpiCard("Lãi gộp (${pct(s.grossMarginPercent)})", fmtMoney(s.grossProfit), Modifier.weight(1f),
                        delta = deltaPct(compare, s.grossProfit, prev?.grossProfit))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpiCard("Tổng chi phí trong kỳ",
                        fmtMoney(s.operatingExpenses.totalOpex + s.financialExpenses.loanInterest + s.taxInvoiceCosts.total),
                        Modifier.weight(1f), valueColor = AdminColors.Warning, sub = "Vận hành + lãi vay + thuế & HĐ")
                    KpiCard("Lãi ròng (${pct(s.netMarginPercent)})", fmtMoney(s.netProfit), Modifier.weight(1f),
                        valueColor = profitColor(s.netProfit), highlight = s.netProfit >= 0,
                        highlightBad = s.netProfit < 0, delta = deltaPct(compare, s.netProfit, prev?.netProfit))
                }

                Spacer(Modifier.height(12.dp))
                // ===== Sub-tab chips =====
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubChip("Chung", subTab == "general") { subTab = "general" }
                    SubChip("Theo đơn", subTab == "orders") { subTab = "orders" }
                    SubChip("Theo khách", subTab == "customers") { subTab = "customers" }
                }
                Spacer(Modifier.height(10.dp))

                when (subTab) {
                    "general" -> GeneralTab(s, categories, timeline, topProducts, year)
                    "orders" -> OrdersTab(orders) { openOrder(it) }
                    "customers" -> CustomersTab(customers) { custDetail = it }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // ===== Overlay chi tiết đơn =====
        if (detailOpen) {
            OrderDetailOverlay(detailOrder, detailLoading) { detailOpen = false }
        }
        // ===== Overlay chi tiết khách (danh sách đơn của khách) =====
        custDetail?.let { c ->
            CustomerDetailOverlay(
                row = c,
                orders = (orders?.rows ?: emptyList()).filter { it.customer?.id == c.customerId },
                onOrder = { openOrder(it) },
                onClose = { custDetail = null },
            )
        }
    }
}

/** Δ% kỳ trước (dương=tăng), null nếu không so hoặc prev=0. */
private fun deltaPct(compare: Boolean, cur: Double, prev: Double?): Double? {
    if (!compare || prev == null || prev == 0.0) return null
    return Math.round((cur - prev) / Math.abs(prev) * 1000.0) / 10.0
}

@Composable
private fun KpiCard(
    label: String, value: String, modifier: Modifier = Modifier,
    valueColor: Color = AdminColors.Text, sub: String? = null, delta: Double? = null,
    highlight: Boolean = false, highlightBad: Boolean = false,
) {
    val bg = when { highlight -> AdminColors.Success.copy(alpha = 0.10f); highlightBad -> AdminColors.Danger.copy(alpha = 0.10f); else -> AdminColors.Card }
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(10.dp)) {
        Text(label, color = AdminColors.TextMuted, fontSize = 11.sp, maxLines = 1)
        Text(fmtSuffix(value), color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (sub != null) Text(sub, color = AdminColors.TextMuted, fontSize = 10.sp)
        if (delta != null) {
            val up = delta >= 0
            Text((if (up) "▲ " else "▼ ") + Math.abs(delta) + "%", color = if (up) AdminColors.Success else AdminColors.Danger, fontSize = 10.sp)
        }
    }
}

/** Thêm " đ" sau số tiền (quy tắc hiển thị đ). */
private fun fmtSuffix(v: String): String = "$v đ"

@Composable
private fun SubChip(label: String, sel: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (sel) AdminColors.Primary else AdminColors.TextMuted, fontSize = 13.sp,
        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (sel) AdminColors.Primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Dropdown tối tái dùng: nhãn hiện tại + list (label,value). */
@Composable
private fun PnlSelect(selected: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(AdminColors.Surface)
                .clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected, color = AdminColors.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("▾", color = AdminColors.TextMuted, fontSize = 12.sp)
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label, color = if (label == selected) AdminColors.Primary else AdminColors.Text, fontSize = 13.sp) },
                        onClick = { open = false; onSelect(value) },
                        colors = MenuDefaults.itemColors(textColor = AdminColors.Text),
                    )
                }
            }
        }
    }
}

// ===================== Sub-tab CHUNG =====================
@Composable
private fun GeneralTab(s: PnLStatementDto, categories: List<PnLCategoryRowDto>, timeline: List<PnLTimelineRowDto>, topProducts: List<PnLTopProductDto>, year: Int) {
    // Waterfall
    PnlCard("Kết quả kinh doanh") {
        WRow("Doanh thu bán hàng", s.revenue.grossRevenue, muted = true)
        if (s.revenue.customerReturns != 0.0) WRow("− Trả hàng", s.revenue.customerReturns, muted = true, indent = 1)
        if (s.revenue.revenueDeduction != 0.0) WRow("− Chiết khấu doanh thu", s.revenue.revenueDeduction, muted = true, indent = 1)
        WRow("Doanh thu thuần", s.revenue.netRevenue, bold = true, topBorder = true)
        WRow("− Giá vốn hàng bán", s.cogs.netCogs, muted = true)
        WRow("Lãi gộp (${pct(s.grossMarginPercent)})", s.grossProfit, bold = true, topBorder = true, color = AdminColors.Success)
        WRow("− Chi phí vận hành", s.operatingExpenses.operatingBase, muted = true)
        s.operatingExpenses.breakdown.forEach { WRow("· ${it.name} (${it.entries})", it.amount, muted = true, indent = 2, small = true) }
        if (s.operatingExpenses.marketingDiscount != 0.0) WRow("− Chiết khấu marketing", s.operatingExpenses.marketingDiscount, muted = true, indent = 1)
        if (s.operatingExpenses.loyaltyDiscount != 0.0) WRow("− Tích điểm/loyalty", s.operatingExpenses.loyaltyDiscount, muted = true, indent = 1)
        if (s.operatingExpenses.returnsWriteoff != 0.0) WRow("− Hao hụt trả hàng", s.operatingExpenses.returnsWriteoff, muted = true, indent = 1)
        WRow("Lãi hoạt động (${pct(s.operatingMarginPercent)})", s.operatingProfit, bold = true, topBorder = true)
        if (s.financialExpenses.loanInterest != 0.0) WRow("− Lãi vay", s.financialExpenses.loanInterest, muted = true)
        WRow("Chi phí thuế & hóa đơn", null, bold = true)
        WRow("− Phí hóa đơn", s.taxInvoiceCosts.invoiceFee, muted = true, indent = 1)
        WRow("− VAT phải nộp", s.taxInvoiceCosts.vatPayable, muted = true, indent = 1)
        WRow("− Thuế TNDN", s.taxInvoiceCosts.cit, muted = true, indent = 1)
        WRow("Lãi ròng (${pct(s.netMarginPercent)})", s.netProfit, bold = true, topBorder = true, color = profitColor(s.netProfit))
    }

    Spacer(Modifier.height(12.dp))
    PnlCard("Chi phí theo danh mục") {
        if (categories.isEmpty()) {
            Text("Không có chi phí trong kỳ", color = AdminColors.TextMuted, fontSize = 13.sp)
        } else {
            val catMax = (categories.maxOfOrNull { it.totalAmount } ?: 1.0).coerceAtLeast(1.0)
            categories.take(12).forEach { c ->
                Column(Modifier.padding(bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.name, color = AdminColors.Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("${fmtMoney(c.totalAmount)} đ", color = AdminColors.TextMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AdminColors.Bg)) {
                        Box(Modifier.fillMaxWidth((c.totalAmount / catMax).toFloat().coerceIn(0f, 1f)).height(6.dp)
                            .clip(RoundedCornerShape(3.dp)).background(AdminColors.Primary.copy(alpha = 0.7f)))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    PnlCard("Lãi ròng theo tháng — $year") {
        val tlMax = (timeline.maxOfOrNull { Math.abs(it.netProfit) } ?: 1.0).coerceAtLeast(1.0)
        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            timeline.forEach { r ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text("${Math.round(r.netProfit / 1000.0)}k", color = profitColor(r.netProfit), fontSize = 8.sp, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.fillMaxWidth().fillMaxHeight((Math.abs(r.netProfit) / tlMax).toFloat().coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(profitColor(r.netProfit).copy(alpha = 0.6f)))
                    Text(r.label.takeLast(2), color = AdminColors.TextMuted, fontSize = 8.sp)
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    PnlCard("Top sản phẩm theo lãi gộp") {
        if (topProducts.isEmpty()) {
            Text("Không có bán hàng trong kỳ", color = AdminColors.TextMuted, fontSize = 13.sp)
        } else topProducts.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(p.productName, color = AdminColors.Text, fontSize = 13.sp, maxLines = 1)
                Row {
                    Text("SL ${trimNum(p.qtySold)} · DT ${fmtMoney(p.revenue)}", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("Lãi ${fmtMoney(p.grossProfit)} (${pct(p.grossMarginPercent)})", color = AdminColors.Success, fontSize = 11.sp)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(AdminColors.Border).padding(top = 4.dp))
            }
        }
    }
}

/** 1 dòng waterfall: nhãn trái + tiền phải (null = chỉ nhãn header). */
@Composable
private fun WRow(label: String, value: Double?, muted: Boolean = false, bold: Boolean = false, indent: Int = 0, small: Boolean = false, topBorder: Boolean = false, color: Color? = null) {
    if (topBorder) Box(Modifier.fillMaxWidth().height(0.5.dp).background(AdminColors.Border))
    Row(Modifier.fillMaxWidth().padding(vertical = if (small) 1.dp else 3.dp, horizontal = 0.dp).padding(start = (indent * 12).dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color ?: if (muted) AdminColors.TextMuted else AdminColors.Text, fontSize = if (small) 11.sp else 13.sp,
            fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (value != null) Text("${fmtMoney(value)} đ", color = color ?: if (muted) AdminColors.TextMuted else AdminColors.Text,
            fontSize = if (small) 11.sp else 13.sp, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)
    }
}

// ===================== Sub-tab THEO ĐƠN =====================
@Composable
private fun OrdersTab(orders: PnLOrdersReportDto?, onOrder: (Long) -> Unit) {
    if (orders == null) {
        Text("Chi tiết theo đơn chỉ khả dụng ở cơ sở \"Dồn tích\".", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
        return
    }
    PnlCard("Chi tiết theo đơn (${orders.count})") {
        Text("Lãi ròng đơn (DT − giá vốn − ship chưa hoàn − chiết khấu). Bấm 1 đơn để xem chi tiết.", color = AdminColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Ngày", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(84.dp))
            Text("Khách hàng", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Lãi ròng", color = AdminColors.TextMuted, fontSize = 11.sp)
        }
        orders.rows.forEach { r ->
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(AdminColors.Border))
            Row(Modifier.fillMaxWidth().clickable { onOrder(r.orderId) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtDate(r.orderedAt), color = AdminColors.Text, fontSize = 12.sp, modifier = Modifier.width(84.dp))
                Text(r.customer?.name ?: "Khách lẻ", color = AdminColors.Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("${fmtMoney(r.netProfit)} đ", color = profitColor(r.netProfit), fontSize = 12.sp)
            }
        }
        if (orders.rows.isEmpty()) Text("Không có đơn nào", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
        else {
            Box(Modifier.fillMaxWidth().height(1.dp).background(AdminColors.Divider))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Tổng (${orders.rows.size} đơn)", color = AdminColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${fmtMoney(orders.totals.netProfit)} đ", color = profitColor(orders.totals.netProfit), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ===================== Sub-tab THEO KHÁCH =====================
@Composable
private fun CustomersTab(customers: PnLCustomersReportDto?, onCustomer: (PnLCustomerRowDto) -> Unit) {
    if (customers == null) {
        Text("Chi tiết theo khách chỉ khả dụng ở cơ sở \"Dồn tích\".", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
        return
    }
    PnlCard("Chi tiết theo khách (${customers.count})") {
        Text("Lãi ròng gộp mọi đơn của khách trong kỳ. Bấm 1 khách để xem chi tiết.", color = AdminColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Khách hàng", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Số đơn", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(52.dp))
            Text("Lãi ròng", color = AdminColors.TextMuted, fontSize = 11.sp)
        }
        customers.rows.forEach { r ->
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(AdminColors.Border))
            Row(Modifier.fillMaxWidth().clickable { onCustomer(r) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.customerName ?: "Khách lẻ", color = AdminColors.Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("${r.orderCount}", color = AdminColors.Text, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                Text("${fmtMoney(r.netProfit)} đ", color = profitColor(r.netProfit), fontSize = 12.sp)
            }
        }
        if (customers.rows.isEmpty()) Text("Không có khách nào", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
        else {
            Box(Modifier.fillMaxWidth().height(1.dp).background(AdminColors.Divider))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Tổng (${customers.rows.size} khách)", color = AdminColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${fmtMoney(customers.totals.netProfit)} đ", color = profitColor(customers.totals.netProfit), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Card có tiêu đề. */
@Composable
private fun PnlCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(12.dp)) {
        Text(title, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

// ===================== Overlay chi tiết đơn =====================
@Composable
private fun OrderDetailOverlay(o: PnLOrderDetailDto?, loading: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { onClose() } }) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.96f).fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(12.dp)).background(AdminColors.Card)
                .pointerInput(Unit) { detectTapGestures {} }.padding(14.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (o != null) "Đơn ${o.code}" else "Chi tiết đơn", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("Đóng", color = AdminColors.Primary, fontSize = 13.sp, modifier = Modifier.clickable { onClose() }.padding(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
                o == null -> {}
                !o.applicable -> Text("Đơn này không tính được lãi/lỗ (${o.reason ?: "—"}).", color = AdminColors.TextMuted, fontSize = 13.sp)
                else -> {
                    Text("Khách: ${o.customer?.name ?: "Khách lẻ"}  ·  ${fmtDate(o.orderedAt)}", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("DT thuần ${fmtMoney(o.revenue.net)} đ  ·  Giá vốn ${fmtMoney(o.cogs)} đ  ·  Lãi gộp ${fmtMoney(o.grossProfit)} đ (${pct(o.grossMarginPercent)})",
                        color = AdminColors.Text, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    // Bảng mặt hàng — cuộn ngang (nhiều cột)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Column {
                            Row(Modifier.padding(vertical = 4.dp)) {
                                DCell("Mặt hàng", 150, header = true)
                                DCell("SL", 44, header = true, end = true)
                                DCell("Đơn vị", 60, header = true)
                                DCell("Giá nhập", 84, header = true, end = true)
                                DCell("Giá bán", 84, header = true, end = true)
                                DCell("Doanh thu", 96, header = true, end = true)
                                DCell("Giá vốn", 96, header = true, end = true)
                                DCell("Lãi", 96, header = true, end = true)
                                DCell("Biên", 56, header = true, end = true)
                            }
                            o.items.forEach { it ->
                                Box(Modifier.width(816.dp).height(0.5.dp).background(AdminColors.Border))
                                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    DCell(it.name ?: "—", 150)
                                    DCell(trimNum(it.qtyUnit), 44, end = true)
                                    DCell(it.unitName ?: "—", 60)
                                    DCell(fmtMoney(it.costEach), 84, end = true, muted = true)
                                    DCell(fmtMoney(it.unitPrice), 84, end = true)
                                    DCell(fmtMoney(it.lineRevenue), 96, end = true)
                                    DCell(fmtMoney(it.lineCogs), 96, end = true, muted = true)
                                    DCell(fmtMoney(it.lineProfit), 96, end = true, color = profitColor(it.lineProfit))
                                    DCell(pct(it.marginPercent), 56, end = true)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(AdminColors.Divider))
                    val shipNote = buildString {
                        append("Chi ship: ứng hộ khách chưa hoàn ${fmtMoney(o.ship.openAdvance)}")
                        if (o.ship.actual != 0.0) append(" + kho chịu ${fmtMoney(o.ship.actual)}")
                        if (o.ship.reimbursedAdvance != 0.0) append(" · đã hoàn ${fmtMoney(o.ship.reimbursedAdvance)} (loại)")
                    }
                    SumRow(shipNote, fmtMoney(o.ship.margin), color = if (o.ship.margin < 0) AdminColors.Danger else AdminColors.Text)
                    if (o.otherDiscount != 0.0) SumRow("Chiết khấu (marketing/vận hành)", "−${fmtMoney(o.otherDiscount)}", color = AdminColors.Danger)
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(AdminColors.Border))
                    SumRow("Lãi ròng đơn (${pct(o.netMarginPercent)})", fmtMoney(o.netProfit), bold = true, color = profitColor(o.netProfit))
                }
            }
        }
    }
}

@Composable
private fun DCell(text: String, width: Int, header: Boolean = false, end: Boolean = false, muted: Boolean = false, color: Color? = null) {
    Text(
        text,
        color = color ?: if (header || muted) AdminColors.TextMuted else AdminColors.Text,
        fontSize = if (header) 11.sp else 12.sp,
        maxLines = 1,
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
        modifier = Modifier.width(width.dp).padding(horizontal = 3.dp),
    )
}

@Composable
private fun SumRow(label: String, value: String, bold: Boolean = false, color: Color = AdminColors.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("$value đ", color = color, fontSize = 12.sp, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)
    }
}

// ===================== Overlay chi tiết khách =====================
@Composable
private fun CustomerDetailOverlay(row: PnLCustomerRowDto, orders: List<PnLOrderRowDto>, onOrder: (Long) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { onClose() } }) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.96f).fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(12.dp)).background(AdminColors.Card)
                .pointerInput(Unit) { detectTapGestures {} }.padding(14.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Khách: ${row.customerName ?: "Khách lẻ"}", color = AdminColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("Đóng", color = AdminColors.Primary, fontSize = 13.sp, modifier = Modifier.clickable { onClose() }.padding(6.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text("Số đơn ${row.orderCount}  ·  Lãi ròng ${fmtMoney(row.netProfit)} đ", color = AdminColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            if (orders.isEmpty()) Text("Không có đơn nào (khác kỳ hoặc chưa tải).", color = AdminColors.TextMuted, fontSize = 13.sp)
            orders.forEach { r ->
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(AdminColors.Border))
                Row(Modifier.fillMaxWidth().clickable { onOrder(r.orderId) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.code, color = AdminColors.Primary, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                    Text(fmtDate(r.orderedAt), color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${fmtMoney(r.netProfit)} đ", color = profitColor(r.netProfit), fontSize = 12.sp)
                }
            }
        }
    }
}
