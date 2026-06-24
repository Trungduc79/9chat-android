package vn.chat9.app.ui.modules.warehouse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.AttachmentDto
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.data.vapi.dto.WarehouseDto
import vn.chat9.app.ui.explore.AdminColors

/**
 * Root module Vận hành kho. Sở hữu tab + data; TabRow LUÔN hiển thị (kể cả khi xem
 * chi tiết đơn). Nội dung (list ↔ detail) bọc trong AnimatedContent → slide khi:
 * đổi tab, mở/đóng đơn, vuốt qua đơn. Hướng slide theo cờ `forward`.
 * Back hệ thống: detail→list, list→thoát module.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseScreen(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as App).container
    val repo = container.warehouseRepo
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openOrderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var photosOpen by rememberSaveable { mutableStateOf(false) }   // Ảnh chụp: mở từ menu 3 chấm (không còn là tab)
    var siblingIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var forward by remember { mutableStateOf(true) }

    var confirmed by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var done by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var photos by remember { mutableStateOf<List<AttachmentDto>>(emptyList()) }
    var doneLoaded by remember { mutableStateOf(false) }
    var photosLoaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }   // TEST tạm: pull-to-refresh
    var error by remember { mutableStateOf<String?>(null) }

    // Kho làm việc — bắt buộc chọn trước khi xem đơn. Persist trong TokenManager.
    var warehouses by remember { mutableStateOf<List<WarehouseDto>>(emptyList()) }
    var selectedWarehouseId by remember { mutableStateOf(container.tokenManager.selectedWarehouseId) }
    var pickerOpen by remember { mutableStateOf(false) }
    val currentWarehouse = warehouses.firstOrNull { it.id == selectedWarehouseId }

    LaunchedEffect(Unit) {
        try { warehouses = repo.listWarehouses() } catch (_: Exception) {}
    }

    // Reload đơn confirmed khi đổi kho làm việc (key=warehouseId).
    LaunchedEffect(selectedWarehouseId) {
        if (selectedWarehouseId == null) return@LaunchedEffect
        loading = true; error = null; doneLoaded = false
        try { confirmed = repo.listOrders("confirmed", selectedWarehouseId) }
        catch (e: Exception) { error = "Không tải được đơn" }
        loading = false
    }
    LaunchedEffect(tab, selectedWarehouseId) {
        if (selectedWarehouseId == null) return@LaunchedEffect
        if (tab == 2 && !doneLoaded) {
            loading = true; error = null
            try {
                done = repo.listOrders("delivered", selectedWarehouseId) + repo.listOrders("received", selectedWarehouseId)
                doneLoaded = true
            } catch (e: Exception) { error = "Không tải được đơn hoàn thành" }
            loading = false
        }
    }
    // Ảnh chụp tải khi mở từ menu (không gắn tab nữa).
    LaunchedEffect(photosOpen, selectedWarehouseId) {
        if (photosOpen && !photosLoaded && selectedWarehouseId != null) {
            loading = true; error = null
            try { photos = repo.allPhotos(); photosLoaded = true }
            catch (e: Exception) { error = "Không tải được ảnh" }
            loading = false
        }
    }

    // Tự mở picker nếu chưa chọn kho (sau khi warehouses load xong).
    LaunchedEffect(warehouses, selectedWarehouseId) {
        if (selectedWarehouseId == null && warehouses.isNotEmpty()) pickerOpen = true
    }

    val saleList = remember(confirmed) { confirmed.filter { !isPurchaseOrder(it) }.sortedBy(::sentToWhTime) }
    val purchaseList = remember(confirmed) { confirmed.filter { isPurchaseOrder(it) }.sortedBy(::sentToWhTime) }
    val doneList = remember(done) {
        val seeAll = container.permissions.isBypass || container.permissions.hasRole("manager")
        val myId = container.tokenManager.user?.id?.toLong()
        done.filter { isToday(it.fulfilledRealTime) && (seeAll || it.meta?.fulfillment?.byUserId == myId) }
            .sortedByDescending { it.fulfilledRealTime ?: "" }
    }
    fun listFor(t: Int): List<OrderDto> = when (t) { 0 -> saleList; 1 -> purchaseList; 2 -> doneList; else -> emptyList() }

    // Back: detail → list; ảnh chụp → đóng; list → thoát module. (Mở ảnh rồi tap đơn:
    // back đóng detail trước, vẫn ở ảnh; back nữa đóng ảnh.)
    BackHandler(enabled = openOrderId != null || photosOpen) {
        forward = false
        if (openOrderId != null) openOrderId = null else photosOpen = false
    }

    fun goTab(t: Int) { if (t in 0..3) { forward = t > tab; tab = t; openOrderId = null; photosOpen = false } }

    // Vuốt xuống reload data đang xem (ảnh chụp / đơn theo tab).
    fun refresh() {
        scope.launch {
            refreshing = true; error = null
            try {
                when {
                    photosOpen -> { photos = repo.allPhotos(); photosLoaded = true }
                    tab == 0 || tab == 1 -> confirmed = repo.listOrders("confirmed", selectedWarehouseId)
                    tab == 2 -> {
                        done = repo.listOrders("delivered", selectedWarehouseId) +
                            repo.listOrders("received", selectedWarehouseId)
                        doneLoaded = true
                    }
                    // tab == 3 (Kiểm kho) chưa có data
                }
            } catch (e: Exception) { error = "Không tải được dữ liệu" }
            refreshing = false
        }
    }

    // Sau khi xác nhận giao/nhận THÀNH CÔNG: nạp lại confirmed (đơn bán/nhập) + done (hoàn thành)
    // → cập nhật trạng thái đơn + số đếm mọi tab ngay, không cần vuốt refresh.
    fun reloadAfterFulfill() {
        scope.launch {
            try {
                confirmed = repo.listOrders("confirmed", selectedWarehouseId)
                done = repo.listOrders("delivered", selectedWarehouseId) +
                    repo.listOrders("received", selectedWarehouseId)
                doneLoaded = true
            } catch (_: Exception) { /* giữ data cũ nếu lỗi mạng */ }
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    val userName = container.tokenManager.user?.username ?: "—"

    Column(Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()) {
        // TabRow + 3-dot menu cuối (mirror NTabs #suffix của web). Bỏ status row trên cùng.
        Row(
            modifier = Modifier.fillMaxWidth().background(AdminColors.Card).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = AdminColors.Card,
                contentColor = AdminColors.Primary,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                WhTab(tab == 0, "Đơn bán", saleList.size, AdminColors.Warning) { goTab(0) }
                WhTab(tab == 1, "Đơn nhập", purchaseList.size, AdminColors.Info) { goTab(1) }
                WhTab(tab == 2, "Hoàn thành", doneList.size, AdminColors.Success) { goTab(2) }
                WhTab(tab == 3, "Kiểm kho", 0, null) { goTab(3) }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.width(40.dp)) {
                    Icon(Icons.Default.MoreVert, "Tùy chọn", tint = AdminColors.TextMuted)
                }
                // Wrap MaterialTheme dark → DropdownMenu container nền tối (mặc định M3 = surface light).
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = AdminColors.Card,
                        onSurface = AdminColors.Text,
                        onSurfaceVariant = AdminColors.TextMuted,
                    ),
                ) {
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val itemColors = MenuDefaults.itemColors(
                            textColor = AdminColors.Text,
                            disabledTextColor = AdminColors.TextMuted,
                            leadingIconColor = AdminColors.Primary,
                        )
                        DropdownMenuItem(
                            text = { Text("Tài khoản: $userName", fontSize = 13.sp) },
                            onClick = { menuOpen = false },
                            enabled = false,
                            colors = itemColors,
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Kho làm việc: ${currentWarehouse?.name ?: "Chưa chọn"}",
                                    color = if (currentWarehouse == null) AdminColors.Danger else AdminColors.Text,
                                    fontSize = 14.sp,
                                )
                            },
                            onClick = { menuOpen = false; pickerOpen = true },
                            leadingIcon = { Icon(Icons.Default.Warehouse, null, tint = AdminColors.Primary) },
                            colors = itemColors,
                        )
                        DropdownMenuItem(
                            text = { Text("Ảnh chụp", fontSize = 14.sp) },
                            onClick = { menuOpen = false; forward = true; openOrderId = null; photosOpen = true },
                            leadingIcon = { Icon(Icons.Default.Image, null, tint = AdminColors.Primary) },
                            colors = itemColors,
                        )
                        DropdownMenuItem(
                            text = { Text("Tải lại", fontSize = 14.sp) },
                            onClick = { menuOpen = false; refresh() },
                            colors = itemColors,
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = Triple(openOrderId, tab, photosOpen),
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally { dir * it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -dir * it } + fadeOut())
                },
                label = "warehouseContent",
            ) { (oid, t, ph) ->
                if (oid != null) {
                    WarehouseOrderDetail(
                        orderId = oid,
                        siblingIds = siblingIds,
                        warehouseName = currentWarehouse?.name,
                        onNavigate = { newId ->
                            forward = siblingIds.indexOf(newId) > siblingIds.indexOf(openOrderId)
                            openOrderId = newId
                        },
                        onFulfilled = { reloadAfterFulfill() },
                        onClose = { forward = false; openOrderId = null },
                    )
                } else if (!ph && t == 3) {
                    // Tab Kiểm kho — màn tự chứa (header + list + nút lưu), không qua PullToRefreshBox chung.
                    WarehouseStocktake(warehouseId = selectedWarehouseId, warehouseName = currentWarehouse?.name)
                } else {
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = { refresh() },
                        state = pullState,
                        modifier = Modifier.fillMaxSize(),
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                state = pullState,
                                isRefreshing = refreshing,
                                modifier = Modifier.align(Alignment.TopCenter),
                                containerColor = AdminColors.Card,
                                color = AdminColors.Primary,
                            )
                        },
                    ) {
                        if (ph) {
                            WarehousePhotosGrid(
                                photos = photos,
                                loading = loading,
                                error = error,
                                onOpenPhoto = { id, ids -> siblingIds = ids; forward = true; openOrderId = id },
                            )
                        } else {
                            WarehouseOrdersList(
                                tab = t,
                                list = listFor(t),
                                loading = loading,
                                error = error,
                                warehouseName = currentWarehouse?.name,
                                onOpenOrder = { id, ids -> siblingIds = ids; forward = true; openOrderId = id },
                                onTabDelta = { d -> goTab(tab + d) },
                                onExitModule = onBack,
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog chọn kho làm việc — auto mở khi chưa chọn; KHÔNG cho dismiss nếu null.
    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { if (selectedWarehouseId != null) pickerOpen = false },
            title = { Text("Chọn kho làm việc", color = AdminColors.Text) },
            text = {
                Column {
                    if (warehouses.isEmpty()) {
                        Text("Chưa có kho nào active", color = AdminColors.TextMuted)
                    } else warehouses.forEach { w ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedWarehouseId = w.id
                                container.tokenManager.selectedWarehouseId = w.id
                                pickerOpen = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Warehouse, null,
                                tint = if (w.id == selectedWarehouseId) AdminColors.Primary else AdminColors.TextMuted,
                                modifier = Modifier.height(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                w.name + (if (w.isDefault) "  (mặc định)" else ""),
                                color = if (w.id == selectedWarehouseId) AdminColors.Primary else AdminColors.Text,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (selectedWarehouseId != null) {
                    TextButton(onClick = { pickerOpen = false }) { Text("Xong", color = AdminColors.Primary) }
                }
            },
            containerColor = AdminColors.Card,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhTab(selected: Boolean, label: String, count: Int, badgeColor: Color?, onClick: () -> Unit) {
    Tab(
        selected = selected, onClick = onClick,
        selectedContentColor = AdminColors.Primary, unselectedContentColor = AdminColors.TextMuted,
        text = {
            if (count > 0 && badgeColor != null) {
                BadgedBox(badge = {
                    Badge(
                        containerColor = badgeColor,
                        contentColor = AdminColors.White,
                        // lệch lên cao + sang phải để KHÔNG che tên tab (+20% đường kính badge)
                        modifier = Modifier.offset(x = 10.dp, y = (-6).dp),
                    ) { Text("$count", fontSize = 9.sp) }
                }) {
                    Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            } else {
                Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        },
    )
}
