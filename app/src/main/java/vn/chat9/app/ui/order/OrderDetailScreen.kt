package vn.chat9.app.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.data.vapi.dto.OrderItemDto
import java.text.NumberFormat
import java.util.Locale

private val moneyFmt = NumberFormat.getInstance(Locale("vi", "VN"))
private fun money(v: Double?): String = (moneyFmt.format((v ?: 0.0).toLong())) + " đ"

private val TYPE_LABEL = mapOf(
    "sale" to "Đơn bán", "purchase" to "Đơn nhập",
    "customer_return" to "Khách trả", "supplier_return" to "Trả NCC",
)
private fun statusInfo(s: String): Pair<String, Color> = when (s) {
    "draft" -> "Nháp" to Color(0xFF667085)
    "confirmed" -> "Đã duyệt" to Color(0xFF2563EB)
    "processing" -> "Đang xử lý" to Color(0xFF2563EB)
    "shipped" -> "Đang giao" to Color(0xFF2563EB)
    "delivered", "received", "completed" -> (if (s == "received") "Đã nhận" else if (s == "completed") "Hoàn tất" else "Đã giao") to Color(0xFF16A34A)
    "canceled", "cancelled" -> "Đã huỷ" to Color(0xFFDC2626)
    else -> s to Color(0xFF667085)
}

/**
 * Màn chi tiết đơn hàng READ-ONLY, mở từ thẻ order trong chat (tap → deeplink).
 * Fetch qua VapiClient (warehouseRepo.getOrder). Không sửa — chỉ xem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(orderId: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as App).container.warehouseRepo
    val scope = rememberCoroutineScope()

    var order by remember { mutableStateOf<OrderDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(orderId) {
        loading = true; error = null
        scope.launch {
            try {
                order = repo.getOrder(orderId)
                if (order == null) error = "Không tìm thấy đơn"
            } catch (e: Exception) {
                error = e.message ?: "Lỗi tải đơn"
            } finally { loading = false }
        }
    }

    Scaffold(
        containerColor = Color(0xFFF4F5F7),
        topBar = {
            TopAppBar(
                title = { Text(order?.code ?: "Chi tiết đơn", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Đóng")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White, titleContentColor = Color(0xFF2C3E50),
                ),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF3E1F91))
                error != null -> Text(error!!, Modifier.align(Alignment.Center), color = Color(0xFFDC2626))
                order != null -> OrderBody(order!!)
            }
        }
    }
}

@Composable
private fun OrderBody(o: OrderDto) {
    val (stLabel, stColor) = statusInfo(o.status)
    val itemsSubtotal = o.items.sumOf { it.qtyUnit * it.unitPrice }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header card
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((TYPE_LABEL[o.type] ?: "Đơn") + " · " + o.code, fontSize = 12.sp, color = Color(0xFF8593A1), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            stLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = stColor,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(stColor.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                    Text(o.partyName, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
                    (o.orderedAt ?: o.createdAt)?.let { Text("Ngày: $it", fontSize = 13.sp, color = Color(0xFF8593A1)) }
                    o.notes?.takeIf { it.isNotBlank() }?.let { Text("Ghi chú: $it", fontSize = 13.sp, color = Color(0xFF5A6770)) }
                }
            }
        }

        // Items
        item { Text("MẶT HÀNG (${o.items.size})", fontSize = 12.sp, color = Color(0xFF8593A1), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
        items(o.items) { it -> OrderItemRow(it) }

        // Totals
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TotalRow("Tạm tính", money(itemsSubtotal))
                    o.shippingFee?.takeIf { it != 0.0 }?.let { TotalRow("Phí ship", money(it)) }
                    o.discountAmount?.takeIf { it != 0.0 }?.let { TotalRow("Giảm giá", "-" + money(it)) }
                    HorizontalDivider(Modifier.padding(vertical = 2.dp), color = Color(0xFFEEEEEE))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Tổng cộng", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
                        Text(money(o.totalAmount), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3E1F91))
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderItemRow(it: OrderItemDto) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(it.productName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2C3E50))
                val qty = (if (it.qtyUnit % 1.0 == 0.0) it.qtyUnit.toLong().toString() else it.qtyUnit.toString())
                Text("$qty ${it.unitName} × ${money(it.unitPrice)}", fontSize = 12.sp, color = Color(0xFF5A6770))
            }
            Text(money(it.qtyUnit * it.unitPrice), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = Color(0xFF8593A1))
        Text(value, fontSize = 13.sp, color = Color(0xFF2C3E50), fontWeight = FontWeight.Medium)
    }
}
