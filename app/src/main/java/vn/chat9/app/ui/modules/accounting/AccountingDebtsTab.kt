package vn.chat9.app.ui.modules.accounting

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.*
import vn.chat9.app.ui.common.partyColor
import vn.chat9.app.ui.explore.AdminColors
import vn.chat9.app.ui.explore.AdminPullToRefresh
import vn.chat9.app.ui.modules.sale.SaleOrderForm
import vn.chat9.app.ui.modules.sale.saveBitmapToGallery
import java.text.NumberFormat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val nf = NumberFormat.getNumberInstance(Locale("vi"))
private fun money(n: Double): String = nf.format(n.toLong())
private fun trimZeros(n: Double): String =
    if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString().trimEnd('0').trimEnd('.')

/** Nhập tiền "theo nghìn": có thập phân/số <1000 → ×1000; ≥4 chữ số giữ số thật; nhiều dấu = phân cách nghìn. */
private fun expandMoneyShorthand(raw: String): Double? {
    val cleaned = raw.filter { it.isDigit() || it == '.' || it == ',' }
    if (cleaned.isEmpty()) return null
    val sepCount = cleaned.count { it == '.' || it == ',' }
    val value: Double = when {
        sepCount >= 2 -> cleaned.filter { it.isDigit() }.toDoubleOrNull() ?: return null
        sepCount == 1 -> (cleaned.replace(',', '.').toDoubleOrNull() ?: return null) * 1000
        else -> {
            val n = cleaned.toDoubleOrNull() ?: return null
            if (n < 1000) n * 1000 else n
        }
    }
    if (value.isNaN() || value.isInfinite()) return null
    return Math.round(value).toDouble()
}
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
 * Số tiền căn phải với ô "đ" rộng cố định → mép phải của SỐ giữa các dòng (đầu kỳ,
 * cuối kỳ) luôn thẳng hàng, không lệch theo bề rộng chữ "đ". showDong=false: ẩn đ
 * nhưng vẫn giữ chỗ để căn.
 */
@Composable
private fun MoneyAmount(
    amount: String, numColor: Color, numSize: androidx.compose.ui.unit.TextUnit,
    showDong: Boolean, modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(amount, color = numColor, fontSize = numSize, fontWeight = FontWeight.Medium)
        Text(
            "đ", color = if (showDong) GOLD else Color.Transparent, fontSize = 13.sp,
            fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light,
            modifier = Modifier.width(15.dp).padding(start = 3.dp),
        )
    }
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
                                Text(r.name, color = partyColor(r.partyId, r.displayColor), fontSize = 14.sp, maxLines = 1)
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
    // Sửa inline (chỉ dòng item đơn hàng): itemId>0 + variant/unit để gửi updateOrderItem.
    val itemId: Long = 0, val variantId: Long = 0, val unitId: Long = 0,
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

    // Lọc sổ chi tiết theo khoảng ngày (null = BE tự áp 15 ngày gần nhất).
    var stmtFrom by remember { mutableStateOf<Long?>(null) }
    var stmtTo by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(reloadTick, stmtFrom, stmtTo) {
        loading = true
        try {
            val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val f = if (stmtFrom != null && stmtTo != null) ymd.format(Date(stmtFrom!!)) else null
            val t = if (stmtFrom != null && stmtTo != null) ymd.format(Date(stmtTo!!)) else null
            statement = container.vapi.debtStatement(party.partyType, party.partyId, f, t).data
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
                val itemId = if (src.originTable == "orders") (r.meta?.orderItemId ?: 0L) else 0L
                add(PendingRowView(ri == 0, src.accountingDate, src.docNo ?: "", src.originTable, src.originId, r.description, r.qty, r.unitName, r.unitPrice, r.bornDebt, r.bornCredit,
                    itemId = itemId, variantId = r.variantId ?: 0L, unitId = r.unitId ?: 0L))
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
                        Text(party.name, color = partyColor(party.partyId, party.displayColor), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
                UnpaidTab(pendingRows, advances, hasBlockingAdvance, pendingNet, canSettle, posting, onOpenOrder = { editOrderId = it }, onPost = { onPostClick() }, onReload = { reloadTick++ })
            } else {
                SettledTab(statement, stmtRows, closing, party.partyType, party.partyId, party.name,
                    rangeStart = stmtFrom, rangeEnd = stmtTo,
                    onPickRange = { s, e -> stmtFrom = s; stmtTo = e })
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
    onReload: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    var editingKey by remember { mutableStateOf<Int?>(null) }        // idx dòng đang sửa (phóng to + làm mờ dòng khác)
    var qtyConfirm by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) } // chờ xác nhận đổi SL
    var flashItemId by remember { mutableStateOf<Long?>(null) }      // dòng vừa lưu OK → flash xanh

    Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
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
            itemsIndexed(rows) { idx, r ->
                if (r.itemId > 0L) EditableLedgerRow(
                    idx = idx, row = r,
                    dim = editingKey != null && editingKey != idx, editing = editingKey == idx,
                    flashing = flashItemId == r.itemId,
                    onEditingChange = { focused -> editingKey = if (focused) idx else if (editingKey == idx) null else editingKey },
                    onOpenOrder = { onOpenOrder(r.originId) },
                    confirmQty = { val d = CompletableDeferred<Boolean>(); qtyConfirm = d; d.await() },
                    save = { qty, price ->
                        val ok = try {
                            container.vapi.updateOrderItem(r.originId, r.itemId, CreateOrderItem(r.variantId, r.unitId, qty, price)); true
                        } catch (_: Exception) { false }
                        if (ok) {
                            flashItemId = r.itemId
                            scope.launch { delay(900); if (flashItemId == r.itemId) flashItemId = null }
                            onReload()
                        } else Toast.makeText(context, "Cập nhật dòng thất bại", Toast.LENGTH_SHORT).show()
                        ok
                    },
                ) else LedgerRow(idx, r.showHeader, r.date, r.docNo, r.description, r.qty, r.unitName, r.unitPrice, r.debit, r.credit, clickableDoc = r.originTable == "orders" && r.originId > 0, onDocClick = { onOpenOrder(r.originId) })
            }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(0.5.dp).background(AdminColors.Border))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Tổng chưa chốt", color = AdminColors.TextMuted, fontSize = 14.sp)
                    Text(moneyD(money(pendingNet), AdminColors.Text), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Text("Chạm SL/đơn giá để sửa nhanh; bấm số đơn để mở đơn đầy đủ.", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                Button(
                    onClick = onPost, enabled = canSettle && !hasBlockingAdvance && !posting,
                    colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text(if (posting) "Đang chốt…" else "Chốt công nợ") }
                if (!canSettle) Text("Tài khoản của bạn không có quyền chốt công nợ.", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(40.dp))
            }
        }
        // Xác nhận đổi SL (đụng tồn) — hoist trên list để phủ toàn vùng.
        qtyConfirm?.let { d ->
            ConfirmOverlay(
                title = "Đổi số lượng",
                message = "Đơn đã giao — đổi số lượng sẽ điều chỉnh lại tồn kho. Tiếp tục?",
                onConfirm = { qtyConfirm = null; d.complete(true) },
                onCancel = { qtyConfirm = null; d.complete(false) },
            )
        }
    }
}

/** Dòng Chưa chốt CÓ sửa inline: chạm SL/đơn giá để sửa; focus → phóng to + làm mờ dòng khác;
 *  đơn giá nhập "theo nghìn" + hint; blur lưu nếu đổi (đổi SL hỏi xác nhận vì đụng tồn). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableLedgerRow(
    idx: Int, row: PendingRowView, dim: Boolean, editing: Boolean, flashing: Boolean,
    onEditingChange: (Boolean) -> Unit, onOpenOrder: () -> Unit,
    confirmQty: suspend () -> Boolean, save: suspend (qty: Double, price: Double) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val bring = remember { BringIntoViewRequester() }
    val origQty = row.qty ?: 0.0
    val origPrice = row.unitPrice ?: 0.0
    var qtyText by remember(row.itemId, origQty) { mutableStateOf(trimZeros(origQty)) }
    var priceText by remember(row.itemId, origPrice) { mutableStateOf(money(origPrice)) }
    var priceFocused by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val numSize = if (editing) 17.sp else 13.sp
    // Focus input → cuộn dòng vào tầm nhìn (trên bàn phím) sau khi IME mở (LazyColumn imePadding).
    fun scrollUp() { scope.launch { delay(300); runCatching { bring.bringIntoView() } } }

    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring).alpha(if (dim) 0.5f else 1f)
        .background(if (flashing) Color(0xFF22C55E).copy(alpha = 0.14f) else Color.Transparent)) {
        if (idx > 0) Box(Modifier.fillMaxWidth().height(if (row.showHeader) 1.dp else 0.5.dp).background(if (row.showHeader) GOLD else AdminColors.Border))
        Column(Modifier.fillMaxWidth().padding(vertical = if (editing) 12.dp else 8.dp)) {
            if (row.showHeader) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fmtDate(row.date), color = AdminColors.TextMuted, fontSize = 11.sp)
                if (row.docNo.isNotBlank()) Text(row.docNo, color = AdminColors.Primary, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onOpenOrder))
            }
            Text(row.description ?: "—", color = AdminColors.Text, fontSize = if (editing) 15.sp else 14.sp)
            Row(Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).padding(start = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = qtyText,
                        onValueChange = { raw -> qtyText = raw.filter { c -> c.isDigit() || c == '.' } },
                        readOnly = saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = numSize, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(AdminColors.Primary),
                        modifier = Modifier.onFocusChanged { st ->
                            if (st.isFocused) { onEditingChange(true); scrollUp() }
                            else {
                                onEditingChange(false)
                                val newQty = qtyText.toDoubleOrNull() ?: 0.0
                                if (newQty > 0 && newQty != origQty) scope.launch {
                                    if (confirmQty()) { saving = true; val ok = save(newQty, origPrice); saving = false; if (!ok) qtyText = trimZeros(origQty) }
                                    else qtyText = trimZeros(origQty)
                                } else qtyText = trimZeros(origQty)
                            }
                        },
                    )
                    row.unitName?.let { Text("  $it", color = AdminColors.TextMuted, fontSize = numSize, fontStyle = FontStyle.Italic) }
                    Text("  ×  ", color = AdminColors.TextMuted, fontSize = numSize)
                    Box {
                        if (priceFocused) expandMoneyShorthand(priceText)?.let { pv ->
                            Popup(alignment = Alignment.TopStart, offset = IntOffset(0, -84)) {
                                Text(money(pv), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AdminColors.Primary.copy(alpha = 0.75f)).padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                        BasicTextField(
                            value = priceText,
                            onValueChange = { raw -> priceText = raw.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            readOnly = saving,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            textStyle = TextStyle(color = AdminColors.Text, fontSize = numSize, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(AdminColors.Primary),
                            modifier = Modifier.widthIn(min = 44.dp).onFocusChanged { st ->
                                if (st.isFocused) { priceFocused = true; onEditingChange(true); priceText = trimZeros(origPrice); scrollUp() }
                                else {
                                    priceFocused = false; onEditingChange(false)
                                    val v = expandMoneyShorthand(priceText) ?: 0.0
                                    priceText = money(v)
                                    if (v != origPrice) scope.launch { saving = true; val ok = save(origQty, v); saving = false; if (!ok) priceText = money(origPrice) }
                                }
                            },
                        )
                    }
                }
                if (row.debit > 0) Text("+${money(row.debit)}", color = AdminColors.Danger, fontSize = 15.sp)
                if (row.credit > 0) Text("−${money(row.credit)}", color = AdminColors.Success, fontSize = 15.sp)
            }
        }
    }
}

/** Overlay xác nhận đơn giản (scrim + card + Huỷ/Tiếp tục). */
@Composable
private fun ConfirmOverlay(title: String, message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}.padding(20.dp),
        ) {
            Text(title, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(message, color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                Text("Huỷ", color = AdminColors.TextMuted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 16.dp, vertical = 8.dp))
                Text("Tiếp tục", color = AdminColors.Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onConfirm).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.SettledTab(
    statement: DebtStatementDto?, rows: List<StmtRowView>, closing: Double,
    partyType: String, partyId: Long, partyName: String,
    rangeStart: Long?, rangeEnd: Long?, onPickRange: (Long?, Long?) -> Unit,
) {
    if (statement == null) { Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text("—", color = AdminColors.TextMuted) }; return }
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    var excelBusy by remember { mutableStateOf(false) }
    var imgBusy by remember { mutableStateOf(false) }
    var imgBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imgUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }
    val fileBase = "CN-" + partyName.replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().ifBlank { "khach" }

    // Chip lọc kỳ — CỐ ĐỊNH ngay dưới header (không nằm trong LazyColumn) để khi
    // cuộn danh sách vẫn thấy đang xem kỳ công nợ nào. Mặc định null = BE áp 15 ngày.
    val hasRange = rangeStart != null && rangeEnd != null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AdminColors.Card)
            .border(0.5.dp, AdminColors.Border, RoundedCornerShape(8.dp))
            .clickable { datePickerOpen = true }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (hasRange) "${fmtDate(statement.period.from)} – ${fmtDate(statement.period.to)}" else "15 ngày gần nhất",
            color = if (hasRange) AdminColors.Text else AdminColors.TextMuted, fontSize = 13.sp,
        )
        if (hasRange) Text("Xoá lọc", color = AdminColors.Primary, fontSize = 12.sp,
            modifier = Modifier.clickable { onPickRange(null, null) })
        else Text("Lọc ngày", color = AdminColors.Primary, fontSize = 12.sp)
    }

    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
        item {
            // Số dư đầu kỳ — border-bottom vàng; số căn theo số dư cuối kỳ (đ ẩn giữ chỗ).
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).padding(bottom = 6.dp)
                .drawBottomBorder(GoldLine), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Số dư đầu kỳ:", color = AdminColors.TextMuted, fontSize = 13.sp)
                MoneyAmount(money(statement.openingBalance), AdminColors.Text, 13.sp, showDong = false,
                    modifier = Modifier.padding(end = 8.dp))
            }
        }
        if (rows.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Text("Chưa có phát sinh trong kỳ.", color = AdminColors.TextMuted, fontSize = 13.sp) } }
        itemsIndexed(rows) { idx, sr ->
            val r = sr.row
            LedgerRow(idx, sr.showHeader, r.date, r.docNo ?: "", r.description ?: r.origin, r.qty, r.unitName, r.unitPrice, r.debit, r.credit, clickableDoc = false, onDocClick = {})
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp).drawTopBorder(GreenLine, 2.dp).padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Số dư cuối kỳ", color = AdminColors.TextMuted, fontSize = 14.sp)
                MoneyAmount(money(closing), AdminColors.Text, 16.sp, showDong = true,
                    modifier = Modifier.padding(end = 8.dp))
            }
            // 3 tác vụ (mirror web): Gửi khách · Xuất Excel · Copy ảnh.
            Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DebtActionBtn("Gửi khách", Modifier.weight(1f), busy = false) {
                    Toast.makeText(context, "Gửi khách tự động (Email/Zalo/Tele) — đang phát triển.", Toast.LENGTH_SHORT).show()
                }
                DebtActionBtn("Xuất Excel", Modifier.weight(1f), busy = excelBusy) {
                    if (excelBusy) return@DebtActionBtn
                    scope.launch {
                        excelBusy = true
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                val body = container.vapi.debtStatementExcel(partyType, partyId)
                                val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                                val file = java.io.File(dir, "$fileBase.xlsx")
                                file.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            }
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Xuất Excel").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Không tải được Excel: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally { excelBusy = false }
                    }
                }
                DebtActionBtn("Copy ảnh", Modifier.weight(1f), busy = imgBusy) {
                    if (imgBusy) return@DebtActionBtn
                    scope.launch {
                        imgBusy = true
                        try {
                            val exportedAt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi")).format(Date())
                            val (b, u) = withContext(Dispatchers.Default) {
                                val bmp = renderDebtStatementBitmap(partyName, statement, exportedAt)
                                val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                                val file = java.io.File(dir, "$fileBase.png")
                                java.io.FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                bmp to androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            }
                            imgBitmap = b; imgUri = u
                        } catch (e: Exception) {
                            Toast.makeText(context, "Không tạo được ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally { imgBusy = false }
                    }
                }
            }
            Spacer(Modifier.height(200.dp))
        }
    }

    // Dialog chọn khoảng ngày — dùng lại pattern đã fix lỗi hiển thị (title rỗng,
    // headline 1 dòng, tắt mode-toggle, bọc dark theme) như SaleOrdersList.
    if (datePickerOpen) {
        val rangeState = androidx.compose.material3.rememberDateRangePickerState(
            initialSelectedStartDateMillis = rangeStart, initialSelectedEndDateMillis = rangeEnd,
        )
        val headFmt = SimpleDateFormat("dd/MM/yyyy", Locale("vi"))
        androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.darkColorScheme(
                surface = AdminColors.Card, onSurface = AdminColors.Text,
                surfaceVariant = AdminColors.Card, onSurfaceVariant = AdminColors.TextMuted,
                primary = AdminColors.Primary, onPrimary = Color.White,
            ),
        ) {
            androidx.compose.material3.DatePickerDialog(
                onDismissRequest = { datePickerOpen = false },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            onPickRange(rangeState.selectedStartDateMillis, rangeState.selectedEndDateMillis)
                            datePickerOpen = false
                        },
                        enabled = rangeState.selectedStartDateMillis != null && rangeState.selectedEndDateMillis != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Áp dụng", color = AdminColors.Primary) }
                },
                colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = AdminColors.Card),
            ) {
                androidx.compose.material3.DateRangePicker(
                    state = rangeState, modifier = Modifier.weight(1f),
                    title = {},
                    showModeToggle = false,
                    headline = {
                        val s = rangeState.selectedStartDateMillis?.let { headFmt.format(Date(it)) } ?: "Bắt đầu"
                        val e = rangeState.selectedEndDateMillis?.let { headFmt.format(Date(it)) } ?: "Kết thúc"
                        Text("$s – $e", color = AdminColors.Text, fontSize = 15.sp, maxLines = 1, softWrap = false,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                    },
                )
            }
        }
    }

    // Dialog preview ảnh công nợ — Copy / Chia sẻ / Tải.
    val bmp = imgBitmap; val uri = imgUri
    if (bmp != null && uri != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { imgBitmap = null; imgUri = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(Modifier.fillMaxWidth(0.96f).clip(RoundedCornerShape(12.dp)).background(AdminColors.Card).padding(12.dp)) {
                Text("Bảng công nợ — $partyName", color = AdminColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                Image(
                    bitmap = bmp.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    DebtActionBtn("Copy", Modifier.weight(1f), busy = false) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "Bảng công nợ", uri))
                        Toast.makeText(context, "Đã copy ảnh — dán vào Zalo/chat", Toast.LENGTH_SHORT).show()
                    }
                    DebtActionBtn("Chia sẻ", Modifier.weight(1f), busy = false) {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Chia sẻ công nợ").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    DebtActionBtn("Tải ảnh", Modifier.weight(1f), busy = false) {
                        val ok = saveBitmapToGallery(context, bmp, fileBase)
                        Toast.makeText(context, if (ok) "Đã lưu ảnh vào thư viện" else "Lưu ảnh thất bại", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

/** Nút tác vụ công nợ — viền primary, chữ nhỏ, có trạng thái bận (spinner). */
@Composable
private fun DebtActionBtn(label: String, modifier: Modifier = Modifier, busy: Boolean, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(0.5.dp, AdminColors.Border, RoundedCornerShape(8.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
        else Text(label, color = AdminColors.Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Màu vàng gold cho viền dưới "Số dư đầu kỳ" (khớp gold trong ảnh render). */
private val GoldLine = Color(0xFFD4AF37)

/** Màu xanh cho viền trên "Số dư cuối kỳ". */
private val GreenLine = Color(0xFF22C55E)

/** Vẽ 1 đường viền dưới (border-bottom) cho hàng "Số dư đầu kỳ". */
private fun Modifier.drawBottomBorder(color: Color, width: androidx.compose.ui.unit.Dp = 0.7.dp): Modifier =
    this.drawBehind {
        val h = width.toPx()
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height - h / 2f),
            androidx.compose.ui.geometry.Offset(size.width, size.height - h / 2f), h)
    }

/** Vẽ 1 đường viền trên (border-top) cho hàng "Số dư cuối kỳ". */
private fun Modifier.drawTopBorder(color: Color, width: androidx.compose.ui.unit.Dp = 2.dp): Modifier =
    this.drawBehind {
        val h = width.toPx()
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, h / 2f),
            androidx.compose.ui.geometry.Offset(size.width, h / 2f), h)
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
