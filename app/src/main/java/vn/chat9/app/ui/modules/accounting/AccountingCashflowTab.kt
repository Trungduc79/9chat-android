package vn.chat9.app.ui.modules.accounting

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.CasherDto
import vn.chat9.app.data.vapi.dto.CreateMoneyTxRequest
import vn.chat9.app.data.vapi.dto.CustomerDto
import vn.chat9.app.data.vapi.dto.ExpenseCategoryDto
import vn.chat9.app.data.vapi.dto.MoneyTransactionDto
import vn.chat9.app.data.vapi.dto.PayExpenseRequest
import vn.chat9.app.ui.explore.AdminColors
import vn.chat9.app.ui.explore.AdminPullToRefresh
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val moneyFmt = NumberFormat.getNumberInstance(Locale("vi"))
private fun fmtMoney(n: Double): String = moneyFmt.format(n.toLong())

private fun casherTypeLabel(t: String): String = when (t) {
    "main" -> "Tài khoản chính"; "bank_account" -> "Tài khoản ngân hàng"
    "petty_cash" -> "Quỹ tiền mặt"; "safe" -> "Két sắt"; "mobile" -> "Ví điện tử"; else -> t
}

private fun moneyTxTypeLabel(t: String): String = when (t) {
    "pending" -> "Chờ phân loại"; "cust_bank_in" -> "KH chuyển khoản"; "cust_cash_in" -> "KH trả tiền mặt"
    "cust_cash_out" -> "Hoàn/đảo tiền mặt KH"; "sup_bank_out" -> "Trả NCC (bank)"; "sup_cash_out" -> "Trả NCC (cash)"
    "bank_fee" -> "Phí ngân hàng"; "internal_transfer" -> "Chuyển nội bộ"; "cash_deposit" -> "Nộp tiền vào TK"
    "cash_withdrawal" -> "Rút tiền mặt"; else -> t
}

/** "2026-07-08 14:00:00" / ISO → "14:00 08/07". Fallback: chuỗi gốc. */
private fun fmtTxTime(s: String): String = try {
    val d = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s.replace('T', ' ').take(19))
    SimpleDateFormat("HH:mm dd/MM", Locale.US).format(d!!)
} catch (_: Exception) { s }

private val CASH_CASHER_TYPES = setOf("petty_cash", "safe")

/**
 * Tab Dòng tiền (Android) — port web AccountingCashflowView. Số dư quỹ tổng + chips,
 * danh sách GD (lọc Thu/Chi), tap → drawer chi tiết read-only, FAB tạo GD tiền mặt
 * (2 nhánh: thu tiền mặt KH / chi tiền mặt trả phí).
 *
 * Sheet dùng overlay Box tự vẽ (KHÔNG ModalBottomSheet) → ở CÙNG window nên không bật
 * thanh điều hướng thiết bị + không vuốt-xuống-tắt; chỉ đóng bằng scrim/nút.
 */
@Composable
fun AccountingCashflowTab() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val userName = container.tokenManager.user?.username ?: "nhân viên"
    val scope = rememberCoroutineScope()

    var cashers by remember { mutableStateOf<List<CasherDto>>(emptyList()) }
    var txs by remember { mutableStateOf<List<MoneyTransactionDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }
    var dirFilter by remember { mutableStateOf("all") }   // all|in|out

    LaunchedEffect(refreshTick) {
        loading = true
        try {
            cashers = container.vapi.listCashers(1, 100).data ?: emptyList()
            txs = container.vapi.listMoneyTransactions(80).data ?: emptyList()
        } catch (_: Exception) {}
        loading = false
    }

    val totalBalance = cashers.sumOf { it.currentBalance }
    val filteredTxs = if (dirFilter == "all") txs else txs.filter { it.direction == dirFilter }
    fun casherOf(id: Long?): CasherDto? = cashers.firstOrNull { it.id == id }

    var detailTx by remember { mutableStateOf<MoneyTransactionDto?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var formMode by remember { mutableStateOf<String?>(null) }   // null | "in_customer" | "out_expense"

    Box(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            // ===== Số dư quỹ =====
            Column(Modifier.fillMaxWidth().background(AdminColors.Card).padding(12.dp)) {
                Text("Tổng số dư quỹ hiện tại", color = AdminColors.TextMuted, fontSize = 11.sp)
                Text("${fmtMoney(totalBalance)} đ", color = AdminColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cashers.forEach { c ->
                        Column(Modifier.clip(RoundedCornerShape(6.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(c.name, color = AdminColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                            Text("${fmtMoney(c.currentBalance)} đ", color = AdminColors.Text, fontSize = 13.sp)
                        }
                    }
                }
            }

            // ===== Lọc chiều tiền =====
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "Tất cả", "in" to "Thu", "out" to "Chi").forEach { (key, label) ->
                    val sel = dirFilter == key
                    Text(
                        label, color = if (sel) AdminColors.Primary else AdminColors.TextMuted, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (sel) AdminColors.Primary.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { dirFilter = key }.padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }

            // ===== Danh sách GD =====
            AdminPullToRefresh(isRefreshing = loading, onRefresh = { refreshTick++ }, modifier = Modifier.weight(1f)) {
                if (loading && txs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
                } else if (filteredTxs.isEmpty()) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), Alignment.Center) {
                        Text("Không có giao dịch.", color = AdminColors.TextMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filteredTxs, key = { it.id }) { tx ->
                            TxRow(tx, casherOf(tx.casherId)?.name) { detailTx = tx }
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(AdminColors.Border))
                        }
                    }
                }
            }
        }

        // FAB tạo GD tiền mặt
        FloatingActionButton(
            onClick = { menuOpen = true },
            containerColor = AdminColors.Primary, contentColor = Color.White, shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Tạo giao dịch tiền mặt") }

        // ===== Overlay chi tiết GD (read-only) =====
        detailTx?.let { tx ->
            AccSheet(tx.code.ifBlank { "Giao dịch" }, onClose = { detailTx = null }) {
                DetailRow("Loại", moneyTxTypeLabel(tx.type))
                DetailRow(
                    "Số tiền",
                    (if (tx.direction == "in") "+" else if (tx.direction == "out") "−" else "") + "${fmtMoney(tx.amount)} đ",
                    if (tx.direction == "in") AdminColors.Success else AdminColors.Danger,
                )
                DetailRow("Ngày", fmtTxTime(tx.date))
                casherOf(tx.casherId)?.let { DetailRow("Sổ quỹ", "${it.name} (${casherTypeLabel(it.type)})") }
                if (!tx.bankName.isNullOrBlank() || !tx.bankAccount.isNullOrBlank())
                    DetailRow("Ngân hàng", "${tx.bankName ?: ""} ${tx.bankAccount ?: ""}".trim())
                if (!tx.purposeDetail.isNullOrBlank()) DetailRow("Đã phân bổ", tx.purposeDetail!!, AdminColors.Primary)
                Spacer(Modifier.height(4.dp))
                Text("Nội dung", color = AdminColors.TextMuted, fontSize = 12.sp)
                Text(tx.description ?: tx.reference ?: "—", color = AdminColors.Text, fontSize = 14.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!tx.debtCalc.isNullOrBlank()) MiniTag("Đã chốt công nợ", AdminColors.Danger)
                    if (!tx.moneyCalc.isNullOrBlank()) MiniTag("Đã chốt sổ quỹ", AdminColors.Danger)
                    if (tx.autoMatched) MiniTag("Tự đối soát", AdminColors.Success)
                }
            }
        }

        // ===== Overlay menu chọn loại GD =====
        if (menuOpen) {
            AccSheet("Tạo giao dịch tiền mặt", onClose = { menuOpen = false }) {
                MenuChoice("Thu tiền mặt khách hàng", "Khách hàng trả tiền mặt", AdminColors.Success) { menuOpen = false; formMode = "in_customer" }
                Spacer(Modifier.height(10.dp))
                MenuChoice("Chi tiền mặt trả phí", "Tạo & thanh toán chi phí", AdminColors.Danger) { menuOpen = false; formMode = "out_expense" }
            }
        }

        // ===== Overlay form tạo GD =====
        formMode?.let { mode ->
            val cashCashers = cashers.filter { it.type in CASH_CASHER_TYPES }
            CashTxFormSheet(
                mode = mode, userName = userName, cashCashers = cashCashers,
                onDismiss = { formMode = null },
                loadDefaultCustomers = { try { container.vapi.customersDeliveredToday(5).data ?: emptyList() } catch (_: Exception) { emptyList() } },
                searchCustomers = { q -> try { container.vapi.searchCustomers(q, 20).data ?: emptyList() } catch (_: Exception) { emptyList() } },
                loadCategories = { try { container.vapi.listExpenseCategories().data ?: emptyList() } catch (_: Exception) { emptyList() } },
                onSubmit = { req, categoryId, desc, onDone ->
                    scope.launch {
                        try {
                            if (mode == "in_customer") {
                                container.vapi.createMoneyTransaction(req)
                            } else {
                                val tx = container.vapi.createMoneyTransaction(req).data
                                if (tx != null && categoryId != null) container.vapi.payExpense(tx.id, PayExpenseRequest(categoryId, desc))
                            }
                            Toast.makeText(context, "Đã tạo giao dịch tiền mặt", Toast.LENGTH_SHORT).show()
                            formMode = null
                            refreshTick++
                        } catch (e: Exception) {
                            Toast.makeText(context, "Tạo giao dịch thất bại", Toast.LENGTH_SHORT).show()
                        } finally { onDone() }
                    }
                },
            )
        }
    }
}

@Composable
private fun TxRow(tx: MoneyTransactionDto, casherName: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                when (tx.direction) { "in" -> AdminColors.Success; "out" -> AdminColors.Danger; else -> Color(0xFFE2A03F) },
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tx.code, color = AdminColors.TextMuted, fontSize = 11.sp)
                Text(moneyTxTypeLabel(tx.type), color = AdminColors.TextMuted, fontSize = 11.sp)
            }
            Text(tx.description ?: tx.reference ?: casherName ?: "—", color = AdminColors.TextMuted, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (tx.direction == "in") "+" else if (tx.direction == "out") "−" else "") + fmtMoney(tx.amount),
                color = when (tx.direction) { "in" -> AdminColors.Success; "out" -> AdminColors.Danger; else -> AdminColors.Text },
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            Text(fmtTxTime(tx.date), color = AdminColors.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = AdminColors.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AdminColors.TextMuted, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp)
    }
}

@Composable
private fun MiniTag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun MenuChoice(title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).background(AdminColors.Bg).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = AdminColors.Text, fontSize = 14.sp)
            Text(subtitle, color = AdminColors.TextMuted, fontSize = 12.sp)
        }
    }
}

/** Form nhập GD tiền mặt — overlay Box. Default KH = đơn giao hôm nay (BE delivered-today). */
@Composable
private fun CashTxFormSheet(
    mode: String,
    userName: String,
    cashCashers: List<CasherDto>,
    onDismiss: () -> Unit,
    loadDefaultCustomers: suspend () -> List<CustomerDto>,
    searchCustomers: suspend (String) -> List<CustomerDto>,
    loadCategories: suspend () -> List<ExpenseCategoryDto>,
    onSubmit: (CreateMoneyTxRequest, Long?, String?, onDone: () -> Unit) -> Unit,
) {
    var amountStr by remember { mutableStateOf("") }
    var casherId by remember { mutableStateOf(cashCashers.firstOrNull()?.id) }
    var description by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    var custQuery by remember { mutableStateOf("") }
    var custResults by remember { mutableStateOf<List<CustomerDto>>(emptyList()) }
    var selectedCustomer by remember { mutableStateOf<CustomerDto?>(null) }
    var categories by remember { mutableStateOf<List<ExpenseCategoryDto>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<ExpenseCategoryDto?>(null) }

    LaunchedEffect(mode) { if (mode == "out_expense") categories = loadCategories() }
    // KH: query rỗng → gợi ý "giao hôm nay"; có gõ → search.
    LaunchedEffect(custQuery, mode) {
        if (mode == "in_customer" && selectedCustomer == null) {
            custResults = if (custQuery.isBlank()) loadDefaultCustomers() else { delay(280); searchCustomers(custQuery) }
        }
    }

    val amount = amountStr.toDoubleOrNull() ?: 0.0

    AccSheet(if (mode == "in_customer") "Thu tiền mặt khách hàng" else "Chi tiền mặt trả phí", onClose = onDismiss, scrollable = true) {
        if (mode == "in_customer") {
            Text("Khách hàng", color = AdminColors.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            if (selectedCustomer != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                        .clickable { selectedCustomer = null; custQuery = "" }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(selectedCustomer!!.name, color = AdminColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("Đổi", color = AdminColors.Primary, fontSize = 12.sp)
                }
            } else {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    BasicTextField(
                        value = custQuery, onValueChange = { custQuery = it },
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                        cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                        decorationBox = { inner -> if (custQuery.isEmpty()) Text("Tìm khách hàng…", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (custQuery.isBlank() && custResults.isNotEmpty())
                    Text("Giao hôm nay", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                custResults.take(6).forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selectedCustomer = c
                            description = "Khách hàng ${c.name} trả tiền mặt cho $userName"
                        }.padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = AdminColors.Text, fontSize = 14.sp)
                            c.phone?.let { Text(it, color = AdminColors.TextMuted, fontSize = 12.sp) }
                        }
                    }
                }
            }
        } else {
            DarkDropdown(
                label = "Loại phí", selectedLabel = selectedCategory?.name, placeholder = "Chọn loại phí…",
                items = categories.map { it.id to it.name },
                onSelect = { id -> selectedCategory = categories.firstOrNull { it.id == id }; description = "$userName chi tiền mặt thanh toán ${selectedCategory?.name}" },
            )
        }

        Spacer(Modifier.height(14.dp))
        // Số tiền
        Text("Số tiền (đ)", color = AdminColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 12.dp, vertical = 12.dp)) {
            BasicTextField(
                value = if (amountStr.isEmpty()) "" else fmtMoney(amount),
                onValueChange = { amountStr = it.filter { ch -> ch.isDigit() } },
                textStyle = TextStyle(color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                decorationBox = { inner -> if (amountStr.isEmpty()) Text("0", color = AdminColors.TextMuted, fontSize = 16.sp); inner() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(14.dp))
        DarkDropdown(
            label = "Quỹ tiền mặt",
            selectedLabel = cashCashers.firstOrNull { it.id == casherId }?.let { "${it.name} (${casherTypeLabel(it.type)})" },
            placeholder = "Chọn quỹ tiền mặt",
            items = cashCashers.map { it.id to "${it.name} (${casherTypeLabel(it.type)})" },
            onSelect = { casherId = it },
        )

        Spacer(Modifier.height(14.dp))
        Text("Nội dung", color = AdminColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(12.dp)) {
            BasicTextField(
                value = description, onValueChange = { description = it },
                textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                cursorBrush = SolidColor(AdminColors.Primary),
                decorationBox = { inner -> if (description.isEmpty()) Text("Nội dung giao dịch", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (amount <= 0 || casherId == null) return@Button
                if (mode == "in_customer" && selectedCustomer == null) return@Button
                if (mode == "out_expense" && selectedCategory == null) return@Button
                submitting = true
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val req = if (mode == "in_customer") CreateMoneyTxRequest(
                    type = "cust_cash_in", partyType = "customer", partyId = selectedCustomer!!.id,
                    casherId = casherId, amount = amount, date = date, description = description,
                ) else CreateMoneyTxRequest(
                    type = "pending", direction = "out", channel = "cash",
                    casherId = casherId, amount = amount, date = date, description = description,
                )
                onSubmit(req, selectedCategory?.id, description) { submitting = false }
            },
            enabled = !submitting,
            colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (submitting) "Đang tạo…" else "Tạo giao dịch") }
    }
}

/**
 * Overlay Box tự vẽ (mirror SaleOrderForm.PickerSheet): scrim tối tap-để-đóng, thẻ ở
 * giữa; taps trong thẻ KHÔNG lọt ra scrim. Ở cùng window → không bật thanh điều hướng,
 * không vuốt-xuống-tắt. padBottom = imeBottom − 86dp để thẻ nổi trên bàn phím.
 */
@Composable
private fun AccSheet(title: String, onClose: () -> Unit, scrollable: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val offsetPx = with(density) { 86.dp.toPx() }
    val padBottom = with(density) { (imeBottomPx - offsetPx).coerceAtLeast(0f).toDp() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).padding(bottom = padBottom).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(12.dp)
                .clip(RoundedCornerShape(16.dp)).background(AdminColors.Card)
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(16.dp)
                .then(if (scrollable) Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()) else Modifier)
                .clickable(enabled = false, onClick = {}),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("Đóng", color = AdminColors.Primary, fontSize = 13.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DarkDropdown(
    label: String, selectedLabel: String?, placeholder: String,
    items: List<Pair<Long, String>>, onSelect: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = AdminColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                    .clickable { open = true }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selectedLabel ?: placeholder, color = if (selectedLabel != null) AdminColors.Text else AdminColors.TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null, tint = AdminColors.TextMuted)
            }
            MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    items.forEach { (id, lbl) ->
                        DropdownMenuItem(text = { Text(lbl, color = AdminColors.Text, fontSize = 14.sp) }, onClick = { onSelect(id); open = false })
                    }
                }
            }
        }
    }
}
