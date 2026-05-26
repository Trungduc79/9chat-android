package vn.chat9.app.ui.modules.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.ui.explore.AdminColors
import java.text.NumberFormat
import java.util.Locale

/**
 * List đơn bán do NV hiện tại tạo. Gọi `GET /v1/orders?created_by_user_id=X&type=sale`.
 *
 * MVP: list flat, không tab status (anh Đức chốt sau khi xem nhu cầu thật). Tap row =
 * (TODO) mở detail readonly. Pull-to-refresh + auto-load lúc compose.
 */
@Composable
fun SaleOrdersList() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val userId = container.tokenManager.user?.id?.toLong()

    var orders by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        if (userId == null) { error = "Chưa đăng nhập"; loading = false; return }
        scope.launch {
            loading = true; error = null
            try {
                // status="" lấy hết — BE bỏ filter khi rỗng. type=sale loại đơn purchase.
                val res = container.vapi.listOrders(
                    status = "", createdByUserId = userId, type = "sale", perPage = 100,
                )
                orders = res.data ?: emptyList()
            } catch (e: Exception) {
                error = "Tải đơn thất bại: ${e.message}"
            } finally { loading = false }
        }
    }

    LaunchedEffect(userId) { load() }

    Box(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = AdminColors.Primary)
            }
            error != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = AdminColors.Danger, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Text("Chạm để thử lại", color = AdminColors.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { load() }.padding(8.dp))
            }
            orders.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Chưa có đơn nào — nhấn + để tạo", color = AdminColors.TextMuted, fontSize = 14.sp)
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
                items(orders, key = { it.id }) { o ->
                    OrderRow(o)
                    HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun OrderRow(o: OrderDto) {
    val statusColor = when (o.status) {
        "draft" -> AdminColors.TextMuted
        "confirmed" -> AdminColors.Info
        "delivered", "completed" -> AdminColors.Success
        "cancelled" -> AdminColors.Danger
        else -> AdminColors.TextMuted
    }
    val statusLabel = when (o.status) {
        "draft" -> "Nháp"; "confirmed" -> "Đã xác nhận"
        "delivered" -> "Đã giao"; "completed" -> "Hoàn thành"
        "cancelled" -> "Huỷ"; else -> o.status
    }
    val total = o.items.sumOf { it.qtyUnit * 0.0 } // BE chưa trả total → tạm 0; v.next sẽ append qua resource

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(o.code, color = AdminColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(o.partyName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("${o.items.size} mặt hàng", color = AdminColors.TextMuted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
            Spacer(Modifier.height(4.dp))
            val fmt = NumberFormat.getNumberInstance(Locale("vi"))
            Text("${fmt.format(total.toLong())} đ", color = AdminColors.Text, fontSize = 13.sp)
        }
    }
}
