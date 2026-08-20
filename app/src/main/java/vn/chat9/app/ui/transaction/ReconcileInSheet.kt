package vn.chat9.app.ui.transaction

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.AddVatInvoicePaymentRequest
import vn.chat9.app.data.vapi.dto.AutoMatchApplyRequest
import vn.chat9.app.data.vapi.dto.CorrectMoneyTxRequest
import vn.chat9.app.data.vapi.dto.CustomerDto
import vn.chat9.app.data.vapi.dto.MatchResultDto
import vn.chat9.app.data.vapi.dto.MatchSuggestionDto
import vn.chat9.app.data.vapi.dto.MoneyTransactionDto
import vn.chat9.app.data.vapi.dto.UpdateMoneyTxPartyRequest
import vn.chat9.app.data.vapi.dto.VatInvoicePaymentLinkDto
import vn.chat9.app.data.vapi.dto.VatReceivableInvoiceDto
import vn.chat9.app.ui.explore.AdminColors
import kotlin.math.min

private fun fmt(n: Double): String = java.text.NumberFormat.getNumberInstance(java.util.Locale("vi")).format(Math.round(n))

/**
 * Sheet "Gán khách hàng" GD tiền vào — mirror web ReconcileInPanel.vue.
 *  - mode=ASSIGN   : GD pending → gán khách (+ tuỳ chọn ghi thu HĐ VAT).
 *  - mode=REASSIGN : GD đã gán, chưa chốt nợ → đổi khách.
 *  - mode=VAT_ONLY : GD baseline/đã chốt → CHỈ ghi thu HĐ VAT, không đụng công nợ KH.
 *
 * onResolved = xong việc, đóng sheet + parent refetch. onChanged = có thay đổi nhưng
 * sheet vẫn mở (huỷ 1 phiếu thu rồi gán lại ngay) → parent chỉ refetch nền.
 */
@Composable
fun ReconcileInSheet(
    tx: MoneyTransactionDto,
    mode: MoneyTxActionRules.InPanelMode,
    onClose: () -> Unit,
    onResolved: (Long) -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val vapi = (ctx.applicationContext as App).container.vapi
    val scope = rememberCoroutineScope()

    var match by remember { mutableStateOf<MatchResultDto?>(null) }
    var matching by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }

    var manualCustomerId by remember { mutableStateOf(tx.partyId) }
    var manualCustomerLabel by remember { mutableStateOf<String?>(null) }
    var custQuery by remember { mutableStateOf("") }
    var custResults by remember { mutableStateOf<List<CustomerDto>>(emptyList()) }

    var vatInvoices by remember { mutableStateOf<List<VatReceivableInvoiceDto>>(emptyList()) }
    var invoicesLoading by remember { mutableStateOf(false) }
    val selectedInvoiceIds = remember { mutableStateListOf<Long>() }
    val payments = remember { mutableStateListOf<VatInvoicePaymentLinkDto>() }

    val isVatOnly = mode == MoneyTxActionRules.InPanelMode.VAT_ONLY
    val currentCustomerId = if (tx.partyType == "customer") tx.partyId else null
    var changeReason by remember { mutableStateOf("Gán sai khách hàng — chuyển sang khách đúng") }
    val canChangeCustomer = isVatOnly && currentCustomerId != null &&
        manualCustomerId != null && manualCustomerId != currentCustomerId

    val txAmount = tx.amount
    val alreadyAllocated = payments.sumOf { it.amount }
    val budget = (txAmount - alreadyAllocated).coerceAtLeast(0.0)
    val selectedInvoices = vatInvoices.filter { it.id in selectedInvoiceIds }
    val selectedRemaining = selectedInvoices.sumOf { it.remaining }

    LaunchedEffect(Unit) {
        payments.addAll(tx.vatInvoicePayments ?: emptyList())
        matching = true
        try {
            match = vapi.suggestMoneyTxMatch(tx.id).data
        } catch (_: Exception) {
        } finally {
            matching = false
        }
    }

    // Đổi khách → nạp HĐ VAT còn nợ của khách (gần số tiền GD nhất).
    suspend fun reloadInvoices(customerId: Long) {
        invoicesLoading = true
        try {
            vatInvoices = vapi.vatReceivableForCustomer(customerId, txAmount).data ?: emptyList()
        } catch (_: Exception) {
        } finally {
            invoicesLoading = false
        }
    }
    LaunchedEffect(manualCustomerId) {
        selectedInvoiceIds.clear()
        vatInvoices = emptyList()
        manualCustomerId?.let { reloadInvoices(it) }
    }
    LaunchedEffect(custQuery) {
        if (custQuery.isBlank()) { custResults = emptyList(); return@LaunchedEffect }
        delay(280)
        custResults = try { vapi.searchCustomers(custQuery, 20).data ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    fun pickCustomer(id: Long, name: String) {
        manualCustomerId = id
        manualCustomerLabel = name
        custQuery = ""; custResults = emptyList()
    }

    // 1 CK trả NHIỀU HĐ: ghi thu lần lượt, mỗi HĐ tối đa phần còn nợ, dừng khi hết ngân sách.
    suspend fun payInvoices(): Int {
        var remainBudget = budget
        var paid = 0
        for (id in selectedInvoiceIds.toList()) {
            if (remainBudget <= 1) break
            val inv = vatInvoices.find { it.id == id } ?: continue
            val payAmt = min(inv.remaining, remainBudget)
            if (payAmt <= 0) continue
            vapi.addVatInvoicePayment(
                id,
                AddVatInvoicePaymentRequest(
                    amount = payAmt, paidAt = tx.date.take(10), method = "bank",
                    note = "Thu qua GD ${tx.code}", moneyTxId = tx.id,
                ),
            )
            remainBudget -= payAmt
            paid++
        }
        return paid
    }

    fun apply() {
        scope.launch {
            if (isVatOnly) {
                if (selectedInvoiceIds.isEmpty()) { Toast.makeText(ctx, "Chọn HĐ VAT để gán thanh toán", Toast.LENGTH_SHORT).show(); return@launch }
                applying = true
                try {
                    val n = payInvoices()
                    Toast.makeText(ctx, "Đã gán thanh toán $n HĐ VAT", Toast.LENGTH_SHORT).show()
                    onResolved(tx.id)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Gán thanh toán HĐ VAT thất bại", Toast.LENGTH_SHORT).show()
                } finally { applying = false }
                return@launch
            }
            val customerId = manualCustomerId
            if (customerId == null) { Toast.makeText(ctx, "Chọn khách hàng", Toast.LENGTH_SHORT).show(); return@launch }
            applying = true
            try {
                if (mode == MoneyTxActionRules.InPanelMode.REASSIGN) {
                    vapi.updateMoneyTxParty(tx.id, UpdateMoneyTxPartyRequest(partyId = customerId, partyType = "customer"))
                } else {
                    vapi.applyAutoMatch(tx.id, AutoMatchApplyRequest(customerId = customerId, orderId = null, casherId = null))
                }
                val n = if (selectedInvoiceIds.isNotEmpty()) payInvoices() else 0
                Toast.makeText(ctx, if (n > 0) "Đã gán khách + ghi thu $n HĐ VAT" else "Đã gán khách hàng", Toast.LENGTH_SHORT).show()
                onResolved(tx.id)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Lưu khách hàng thất bại", Toast.LENGTH_SHORT).show()
            } finally { applying = false }
        }
    }

    fun unassign() {
        scope.launch {
            applying = true
            try {
                vapi.updateMoneyTxParty(tx.id, UpdateMoneyTxPartyRequest(partyId = null, partyType = null))
                Toast.makeText(ctx, "Đã huỷ gán khách hàng", Toast.LENGTH_SHORT).show()
                onResolved(tx.id)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Huỷ gán thất bại", Toast.LENGTH_SHORT).show()
            } finally { applying = false }
        }
    }

    fun removePayment(p: VatInvoicePaymentLinkDto) {
        scope.launch {
            applying = true
            try {
                vapi.deleteVatInvoicePayment(p.id)
                payments.remove(p)
                manualCustomerId?.let { reloadInvoices(it) } // HĐ vừa gỡ nay còn nợ trở lại
                Toast.makeText(ctx, "Đã huỷ gán thanh toán HĐ VAT", Toast.LENGTH_SHORT).show()
                onChanged()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Huỷ gán thanh toán thất bại", Toast.LENGTH_SHORT).show()
            } finally { applying = false }
        }
    }

    fun changeCustomer() {
        val newId = manualCustomerId ?: return
        if (changeReason.trim().length < 5) { Toast.makeText(ctx, "Nhập lý do (tối thiểu 5 ký tự)", Toast.LENGTH_SHORT).show(); return }
        scope.launch {
            applying = true
            try {
                vapi.correctMoneyTxParty(tx.id, CorrectMoneyTxRequest(partyId = newId, reason = changeReason.trim()))
                Toast.makeText(ctx, "Đã đổi khách hàng — công nợ khách cũ đã đảo, khách mới đã post lại", Toast.LENGTH_LONG).show()
                onResolved(tx.id)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Đổi khách hàng thất bại", Toast.LENGTH_SHORT).show()
            } finally { applying = false }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(12.dp)
                .clip(RoundedCornerShape(16.dp)).background(AdminColors.Card)
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .heightIn(max = 620.dp).verticalScroll(rememberScrollState())
                .padding(16.dp).clickable(enabled = false, onClick = {}),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Gán khách hàng — ${tx.code}", color = AdminColors.Text, fontSize = 16.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                )
                Text("Đóng", color = AdminColors.Primary, fontSize = 13.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tx.code, color = AdminColors.TextMuted, fontSize = 12.sp)
                Text("+${fmt(txAmount)}đ", color = AdminColors.Success, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            tx.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = AdminColors.TextMuted, fontSize = 12.sp)
            }

            if (isVatOnly) {
                Text(
                    "Chỉ gán thanh toán cho HĐ VAT (sổ HĐ độc lập) — không đụng công nợ khách hàng / trạng thái chốt sổ.",
                    color = AdminColors.Warning, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AdminColors.Warning.copy(alpha = 0.1f))
                        .border(1.dp, AdminColors.Warning.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(8.dp),
                )
            }

            // Đã gán thanh toán HĐ VAT bằng GD này → huỷ từng cái để gán lại.
            if (payments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Đã gán thanh toán (${fmt(alreadyAllocated)}đ)", color = AdminColors.TextMuted, fontSize = 11.sp)
                    payments.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, AdminColors.Border, RoundedCornerShape(6.dp)).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("HĐ ${p.invoice?.number ?: p.vatOutputInvoiceId} · ${fmt(p.amount)}đ", color = AdminColors.Primary, fontSize = 12.sp)
                            Text(
                                "Huỷ gán", color = AdminColors.Danger, fontSize = 12.sp,
                                modifier = Modifier.clickable(enabled = !applying) { removePayment(p) }.padding(6.dp),
                            )
                        }
                    }
                }
            }

            // Gợi ý khách hàng theo nội dung CK.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Gợi ý khách hàng", color = AdminColors.TextMuted, fontSize = 11.sp)
                if (matching) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(18.dp)) }
                } else {
                    val suggestions = match?.suggestions.orEmpty().filter { it.customerId != null }
                    if (suggestions.isEmpty()) {
                        Text("Không có gợi ý — chọn khách thủ công bên dưới.", color = AdminColors.TextMuted, fontSize = 12.sp)
                    } else {
                        suggestions.forEach { s -> SuggestionRow(s) { pickCustomer(s.customerId!!, s.customerName ?: "#${s.customerId}") } }
                    }
                }
            }

            // Chọn khách thủ công.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isVatOnly) "Chọn khách để tra HĐ VAT" else "Hoặc chọn khách thủ công",
                    color = AdminColors.TextMuted, fontSize = 11.sp,
                )
                if (manualCustomerId != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                            .clickable { manualCustomerId = null; manualCustomerLabel = null }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(manualCustomerLabel ?: "#$manualCustomerId", color = AdminColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("Đổi", color = AdminColors.Primary, fontSize = 12.sp)
                    }
                } else {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        BasicTextField(
                            value = custQuery, onValueChange = { custQuery = it },
                            textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                            cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                            decorationBox = { inner -> if (custQuery.isEmpty()) Text("Gõ mã / tên khách để tìm…", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    custResults.take(8).forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { pickCustomer(c.id, c.name) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = AdminColors.Text, fontSize = 14.sp)
                                c.phone?.let { Text(it, color = AdminColors.TextMuted, fontSize = 12.sp) }
                            }
                        }
                    }
                }

                if (canChangeCustomer) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Warning.copy(alpha = 0.1f))
                            .border(1.dp, AdminColors.Warning.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Khách khác với khách đang gán trên giao dịch. Đổi sẽ ĐẢO công nợ khách cũ tại đúng ngày giao dịch rồi post lại cho khách mới — khách cũ về 0, không để lại bút toán treo.",
                            color = AdminColors.Warning, fontSize = 12.sp,
                        )
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                            BasicTextField(
                                value = changeReason, onValueChange = { changeReason = it },
                                textStyle = TextStyle(color = AdminColors.Text, fontSize = 13.sp),
                                cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            if (applying) "Đang lưu…" else "Đổi khách hàng của giao dịch",
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(AdminColors.Warning)
                                .clickable(enabled = !applying) { changeCustomer() }.padding(vertical = 8.dp),
                        )
                    }
                }
            }

            // Thanh toán cho HĐ VAT (multi-select).
            if (manualCustomerId != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Thanh toán cho Hóa đơn VAT" + (if (!isVatOnly) " (không bắt buộc)" else ""),
                        color = AdminColors.TextMuted, fontSize = 11.sp,
                    )
                    if (invoicesLoading) {
                        Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(18.dp)) }
                    } else if (vatInvoices.isEmpty()) {
                        Text("Khách chưa có HĐ VAT đã ký chưa trả đủ.", color = AdminColors.TextMuted, fontSize = 12.sp)
                    } else {
                        vatInvoices.forEach { inv ->
                            val checked = inv.id in selectedInvoiceIds
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { if (checked) selectedInvoiceIds.remove(inv.id) else selectedInvoiceIds.add(inv.id) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { if (it) selectedInvoiceIds.add(inv.id) else selectedInvoiceIds.remove(inv.id) },
                                    colors = CheckboxDefaults.colors(checkedColor = AdminColors.Primary),
                                )
                                Text(
                                    "HĐ ${inv.number ?: "—"} · Tổng ${fmt(inv.total)}đ · Còn ${fmt(inv.remaining)}đ",
                                    color = AdminColors.Text, fontSize = 13.sp,
                                )
                            }
                        }
                        if (selectedInvoiceIds.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Tiền GD còn gán được: ${fmt(budget)}đ", color = AdminColors.TextMuted, fontSize = 11.sp)
                                Text(
                                    "Đã chọn ${selectedInvoiceIds.size} HĐ: ${fmt(selectedRemaining)}đ",
                                    color = if (selectedRemaining - budget > 1) AdminColors.Warning else AdminColors.TextMuted, fontSize = 11.sp,
                                )
                            }
                            if (selectedRemaining - budget > 1) {
                                Text(
                                    "Tổng HĐ vượt tiền GD — chỉ ghi thu tới khi hết ${fmt(budget)}đ (HĐ cuối trả một phần).",
                                    color = AdminColors.Warning, fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }

            val canApply = if (isVatOnly) selectedInvoiceIds.isNotEmpty() else manualCustomerId != null
            Text(
                if (applying) "Đang lưu…" else if (isVatOnly) "Gán thanh toán HĐ VAT" else "Đối chiếu khách đã chọn",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (canApply && !applying) AdminColors.Primary else AdminColors.Border)
                    .clickable(enabled = canApply && !applying) { apply() }.padding(vertical = 10.dp),
            )
            // Huỷ gán KH: chỉ khi GD đã gán (reassign) & chưa chốt nợ.
            if (mode == MoneyTxActionRules.InPanelMode.REASSIGN && tx.debtCalc.isNullOrBlank()) {
                Text(
                    if (applying) "Đang lưu…" else "Huỷ gán khách hàng",
                    color = AdminColors.Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .border(1.dp, AdminColors.Danger.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable(enabled = !applying) { unassign() }.padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(s: MatchSuggestionDto, onPick: () -> Unit) {
    val strong = s.score >= 70
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (strong) AdminColors.Success.copy(alpha = 0.1f) else AdminColors.Bg)
            .border(1.dp, if (strong) AdminColors.Success else AdminColors.Border, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.customerName ?: "#${s.customerId}", color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${s.reasons.joinToString(" · ")} · ${s.score}đ", color = AdminColors.TextMuted, fontSize = 11.sp, maxLines = 1)
        }
        Text(
            "Chọn", color = Color.White, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AdminColors.Primary).clickable(onClick = onPick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
