package vn.chat9.app.ui.modules.accounting

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.*
import vn.chat9.app.ui.explore.AdminColors
import vn.chat9.app.ui.explore.AdminPullToRefresh
import vn.chat9.app.ui.modules.sale.SaleOrderForm
import java.text.NumberFormat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

private val nf = NumberFormat.getNumberInstance(Locale("vi"))
private fun money(n: Double): String = nf.format(n.toLong())
private fun trimZeros(n: Double): String =
    if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString().trimEnd('0').trimEnd('.')
private fun fmtDate(s: String): String = try {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.take(10))
    SimpleDateFormat("dd/MM/yyyy", Locale.US).format(d!!)
} catch (_: Exception) { s }
private fun noAccent(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('đ', 'd').replace('Đ', 'D').lowercase()

private val GOLD = Color(0xFFD4AF37)

/** Số tiền + chữ "đ" (gold, nét mảnh, in nghiêng). numColor tô cho phần số. */
private fun moneyD(amount: String, numColor: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = numColor)) { append(amount) }
    withStyle(SpanStyle(color = GOLD, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light)) { append(" đ") }
}

/**
 * Tab Công nợ (Android) — port web AccountingDebtsView + AccountingDebtDetailView.
 * Overview: toggle KH/NCC + tổng + search + list; tap → chi tiết (overlay full-screen).
 */
@Composable
fun AccountingDebtsTab() {
    var detailParty by remember { mutableStateOf<DebtOverviewRowDto?>(null) }
    if (detailParty != null) {
        AccountingDebtDetail(detailParty!!, onBack = { detailParty = null })
        return
    }

    val context = LocalContext.current
    val container = (context.applicationContext as App).container

    var partyType by remember { mutableStateOf("customer") }
    var overview by remember { mutableStateOf<DebtOverviewDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(partyType, refreshTick) {
        loading = true
        try { overview = container.vapi.debtOverview(partyType).data } catch (_: Exception) {}
        loading = false
    }

    val rows = (overview?.rows ?: emptyList()).let { list ->
        if (query.isBlank()) list else list.filter { noAccent("${it.name} ${it.code ?: ""}").contains(noAccent(query)) }
    }

    Column(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        Column(Modifier.fillMaxWidth().background(AdminColors.Card).padding(12.dp)) {
            // Toggle KH / NCC
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("customer" to "Khách hàng", "supplier" to "Nhà cung cấp").forEach { (key, label) ->
                    val sel = partyType == key
                    Text(
                        label, color = if (sel) AdminColors.Primary else AdminColors.TextMuted, fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                            .background(if (sel) AdminColors.Primary.copy(alpha = 0.12f) else AdminColors.Bg)
                            .clickable { partyType = key }.padding(vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(if (partyType == "customer") "Tổng phải thu" else "Tổng phải trả", color = AdminColors.TextMuted, fontSize = 11.sp)
                    Text(moneyD(money(overview?.totalPositive ?: 0.0), AdminColors.Text), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
                Text("${overview?.count ?: 0} đối tác", color = AdminColors.TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                    decorationBox = { inner -> if (query.isEmpty()) Text("Tìm tên / mã đối tác", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        AdminPullToRefresh(isRefreshing = loading, onRefresh = { refreshTick++ }, modifier = Modifier.weight(1f)) {
            if (loading && overview == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
            else if (rows.isEmpty()) Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), Alignment.Center) { Text("Không có công nợ.", color = AdminColors.TextMuted, fontSize = 13.sp) }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { "${it.partyType}-${it.partyId}" }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { detailParty = r }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(r.name, color = AdminColors.Text, fontSize = 14.sp, maxLines = 1)
                                if (r.pendingCount > 0) Text(
                                    "${r.pendingCount} chưa chốt", color = Color(0xFFE2A03F), fontSize = 9.sp,
                                    lineHeight = 9.sp,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFE2A03F).copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 0.dp),
                                )
                            }
                            r.code?.let { Text(it, color = AdminColors.TextMuted.copy(alpha = 0.5f), fontSize = 11.sp) }
                        }
                        // Số dư: phải thu/phải trả (≥0) xanh; trả trước (<0) đỏ + dấu −. "đ" gold nghiêng.
                        // Nhãn xám nhạt, viết hoa, căn phải theo SỐ (không theo chữ "đ").
                        val neg = r.balance < 0
                        val col = if (neg) AdminColors.Danger else AdminColors.Success
                        Row(verticalAlignment = Alignment.Top) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (neg) "−${money(-r.balance)}" else money(r.balance), color = col, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    if (neg) "Trả trước" else if (partyType == "customer") "Phải thu" else "Phải trả",
                                    color = AdminColors.TextMuted.copy(alpha = 0.5f), fontSize = 11.sp,
                                )
                            }
                            Text(" đ", color = GOLD, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light, fontSize = 14.sp)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(AdminColors.Border))
                }
            }
        }
    }
}

private data class PendingRowView(
    val showHeader: Boolean, val date: String, val docNo: String, val originTable: String, val originId: Long,
    val description: String?, val qty: Double?, val unitName: String?, val unitPrice: Double?, val debit: Double, val credit: Double,
)
private data class StmtRowView(val showHeader: Boolean, val row: DebtStatementRowDto)

/** Chi tiết công nợ 1 đối tác — 2 tab Chưa chốt / Đã chốt (mirror AccountingDebtDetailView). */
@Composable
private fun AccountingDebtDetail(party: DebtOverviewRowDto, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val perms by container.permissions.state.collectAsState()
    val canSettle = perms.bypass_all || "debt.update" in perms.permissions

    var statement by remember { mutableStateOf<DebtStatementDto?>(null) }
    var pending by remember { mutableStateOf<DebtPendingDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadTick by remember { mutableStateOf(0) }
    var posting by remember { mutableStateOf(false) }

    var editOrderId by remember { mutableStateOf<Long?>(null) }
    var backdateSources by remember { mutableStateOf<List<DebtBackdatedSourceDto>>(emptyList()) }
    var showReasons by remember { mutableStateOf(false) }

    LaunchedEffect(reloadTick) {
        loading = true
        try {
            statement = container.vapi.debtStatement(party.partyType, party.partyId).data
            pending = container.vapi.debtPending(party.partyType, party.partyId).data
        } catch (_: Exception) {}
        loading = false
    }

    val advances = pending?.pendingAdvances ?: emptyList()
    val hasBlockingAdvance = advances.isNotEmpty()
    val hasPending = (pending?.sources?.isNotEmpty() == true) || hasBlockingAdvance
    val pendingCount = pending?.pendingCount ?: 0
    val pendingNet = pending?.let { it.totalDebit - it.totalCredit } ?: 0.0
    val closing = statement?.closingBalance ?: 0.0

    var tab by remember { mutableStateOf("settled") }   // unpaid|settled
    LaunchedEffect(hasPending) { if (hasPending) tab = "unpaid" }
    val showUnpaid = hasPending && tab == "unpaid"

    val pendingRows = remember(pending) {
        buildList {
            for (src in pending?.sources ?: emptyList()) src.rows.forEachIndexed { ri, r ->
                add(PendingRowView(ri == 0, src.accountingDate, src.docNo ?: "", src.originTable, src.originId, r.description, r.qty, r.unitName, r.unitPrice, r.bornDebt, r.bornCredit))
            }
        }
    }
    val stmtRows = remember(statement) {
        var pDate = ""; var pDoc = ""
        (statement?.rows ?: emptyList()).map { r ->
            val show = !(r.date == pDate && (r.docNo ?: "") == pDoc); pDate = r.date; pDoc = r.docNo ?: ""
            StmtRowView(show, r)
        }
    }

    fun doPost(reasons: Map<String, String>) {
        posting = true
        scope.launch {
            try {
                val res = container.vapi.debtPost(DebtPostRequest(party.partyType, party.partyId, reasons)).data
                Toast.makeText(context, "Đã chốt ${res?.rowsCreated ?: 0} dòng vào sổ cái", Toast.LENGTH_SHORT).show()
                showReasons = false; tab = "settled"; reloadTick++
            } catch (_: Exception) {
                Toast.makeText(context, "Chốt công nợ thất bại", Toast.LENGTH_SHORT).show()
            } finally { posting = false }
        }
    }
    fun onPostClick() {
        if (!canSettle || hasBlockingAdvance) return
        posting = true
        scope.launch {
            try {
                val preview = container.vapi.debtBackdatedPreview(party.partyType, party.partyId).data
                if ((preview?.backdatedCount ?: 0) > 0) { backdateSources = preview!!.sources; showReasons = true; posting = false }
                else doPost(emptyMap())
            } catch (_: Exception) {
                Toast.makeText(context, "Không kiểm tra được nguồn lùi ngày", Toast.LENGTH_SHORT).show(); posting = false
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) { if (editOrderId != null) editOrderId = null else onBack() }

    Box(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        Column(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var acc = 0f
                detectHorizontalDragGestures(onDragEnd = { if (acc > 90f) onBack(); acc = 0f }, onDragCancel = { acc = 0f }) { _, dx -> acc += dx }
            },
        ) {
            // Header
            Column(Modifier.fillMaxWidth().background(AdminColors.Card)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ArrowBack, "Quay lại", tint = AdminColors.Text, modifier = Modifier.clickable { onBack() }.padding(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(party.name, color = AdminColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(if (party.partyType == "customer") "Khách hàng" else "Nhà cung cấp", color = AdminColors.TextMuted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Số dư hiện tại", color = AdminColors.TextMuted, fontSize = 10.sp)
                        Text(moneyD(money(closing), AdminColors.Text), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (hasPending) Row(Modifier.fillMaxWidth()) {
                    listOf("unpaid" to ("Chưa chốt" + if (pendingCount > 0) " ($pendingCount)" else ""), "settled" to "Đã chốt").forEach { (key, label) ->
                        val sel = tab == key
                        Text(
                            label, color = if (sel) AdminColors.Primary else AdminColors.TextMuted, fontSize = 13.sp,
                            fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f).clickable { tab = key }
                                .then(if (sel) Modifier.background(AdminColors.Primary.copy(alpha = 0.08f)) else Modifier)
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }

            if (loading && statement == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
            } else if (showUnpaid) {
                UnpaidTab(pendingRows, advances, hasBlockingAdvance, pendingNet, canSettle, posting, onOpenOrder = { editOrderId = it }, onPost = { onPostClick() })
            } else {
                SettledTab(statement, stmtRows, closing)
            }
        }

        // Overlay lý do lùi ngày
        if (showReasons) BackdateReasonsOverlay(
            sources = backdateSources, posting = posting,
            onClose = { showReasons = false }, onConfirm = { doPost(it) },
        )

        // Overlay sửa/xem đơn (tap số đơn ở tab Chưa chốt) — reuse SaleOrderForm.
        editOrderId?.let { oid ->
            Column(Modifier.fillMaxSize().background(AdminColors.Bg)) {
                Row(Modifier.fillMaxWidth().background(AdminColors.Card).height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ArrowBack, "Đóng", tint = AdminColors.Text, modifier = Modifier.clickable { editOrderId = null }.padding(8.dp))
                    Text("Chi tiết đơn", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                SaleOrderForm(orderId = oid, allowEditAnyStatus = true, onDone = { editOrderId = null; reloadTick++ })
            }
        }
    }
}

@Composable
private fun ColumnScope.UnpaidTab(
    rows: List<PendingRowView>, advances: List<DebtPendingAdvanceDto>, hasBlockingAdvance: Boolean,
    pendingNet: Double, canSettle: Boolean, posting: Boolean, onOpenOrder: (Long) -> Unit, onPost: () -> Unit,
) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
        if (hasBlockingAdvance) item {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(6.dp)).border(0.5.dp, Color(0xFFE2A03F), RoundedCornerShape(6.dp)).background(Color(0xFFE2A03F).copy(alpha = 0.08f)).padding(10.dp)) {
                Text("⚠️ Có ${advances.size} khoản ứng ship CHỜ HOÀN ỨNG — không thể chốt tới khi hoàn đủ.", color = Color(0xFFE2A03F), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                advances.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(a.orderCode ?: a.code, color = AdminColors.TextMuted, fontSize = 11.sp)
                        Text(a.description ?: "", color = AdminColors.Text, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("còn ${money(a.remaining)}", color = Color(0xFFE2A03F), fontSize = 11.sp)
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Text("Không có công nợ chưa chốt.", color = AdminColors.TextMuted, fontSize = 13.sp) } }
        itemsIndexed(rows) { idx, r -> LedgerRow(idx, r.showHeader, r.date, r.docNo, r.description, r.qty, r.unitName, r.unitPrice, r.debit, r.credit, clickableDoc = r.originTable == "orders" && r.originId > 0, onDocClick = { onOpenOrder(r.originId) }) }
        item {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(0.5.dp).background(AdminColors.Border))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tổng chưa chốt", color = AdminColors.TextMuted, fontSize = 14.sp)
                Text(moneyD(money(pendingNet), AdminColors.Text), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Text("Bấm số đơn để mở đơn và chỉnh sửa trước khi chốt.", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Button(
                onClick = onPost, enabled = canSettle && !hasBlockingAdvance && !posting,
                colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text(if (posting) "Đang chốt…" else "Chốt công nợ") }
            if (!canSettle) Text("Tài khoản của bạn không có quyền chốt công nợ.", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ColumnScope.SettledTab(statement: DebtStatementDto?, rows: List<StmtRowView>, closing: Double) {
    if (statement == null) { Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text("—", color = AdminColors.TextMuted) }; return }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Đầu kỳ", color = AdminColors.TextMuted, fontSize = 13.sp)
                Text(money(statement.openingBalance), color = AdminColors.Text, fontSize = 13.sp)
            }
            Text("${fmtDate(statement.period.from)} – ${fmtDate(statement.period.to)}", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (rows.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Text("Chưa có phát sinh trong kỳ.", color = AdminColors.TextMuted, fontSize = 13.sp) } }
        itemsIndexed(rows) { idx, sr ->
            val r = sr.row
            LedgerRow(idx, sr.showHeader, r.date, r.docNo ?: "", r.description ?: r.origin, r.qty, r.unitName, r.unitPrice, r.debit, r.credit, clickableDoc = false, onDocClick = {})
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Số dư cuối kỳ", color = AdminColors.TextMuted, fontSize = 14.sp)
                Text(moneyD(money(closing), AdminColors.Text), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** 1 dòng sổ (dùng chung Chưa chốt / Đã chốt): viền trên vàng ở đầu chứng từ, mờ ở dòng
 *  cùng chứng từ; header ngày+số CT, tên variant, SL×đơn giá, tiền (+nợ đỏ / −trả xanh). */
@Composable
private fun LedgerRow(
    idx: Int, showHeader: Boolean, date: String, docNo: String, description: String?,
    qty: Double?, unitName: String?, unitPrice: Double?, debit: Double, credit: Double,
    clickableDoc: Boolean, onDocClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (idx > 0) Box(Modifier.fillMaxWidth().height(if (showHeader) 1.dp else 0.5.dp).background(if (showHeader) GOLD else AdminColors.Border))
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (showHeader) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fmtDate(date), color = AdminColors.TextMuted, fontSize = 11.sp)
                if (docNo.isNotBlank()) Text(
                    docNo, color = AdminColors.Primary, fontSize = 11.sp,
                    modifier = if (clickableDoc) Modifier.clickable(onClick = onDocClick) else Modifier,
                )
            }
            Text(description ?: "—", color = AdminColors.Text, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).padding(start = 6.dp)) {
                    if (qty != null && qty != 0.0) {
                        Text(trimZeros(qty), color = AdminColors.Text, fontSize = 11.sp)
                        unitName?.let { Text("  $it", color = AdminColors.TextMuted, fontSize = 11.sp) }
                        if (unitPrice != null && unitPrice != 0.0) Text("  × ${money(unitPrice)}", color = AdminColors.TextMuted, fontSize = 11.sp)
                    }
                }
                if (debit > 0) Text("+${money(debit)}", color = AdminColors.Danger, fontSize = 14.sp)
                if (credit > 0) Text("−${money(credit)}", color = AdminColors.Success, fontSize = 14.sp)
            }
        }
    }
}

/** Overlay nhập lý do cho các nguồn lùi ngày (bắt buộc đủ mới cho chốt). */
@Composable
private fun BackdateReasonsOverlay(sources: List<DebtBackdatedSourceDto>, posting: Boolean, onClose: () -> Unit, onConfirm: (Map<String, String>) -> Unit) {
    val reasons = remember { mutableStateMapOf<String, String>().apply { sources.forEach { put(it.originKey, "") } } }
    val allFilled = sources.all { (reasons[it.originKey] ?: "").isNotBlank() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(12.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(16.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()).clickable(enabled = false) {},
        ) {
            Text("Lý do chốt lùi ngày", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Có phát sinh trước ngày chốt gần nhất — nhập lý do cho từng nguồn.", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            sources.forEach { s ->
                Text("${s.sourceType} · ${fmtDate(s.accountingDate)}", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                Box(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(10.dp)) {
                    BasicTextField(
                        value = reasons[s.originKey] ?: "", onValueChange = { reasons[s.originKey] = it },
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(AdminColors.Primary),
                        decorationBox = { inner -> if ((reasons[s.originKey] ?: "").isEmpty()) Text("Lý do…", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(
                onClick = { onConfirm(reasons.toMap()) }, enabled = allFilled && !posting,
                colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text(if (posting) "Đang chốt…" else "Xác nhận chốt") }
        }
    }
}
