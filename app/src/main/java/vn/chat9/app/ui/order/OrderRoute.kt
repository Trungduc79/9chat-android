package vn.chat9.app.ui.order

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import vn.chat9.app.App
import vn.chat9.app.ui.modules.sale.SaleScreen
import vn.chat9.app.ui.modules.warehouse.WarehouseOrderDetail

/**
 * Định tuyến thẻ order → đúng màn chi tiết theo QUYỀN người xem:
 *   - Admin (bypass_all)      → SaleOrderForm (sửa full).
 *   - NV Kho (warehouse.*)    → WarehouseOrderDetail (tự adapt: chưa giao→fulfill, đã giao→hoàn thành).
 *   - NV Sale (order.*)       → đơn của mình (creator_9chat_user_id == tôi) → SaleOrderForm sửa;
 *                               không phải → chỉ xem (tạm OrderDetailScreen; Phase C = SaleOrderForm readOnly).
 *   - Khách (không quyền NV)  → chỉ xem (tạm OrderDetailScreen; Phase C = SaleOrderForm readOnly+customerView).
 *
 * Vào qua deep-link 9chat://order/{id} → AppDestination.OrderDetail → màn này.
 */
@Composable
fun OrderRoute(orderId: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as App).container
    val perms = container.permissions
    val myId = container.tokenManager.user?.id?.toLong() ?: 0L

    val isAdmin = perms.isBypass
    val isWarehouse = perms.hasAny("warehouse.read", "warehouse.manage")
    val isSale = perms.hasAny("order.create", "order.read", "sale.create_order", "sale.view_orders")

    when {
        // Kho ưu tiên trước sale (NV kho → màn kho). Admin bypass đi nhánh sale full-edit.
        isWarehouse && !isAdmin -> WarehouseOrderDetail(
            orderId = orderId, siblingIds = emptyList(), warehouseName = null,
            onNavigate = {}, onFulfilled = onClose, onClose = onClose,
        )
        // Admin bypass → màn Sale ĐẦY ĐỦ (header + menu 3 chấm + form), sửa full.
        isAdmin -> SaleScreen(onBack = onClose, initialOrderId = orderId)
        isSale -> {
            // Cần biết "đơn của mình" → fetch creator.
            var own by remember(orderId) { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(orderId) {
                val o = runCatching { container.warehouseRepo.getOrder(orderId) }.getOrNull()
                val owner = o?.creator9chatUserId ?: o?.createdByUserId
                own = owner != null && owner == myId
            }
            when (own) {
                null -> Loading()
                true -> SaleScreen(onBack = onClose, initialOrderId = orderId)
                false -> OrderDetailScreen(orderId = orderId, onClose = onClose) // TODO Phase C: SaleOrderForm readOnly
            }
        }
        else -> OrderDetailScreen(orderId = orderId, onClose = onClose) // khách — TODO Phase C: readOnly+customerView
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize()) {
        CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF3E1F91))
    }
}
