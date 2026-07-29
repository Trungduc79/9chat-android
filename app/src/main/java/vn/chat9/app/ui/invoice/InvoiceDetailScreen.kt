package vn.chat9.app.ui.invoice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.VatInputInvoiceDto
import vn.chat9.app.data.vapi.dto.VatOutputInvoiceDto
import java.text.NumberFormat
import java.util.Locale

private val moneyFmt = NumberFormat.getInstance(Locale("vi", "VN"))
private fun money(v: Double?): String = moneyFmt.format((v ?: 0.0).toLong()) + " đ"

/**
 * Chi tiết HĐ VAT READ-ONLY, mở từ thẻ invoice (deeplink 9chat://invoice/{dir}/{id}).
 * dir = "out" (bán, VatOutput) | "in" (mua, VatInput). Fetch qua VapiClient.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceDetailScreen(dir: String, id: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val vapi = (ctx.applicationContext as App).container.vapi
    val scope = rememberCoroutineScope()

    var out by remember { mutableStateOf<VatOutputInvoiceDto?>(null) }
    var inp by remember { mutableStateOf<VatInputInvoiceDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dir, id) {
        loading = true; error = null
        scope.launch {
            try {
                if (dir == "out") out = vapi.getVatOutputInvoice(id).data
                else inp = vapi.getVatInputInvoice(id).data
                if (out == null && inp == null) error = "Không tìm thấy hóa đơn"
            } catch (e: Exception) {
                error = e.message ?: "Lỗi tải hóa đơn"
            } finally { loading = false }
        }
    }

    val title = if (dir == "out") "Hóa đơn bán" else "Hóa đơn mua"

    Scaffold(
        containerColor = Color(0xFFF4F5F7),
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Đóng") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White, titleContentColor = Color(0xFF2C3E50)),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF2B6CB0))
                error != null -> Text(error!!, Modifier.align(Alignment.Center), color = Color(0xFFDC2626))
                out != null -> Body(
                    code = listOfNotNull(out!!.series, out!!.number).joinToString("-").ifBlank { "#${out!!.id}" },
                    party = out!!.buyerName ?: out!!.order?.partyName ?: "—", partyLabel = "Bên mua",
                    taxCode = null, status = out!!.cqtStatusLabel, date = out!!.issueDate,
                    subtotal = out!!.subtotal, vat = out!!.vatAmount, total = out!!.total,
                    extra = null,
                )
                inp != null -> Body(
                    code = listOfNotNull(inp!!.series, inp!!.number).joinToString("-").ifBlank { "#${inp!!.id}" },
                    party = inp!!.sellerName ?: "—", partyLabel = "Bên bán",
                    taxCode = inp!!.sellerTaxCode, status = inp!!.cqtStatusLabel, date = inp!!.issueDate,
                    subtotal = inp!!.subtotal, vat = inp!!.vatAmount, total = inp!!.total,
                    extra = if (inp!!.paymentStatus != null) ("Đã trả" to money(inp!!.paidAmount)) else null,
                )
            }
        }
    }
}

@Composable
private fun Body(
    code: String, party: String, partyLabel: String, taxCode: String?,
    status: String?, date: String?, subtotal: Double, vat: Double, total: Double,
    extra: Pair<String, String>?,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Số HĐ: $code", fontSize = 12.sp, color = Color(0xFF8593A1), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    if (status != null) Text(
                        status, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2563EB),
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x1F2563EB)).padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Text(party, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
                Text(partyLabel + (if (taxCode != null) " · MST $taxCode" else ""), fontSize = 12.sp, color = Color(0xFF8593A1))
                date?.let { Text("Ngày: $it", fontSize = 13.sp, color = Color(0xFF8593A1)) }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row2("Tiền hàng (chưa thuế)", money(subtotal))
                Row2("Thuế VAT", money(vat))
                extra?.let { Row2(it.first, it.second) }
                HorizontalDivider(Modifier.padding(vertical = 2.dp), color = Color(0xFFEEEEEE))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Tổng thanh toán", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
                    Text(money(total), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2B6CB0))
                }
            }
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = Color(0xFF8593A1))
        Text(value, fontSize = 13.sp, color = Color(0xFF2C3E50), fontWeight = FontWeight.Medium)
    }
}
