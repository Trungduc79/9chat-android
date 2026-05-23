package vn.chat9.app.ui.modules.warehouse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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

    LaunchedEffect(Unit) {
        loading = true; error = null
        try { confirmed = repo.listOrders("confirmed") } catch (e: Exception) { error = "Không tải được đơn" }
        loading = false
    }
    LaunchedEffect(tab) {
        if (tab == 2 && !doneLoaded) {
            loading = true; error = null
            try { done = repo.listOrders("delivered") + repo.listOrders("received"); doneLoaded = true }
            catch (e: Exception) { error = "Không tải được đơn hoàn thành" }
            loading = false
        }
        if (tab == 3 && !photosLoaded) {
            loading = true; error = null
            try { photos = repo.allPhotos(); photosLoaded = true }
            catch (e: Exception) { error = "Không tải được ảnh" }
            loading = false
        }
    }

    val saleList = remember(confirmed) { confirmed.filter { !isPurchaseOrder(it) }.sortedBy(::sentToWhTime) }
    val purchaseList = remember(confirmed) { confirmed.filter { isPurchaseOrder(it) }.sortedBy(::sentToWhTime) }
    val doneList = remember(done) {
        val seeAll = container.permissions.isBypass || container.permissions.hasRole("manager")
        val myId = container.tokenManager.user?.id?.toLong()
        done.filter { isToday(it.completedAt) && (seeAll || it.meta?.fulfillment?.byUserId == myId) }
            .sortedByDescending { it.completedAt ?: "" }
    }
    fun listFor(t: Int): List<OrderDto> = when (t) { 0 -> saleList; 1 -> purchaseList; 2 -> doneList; else -> emptyList() }

    BackHandler(enabled = openOrderId != null) { forward = false; openOrderId = null }

    fun goTab(t: Int) { if (t in 0..3) { forward = t > tab; tab = t; openOrderId = null } }

    // TEST tạm: vuốt xuống reload data tab hiện tại (gỡ sau khi test xong).
    fun refresh() {
        scope.launch {
            refreshing = true; error = null
            try {
                when (tab) {
                    0, 1 -> confirmed = repo.listOrders("confirmed")
                    2 -> { done = repo.listOrders("delivered") + repo.listOrders("received"); doneLoaded = true }
                    3 -> { photos = repo.allPhotos(); photosLoaded = true }
                }
            } catch (e: Exception) { error = "Không tải được dữ liệu" }
            refreshing = false
        }
    }

    Column(Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = AdminColors.Card,
            contentColor = AdminColors.Primary,
            modifier = Modifier.height(48.dp),
        ) {
            WhTab(tab == 0, "Đơn bán", saleList.size, AdminColors.Warning) { goTab(0) }
            WhTab(tab == 1, "Đơn nhập", purchaseList.size, AdminColors.Info) { goTab(1) }
            WhTab(tab == 2, "Hoàn thành", doneList.size, AdminColors.Success) { goTab(2) }
            WhTab(tab == 3, "Ảnh chụp", 0, null) { goTab(3) }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = openOrderId to tab,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally { dir * it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -dir * it } + fadeOut())
                },
                label = "warehouseContent",
            ) { (oid, t) ->
                if (oid == null) {
                    // TEST tạm: vuốt xuống để reload (gỡ sau khi test xong).
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
                        WarehouseOrdersList(
                            tab = t,
                            list = listFor(t),
                            photos = photos,
                            loading = loading,
                            error = error,
                            onOpenOrder = { id, ids -> siblingIds = ids; forward = true; openOrderId = id },
                            onTabDelta = { d -> goTab(tab + d) },
                            onExitModule = onBack,
                        )
                    }
                } else {
                    WarehouseOrderDetail(
                        orderId = oid,
                        siblingIds = siblingIds,
                        onNavigate = { newId ->
                            forward = siblingIds.indexOf(newId) > siblingIds.indexOf(openOrderId)
                            openOrderId = newId
                        },
                        onClose = { forward = false; openOrderId = null },
                    )
                }
            }
        }
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
