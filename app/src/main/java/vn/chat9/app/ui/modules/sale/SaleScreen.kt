package vn.chat9.app.ui.modules.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.chat9.app.ui.explore.AdminColors

/**
 * Module "Bán hàng" — entry tab Khám phá → Sale. 3 tab: Đơn hàng / Sản phẩm /
 * Khách hàng (mirror web SaleLayout). Tạo đơn = overlay full-screen.
 *
 * Permission gate ở [ModuleRegistry]: sale.create_order OR sale.view_orders.
 */
private enum class SaleTab(val label: String) { ORDERS("Đơn hàng"), PRODUCTS("Sản phẩm"), CUSTOMERS("Khách hàng") }

@Composable
fun SaleScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(SaleTab.ORDERS) }
    var creating by remember { mutableStateOf(false) }
    var viewingOrderId by remember { mutableStateOf<Long?>(null) }   // tap đơn → chi tiết/edit

    androidx.activity.compose.BackHandler(enabled = true) {
        if (creating || viewingOrderId != null) { creating = false; viewingOrderId = null } else onBack()
    }

    // Overlay tạo đơn / chi tiết đơn (full-screen) — dùng chung SaleOrderForm.
    if (creating || viewingOrderId != null) {
        Column(Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().background(AdminColors.Card).height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { creating = false; viewingOrderId = null }) { Icon(Icons.Default.ArrowBack, "Quay lại", tint = AdminColors.Text) }
                Text(if (creating) "Tạo đơn bán" else "Chi tiết đơn", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            SaleOrderForm(orderId = viewingOrderId, onDone = { creating = false; viewingOrderId = null })
        }
        return
    }

    Column(Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()) {
        // Tab bar 3 tab (BỎ nút back — dùng system back để thoát module).
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = AdminColors.Card,
            contentColor = AdminColors.Primary,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            SaleTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label, fontSize = 13.sp, color = if (tab == t) AdminColors.Primary else AdminColors.TextMuted) },
                )
            }
        }

        // Vuốt trái/phải đổi tab (threshold 80px). Vuốt xuống reload trong từng list.
        var dragAccum by remember { mutableStateOf(0f) }
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(tab) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAccum < -80f && tab.ordinal < SaleTab.entries.lastIndex) tab = SaleTab.entries[tab.ordinal + 1]
                        else if (dragAccum > 80f && tab.ordinal > 0) tab = SaleTab.entries[tab.ordinal - 1]
                        dragAccum = 0f
                    },
                ) { _, dx -> dragAccum += dx }
            },
        ) {
            when (tab) {
                SaleTab.ORDERS -> {
                    SaleOrdersList(onTapOrder = { viewingOrderId = it })
                    FloatingActionButton(
                        onClick = { creating = true },
                        containerColor = AdminColors.Primary,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Icon(Icons.Default.Add, "Tạo đơn bán") }
                }
                SaleTab.PRODUCTS -> SaleProductsList()
                SaleTab.CUSTOMERS -> SaleCustomersList()
            }
        }
    }
}
