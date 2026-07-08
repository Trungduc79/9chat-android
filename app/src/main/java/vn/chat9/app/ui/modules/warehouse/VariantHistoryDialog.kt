package vn.chat9.app.ui.modules.warehouse

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.CalendarLocale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerFormatter
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.ProductHistoryDto
import vn.chat9.app.data.vapi.dto.VariantSearchDto
import vn.chat9.app.ui.explore.AdminColors

/**
 * Dialog lịch sử kho của 1 variant (mirror bản web admin.ai.vn).
 * Mới nhất lên đầu (BE orderByDesc). Mỗi dòng 2 hàng:
 *  - Trên: ngày (trái) | Tồn cuối (phải)
 *  - Dưới: loại + đối tác (trái) | SL biến động ±, căn cột với Tồn (phải)
 * Tồn cuối neo vào tồn thực (SUM batches) nên khớp tồn ngoài danh sách.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariantHistoryDialog(variant: VariantSearchDto, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as App).container

    // Đơn vị mặc định + hệ số quy đổi (đồng bộ với list Kiểm kho).
    val unitObj = variant.units.firstOrNull { it.isDefaultSale }
        ?: variant.units.firstOrNull { it.isBase } ?: variant.units.firstOrNull()
    val factor = (unitObj?.conversionFactor ?: 1.0).let { if (it > 0) it else 1.0 }
    val unit = unitObj?.name ?: "đv"

    var rows by remember { mutableStateOf<List<ProductHistoryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var fromDate by remember { mutableStateOf<String?>(null) }   // yyyy-MM-dd, null = tất cả
    var toDate by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // Ảnh đính kèm đơn: click dòng → 1 ảnh mở full màn hình; ≥2 ảnh lưới chọn; không có → toast.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photoGrid by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    var viewerCaption by remember { mutableStateOf("") } // "tên đối tác - ngày"
    fun openHistoryPhotos(h: ProductHistoryDto) {
        val isOrder = h.refType in listOf("order", "order_edit", "orders") && h.refId != null
        if (!isOrder) { Toast.makeText(context, "Không có ảnh", Toast.LENGTH_SHORT).show(); return }
        viewerCaption = listOfNotNull(
            h.actorName?.takeIf { it.isNotBlank() },
            histDate(h.createdAt).takeIf { it.isNotBlank() },
        ).joinToString(" - ")
        scope.launch {
            val urls = try {
                container.vapi.listAttachments(id = h.refId, kind = "photo").data?.mapNotNull { it.url } ?: emptyList()
            } catch (_: Exception) { emptyList() }
            when {
                urls.isEmpty() -> Toast.makeText(context, "Không có ảnh", Toast.LENGTH_SHORT).show()
                urls.size == 1 -> viewerUrl = urls[0]
                else -> photoGrid = urls
            }
        }
    }

    LaunchedEffect(variant.id, fromDate, toDate) {
        loading = true
        rows = try {
            container.vapi.listProductHistory(
                productId = variant.product?.id, variantId = variant.id,
                from = fromDate, to = toDate, perPage = 100,
            ).data ?: emptyList()
        } catch (_: Exception) { emptyList() }
        loading = false
    }

    if (showPicker) {
        val rangeState = rememberDateRangePickerState()
        // Formatter viết hoa chữ đầu nhãn tháng: "tháng 7 năm 2026" → "Tháng 7 năm 2026".
        val base = remember { DatePickerDefaults.dateFormatter() }
        val capFormatter = remember(base) {
            object : DatePickerFormatter {
                override fun formatMonthYear(monthInMillis: Long?, locale: CalendarLocale): String? =
                    base.formatMonthYear(monthInMillis, locale)?.replaceFirstChar { it.uppercase() }
                override fun formatDate(dateInMillis: Long?, locale: CalendarLocale, forContentDescription: Boolean): String? =
                    base.formatDate(dateInMillis, locale, forContentDescription)
            }
        }
        // Chọn đủ ngày bắt đầu + kết thúc → trễ 1s tự áp + đóng (không cần nút).
        LaunchedEffect(rangeState.selectedStartDateMillis, rangeState.selectedEndDateMillis) {
            val s = rangeState.selectedStartDateMillis
            val e = rangeState.selectedEndDateMillis
            if (s != null && e != null) {
                delay(1000)
                fromDate = ymd(s)
                toDate = ymd(e)
                showPicker = false
            }
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text, primary = AdminColors.Primary)) {
            DatePickerDialog(
                onDismissRequest = { showPicker = false },
                confirmButton = {},   // bỏ hàng Huỷ/Xong dưới cùng — tự áp sau khi chọn
            ) {
                DateRangePicker(
                    state = rangeState,
                    dateFormatter = capFormatter,
                    title = null,
                    // Headline: dd/MM/yyyy -> dd/MM/yyyy, căn giữa.
                    headline = {
                        val s = rangeState.selectedStartDateMillis
                        val e = rangeState.selectedEndDateMillis
                        val txt = when {
                            s != null && e != null -> "${dmy(s)} -> ${dmy(e)}"
                            s != null -> dmy(s)
                            else -> "Chọn khoảng ngày"
                        }
                        Text(txt, Modifier.fillMaxWidth().padding(horizontal = 12.dp), color = AdminColors.Text, fontSize = 18.sp, textAlign = TextAlign.Center)
                    },
                    showModeToggle = false,
                    // Làm nổi tiêu đề tháng (vd "tháng 7 năm 2026") để phân tách tháng rõ hơn.
                    colors = DatePickerDefaults.colors(subheadContentColor = AdminColors.Primary),
                )
            }
        }
    }

    val title = variant.name?.takeIf { it.isNotBlank() } ?: variant.sku ?: "Lịch sử"
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.66f).dp

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            // Nút X: ngoài card, ngay phía trên — bên phải.
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(AdminColors.White.copy(alpha = 0.12f))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Đóng", tint = AdminColors.White, modifier = Modifier.size(20.dp)) }
            }
            // Card có viền sáng lan tỏa.
            Column(
                Modifier.fillMaxWidth()
                    .shadow(18.dp, RoundedCornerShape(12.dp), spotColor = AdminColors.Primary, ambientColor = AdminColors.Primary)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AdminColors.Card)
                    .border(1.dp, AdminColors.Primary, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Text(title, Modifier.fillMaxWidth(), color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                // Bộ lọc khoảng thời gian
                val hasFilter = fromDate != null || toDate != null
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                        .border(1.dp, AdminColors.Border, RoundedCornerShape(8.dp))
                        .clickable { showPicker = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.DateRange, null, tint = AdminColors.TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(rangeLabel(fromDate, toDate), color = if (hasFilter) AdminColors.Primary else AdminColors.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    if (hasFilter) {
                        Box(Modifier.size(20.dp).clip(CircleShape).clickable { fromDate = null; toDate = null }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Close, "Xoá lọc", tint = AdminColors.TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
                    rows.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), Alignment.Center) { Text("Chưa có lịch sử", color = AdminColors.TextMuted) }
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(rows, key = { _, it -> it.id }) { i, h ->
                            // Đổi tháng so với dòng trên → kẻ line phân tách cho dễ nhìn.
                            if (i > 0 && monthKey(h.createdAt) != monthKey(rows[i - 1].createdAt)) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(AdminColors.Divider))
                            }
                            HistoryRow(h, factor, unit, onClick = { openHistoryPhotos(h) })
                        }
                    }
                }
            }
        }
    }

    // Lưới chọn khi đơn có ≥2 ảnh → tap mở full màn hình.
    if (photoGrid.isNotEmpty()) {
        OrderPhotoGridDialog(urls = photoGrid, onPick = { viewerUrl = it }, onDismiss = { photoGrid = emptyList() })
    }
    // Xem ảnh full màn hình (zoom/pan) — tái dùng viewer của đơn hàng.
    viewerUrl?.let { url -> PhotoZoomViewer(url = url, onClose = { viewerUrl = null }, caption = viewerCaption) }
}

@Composable
private fun HistoryRow(h: ProductHistoryDto, factor: Double, unit: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
            .border(1.dp, AdminColors.Border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 8.dp),
    ) {
        // Dòng 1: ngày (trái) | Tồn cuối (phải)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(histDate(h.createdAt), color = AdminColors.TextMuted, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Tồn: ", color = AdminColors.TextMuted, fontSize = 11.sp)
                Text(numText(h.stockAfter, factor, signed = false), color = AdminColors.Text, fontSize = 14.sp)
                Text(" $unit", color = AdminColors.TextMuted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        // Dòng 2: loại + đối tác (trái) | SL biến động (phải — căn cột với Tồn)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Bottom) {
                Text("${h.typeLabel}:", color = typeColor(h.type), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (!h.actorName.isNullOrBlank()) {
                    Text(" ${h.actorName}", color = AdminColors.TextMuted, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(numText(h.qtyChange, factor, signed = true), color = qtyColor(h.qtyChange), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(" $unit", color = AdminColors.TextMuted, fontSize = 11.sp)
            }
        }
    }
}

/** Số theo đơn vị mặc định; signed=true thêm dấu + khi tăng (âm đã có sẵn -). */
private fun numText(base: Double?, factor: Double, signed: Boolean): String {
    if (base == null) return "—"
    val n = base / factor
    val s = trimZeros(n)
    return if (signed && n > 0) "+$s" else s
}

/** Ngày "yyyy-MM-dd..." → "dd/MM/yyyy" (tránh java.time/timezone). */
private fun histDate(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val d = raw.take(10).split("-")
    return if (d.size == 3) "${d[2]}/${d[1]}/${d[0]}" else raw
}

/** UTC millis (từ DateRangePicker) → "yyyy-MM-dd" cho query BE. */
private fun ymd(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

/** UTC millis → "dd/MM/yyyy" để hiển thị headline picker. */
private fun dmy(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

/** Khoá tháng "yyyy-MM" để phát hiện đổi tháng giữa các dòng. */
private fun monthKey(raw: String?): String = raw?.take(7) ?: ""

/** Nhãn nút lọc theo khoảng đang chọn. */
private fun rangeLabel(from: String?, to: String?): String = when {
    from != null && to != null -> "${histDate(from)} -> ${histDate(to)}"
    from != null -> "Từ ${histDate(from)}"
    to != null -> "Đến ${histDate(to)}"
    else -> "Lọc theo ngày"
}

private fun qtyColor(qty: Double?): Color = when {
    (qty ?: 0.0) > 0 -> AdminColors.Success
    (qty ?: 0.0) < 0 -> AdminColors.Danger
    else -> AdminColors.TextMuted
}

/** Mỗi loại hành động kho một màu (mã type product_history). */
private fun typeColor(type: Int): Color = when (type) {
    1, 3, 10, 12, 14 -> AdminColors.Success      // nhập
    2, 8, 9, 13, 20, 68 -> AdminColors.Info      // bán
    5, 6 -> AdminColors.Primary                   // kiểm kê
    15, 16, 17, 18 -> AdminColors.Warning         // khách trả
    19, 21 -> AdminColors.Danger                  // trả NCC / xoá đơn nhập
    else -> AdminColors.TextSecondary
}

/** Lưới ảnh đơn (≥2 ảnh) → tap 1 ảnh để mở full màn hình. */
@Composable
private fun OrderPhotoGridDialog(urls: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp))
                .background(AdminColors.Card).padding(12.dp),
        ) {
            Text("Ảnh đơn hàng", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(urls) { u ->
                    AsyncImage(
                        model = u, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onPick(u) },
                    )
                }
            }
        }
    }
}
