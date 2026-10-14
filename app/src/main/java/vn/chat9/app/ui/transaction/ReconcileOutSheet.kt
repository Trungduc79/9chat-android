package vn.chat9.app.ui.transaction

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.HttpException
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.AllocateLineDto
import vn.chat9.app.data.vapi.dto.AllocateRequest
import vn.chat9.app.data.vapi.dto.CasherDto
import vn.chat9.app.data.vapi.dto.CreateLoanDto
import vn.chat9.app.data.vapi.dto.CustomerDto
import vn.chat9.app.data.vapi.dto.ExpenseCategoryDto
import vn.chat9.app.data.vapi.dto.ExpenseListItemDto
import vn.chat9.app.data.vapi.dto.InternalToCashRequest
import vn.chat9.app.data.vapi.dto.LoanDto
import vn.chat9.app.data.vapi.dto.MoneyTransactionDto
import vn.chat9.app.data.vapi.dto.OffsetTransferRequest
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.data.vapi.dto.PendingAdvanceDto
import vn.chat9.app.data.vapi.dto.SupplierDto
import vn.chat9.app.ui.explore.AdminColors
import kotlin.math.min
import kotlin.math.round

private fun fmt(n: Double): String = java.text.NumberFormat.getNumberInstance(java.util.Locale("vi")).format(round(n))
private fun parseMoney(s: String): Double = s.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0

/** Rút message lỗi thật từ BE (RuntimeException → 422 JSON {message}) thay vì toast chung chung. */
private fun errMsg(e: Exception, fallback: String): String {
    val body = (e as? HttpException)?.response()?.errorBody()?.let { runCatching { it.string() }.getOrNull() }
    val msg = body?.let { runCatching { JSONObject(it).optString("message") }.getOrNull() }
    return msg?.takeIf { it.isNotBlank() } ?: fallback
}

private enum class OutPurpose(val label: String) {
    EXPENSE("Chi phí"), SUPPLIER_DEBT("Trả NCC"), CUSTOMER_DEBT("Chi hộ KH"),
    CUSTOMER_REFUND("Hoàn tiền KH"), LOAN_PAY("Trả góp"), LOAN_CREATE("Cho vay"),
    LANDED_COST("Phân bổ nhập"), INTERNAL_TRANSFER("Đối trừ nội bộ"), INTERNAL_TO_CASH("Nhập quỹ tiền mặt"),
}

private val LD_METHODS = listOf("VALUE" to "Theo giá trị", "QUANTITY" to "Theo số lượng", "WEIGHT" to "Theo khối lượng", "VOLUME" to "Theo thể tích")

private class CustRow {
    var advanceId by mutableStateOf<Long?>(null)
    var customerId by mutableStateOf<Long?>(null)
    var customerLabel by mutableStateOf<String?>(null)
    var orderId by mutableStateOf<Long?>(null)
    var orderLabel by mutableStateOf<String?>(null)
    var orderOpts by mutableStateOf<List<Pair<Long, String>>>(emptyList())
    var amountStr by mutableStateOf("")
}

/**
 * Sheet "Phân bổ tiền ra" — mirror web ReconcileOutPanel.vue (9 purpose). Đơn giản hoá so
 * bản web ở vài chỗ (ghi rõ trong code): "Chi phí · Gắn có sẵn" bỏ 2 ô lọc phụ (loại phí/tìm
 * nội dung) — SMB ít khoản, list ngắn khỏi cần lọc; "Phân bổ nhập" bỏ bảng preview phân bổ
 * theo từng mặt hàng + không cho loại trừ mặt hàng (`item_ids=null` = BE tự áp hết đơn, đã
 * xác nhận hợp lệ ở SettlementService::landed_cost) + không hiện landed cost ĐÃ phân bổ
 * trước; "Chi phí · Tạo mới" bỏ chọn "đối tượng phân bổ" (target_type/target_id) + gợi ý
 * danh mục theo expense_sign — BE tự lấy default của danh mục khi trống.
 */
@Composable
fun ReconcileOutSheet(tx: MoneyTransactionDto, onClose: () -> Unit, onResolved: (Long) -> Unit) {
    val ctx = LocalContext.current
    val vapi = (ctx.applicationContext as App).container.vapi
    val scope = rememberCoroutineScope()

    var purpose by remember { mutableStateOf(OutPurpose.EXPENSE) }
    var submitting by remember { mutableStateOf(false) }
    var amountStr by remember { mutableStateOf(fmt(tx.amount)) }
    var note by remember { mutableStateOf("") }

    // ===== option lists — nạp 1 lần, độc lập (1 list lỗi không rớt list khác) =====
    var categories by remember { mutableStateOf<List<ExpenseCategoryDto>>(emptyList()) }
    var suppliers by remember { mutableStateOf<List<SupplierDto>>(emptyList()) }
    var loans by remember { mutableStateOf<List<LoanDto>>(emptyList()) }
    var cashers by remember { mutableStateOf<List<CasherDto>>(emptyList()) }
    var pendingAdvances by remember { mutableStateOf<List<PendingAdvanceDto>>(emptyList()) }
    var purchaseOrders by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { vapi.listExpenseCategories().data }.getOrNull()?.let { categories = it }
        runCatching { vapi.listSuppliers(perPage = 100).data }.getOrNull()?.let { suppliers = it }
        runCatching { vapi.listLoans().data }.getOrNull()
            ?.let { loans = it.filter { l -> l.outstandingPrincipal > 0 } }
        runCatching { vapi.listCashers(1, 100).data }.getOrNull()?.let { cashers = it }
        runCatching { vapi.pendingAdvances(null).data }.getOrNull()?.let { pendingAdvances = it }
        runCatching { vapi.listOrders(type = "purchase", perPage = 100).data }.getOrNull()?.let { purchaseOrders = it }
    }

    // ===== Chi phí =====
    var expenseMode by remember { mutableStateOf("new") } // new|existing
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var nonBusiness by remember { mutableStateOf(false) }
    var unpaidExpenses by remember { mutableStateOf<List<ExpenseListItemDto>>(emptyList()) }
    var candLoading by remember { mutableStateOf(false) }
    val selectedKeys = remember { mutableStateListOf<String>() } // "adv-<id>" | "exp-<id>", giữ thứ tự chọn
    val hasPendingFees = pendingAdvances.isNotEmpty() || unpaidExpenses.isNotEmpty()
    LaunchedEffect(expenseMode, purpose) {
        if (purpose != OutPurpose.EXPENSE || expenseMode != "existing") return@LaunchedEffect
        candLoading = true
        unpaidExpenses = runCatching { vapi.listUnpaidExpenses(true, 50).data }.getOrNull() ?: emptyList()
        candLoading = false
    }
    fun capacityOf(key: String): Double {
        val id = key.substring(4).toLong()
        return if (key.startsWith("adv-")) pendingAdvances.find { it.advanceId == id }?.remaining ?: 0.0
        else unpaidExpenses.find { it.id == id }?.remainingAmount ?: 0.0
    }
    val selectedCapacity = selectedKeys.sumOf { capacityOf(it) }
    // Rải GD vào các khoản chọn (greedy theo thứ tự), mỗi khoản ≤ sức chứa của nó.
    fun distribute(): List<Pair<String, Double>> {
        var left = round(tx.amount)
        val out = mutableListOf<Pair<String, Double>>()
        for (k in selectedKeys) {
            if (left <= 0) break
            val amt = min(round(capacityOf(k)), left)
            if (amt > 0) { out.add(k to amt); left -= amt }
        }
        return out
    }

    // ===== Trả NCC =====
    var supplierId by remember { mutableStateOf<Long?>(null) }

    // ===== Chi hộ KH (multi-row) =====
    val custRows = remember { mutableStateListOf(CustRow().apply { amountStr = fmt(tx.amount) }) }
    suspend fun loadRowOrders(row: CustRow) {
        row.orderId = null; row.orderOpts = emptyList()
        val cid = row.customerId ?: return
        row.orderOpts = runCatching { vapi.listOrders(partyId = cid, perPage = 30).data }.getOrNull()
            ?.map { it.id to "${it.code} · ${fmt(it.totalAmount ?: 0.0)}đ" } ?: emptyList()
    }
    fun onPickAdvance(row: CustRow, advanceId: Long?) {
        row.advanceId = advanceId
        val a = pendingAdvances.find { it.advanceId == advanceId }
        if (a == null) { row.orderId = null; return }
        row.customerId = a.customerId
        row.customerLabel = a.customerName
        row.orderId = a.orderId
        row.orderLabel = a.orderCode
        row.amountStr = fmt(a.remaining ?: 0.0)
        row.orderOpts = a.orderId?.let { listOf(it to (a.orderCode ?: "#$it")) } ?: emptyList()
    }

    // ===== Hoàn tiền KH =====
    var refundCustomerId by remember { mutableStateOf<Long?>(null) }
    var refundCustomerLabel by remember { mutableStateOf<String?>(null) }
    var refundOrderId by remember { mutableStateOf<Long?>(null) }
    var refundOrderOpts by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    LaunchedEffect(refundCustomerId) {
        refundOrderId = null; refundOrderOpts = emptyList()
        val cid = refundCustomerId ?: return@LaunchedEffect
        refundOrderOpts = runCatching { vapi.listOrders(partyId = cid, perPage = 30).data }.getOrNull()
            ?.map { it.id to "${it.code} · ${fmt(it.totalAmount ?: 0.0)}đ" } ?: emptyList()
    }

    // ===== Trả góp / Cho vay =====
    var loanId by remember { mutableStateOf<Long?>(null) }
    var counterpartyName by remember { mutableStateOf("") }

    // ===== Phân bổ nhập =====
    var poId by remember { mutableStateOf<Long?>(null) }
    var ldMethod by remember { mutableStateOf("VALUE") }

    // ===== Đối trừ nội bộ =====
    var offsetCandidates by remember { mutableStateOf<List<MoneyTransactionDto>>(emptyList()) }
    var offsetLoading by remember { mutableStateOf(false) }
    val offsetSelectedIds = remember { mutableStateListOf<Long>() }
    LaunchedEffect(purpose) {
        if (purpose != OutPurpose.INTERNAL_TRANSFER) return@LaunchedEffect
        offsetLoading = true
        val all = runCatching { vapi.listMoneyTransactions(perPage = 100, channel = "bank", direction = "in", unsettled = true).data }.getOrNull() ?: emptyList()
        val gdDayMs = tx.date.take(10)
        offsetCandidates = all.filter { it.id != tx.id && Math.abs(it.amount - tx.amount) < 1 }
            // lệch tối đa 1 ngày: so 3 mốc ngày liền kề (đủ dùng, khỏi parse Date phức tạp).
            .filter { c ->
                val d = c.date.take(10)
                d == gdDayMs || dayShift(gdDayMs, 1) == d || dayShift(gdDayMs, -1) == d
            }
        offsetLoading = false
    }
    val offsetSum = offsetSelectedIds.sumOf { id -> offsetCandidates.find { it.id == id }?.amount ?: 0.0 }

    // ===== Nhập quỹ tiền mặt =====
    fun isBankCasher(c: CasherDto) = c.type == "bank_account" || !c.bankAccount.isNullOrBlank()
    var internalToCashCasherId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(cashers, purpose) {
        if (purpose != OutPurpose.INTERNAL_TO_CASH || internalToCashCasherId != null) return@LaunchedEffect
        internalToCashCasherId = cashers.find { it.type == "petty_cash" }?.id ?: cashers.find { !isBankCasher(it) }?.id
    }

    val amountVal = parseMoney(amountStr)
    val allocated = when {
        purpose == OutPurpose.CUSTOMER_DEBT -> custRows.sumOf { parseMoney(it.amountStr) }
        purpose == OutPurpose.INTERNAL_TRANSFER -> offsetSum
        purpose == OutPurpose.INTERNAL_TO_CASH -> tx.amount
        purpose == OutPurpose.EXPENSE && expenseMode == "existing" -> min(tx.amount, selectedCapacity)
        else -> amountVal
    }
    val remaining = round((tx.amount - allocated) * 100) / 100

    val canSubmit = when (purpose) {
        OutPurpose.EXPENSE -> if (expenseMode == "new") categoryId != null else selectedKeys.isNotEmpty() && selectedCapacity + 1 >= tx.amount
        OutPurpose.SUPPLIER_DEBT -> supplierId != null
        OutPurpose.CUSTOMER_DEBT -> custRows.any { it.customerId != null && it.orderId != null && parseMoney(it.amountStr) > 0 }
        OutPurpose.CUSTOMER_REFUND -> refundCustomerId != null
        OutPurpose.LOAN_PAY -> loanId != null
        OutPurpose.LOAN_CREATE -> counterpartyName.trim().isNotEmpty()
        OutPurpose.LANDED_COST -> poId != null
        OutPurpose.INTERNAL_TRANSFER -> offsetSelectedIds.isNotEmpty()
        OutPurpose.INTERNAL_TO_CASH -> internalToCashCasherId != null
    } && allocated > 0 && remaining >= -1

    fun buildLines(): List<AllocateLineDto> = when (purpose) {
        OutPurpose.EXPENSE -> if (expenseMode == "existing") {
            distribute().map { (key, amt) ->
                if (key.startsWith("adv-")) {
                    val a = pendingAdvances.first { it.advanceId == key.substring(4).toLong() }
                    AllocateLineDto("customer_debt", a.customerId, a.orderId, amt, note = note.ifBlank { null })
                } else {
                    AllocateLineDto("expense", null, null, amt, expenseId = key.substring(4).toLong())
                }
            }
        } else {
            listOf(AllocateLineDto("expense", null, null, amountVal, categoryId = categoryId, description = note.ifBlank { null }, nonBusiness = nonBusiness))
        }
        OutPurpose.SUPPLIER_DEBT -> listOf(AllocateLineDto("supplier_debt", supplierId, null, amountVal, note = note.ifBlank { null }))
        OutPurpose.CUSTOMER_DEBT -> custRows.filter { it.customerId != null && it.orderId != null && parseMoney(it.amountStr) > 0 }
            .map { AllocateLineDto("customer_debt", it.customerId, it.orderId, parseMoney(it.amountStr), note = note.ifBlank { null }) }
        OutPurpose.CUSTOMER_REFUND -> listOf(AllocateLineDto("customer_refund", refundCustomerId, refundOrderId, amountVal, note = note.ifBlank { null }))
        OutPurpose.LOAN_PAY -> listOf(AllocateLineDto("loan", null, null, amountVal, loanId = loanId, note = note.ifBlank { null }))
        OutPurpose.LOAN_CREATE -> listOf(AllocateLineDto("loan", null, null, amountVal, note = note.ifBlank { null }, createLoan = CreateLoanDto(counterpartyName = counterpartyName.trim())))
        OutPurpose.LANDED_COST -> listOf(AllocateLineDto("landed_cost", null, poId, amountVal, method = ldMethod, note = note.ifBlank { null }))
        else -> emptyList()
    }

    fun submit() {
        if (!canSubmit || submitting) return
        scope.launch {
            submitting = true
            try {
                when (purpose) {
                    OutPurpose.INTERNAL_TRANSFER -> vapi.offsetTransfer(tx.id, OffsetTransferRequest(offsetSelectedIds.toList()))
                    OutPurpose.INTERNAL_TO_CASH -> vapi.internalToCash(tx.id, InternalToCashRequest(internalToCashCasherId))
                    else -> vapi.allocateMoneyTx(tx.id, AllocateRequest(buildLines()))
                }
                Toast.makeText(ctx, "Đã phân bổ giao dịch tiền ra", Toast.LENGTH_SHORT).show()
                onResolved(tx.id)
            } catch (e: Exception) {
                Toast.makeText(ctx, errMsg(e, "Phân bổ thất bại"), Toast.LENGTH_LONG).show()
            } finally { submitting = false }
        }
    }

    // Bàn phím mở → đẩy dialog lên, không để che (mirror ReconcileInSheet/AccSheet).
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeOffsetPx = with(density) { 48.dp.toPx() }
    val padBottom = with(density) { (imeBottomPx - imeOffsetPx).coerceAtLeast(0f).toDp() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).padding(bottom = padBottom).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(12.dp)
                .clip(RoundedCornerShape(16.dp)).background(AdminColors.Card)
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .heightIn(max = 660.dp).verticalScroll(rememberScrollState())
                .padding(16.dp).clickable(enabled = false, onClick = {}),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Phân bổ tiền ra — ${tx.code}", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("Đóng", color = AdminColors.Primary, fontSize = 13.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tx.code, color = AdminColors.TextMuted, fontSize = 12.sp)
                Text("−${fmt(tx.amount)}đ", color = AdminColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            tx.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = AdminColors.TextMuted, fontSize = 12.sp) }

            // Chọn mục đích — chip cuộn ngang (9 lựa chọn, "Nhập quỹ tiền mặt" chỉ khi GD ngân hàng).
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutPurpose.entries.filter { it != OutPurpose.INTERNAL_TO_CASH || tx.channel == "bank" }.forEach { p ->
                    val sel = purpose == p
                    Text(
                        p.label, color = if (sel) Color.White else AdminColors.Text, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (sel) AdminColors.Primary else AdminColors.Bg)
                            .clickable { purpose = p }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }

            when (purpose) {
                OutPurpose.EXPENSE -> ExpenseSection(
                    expenseMode = expenseMode, onModeChange = { expenseMode = it }, hasPendingFees = hasPendingFees,
                    categories = categories, categoryId = categoryId, onCategory = { categoryId = it },
                    nonBusiness = nonBusiness, onNonBusiness = { nonBusiness = it },
                    candLoading = candLoading, pendingAdvances = pendingAdvances, unpaidExpenses = unpaidExpenses,
                    selectedKeys = selectedKeys, capacityOf = ::capacityOf, distributionOf = { key -> distribute().find { it.first == key }?.second },
                    selectedCapacity = selectedCapacity, gdAmount = tx.amount,
                )
                OutPurpose.SUPPLIER_DEBT -> SupplierSection(suppliers, supplierId) { supplierId = it }
                OutPurpose.CUSTOMER_DEBT -> CustomerDebtSection(
                    vapi = vapi, pendingAdvances = pendingAdvances, rows = custRows,
                    onPickAdvance = ::onPickAdvance, onLoadOrders = { scope.launch { loadRowOrders(it) } },
                    onAddRow = { custRows.add(CustRow()) }, onRemoveRow = { custRows.removeAt(it) },
                )
                OutPurpose.CUSTOMER_REFUND -> CustomerRefundSection(
                    vapi = vapi, customerId = refundCustomerId, customerLabel = refundCustomerLabel,
                    onPickCustomer = { id, name -> refundCustomerId = id; refundCustomerLabel = name },
                    orderId = refundOrderId, orderOpts = refundOrderOpts, onOrder = { refundOrderId = it },
                )
                OutPurpose.LOAN_PAY -> LoanPickSection(loans, loanId) { loanId = it }
                OutPurpose.LOAN_CREATE -> {
                    Text("Tạo khoản cho vay mới (giải ngân). Trả lại sau ghi ở khoản vay.", color = AdminColors.TextMuted, fontSize = 12.sp)
                    TextField(counterpartyName, { counterpartyName = it }, "Tên người vay (vd: bạn thân, nhân viên...)")
                }
                OutPurpose.LANDED_COST -> LandedCostSection(purchaseOrders, poId, { poId = it }, ldMethod) { ldMethod = it }
                OutPurpose.INTERNAL_TRANSFER -> InternalTransferSection(offsetLoading, offsetCandidates, offsetSelectedIds, tx.amount)
                OutPurpose.INTERNAL_TO_CASH -> InternalToCashSection(cashers, internalToCashCasherId) { internalToCashCasherId = it }
            }

            // Số tiền (purpose 1-đích) + ghi chú — ẩn với customer_debt/internal_*/expense-existing (theo web).
            if (purpose != OutPurpose.CUSTOMER_DEBT && purpose != OutPurpose.INTERNAL_TRANSFER && purpose != OutPurpose.INTERNAL_TO_CASH &&
                !(purpose == OutPurpose.EXPENSE && expenseMode == "existing")
            ) {
                MoneyField(amountStr, { amountStr = it }, "Số tiền phân bổ (đ)")
            }
            if (purpose != OutPurpose.EXPENSE && purpose != OutPurpose.INTERNAL_TRANSFER && purpose != OutPurpose.INTERNAL_TO_CASH) {
                TextField(note, { note = it }, "Ghi chú")
            }

            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tổng GD: ${fmt(tx.amount)}đ", color = AdminColors.TextMuted, fontSize = 11.sp)
                Text("Còn lại: ${fmt(remaining)}đ", color = if (remaining < 0) AdminColors.Danger else AdminColors.TextMuted, fontSize = 11.sp)
            }

            Text(
                when {
                    submitting -> "Đang lưu…"
                    purpose == OutPurpose.INTERNAL_TRANSFER -> "Xác nhận đối trừ"
                    purpose == OutPurpose.INTERNAL_TO_CASH -> "Xác nhận nhập quỹ"
                    else -> "Xác nhận phân bổ"
                },
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (canSubmit && !submitting) AdminColors.Primary else AdminColors.Border)
                    .clickable(enabled = canSubmit && !submitting) { submit() }.padding(vertical = 10.dp),
            )
        }
    }
}

/** Ngày lệch n ngày (chuỗi yyyy-MM-dd) — so lệch tối đa ±1 ngày cho đối trừ nội bộ. */
private fun dayShift(ymd: String, n: Int): String = runCatching {
    java.time.LocalDate.parse(ymd).plusDays(n.toLong()).toString()
}.getOrDefault(ymd)

// ===================== Section composables =====================

@Composable
private fun SectionHint(text: String) {
    Text(text, color = AdminColors.TextMuted, fontSize = 11.sp)
}

@Composable
private fun TextField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
            cursorBrush = SolidColor(AdminColors.Primary),
            decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MoneyField(valueStr: String, onChange: (String) -> Unit, placeholder: String) {
    val amount = parseMoney(valueStr)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 12.dp, vertical = 12.dp)) {
        BasicTextField(
            value = if (valueStr.isEmpty()) "" else fmt(amount),
            onValueChange = { onChange(it.filter { ch -> ch.isDigit() }) },
            textStyle = TextStyle(color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            decorationBox = { inner -> if (valueStr.isEmpty()) Text("0", color = AdminColors.TextMuted, fontSize = 16.sp); inner() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Dropdown đơn giản (mirror AccountingCashflowTab.DarkDropdown) — id/label bất kỳ (String id để dùng chung Long/String key). */
@Composable
private fun OptionDropdown(selectedLabel: String?, placeholder: String, items: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selectedLabel ?: placeholder, color = if (selectedLabel != null) AdminColors.Text else AdminColors.TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null, tint = AdminColors.TextMuted)
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label, color = AdminColors.Text, fontSize = 14.sp) }, onClick = { onSelect(id); open = false })
                }
            }
        }
    }
}

/** Ô tìm khách remote-search (mirror ReconcileInSheet) — dùng lại cho customer_debt/customer_refund. */
@Composable
private fun CustomerSearchField(vapi: vn.chat9.app.data.vapi.VapiApiService, onPick: (Long, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CustomerDto>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(280)
        results = runCatching { vapi.searchCustomers(query, 15).data }.getOrNull() ?: emptyList()
    }
    Column {
        TextField(query, { query = it }, "Gõ mã / tên khách để tìm…")
        results.take(6).forEach { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(c.id, c.name); query = ""; results = emptyList() }.padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = AdminColors.Text, fontSize = 14.sp)
                    c.phone?.let { Text(it, color = AdminColors.TextMuted, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun ExpenseSection(
    expenseMode: String, onModeChange: (String) -> Unit, hasPendingFees: Boolean,
    categories: List<ExpenseCategoryDto>, categoryId: Long?, onCategory: (Long) -> Unit,
    nonBusiness: Boolean, onNonBusiness: (Boolean) -> Unit,
    candLoading: Boolean, pendingAdvances: List<PendingAdvanceDto>, unpaidExpenses: List<ExpenseListItemDto>,
    selectedKeys: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    capacityOf: (String) -> Double, distributionOf: (String) -> Double?,
    selectedCapacity: Double, gdAmount: Double,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("new" to "Tạo mới", "existing" to "Gắn có sẵn${if (hasPendingFees) " ●" else ""}").forEach { (key, lbl) ->
                val sel = expenseMode == key
                Text(
                    lbl, color = if (sel) Color.White else AdminColors.Text, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) AdminColors.Primary else AdminColors.Bg)
                        .clickable { onModeChange(key) }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (expenseMode == "new") {
            OptionDropdown(categories.find { it.id == categoryId }?.name, "Danh mục chi phí", categories.map { it.id.toString() to it.name }) { onCategory(it.toLong()) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onNonBusiness(!nonBusiness) }) {
                Checkbox(checked = nonBusiness, onCheckedChange = onNonBusiness, colors = CheckboxDefaults.colors(checkedColor = AdminColors.Primary))
                Text("Không tính vào chi phí công ty (cá nhân)", color = AdminColors.Text, fontSize = 12.sp)
            }
        } else {
            if (candLoading) {
                Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(18.dp)) }
            } else {
                val items = pendingAdvances.map { a -> Triple("adv-${a.advanceId}", "[ứng] ${a.customerName ?: ""} · ${a.orderCode ?: "—"}", a.description ?: "") } +
                    unpaidExpenses.map { e -> Triple("exp-${e.id}", e.category?.name ?: e.code, e.description ?: e.code) }
                if (items.isEmpty()) {
                    SectionHint("Không có chi phí / tạm ứng chờ chi")
                } else {
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items.forEach { (key, title, subtitle) ->
                            val checked = key in selectedKeys
                            val cap = capacityOf(key)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .background(if (checked) AdminColors.Primary.copy(alpha = 0.1f) else AdminColors.Bg)
                                    .clickable { if (checked) selectedKeys.remove(key) else selectedKeys.add(key) }.padding(8.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { if (it) selectedKeys.add(key) else selectedKeys.remove(key) }, colors = CheckboxDefaults.colors(checkedColor = AdminColors.Primary))
                                Column(Modifier.weight(1f)) {
                                    Text(title, color = AdminColors.Text, fontSize = 13.sp, maxLines = 1)
                                    Text(subtitle, color = AdminColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                                }
                                val dist = distributionOf(key)
                                Text(
                                    (dist?.let { "→ ${fmt(it)}/" } ?: "") + "${fmt(cap)}đ",
                                    color = AdminColors.TextMuted, fontSize = 11.sp,
                                )
                            }
                        }
                    }
                    if (selectedKeys.isNotEmpty()) {
                        val ok = selectedCapacity + 1 >= gdAmount
                        Text(
                            "Tổng chọn: ${fmt(selectedCapacity)}đ · Tiền ra: ${fmt(gdAmount)}đ" + if (!ok) " — chọn thêm khoản" else " ✓",
                            color = if (ok) AdminColors.Success else AdminColors.Danger, fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplierSection(suppliers: List<SupplierDto>, supplierId: Long?, onPick: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OptionDropdown(suppliers.find { it.id == supplierId }?.display, "Chọn nhà cung cấp", suppliers.map { it.id.toString() to it.display }) { onPick(it.toLong()) }
        SectionHint("Ghi giảm công nợ phải trả NCC + trừ quỹ.")
    }
}

@Composable
private fun CustomerDebtSection(
    vapi: vn.chat9.app.data.vapi.VapiApiService, pendingAdvances: List<PendingAdvanceDto>, rows: androidx.compose.runtime.snapshots.SnapshotStateList<CustRow>,
    onPickAdvance: (CustRow, Long?) -> Unit, onLoadOrders: (CustRow) -> Unit, onAddRow: () -> Unit, onRemoveRow: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pendingAdvances.isNotEmpty()) {
            Text(
                "Có ${pendingAdvances.size} khoản ứng/chi hộ ship CHỜ HOÀN ỨNG — chọn ở mỗi dòng để hoàn ứng.",
                color = AdminColors.Warning, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AdminColors.Warning.copy(alpha = 0.08f)).padding(8.dp),
            )
        }
        rows.forEachIndexed { i, row ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, AdminColors.Border, RoundedCornerShape(8.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OptionDropdown(
                    pendingAdvances.find { it.advanceId == row.advanceId }?.let { "${it.customerName ?: ""} · ${it.orderCode ?: "—"} · còn ${fmt(it.remaining ?: 0.0)}đ" },
                    "Chọn khoản chi hộ chờ hoàn ứng (hoặc nhập tay bên dưới)",
                    pendingAdvances.map { it.advanceId.toString() to "${it.customerName ?: ""} · ${it.orderCode ?: "—"} · còn ${fmt(it.remaining ?: 0.0)}đ" },
                ) { onPickAdvance(row, it.toLong()) }
                if (row.advanceId == null) {
                    CustomerSearchField(vapi) { id, name -> row.customerId = id; row.customerLabel = name; onLoadOrders(row) }
                    row.customerLabel?.let { Text("Đã chọn: $it", color = AdminColors.Primary, fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) { MoneyField(row.amountStr, { row.amountStr = it }, "Số tiền") }
                    if (rows.size > 1) {
                        Text("✕", color = AdminColors.Danger, fontSize = 16.sp, modifier = Modifier.clickable { onRemoveRow(i) }.padding(8.dp))
                    }
                }
                OptionDropdown(row.orderOpts.find { it.first == row.orderId }?.second, "Đơn hàng (bắt buộc)", row.orderOpts.map { it.first.toString() to it.second }) {
                    row.orderId = it.toLong()
                }
            }
        }
        Text(
            "+ Thêm khách / đơn", color = AdminColors.Primary, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, AdminColors.Border, RoundedCornerShape(6.dp))
                .clickable { onAddRow() }.padding(vertical = 8.dp),
        )
        SectionHint("Chọn khoản chờ hoàn ứng để hoàn (cho phép hoàn 1 phần). Hoặc nhập đơn chưa có ship → cộng ship + ghi nhận đã chi.")
    }
}

@Composable
private fun CustomerRefundSection(
    vapi: vn.chat9.app.data.vapi.VapiApiService, customerId: Long?, customerLabel: String?, onPickCustomer: (Long, String) -> Unit,
    orderId: Long?, orderOpts: List<Pair<Long, String>>, onOrder: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (customerId != null) {
            Text("Khách hàng: $customerLabel", color = AdminColors.Text, fontSize = 14.sp)
        } else {
            CustomerSearchField(vapi, onPickCustomer)
        }
        OptionDropdown(orderOpts.find { it.first == orderId }?.second, "Đơn liên quan (tuỳ chọn)", orderOpts.map { it.first.toString() to it.second }) { onOrder(it.toLong()) }
        SectionHint("Hoàn/bù tiền cho khách → GIẢM công nợ phải thu KH + trừ quỹ.")
    }
}

@Composable
private fun LoanPickSection(loans: List<LoanDto>, loanId: Long?, onPick: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OptionDropdown(
            loans.find { it.id == loanId }?.let { "${it.code} · ${it.counterpartyName ?: ""} · còn ${fmt(it.outstandingPrincipal)}đ" },
            "Chọn khoản vay / tài sản trả góp (còn dư nợ)",
            loans.map { it.id.toString() to "${it.code} · ${it.counterpartyName ?: ""} · còn ${fmt(it.outstandingPrincipal)}đ" },
        ) { onPick(it.toLong()) }
        SectionHint("Giảm dư nợ khoản vay (ưu tiên trừ lãi trước, dư giảm gốc).")
    }
}

@Composable
private fun LandedCostSection(purchaseOrders: List<OrderDto>, poId: Long?, onPo: (Long) -> Unit, method: String, onMethod: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OptionDropdown(purchaseOrders.find { it.id == poId }?.let { "${it.code} · ${fmt(it.totalAmount ?: 0.0)}đ" }, "Chọn đơn nhập (PO)", purchaseOrders.map { it.id.toString() to "${it.code} · ${fmt(it.totalAmount ?: 0.0)}đ" }) { onPo(it.toLong()) }
        OptionDropdown(LD_METHODS.find { it.first == method }?.second, "Phương pháp phân bổ", LD_METHODS) { onMethod(it) }
        SectionHint("Cộng vào giá vốn TOÀN BỘ mặt hàng của đơn nhập này (theo phương pháp chọn), KHÔNG ghi chi phí P&L riêng (đã vốn hoá).")
    }
}

@Composable
private fun InternalTransferSection(loading: Boolean, candidates: List<MoneyTransactionDto>, selectedIds: androidx.compose.runtime.snapshots.SnapshotStateList<Long>, gdAmount: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHint("Chọn các giao dịch tiền ĐẾN là vế nhận của lệnh chuyển này → đánh dấu nội bộ. Chỉ hiện GD cùng số tiền, lệch tối đa 1 ngày.")
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(18.dp)) }
        } else if (candidates.isEmpty()) {
            SectionHint("Không có GD tiền đến cùng số tiền (±1 ngày)")
        } else {
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { c ->
                    val sel = c.id in selectedIds
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (sel) AdminColors.Primary.copy(alpha = 0.1f) else AdminColors.Bg)
                            .clickable { if (sel) selectedIds.remove(c.id) else selectedIds.add(c.id) }.padding(8.dp),
                    ) {
                        Checkbox(checked = sel, onCheckedChange = { if (it) selectedIds.add(c.id) else selectedIds.remove(c.id) }, colors = CheckboxDefaults.colors(checkedColor = AdminColors.Primary))
                        Column(Modifier.weight(1f)) {
                            Text(c.code, color = AdminColors.TextMuted, fontSize = 11.sp)
                            Text(c.description ?: "(không nội dung)", color = AdminColors.Text, fontSize = 12.sp, maxLines = 1)
                        }
                        Text("+${fmt(c.amount)}đ", color = AdminColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (selectedIds.isNotEmpty()) {
                val sum = selectedIds.sumOf { id -> candidates.find { it.id == id }?.amount ?: 0.0 }
                val matched = Math.abs(sum - gdAmount) < 1
                Text(
                    "Tổng chọn: ${fmt(sum)}đ · Tiền ra: ${fmt(gdAmount)}đ" + if (matched) " ✓ Khớp" else " — lệch ${fmt(sum - gdAmount)}đ",
                    color = if (matched) AdminColors.Success else AdminColors.Warning, fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun InternalToCashSection(cashers: List<CasherDto>, casherId: Long?, onPick: (Long) -> Unit) {
    fun isBankCasher(c: CasherDto) = c.type == "bank_account" || !c.bankAccount.isNullOrBlank()
    val opts = cashers.filter { !isBankCasher(it) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHint("Rút NGUYÊN số tiền GD ngân hàng này về nhập quỹ tiền mặt — tự tạo phiếu thu tiền mặt đối ứng, khoá lại để không phân bổ nhầm.")
        OptionDropdown(opts.find { it.id == casherId }?.let { "${it.code} · ${it.name}" }, "Chọn quỹ tiền mặt nhận", opts.map { it.id.toString() to "${it.code} · ${it.name}" }) { onPick(it.toLong()) }
    }
}
