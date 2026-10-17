package vn.chat9.app.ui.modules.accounting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.VatInputInvoiceDto
import vn.chat9.app.data.vapi.dto.VatInputItemDto
import vn.chat9.app.ui.explore.AdminColors
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

private val GOLD_IN = Color(0xFFD4AF37)
private val TEAL_IN = Color(0xFF2DD4BF)
private val WARN_IN = Color(0xFFE2A03F)

private fun moneyIn(n: Double): String = NumberFormat.getNumberInstance(Locale("vi")).format(Math.round(n))

/** Đơn giá: 2 số lẻ, giá chẵn bỏ ",00" — làm tròn 2 số trước khi xét chẵn. */
private fun priceIn(n: Double): String {
    val r = Math.round(n * 100) / 100.0
    val f = NumberFormat.getNumberInstance(Locale("vi"))
    f.maximumFractionDigits = 2
    f.minimumFractionDigits = if (r == Math.floor(r)) 0 else 2
    return f.format(r)
}
private fun qtyIn(n: Double): String = NumberFormat.getNumberInstance(Locale("vi")).apply { maximumFractionDigits = 4 }.format(n)
private fun rateIn(r: Double): String = if (r == Math.floor(r)) r.toLong().toString() else r.toString()
private fun dateIn(s: String?): String {
    if (s.isNullOrBlank()) return "—"
    return try {
        SimpleDateFormat("dd/MM/yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.take(10))!!)
    } catch (_: Exception) { s }
}

/** Chế độ hiển thị dòng: HĐ gốc (y nguyên TCT, không ảnh) / quy đổi trước thuế / quy đổi sau thuế. */
private enum class InMode(val label: String) { ORIGINAL("HĐ gốc"), NET("Trước thuế"), GROSS("Sau thuế") }

private data class InRow(val it: VatInputItemDto, val qty: Double, val unit: String, val price: Double, val amount: Double)

/**
 * Chi tiết HĐ MUA VÀO (chỉ xem) — mirror web AccountingVatInputDetailView:
 * header "Hóa đơn VAT - ký hiệu - số" · thẻ HĐ (ngày | MST | trạng thái, tên NCC, địa chỉ) ·
 * thẻ mặt hàng quy đổi về đơn vị đơn hàng (BE order_unit) + 3 chế độ · tổng tiền / VAT theo thuế suất.
 */
@Composable
fun VatInputDetail(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val vapi = (context.applicationContext as App).container.vapi
    var inv by remember { mutableStateOf<VatInputInvoiceDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(InMode.NET) }

    LaunchedEffect(id) {
        try { inv = vapi.getVatInputInvoice(id).data } catch (_: Exception) { error = "Không tải được HĐ mua vào" }
        loading = false
    }
    androidx.activity.compose.BackHandler(enabled = true) { onBack() }

    val items = (inv?.items ?: emptyList()).sortedBy { it.lineNo }
    // Đơn giá bám đơn giá gốc HĐ: unit_price ÷ ratio (× (1+VAT) khi sau thuế); thành tiền = số trên HĐ.
    val rows = items.map { it ->
        if (mode == InMode.ORIGINAL) InRow(it, it.quantity, it.unit ?: "", it.unitPrice, it.subtotal)
        else {
            val gross = mode == InMode.GROSS
            val ratio = it.orderUnit?.ratio ?: 1.0
            InRow(
                it, it.quantity * ratio, it.orderUnit?.name ?: it.unit ?: "",
                it.unitPrice / ratio * (if (gross) 1 + it.vatRate / 100 else 1.0),
                it.subtotal + (if (gross) it.vatAmount else 0.0),
            )
        }
    }
    // VAT gộp theo thuế suất (bỏ dòng ghi chú).
    val vatRates = items.filter { it.feature != 4 }.groupBy { it.vatRate }.toSortedMap().map { (r, l) -> r to l.sumOf { it.vatAmount } }

    Column(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        // Header: ← · Hóa đơn VAT - ký hiệu - số · badge kiểm
        Row(
            Modifier.fillMaxWidth().background(AdminColors.Card).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = AdminColors.Text,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(6.dp).size(20.dp))
            Spacer(Modifier.width(6.dp))
            inv?.let { i ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("Hóa đơn VAT", color = AdminColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (!i.series.isNullOrBlank()) {
                        Text(" - ", color = AdminColors.TextMuted, fontSize = 13.sp)
                        Text(i.series, color = AdminColors.Primary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                    Text(" - ", color = AdminColors.TextMuted, fontSize = 13.sp)
                    Text(i.number ?: "—", color = AdminColors.Primary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                val ok = i.verifiedAt != null
                val col = if (ok) AdminColors.Success else WARN_IN
                Text(if (ok) "Đã kiểm" else "Chưa kiểm", color = col, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(col.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp))
            } ?: Text("Đang tải...", color = AdminColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = AdminColors.Border)

        val i = inv
        if (i == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (loading) CircularProgressIndicator(color = AdminColors.Primary)
                else Text(error ?: "Không có dữ liệu", color = AdminColors.TextMuted, fontSize = 13.sp)
            }
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            // Thẻ HĐ: ngày | MST (giữa) | trạng thái CQT · tên người bán · đường kẻ 68% · địa chỉ
            InCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateIn(i.issueDate), color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("MST ${i.sellerTaxCode ?: ""}", color = AdminColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(i.cqtStatusLabel ?: "", color = AdminColors.TextMuted, fontSize = 11.sp, textAlign = TextAlign.End,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Text(i.sellerName ?: "—", color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.68f).height(1.dp).background(Color.White.copy(alpha = 0.4f)).align(Alignment.CenterHorizontally))
                if (!i.sellerAddress.isNullOrBlank()) Text(i.sellerAddress, color = AdminColors.TextMuted, fontSize = 11.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))

            // Thẻ mặt hàng
            InCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Mặt hàng (${items.size})", color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(6.dp)).border(0.5.dp, AdminColors.Border, RoundedCornerShape(6.dp))) {
                        InMode.entries.forEach { m ->
                            val on = mode == m
                            Text(m.label, color = if (on) AdminColors.Primary else AdminColors.TextMuted, fontSize = 11.sp,
                                modifier = Modifier.background(if (on) AdminColors.Primary.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable { mode = m }.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
                HorizontalDivider(color = AdminColors.Border, modifier = Modifier.padding(top = 8.dp))

                if (items.isEmpty()) Text("HĐ chưa có dòng hàng (TCT chưa trả chi tiết)", color = AdminColors.TextMuted, fontSize = 12.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
                rows.forEachIndexed { idx, r ->
                    if (idx > 0) HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.4f))
                    if (r.it.feature == 4) Text(r.it.itemName ?: "", color = AdminColors.TextMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(vertical = 8.dp))
                    else InItemRow(r, showImage = mode != InMode.ORIGINAL)
                }

                // Tổng: tiền hàng / VAT theo thuế suất / tổng cộng
                HorizontalDivider(color = AdminColors.Border, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(6.dp))
                InMoneyRow("Tổng tiền hàng (chưa thuế)", i.subtotal)
                if (vatRates.isNotEmpty()) vatRates.forEach { (rate, amt) -> InMoneyRow("VAT (${rateIn(rate)}%)", amt) }
                else InMoneyRow("VAT", i.vatAmount)
                HorizontalDivider(color = AdminColors.Border, modifier = Modifier.padding(top = 4.dp))
                InMoneyRow("Tổng cộng (Đã bao gồm VAT):", i.total, emphasize = true)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(0.5.dp, AdminColors.Border, RoundedCornerShape(12.dp))
            .background(AdminColors.Card).padding(12.dp),
        content = content,
    )
}

/** Dòng hàng — layout như VatItemRow HĐ bán ra (thumb 59dp · tên / SL · đơn vị × giá = thành tiền), chỉ xem. */
@Composable
private fun InItemRow(r: InRow, showImage: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showImage && r.it.imageUrl != null) AsyncImage(model = r.it.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)))
        else Box(Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(r.it.itemName ?: "", color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(qtyIn(r.qty), color = AdminColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(r.unit, color = AdminColors.TextMuted.copy(alpha = 0.6f), fontSize = 10.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text("×", color = AdminColors.TextMuted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(priceIn(r.price), color = AdminColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("=", color = AdminColors.TextMuted, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(moneyIn(r.amount), color = AdminColors.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun InMoneyRow(label: String, amount: Double, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(moneyIn(amount), color = if (emphasize) TEAL_IN else AdminColors.Text,
            fontSize = if (emphasize) 18.sp else 12.sp, fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal)
        Text(" đ", color = GOLD_IN, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light, fontSize = if (emphasize) 13.sp else 11.sp)
    }
}
