package vn.chat9.app.ui.modules.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
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
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.ui.explore.AdminColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * List đơn bán của NV — header search tên KH (70%) + dropdown ngày giao (30%),
 * đồng nhất layout với tab Sản phẩm / Khách hàng (mirror web).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleOrdersList(onTapOrder: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val userId = container.tokenManager.user?.id?.toLong()

    var orders by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var deliveryDateMs by remember { mutableStateOf<Long?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }

    fun load() {
        if (userId == null) { error = "Chưa đăng nhập"; loading = false; return }
        scope.launch {
            loading = true; error = null
            try {
                val res = container.vapi.listOrders(status = "", createdByUserId = userId, type = "sale", perPage = 100)
                orders = res.data ?: emptyList()
            } catch (e: Exception) {
                error = "Tải đơn thất bại: ${e.message}"
            } finally { loading = false }
        }
    }
    LaunchedEffect(userId) { load() }

    val dayFmt = SimpleDateFormat("dd/MM/yyyy", Locale("vi"))
    val filtered = orders.filter { o ->
        val q = query.trim().lowercase()
        val matchQ = q.isEmpty() || o.partyName.lowercase().contains(q)
        val matchDate = deliveryDateMs?.let { sel ->
            val d = o.completedAt ?: o.orderedAt
            d != null && runCatching { dayFmt.format(Date(java.time.Instant.parse(d).toEpochMilli())) }.getOrNull() == dayFmt.format(Date(sel))
        } ?: true
        matchQ && matchDate
    }

    Column(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        // Header: search 70% + ngày giao 30%
        Row(Modifier.fillMaxWidth().background(AdminColors.Card).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.7f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                    decorationBox = { inner -> if (query.isEmpty()) Text("Tìm đơn theo tên KH", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(0.3f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                    .clickable { datePickerOpen = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    deliveryDateMs?.let { dayFmt.format(Date(it)) } ?: "Ngày giao",
                    color = if (deliveryDateMs != null) AdminColors.Text else AdminColors.TextMuted,
                    fontSize = 13.sp, maxLines = 1,
                )
            }
        }

        // Vuốt xuống = reload (PullToRefreshBox).
        PullToRefreshBox(isRefreshing = loading, onRefresh = { load() }, modifier = Modifier.weight(1f)) {
            when {
                loading && orders.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
                error != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = AdminColors.Danger, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Chạm để thử lại", color = AdminColors.Primary, fontSize = 13.sp, modifier = Modifier.clickable { load() }.padding(8.dp))
                }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(if (orders.isEmpty()) "Chưa có đơn nào — nhấn + để tạo" else "Không có đơn khớp lọc", color = AdminColors.TextMuted, fontSize = 14.sp)
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    items(filtered, key = { it.id }) { o ->
                        OrderRow(o, onClick = { onTapOrder(o.id) })
                        HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    if (datePickerOpen) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = deliveryDateMs)
        LaunchedEffect(dpState.selectedDateMillis) {
            val sel = dpState.selectedDateMillis
            if (sel != null && sel != deliveryDateMs) { kotlinx.coroutines.delay(300); deliveryDateMs = sel; datePickerOpen = false }
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text, primary = AdminColors.Primary, onPrimary = Color.White)) {
            DatePickerDialog(
                onDismissRequest = { datePickerOpen = false },
                confirmButton = { TextButton(onClick = { dpState.selectedDateMillis?.let { deliveryDateMs = it }; datePickerOpen = false }) { Text("OK", color = AdminColors.Primary) } },
                dismissButton = { TextButton(onClick = { deliveryDateMs = null; datePickerOpen = false }) { Text("Xoá lọc", color = AdminColors.TextMuted) } },
                colors = DatePickerDefaults.colors(containerColor = AdminColors.Card),
            ) { DatePicker(state = dpState) }
        }
    }
}

@Composable
private fun OrderRow(o: OrderDto, onClick: () -> Unit) {
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
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(o.code, color = AdminColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(o.partyName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("${o.items.size} mặt hàng", color = AdminColors.TextMuted, fontSize = 12.sp)
        }
        Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
