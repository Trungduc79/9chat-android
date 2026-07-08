package vn.chat9.app.ui.modules.accounting

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.data.vapi.dto.VatOutputInvoiceDto
import vn.chat9.app.ui.explore.AdminColors
import vn.chat9.app.ui.explore.AdminPullToRefresh
import vn.chat9.app.ui.modules.sale.SaleVatForm
import java.text.NumberFormat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

private val nfVat = NumberFormat.getNumberInstance(Locale("vi"))
private fun moneyVat(n: Double): String = nfVat.format(n.toLong())
private fun fmtDateVat(s: String?): String {
    if (s.isNullOrBlank()) return "—"
    return try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.take(10))
        SimpleDateFormat("dd/MM/yyyy", Locale.US).format(d!!)
    } catch (_: Exception) { s }
}
/** Parse ISO timestamp → epoch millis (0 nếu rỗng/lỗi) — dùng sort giảm dần. */
private fun tsVat(s: String?): Long {
    if (s.isNullOrBlank()) return 0L
    return try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(s.take(19))?.time ?: 0L
    } catch (_: Exception) { 0L }
}
private fun noAccentVat(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('đ', 'd').replace('Đ', 'D').lowercase()

private val GOLD_VAT = Color(0xFFD4AF37)
private val WARN = Color(0xFFE2A03F)

/**
 * Tab VAT (Android) — port SaleVatListView. Nguồn: vat_output_invoices (HĐ đầu ra) +
 * đơn VAT_only chưa có HĐ liên kết ("đơn nháp chưa xuất" — cho xoá dọn rác).
 * Phase 3A: list + xoá nháp + mở đơn sửa items. Ký EI/PO/tạo mới HĐ ở Phase 3B.
 */
@Composable
fun AccountingVatTab() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var invoices by remember { mutableStateOf<List<VatOutputInvoiceDto>>(emptyList()) }
    var drafts by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var deletingId by remember { mutableStateOf<Long?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var formOrderId by remember { mutableStateOf<Long?>(null) }   // null + showForm = tạo mới

    LaunchedEffect(refreshTick) {
        loading = true
        try {
            invoices = container.vapi.listVatOutputInvoices(200).data ?: emptyList()
            val ord = container.vapi.listOrders(type = "sale", invoiceOnly = "1", perPage = 100).data ?: emptyList()
            drafts = ord.filter { it.vatOutputInvoiceId == null }   // chỉ đơn chưa gắn HĐ
        } catch (_: Exception) {}
        loading = false
    }

    val q = noAccentVat(query.trim())
    val fInvoices = invoices
        .filter { q.isBlank() || noAccentVat("${it.customerName} ${it.buyerName ?: ""}").contains(q) }
        .sortedWith(
            // Tier 2 (HĐ nháp EI chưa PH) trước Tier 3 (đã PH); tier 2 theo created_at ↓, tier 3 theo số HĐ ↓.
            compareBy<VatOutputInvoiceDto> { if (it.signed) 1 else 0 }
                .thenByDescending { if (it.signed) 0L else tsVat(it.createdAt) }
                .thenByDescending { if (it.signed) (it.number?.toLongOrNull() ?: 0L) else 0L }
        )
    val fDrafts = drafts
        .filter { q.isBlank() || noAccentVat(it.partyName).contains(q) }
        // Tier 1: theo thời gian tạo đơn ↓.
        .sortedByDescending { tsVat(it.orderedAt ?: it.createdAt) }

    fun doDelete(o: OrderDto) {
        if (deletingId != null) return
        deletingId = o.id
        scope.launch {
            try {
                container.vapi.deleteOrder(o.id)
                drafts = drafts.filter { it.id != o.id }
                Toast.makeText(context, "Đã xoá đơn ${o.code}", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Xoá đơn thất bại", Toast.LENGTH_SHORT).show()
            } finally { deletingId = null }
        }
    }

    // Overlay form HĐ VAT (tạo mới / mở đơn nháp / xem HĐ).
    if (showForm) {
        androidx.activity.compose.BackHandler(enabled = true) { showForm = false }
        SaleVatForm(orderId = formOrderId, onDone = { showForm = false; refreshTick++ })
        return
    }

    var confirmDelete by remember { mutableStateOf<OrderDto?>(null) }

    Box(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            // Thanh tìm kiếm
            Box(Modifier.fillMaxWidth().background(AdminColors.Card).padding(12.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    BasicTextField(
                        value = query, onValueChange = { query = it },
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                        cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                        decorationBox = { inner -> if (query.isEmpty()) Text("Tìm theo tên đơn vị / khách", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            AdminPullToRefresh(isRefreshing = loading, onRefresh = { refreshTick++ }, modifier = Modifier.weight(1f)) {
                if (loading && invoices.isEmpty() && drafts.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
                } else if (fInvoices.isEmpty() && fDrafts.isEmpty()) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Chưa có HĐ VAT nào", color = AdminColors.TextMuted, fontSize = 14.sp)
                            Text("Nhấn + để tạo HĐ VAT", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    // Mục dọn rác: đơn nháp chưa xuất HĐ
                    if (fDrafts.isNotEmpty()) {
                        item {
                            Row(Modifier.padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Warning, null, tint = WARN, modifier = Modifier.size(14.dp))
                                Text("Đơn nháp chưa xuất HĐ (${fDrafts.size})", color = WARN, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        items(fDrafts, key = { "d${it.id}" }) { o ->
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
                                    .border(0.5.dp, WARN.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).background(AdminColors.Card).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).clickable { formOrderId = o.id; showForm = true }) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(o.code, color = AdminColors.Primary, fontSize = 13.sp)
                                        Text("Chưa xuất HĐ", color = AdminColors.TextMuted, fontSize = 10.sp,
                                            lineHeight = 10.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AdminColors.TextMuted.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 0.dp))
                                    }
                                    Text(o.partyName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                                    Text("${o.items.size} mặt hàng · ${fmtDateVat(o.orderedAt)}", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
                                }
                                Icon(
                                    Icons.Default.Delete, "Xoá", tint = AdminColors.Danger,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = deletingId != o.id) { confirmDelete = o }.padding(8.dp).size(20.dp),
                                )
                            }
                        }
                    }

                    // Danh sách HĐ VAT (vat_output_invoices)
                    if (fInvoices.isNotEmpty()) {
                        if (fDrafts.isNotEmpty()) item {
                            Text("Hóa đơn VAT (${fInvoices.size})", color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                        } else item { Spacer(Modifier.height(12.dp)) }
                        items(fInvoices, key = { "i${it.id}" }) { i ->
                            VatInvoiceCard(i, onClick = {
                                if (i.orderId != null) { formOrderId = i.orderId; showForm = true }
                                else Toast.makeText(context, "HĐ này không gắn đơn (đồng bộ TCT/EI) — xem trên máy tính.", Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }

        // FAB tạo HĐ VAT mới.
        Box(
            Modifier.align(Alignment.BottomEnd).padding(20.dp).size(56.dp).clip(RoundedCornerShape(28.dp))
                .background(AdminColors.Primary).clickable { formOrderId = null; showForm = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Add, "Tạo HĐ VAT", tint = Color.White, modifier = Modifier.size(28.dp)) }
    }

    // Xác nhận xoá đơn nháp
    confirmDelete?.let { o ->
        VatConfirmOverlay(
            title = "Xoá đơn nháp",
            message = "Xoá đơn ${o.code} (${o.partyName})? Không hoàn tác được.",
            onCancel = { confirmDelete = null },
            onConfirm = { confirmDelete = null; doDelete(o) },
        )
    }
}

@Composable
private fun VatInvoiceCard(i: VatOutputInvoiceDto, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
            .border(0.5.dp, AdminColors.Border, RoundedCornerShape(12.dp)).background(AdminColors.Card)
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        // Dòng 1: tên KH · ngày
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(i.customerName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
            Text(fmtDateVat(i.issueDate), color = AdminColors.TextMuted, fontSize = 11.sp)
        }
        // Dòng 2: tên đơn vị mua — hiển thị ĐẦY ĐỦ (wrap nhiều dòng, không cắt).
        Text(i.buyerName ?: "—", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        // Dòng 3: tình trạng + mã EI (trái) · số tiền (phải)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val label = if (i.signed) (i.number?.let { "Đã PH · Số $it" } ?: "Đã phát hành") else "HĐ nháp EI"
                val col = if (i.signed) AdminColors.Success else WARN
                Text(label, color = col, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    lineHeight = 10.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(col.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 0.dp))
                if (i.number.isNullOrBlank() && !i.eiLookupCode.isNullOrBlank()) Text(i.eiLookupCode, color = AdminColors.Primary, fontSize = 10.sp, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(moneyVat(i.total), color = AdminColors.Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(" đ", color = GOLD_VAT, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light, fontSize = 14.sp)
            }
        }
    }
}

/** Overlay xác nhận (Box-overlay, không dùng Dialog để giữ ẩn nav bar). */
@Composable
private fun VatConfirmOverlay(title: String, message: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onCancel)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(20.dp).clickable(enabled = false) {},
        ) {
            Text(title, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(message, color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Huỷ", color = AdminColors.TextMuted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).clickable { onCancel() }.padding(vertical = 10.dp),
                )
                Text(
                    "Xoá", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Danger).clickable { onConfirm() }.padding(vertical = 10.dp),
                )
            }
        }
    }
}
