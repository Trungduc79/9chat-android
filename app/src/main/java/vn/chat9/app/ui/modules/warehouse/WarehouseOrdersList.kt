package vn.chat9.app.ui.modules.warehouse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import vn.chat9.app.data.vapi.dto.AttachmentDto
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.ui.explore.AdminColors

/**
 * Nội dung 1 tab kho (DARK). Tab + data do WarehouseScreen sở hữu & truyền vào.
 * Vuốt ngang: ← sang tab phải (onTabDelta +1), → sang tab trái (onTabDelta −1);
 * ở tab đầu vuốt → onExitModule (về Khám phá).
 */
@Composable
fun WarehouseOrdersList(
    tab: Int,
    list: List<OrderDto>,
    loading: Boolean,
    error: String?,
    warehouseName: String?,
    onOpenOrder: (Long, List<Long>) -> Unit,
    onTabDelta: (Int) -> Unit,
    onExitModule: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().pointerInput(tab) {
            var dragSum = 0f
            detectHorizontalDragGestures(
                onDragEnd = {
                    val threshold = 56.dp.toPx()
                    if (dragSum <= -threshold) { if (tab < 3) onTabDelta(1) }          // vuốt ←: tab phải
                    else if (dragSum >= threshold) { if (tab > 0) onTabDelta(-1) else onExitModule() } // vuốt →: tab trái / thoát
                    dragSum = 0f
                },
                onDragCancel = { dragSum = 0f },
            ) { _, dragAmount -> dragSum += dragAmount }
        },
    ) {
        when {
            loading && list.isEmpty() ->
                ScrollableCenter { CircularProgressIndicator(color = AdminColors.Primary) }
            list.isEmpty() -> ScrollableCenter { Text(error ?: "Không có đơn", color = AdminColors.TextMuted) }
            else -> LazyColumn(contentPadding = PaddingValues(12.dp)) {
                items(list, key = { it.id }) { o -> OrderCard(o, tab, warehouseName) { onOpenOrder(o.id, list.map { it.id }) } }
            }
        }
    }
}

/** Grid ảnh chụp — mở từ menu 3 chấm (không còn là tab). Tap 1 ảnh → mở đơn của ảnh đó. */
@Composable
fun WarehousePhotosGrid(
    photos: List<AttachmentDto>,
    loading: Boolean,
    error: String?,
    onOpenPhoto: (Long, List<Long>) -> Unit,
) {
    when {
        loading && photos.isEmpty() -> ScrollableCenter { CircularProgressIndicator(color = AdminColors.Primary) }
        photos.isEmpty() -> ScrollableCenter { Text(error ?: "Chưa có ảnh", color = AdminColors.TextMuted) }
        else -> LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(8.dp)) {
            gridItems(photos, key = { it.id }) { p ->
                AsyncImage(
                    model = p.url, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenPhoto(p.attachableId, photos.map { it.attachableId }.distinct()) },
                )
            }
        }
    }
}

/** LazyColumn (1 item phủ toàn màn) LUÔN dispatch nested-scroll → pull-to-refresh bắt
 *  được cú vuốt CẢ KHI tab trống/đang tải. (Box/verticalScroll range-0 không dispatch ổn định.) */
@Composable
private fun ScrollableCenter(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { content() } }
    }
}

@Composable
private fun OrderCard(o: OrderDto, tab: Int, warehouseName: String?, onClick: () -> Unit) {
    val (tagText, tagColor) = when {
        tab == 2 -> (if (isPurchaseOrder(o)) "Đã nhập" else "Đã giao") to AdminColors.Success
        isPurchaseOrder(o) -> "Nhập kho" to AdminColors.Info
        else -> "Xuất kho" to AdminColors.Warning
    }
    val dateStr = if (tab == 2) fmtDate(o.completedAt) else fmtDate(o.orderedAt)
    // Đơn nhập (chưa hoàn thành) hiện mô tả thay badge; Tổng xuống dòng riêng.
    val showPurchaseLabel = tab != 2 && isPurchaseOrder(o)

    Surface(
        shape = RoundedCornerShape(12.dp), color = AdminColors.Card,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.partyName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AdminColors.Text, modifier = Modifier.weight(1f))
                Text(dateStr, fontSize = 11.sp, color = AdminColors.TextMuted)
                Spacer(Modifier.width(8.dp))
                Text(o.code, fontSize = 11.sp, color = AdminColors.Primary, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            if (showPurchaseLabel) {
                // "Nhập hàng {kho}" hoặc "Giao hàng từ {NCC} → {KH}" (drop-ship, tô 2 màu).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (o.isDropship) {
                        Text("Giao hàng từ ", fontSize = 14.sp, color = AdminColors.TextMuted)
                        Text(o.dropshipSupplier, fontSize = 14.sp, color = AdminColors.Info, fontWeight = FontWeight.Medium)
                        Text(" → ", fontSize = 14.sp, color = AdminColors.Warning)
                        Text(o.dropshipCustomer, fontSize = 14.sp, color = AdminColors.Success, fontWeight = FontWeight.Medium, maxLines = 1)
                    } else {
                        Text("Nhập hàng ${warehouseName ?: "—"}", fontSize = 14.sp, color = AdminColors.TextSecondary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tổng:", fontSize = 14.sp, color = AdminColors.TextMuted, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal)
                    Spacer(Modifier.width(4.dp))
                    Text(qtySummary(o), fontSize = 14.sp, color = AdminColors.TextSecondary)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WhBadge(tagText, tagColor)
                    Spacer(Modifier.width(8.dp))
                    Text("Tổng:", fontSize = 14.sp, color = AdminColors.TextMuted, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal)
                    Spacer(Modifier.width(4.dp))
                    Text(qtySummary(o), fontSize = 14.sp, color = AdminColors.TextSecondary)
                }
            }
            if (!o.notes.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(o.notes!!, fontSize = 12.sp, color = AdminColors.TextMuted)
            }
            // Tab Hoàn thành: truy vết "NV X · HH:mm dd/mm" (mirror web), màu success.
            if (tab == 2) o.meta?.fulfillment?.takeIf { it.byName != null || it.at != null }?.let { f ->
                Spacer(Modifier.height(6.dp))
                val nm = f.byName ?: "—"
                val ts = fmtDateTime(f.at ?: o.completedAt)
                Text("$nm · $ts", fontSize = 12.sp, color = AdminColors.Success)
            }
        }
    }
}
