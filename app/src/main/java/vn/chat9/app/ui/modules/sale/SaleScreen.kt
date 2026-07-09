package vn.chat9.app.ui.modules.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.ui.explore.AdminColors

/**
 * Module "Bán hàng" — entry tab Khám phá → Sale. 3 tab: Đơn hàng / Sản phẩm /
 * Khách hàng (mirror web SaleLayout). Tạo đơn = overlay full-screen.
 *
 * Permission gate ở [ModuleRegistry]: sale.create_order OR sale.view_orders.
 */
private enum class SaleTab(val label: String) { ORDERS("Đơn bán"), PURCHASES("Đơn nhập"), PRODUCTS("Sản phẩm"), CUSTOMERS("Khách hàng") }

@Composable
fun SaleScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(SaleTab.ORDERS) }
    var creating by remember { mutableStateOf(false) }
    var viewingOrderId by remember { mutableStateOf<Long?>(null) }   // tap đơn → chi tiết/edit
    var viewingPurchase by remember { mutableStateOf(false) }        // đơn đang tạo/xem là đơn nhập?

    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    // Quyền tạo đơn: ẩn nút nếu thiếu order.create (UX — server cũng chặn thật qua X-Staff-Phone).
    val perms by container.permissions.state.collectAsState()
    val canCreate = perms.bypass_all || "order.create" in perms.permissions

    // Menu 3 chấm header đơn nhập đã lưu
    var headerMenuOpen by remember { mutableStateOf(false) }
    var headerBusy by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (creating || viewingOrderId != null) { creating = false; viewingOrderId = null; viewingPurchase = false } else onBack()
    }

    // Overlay tạo đơn / chi tiết đơn (full-screen) — dùng chung SaleOrderForm.
    if (creating || viewingOrderId != null) {
        Column(
            Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()
                // Vuốt phải (>90px) → về trang gốc (list). Item row tự consume vuốt-trái-xoá
                // nên không xung đột; vuốt phải ở vùng trống của form sẽ thoát.
                .pointerInput(Unit) {
                    var dragAccum = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = { if (dragAccum > 90f) { creating = false; viewingOrderId = null; viewingPurchase = false }; dragAccum = 0f },
                        onDragCancel = { dragAccum = 0f },
                    ) { _, dx -> dragAccum += dx }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().background(AdminColors.Card).height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { creating = false; viewingOrderId = null; viewingPurchase = false }) { Icon(Icons.Default.ArrowBack, "Quay lại", tint = AdminColors.Text) }
                val headerTitle = when {
                    creating && viewingPurchase -> "Tạo đơn nhập"
                    creating -> "Tạo đơn bán"
                    else -> "Chi tiết đơn"
                }
                Text(headerTitle, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                // Tùy chọn 3 chấm — chỉ đơn nhập ĐÃ LƯU (đang xem chi tiết): gửi nhóm / copy / chia sẻ.
                val curId = viewingOrderId
                if (viewingPurchase && !creating && curId != null) {
                    Box {
                        IconButton(onClick = { headerMenuOpen = true }, enabled = !headerBusy) {
                            if (headerBusy) CircularProgressIndicator(Modifier.size(20.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                            else Icon(Icons.Default.MoreVert, "Tùy chọn", tint = AdminColors.Text)
                        }
                        DropdownMenu(
                            expanded = headerMenuOpen,
                            onDismissRequest = { headerMenuOpen = false },
                            modifier = Modifier
                                .background(AdminColors.Card)
                                .border(0.5.dp, AdminColors.Border, RoundedCornerShape(8.dp)),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Gửi vào nhóm đặt hàng", color = AdminColors.Text) },
                                leadingIcon = { Icon(Icons.Default.Send, null, tint = AdminColors.Text, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    headerMenuOpen = false
                                    Toast.makeText(context, "Chức năng gửi vào nhóm đặt hàng sẽ được bổ sung sau", Toast.LENGTH_SHORT).show()
                                },
                            )
                            HorizontalDivider(color = AdminColors.Border)
                            DropdownMenuItem(
                                text = { Text("Copy đơn nhập", color = AdminColors.Text) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = AdminColors.Text, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    headerMenuOpen = false
                                    scope.launch {
                                        headerBusy = true
                                        try {
                                            val nw = container.vapi.copyOrder(curId).data
                                            if (nw != null) {
                                                Toast.makeText(context, "Đã sao chép → ${nw.code}", Toast.LENGTH_SHORT).show()
                                                viewingOrderId = nw.id   // mở đơn nhập mới (vẫn viewingPurchase=true)
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Sao chép đơn nhập thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
                                        } finally { headerBusy = false }
                                    }
                                },
                            )
                            HorizontalDivider(color = AdminColors.Border)
                            DropdownMenuItem(
                                text = { Text("Chia sẻ", color = AdminColors.Text) },
                                leadingIcon = { Icon(Icons.Default.Share, null, tint = AdminColors.Text, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    headerMenuOpen = false
                                    scope.launch {
                                        try {
                                            val code = container.vapi.getOrder(curId).data?.code ?: ""
                                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_TEXT, "Đơn nhập $code")
                                            }
                                            context.startActivity(android.content.Intent.createChooser(send, "Chia sẻ đơn nhập"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Chia sẻ thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            SaleOrderForm(orderId = viewingOrderId, isPurchase = viewingPurchase, onDone = { creating = false; viewingOrderId = null; viewingPurchase = false })
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
                        else if (dragAccum > 80f) {
                            if (tab.ordinal > 0) tab = SaleTab.entries[tab.ordinal - 1]
                            else onBack()   // tab đầu (Đơn hàng) vuốt phải → thoát module về Khám phá
                        }
                        dragAccum = 0f
                    },
                ) { _, dx -> dragAccum += dx }
            },
        ) {
            when (tab) {
                SaleTab.ORDERS -> {
                    SaleOrdersList(onTapOrder = { viewingOrderId = it; viewingPurchase = false })
                    if (canCreate) {
                        FloatingActionButton(
                            onClick = { creating = true; viewingPurchase = false },
                            containerColor = AdminColors.Primary,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) { Icon(Icons.Default.Add, "Tạo đơn bán") }
                    }
                }
                SaleTab.PURCHASES -> {
                    SalePurchasesList(onTapOrder = { viewingOrderId = it; viewingPurchase = true })
                    if (canCreate) {
                        FloatingActionButton(
                            onClick = { creating = true; viewingPurchase = true },
                            containerColor = Color(0xFFEC4899),   // hồng — phân biệt với đơn bán
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) { Icon(Icons.Default.Add, "Tạo đơn nhập") }
                    }
                }
                SaleTab.PRODUCTS -> SaleProductsList()
                SaleTab.CUSTOMERS -> SaleCustomersList()
            }
        }
    }
}
