package vn.chat9.app.ui.modules.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.CustomerDto
import vn.chat9.app.ui.explore.AdminColors
import java.text.NumberFormat
import java.util.Locale

/**
 * Tab Khách hàng (Android) — port web SaleCustomersView. Search tên KH; mặc định
 * 20 KH gần nhất theo đơn của NV. Row: avatar + tên + phone + công nợ.
 */
@Composable
fun SaleCustomersList() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val userId = container.tokenManager.user?.id?.toLong()

    var query by remember { mutableStateOf("") }
    var customers by remember { mutableStateOf<List<CustomerDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(query) {
        loading = true
        try {
            customers = if (query.isBlank()) {
                if (userId != null) container.vapi.recentCustomers(userId, 20).data ?: emptyList() else emptyList()
            } else { delay(280); container.vapi.searchCustomers(query, 50).data ?: emptyList() }
        } catch (_: Exception) {}
        loading = false
    }

    val fmt = NumberFormat.getNumberInstance(Locale("vi"))

    Column(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        // Header: search 70% + dropdown TODO 30% (chưa làm — đồng nhất layout 3 tab)
        Row(Modifier.fillMaxWidth().background(AdminColors.Card).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.7f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                    decorationBox = { inner -> if (query.isEmpty()) Text("Tìm khách hàng theo tên", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(0.3f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text("TODO", color = AdminColors.TextMuted, fontSize = 13.sp, maxLines = 1)
            }
        }

        if (loading && customers.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
        else if (customers.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Không có khách hàng", color = AdminColors.TextMuted) }
        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isBlank()) item { Text("20 khách hàng gần nhất", color = AdminColors.TextMuted, fontSize = 11.sp) }
            items(customers, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(AdminColors.Primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Text((c.name.firstOrNull() ?: '?').uppercase(), color = AdminColors.Primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        c.phone?.let { Text(it, color = AdminColors.TextMuted, fontSize = 12.sp) }
                    }
                    val debt = c.debtBalance ?: 0.0
                    if (debt != 0.0) Column(horizontalAlignment = Alignment.End) {
                        Text("Công nợ", color = AdminColors.TextMuted, fontSize = 11.sp)
                        Text("${fmt.format(debt.toLong())} đ", color = if (debt > 0) AdminColors.Danger else AdminColors.Success, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
