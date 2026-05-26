package vn.chat9.app.ui.modules.warehouse

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.R
import vn.chat9.app.data.vapi.dto.AttachmentDto
import vn.chat9.app.data.vapi.dto.CasherDto
import vn.chat9.app.data.vapi.dto.DeliveredItem
import vn.chat9.app.data.vapi.dto.FulfillRequest
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.data.vapi.dto.OrderItemDto
import vn.chat9.app.ui.explore.AdminColors as C

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseOrderDetail(
    orderId: Long,
    siblingIds: List<Long>,
    onNavigate: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as App).container
    val repo = container.warehouseRepo
    val scope = rememberCoroutineScope()

    var order by remember { mutableStateOf<OrderDto?>(null) }
    var photos by remember { mutableStateOf<List<AttachmentDto>>(emptyList()) }
    val delivered = remember { mutableStateMapOf<Long, Double>() }
    val checked = remember { mutableStateMapOf<Long, Boolean>() }
    var loading by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }

    // Phí ship + Thu hộ — NV kho chốt lúc giao (init từ order). NV KHÔNG chọn quỹ:
    // BE auto-resolve quỹ thu mặc định của kho làm việc (cài trong "Quỹ tiền mặt").
    var shipCustomer by remember { mutableStateOf("") }   // Phí ship KH → order.shipping_fee → công nợ
    var shipCompany by remember { mutableStateOf("") }    // Phí ship KHO → actual_shipping_fee → chi phí
    var codAmount by remember { mutableStateOf("") }      // Thu hộ → cust_cash_in (BE chọn quỹ)
    // Ngày xác nhận giao/nhận (NV chọn); ms. Init từ order.completedAt/orderedAt/now.
    var confirmDateMs by remember { mutableStateOf<Long?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(orderId) {
        loading = true; order = null
        try {
            val o = repo.getOrder(orderId)
            order = o
            delivered.clear(); checked.clear()
            o?.items?.forEach { delivered[it.id] = it.qtyUnit; checked[it.id] = false }
            shipCustomer = o?.shippingFee?.takeIf { it > 0 }?.toLong()?.toString() ?: ""
            shipCompany = o?.actualShippingFee?.takeIf { it > 0 }?.toLong()?.toString() ?: ""
            codAmount = o?.codCollected?.takeIf { it > 0 }?.toLong()?.toString() ?: ""
            val dateStr = o?.completedAt ?: o?.orderedAt ?: o?.confirmedAt
            confirmDateMs = dateStr?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: System.currentTimeMillis()
            photos = repo.photos(orderId)
        } catch (_: Exception) {
            Toast.makeText(ctx, "Không tải được đơn", Toast.LENGTH_SHORT).show()
        }
        loading = false
    }

    val o = order
    val isPurchase = o?.isPurchase == true
    val consumesStock = !isPurchase
    val canFulfill = o?.status == "confirmed"

    fun blocked(it: OrderItemDto): Boolean {
        val s = it.stockUnit ?: return false
        return consumesStock && (delivered[it.id] ?: 0.0) > s + 1e-9
    }
    val items = o?.items ?: emptyList()
    val hasOverStock = items.any { blocked(it) }
    val totalDelivered = items.sumOf { (delivered[it.id] ?: 0.0).coerceAtLeast(0.0) }
    val allChecked = items.isNotEmpty() && items.all { (checked[it.id] == true) && !blocked(it) }
    // Điều kiện xác nhận. Quỹ COD do BE auto-resolve theo cài đặt kho — không check ở FE.
    val canConfirm = photos.isNotEmpty() && allChecked && totalDelivered > 0 && !hasOverStock && !uploading

    val confirmBlockReason: String = when {
        !canFulfill -> ""
        hasOverStock -> "Có món vượt tồn — giảm SL xuống ≤ tồn"
        totalDelivered <= 0.0 -> "Tổng số lượng giao phải > 0"
        !allChecked -> "Tích kiểm tất cả món đã giao"
        photos.isEmpty() -> "Cần ≥1 ảnh xác nhận"
        uploading -> "Đang tải ảnh..."
        else -> ""
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            uploading = true
            for (uri in uris) {
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) repo.uploadPhoto(orderId, bytes)?.let { photos = listOf(it) + photos }
                } catch (_: Exception) {}
            }
            uploading = false
        }
    }

    fun doConfirm() {
        val ord = order ?: return
        val payload = ord.items.map { DeliveredItem(it.id, (delivered[it.id] ?: 0.0).coerceAtLeast(0.0)) }
        val shipKh = if (!isPurchase) shipCustomer.toDoubleOrNull() else null
        val shipKho = shipCompany.toDoubleOrNull()
        val cod = if (!isPurchase) codAmount.toDoubleOrNull() else null
        // Không truyền codCasherId — BE auto-resolve theo cài đặt kho.
        scope.launch {
            confirming = true
            try {
                val u = container.tokenManager.user
                val res = repo.fulfill(
                    ord.id,
                    FulfillRequest(
                        confirmedByUserId = u?.id?.toLong(),
                        confirmedByName = u?.username,
                        items = payload,
                        shippingFee = shipKh,
                        actualShippingFee = shipKho,
                        codAmount = cod,
                        codCasherId = null, // BE auto-resolve theo warehouse_id
                        completedAt = confirmDateMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
                    ),
                )
                val base = if (isPurchase) "Đã xác nhận nhập hàng" else "Đã xác nhận giao hàng"
                // Drop-ship cascade: nhận đơn nhập → BE tự giao đơn bán liên kết. Báo cho NV.
                val linked = res?.order?.linkedOrder
                val cascadeNote = if (isPurchase && linked?.code?.isNotEmpty() == true && linked.type == "sale" && linked.status == "delivered") {
                    "Đơn bán liên kết ${linked.code} đã tự giao."
                } else null
                val remainderNote = res?.remainderOrder?.code?.let { "Đơn còn lại: $it" }
                val msg = listOfNotNull(base, cascadeNote, remainderNote).joinToString(" · ")
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                onClose()
            } catch (e: retrofit2.HttpException) {
                // Parse error body — bắt code DROPSHIP_SALE_VIA_PURCHASE cho thông điệp đúng.
                val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                val (code, msgStr) = try {
                    val j = org.json.JSONObject(body ?: "")
                    j.optString("code", "") to j.optString("error", j.optString("message", ""))
                } catch (_: Exception) { "" to "" }
                val msg = when (code) {
                    "DROPSHIP_SALE_VIA_PURCHASE" -> "Đơn bán giao thẳng (drop-ship) — xác nhận qua đơn NHẬP liên kết, không xác nhận riêng."
                    else -> msgStr.ifBlank { "Xác nhận thất bại (HTTP ${e.code()})" }
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(ctx, "Xác nhận thất bại", Toast.LENGTH_SHORT).show()
            }
            confirming = false
        }
    }

    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val scrollState = rememberScrollState()
    // Ctx cho ItemRow + ShipFieldRow: scroll explicit center input vào "view còn lại"
    // (= screen - keyboard). imeBottomState dùng rememberUpdatedState để coroutine
    // sau delay(280) đọc được giá trị IME ĐÃ MỞ XONG, không phải 0 lúc onFocus.
    val imeBottomState = rememberUpdatedState(imeBottomPx)
    val focusCenterCtx = FocusCenterCtx(scrollState, screenHeightPx, imeBottomState)
    Box(
        Modifier.fillMaxSize().background(C.Bg)
            // KHÔNG imePadding: anh Đức muốn bàn phím overlay (che luôn bg nút Xác nhận +
            // BottomNav), không đẩy layout. Scroll explicit lo việc đưa input lên giữa
            // "view còn lại" cho từng input riêng.
            // Tap vùng trống (không phải input field) → tắt bàn phím.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .pointerInput(orderId, siblingIds) {
            var dragSum = 0f
            detectHorizontalDragGestures(
                onDragEnd = {
                    val threshold = 56.dp.toPx()
                    val idx = siblingIds.indexOf(orderId)
                    if (dragSum <= -threshold) {                              // vuốt ←: đơn kế (hết thì dừng)
                        if (idx in 0 until siblingIds.lastIndex) onNavigate(siblingIds[idx + 1])
                    } else if (dragSum >= threshold) {                        // vuốt →: đơn trước; đơn đầu → về list
                        if (idx >= 1) onNavigate(siblingIds[idx - 1]) else onClose()
                    }
                    dragSum = 0f
                },
                onDragCancel = { dragSum = 0f },
            ) { _, dragAmount -> dragSum += dragAmount }
        },
    ) {
        // Bottom padding chừa chỗ cho bar đáy: nút Xác nhận (96dp) khi đủ ĐK,
        // hoặc bar blocker (~44dp) khi có lý do chặn.
        val bottomPad = when {
            !canFulfill -> 12.dp
            canConfirm -> 96.dp                                    // reserve cho nút Xác nhận float (kể cả khi IME mở — bàn phím chỉ overlay)
            confirmBlockReason.isNotEmpty() -> 44.dp
            else -> 12.dp
        }
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(top = 8.dp, bottom = bottomPad)) {
            if (loading || o == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(color = C.Primary)
                }
                return@Column
            }

            InfoCard(o, isPurchase, canFulfill, confirmDateMs) { datePickerOpen = true }

            if (!o.notes.isNullOrBlank()) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .border(1.dp, C.Danger, RoundedCornerShape(12.dp)).padding(12.dp),
                ) { Text("Ghi chú: ${o.notes}", color = C.Text, fontSize = 14.sp) }
            }

            // Mặt hàng (top=0 → khoảng cách với card thông tin giảm 50%: 24→12dp)
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().then(if (canFulfill) Modifier.height(23.dp) else Modifier),
                    ) {
                        Text("Mặt hàng (${items.size})", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (canFulfill) {
                            Icon(
                                painter = painterResource(R.drawable.ic_checklist_all),
                                contentDescription = "Chọn tất cả",
                                tint = if (allChecked) C.Primary else C.TextSecondary,
                                modifier = Modifier.size(22.dp).clickable {
                                    val v = !allChecked
                                    items.forEach { if (!blocked(it)) checked[it.id] = v }
                                },
                            )
                        }
                    }
                    items.forEach { it2 ->
                        HorizontalDivider(Modifier.padding(vertical = 2.dp), color = C.Border)
                        ItemRow(
                            item = it2, canFulfill = canFulfill,
                            delivered = delivered[it2.id] ?: it2.qtyUnit,
                            checkedVal = (checked[it2.id] == true) && !blocked(it2),
                            blocked = blocked(it2),
                            onSet = { v -> delivered[it2.id] = v.coerceAtLeast(0.0) },
                            onCheck = { v -> checked[it2.id] = v },
                            focusCtx = focusCenterCtx,
                        )
                    }
                }
            }

            // Phí ship + Thu hộ — KHÔNG header, mỗi hàng = label(5):(1)input(6), border-bottom only.
            // Đơn bán: 3 hàng + quỹ COD khi >0. Đơn nhập: 1 hàng Phí ship KHO.
            if (canFulfill) {
                Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        if (!isPurchase) ShipFieldRow("Phí ship KH", shipCustomer, focusCenterCtx) { shipCustomer = it }
                        ShipFieldRow("Phí ship KHO", shipCompany, focusCenterCtx) { shipCompany = it }
                        if (!isPurchase) {
                            ShipFieldRow("Thu hộ", codAmount, focusCenterCtx) { codAmount = it }
                        }
                    }
                }
            }

            // Ảnh xác nhận
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().then(if (canFulfill) Modifier.height(23.dp) else Modifier), // = header Mặt hàng
                    ) {
                        Text(
                            if (canFulfill) "Ảnh xác nhận (bắt buộc ≥1)" else "Ảnh xác nhận",
                            fontSize = 11.sp, color = C.TextMuted,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            modifier = Modifier.weight(1f),
                        )
                        if (canFulfill) Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !uploading) { picker.launch("image/*") }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null, tint = C.Primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Chọn / chụp", color = C.Primary, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 8.dp), color = C.Border) // border bottom header
                    if (photos.isEmpty()) {
                        Text("Chưa có ảnh", color = C.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(((photos.size + 2) / 3 * 110).dp), userScrollEnabled = false) {
                            items(photos, key = { it.id }) { p ->
                                Box(Modifier.padding(2.dp)) {
                                    AsyncImage(model = p.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)))
                                    if (canFulfill) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).clickable {
                                        scope.launch { try { repo.deletePhoto(p.id); photos = photos.filter { it.id != p.id } } catch (_: Exception) {} }
                                    }) { Icon(Icons.Default.Close, "Xoá", tint = Color.White, modifier = Modifier.size(18.dp).padding(2.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bar đáy KHI CHƯA ĐỦ ĐK: hiện text lý do thay vì nút (mirror web confirmBlockReason).
        AnimatedVisibility(
            visible = canFulfill && !canConfirm && confirmBlockReason.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(shadowElevation = 4.dp, color = C.Card, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(confirmBlockReason, color = C.TextMuted, fontSize = 13.sp)
                }
            }
        }

        // Nút xác nhận chỉ hiện (trượt lên từ đáy) khi ĐỦ điều kiện; chưa đủ → bar
        // blocker (ở trên) hiện text lý do.
        AnimatedVisibility(
            visible = canFulfill && canConfirm,                     // luôn giữ nút (bàn phím chỉ overlay che, không cần ẩn)
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(shadowElevation = 8.dp, color = C.Card, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(12.dp)) {
                    Button(
                        onClick = { doConfirm() }, enabled = !confirming,
                        colors = ButtonDefaults.buttonColors(containerColor = C.Primary),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        if (confirming) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text(if (isPurchase) "Xác nhận nhập hàng" else "Xác nhận giao hàng")
                    }
                }
            }
        }
    }

    // DatePickerDialog cho ngày xác nhận — NV tap vào ô ngày trên InfoCard mở picker.
    // Wrap MaterialTheme(darkColorScheme) để dialog + picker nền tối (M3 mặc định light surface).
    if (datePickerOpen) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = confirmDateMs)
        MaterialTheme(
            colorScheme = darkColorScheme(
                surface = C.Card,
                surfaceVariant = C.Card,
                onSurface = C.Text,
                onSurfaceVariant = C.TextMuted,
                primary = C.Primary,
                onPrimary = Color.White,
            ),
        ) {
            DatePickerDialog(
                onDismissRequest = { datePickerOpen = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { confirmDateMs = it }
                        datePickerOpen = false
                    }) { Text("OK", color = C.Primary) }
                },
                dismissButton = {
                    TextButton(onClick = { datePickerOpen = false }) { Text("Huỷ", color = C.TextMuted) }
                },
                colors = DatePickerDefaults.colors(containerColor = C.Card),
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}

@Composable
private fun InfoCard(o: OrderDto, isPurchase: Boolean, canFulfill: Boolean, confirmDateMs: Long?, onDateClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.partyName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = C.Text, modifier = Modifier.weight(1f))
                // Thay mã đơn = ngày xác nhận giao/nhận (NV chọn). canFulfill = tap → mở picker.
                val dateLabel = confirmDateMs?.let {
                    java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("vi")).format(java.util.Date(it))
                } ?: "—"
                if (canFulfill) {
                    Text(
                        dateLabel,
                        color = C.Primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onDateClick() }
                            .background(C.Primary.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                } else {
                    Text(dateLabel, color = C.TextMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WhBadge(if (isPurchase) "Nhập kho" else "Xuất kho", if (isPurchase) C.Info else C.Warning)
                Spacer(Modifier.width(8.dp))
                Text("Tổng:", fontSize = 13.sp, color = C.TextMuted, fontStyle = FontStyle.Italic)
                Spacer(Modifier.width(4.dp))
                Text(qtySummary(o), fontSize = 13.sp, color = C.TextSecondary)
            }
            // Truy vết: ai xác nhận / lúc nào (chỉ hiện khi đơn đã fulfill).
            o.meta?.fulfillment?.takeIf { it.byName != null || it.at != null }?.let { f ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = C.Success, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Đã ${if (isPurchase) "nhận" else "giao"} bởi ${f.byName ?: "—"} · ${fmtDateTime(f.at ?: o.completedAt)}",
                        fontSize = 12.sp, color = C.Success,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: OrderItemDto, canFulfill: Boolean, delivered: Double, checkedVal: Boolean,
    blocked: Boolean, onSet: (Double) -> Unit, onCheck: (Boolean) -> Unit,
    focusCtx: FocusCenterCtx,
) {
    // Row ngoài: thumb trái 55dp (rowspan=2 — fill 2 dòng của Column phải).
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val img = item.imageUrl
        if (img != null) AsyncImage(
            model = img, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(55.dp).clip(RoundedCornerShape(7.dp)),
        ) else Box(
            modifier = Modifier.size(55.dp).clip(RoundedCornerShape(7.dp)).background(C.Border.copy(alpha = 0.3f)),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.productName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.Text)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val variantText = buildAnnotatedString {
                    val pairs = item.variantPairs
                    // Tên nhóm phân loại: nhỏ 10% + Light + italic + mờ thêm 35% (alpha 0.65).
                    val nameStyle = SpanStyle(
                        color = C.TextMuted.copy(alpha = 0.65f),
                        fontSize = 11.7.sp,
                        fontWeight = FontWeight.Light,
                        fontStyle = FontStyle.Italic,
                    )
                    if (pairs.isEmpty()) withStyle(SpanStyle(color = C.TextMuted)) { append("—") }
                    else pairs.forEachIndexed { i, (name, value) ->
                        if (i > 0) withStyle(SpanStyle(color = C.TextMuted)) { append(", ") }
                        withStyle(nameStyle) { append("$name: ") }
                        withStyle(SpanStyle(color = C.Text)) { append(value) }
                    }
                }
                Text(variantText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                if (canFulfill) {
                    // Qty BasicTextField inline tap-to-edit. Khi focus → tính delta scroll explicit
                    // để center field vào GIỮA "view còn lại" (= screen - keyboard), không center
                    // theo viewport scroll Column (lệch vì có status bar + WarehouseScreen TabRow
                    // + InfoCard chiếm phần trên ~200dp).
                    var qtyText by remember(item.id) { mutableStateOf(trimZeros(delivered)) }
                    var fieldYInWindow by remember(item.id) { mutableStateOf(0f) }
                    var fieldHeightPx by remember(item.id) { mutableStateOf(0f) }
                    val scope = rememberCoroutineScope()
                    BasicTextField(
                        value = qtyText,
                        onValueChange = { raw ->
                            val filtered = raw.filter { c -> c.isDigit() || c == '.' }
                            qtyText = filtered
                            onSet(filtered.toDoubleOrNull() ?: 0.0)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = TextStyle(color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(C.Primary),
                        decorationBox = { inner ->
                            Column {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) { inner() }
                                HorizontalDivider(thickness = 0.5.dp, color = (if (blocked) C.Danger else C.Primary).copy(alpha = 0.5f))
                            }
                        },
                        modifier = Modifier.width(56.dp)
                            .onGloballyPositioned { coords ->
                                fieldYInWindow = coords.positionInWindow().y
                                fieldHeightPx = coords.size.height.toFloat()
                            }
                            .onFocusChanged { state ->
                                if (state.isFocused) scope.launch {
                                    kotlinx.coroutines.delay(280) // chờ IME mở xong + layout resize
                                    // Visible app area = screen - keyboard (top). Center field
                                    // vào giữa vùng này: targetY = visibleH/2 - fieldH/2.
                                    val visibleH = focusCtx.screenHeightPx - focusCtx.imeBottomState.value
                                    val targetY = visibleH / 2f - fieldHeightPx / 2f
                                    val delta = fieldYInWindow - targetY
                                    if (delta > 0f) runCatching {
                                        focusCtx.scrollState.animateScrollBy(delta)
                                    }
                                }
                            },
                    )
                } else {
                    Text(trimZeros(item.qtyUnit), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = C.Text)
                }
                Spacer(Modifier.width(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canFulfill) item.stockUnit?.let {
                        Text("Kho ${trimZeros(it)}", fontSize = 13.sp, color = (if (blocked) C.Danger else C.TextMuted).copy(alpha = 0.6f), maxLines = 1, softWrap = false)
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(item.unitName, fontSize = 13.sp, color = C.TextMuted.copy(alpha = 0.6f), maxLines = 1, softWrap = false)
                }
                if (canFulfill) {
                    Spacer(Modifier.width(10.dp))
                    WhCheckbox(
                        checked = checkedVal, enabled = !blocked, onCheckedChange = onCheck,
                        checkedColor = C.Primary, borderColor = C.TextMuted,
                    )
                }
            }
        }
    }
}

/**
 * VisualTransformation tách hàng nghìn kiểu kế toán VN: "1000000" → "1.000.000".
 * Mỗi 3 chữ số từ PHẢI sang TRÁI chèn dấu "."; OffsetMapping cập nhật cursor.
 * Giả định input ngoài đã filter digit-only (qua onValueChange).
 */
private object ThousandsVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val len = digits.length
        val sb = StringBuilder(len + len / 3)
        for (i in 0 until len) {
            sb.append(digits[i])
            val remaining = len - i - 1
            if (remaining > 0 && remaining % 3 == 0) sb.append('.')
        }
        val formatted = sb.toString()
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val o = offset.coerceIn(0, len)
                if (o == 0) return 0
                var digitCount = 0
                for (idx in formatted.indices) {
                    if (digitCount == o) return idx
                    if (formatted[idx].isDigit()) digitCount++
                }
                return formatted.length
            }
            override fun transformedToOriginal(offset: Int): Int {
                val o = offset.coerceIn(0, formatted.length)
                return formatted.take(o).count { it.isDigit() }
            }
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

/**
 * Hàng nhập tiền VND theo style web: label(weight 0.42) ":" input(weight 0.58)
 * — input bare có border-BOTTOM only, text căn phải, "đ" suffix; compact ~24dp.
 * Hiển thị tự tách hàng nghìn (1.000.000); state vẫn chỉ digit.
 */
@Composable
private fun ShipFieldRow(label: String, value: String, focusCtx: FocusCenterCtx, onChange: (String) -> Unit) {
    var fieldYInWindow by remember { mutableStateOf(0f) }
    var fieldHeightPx by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(label, fontSize = 12.sp, color = C.TextMuted, modifier = Modifier.weight(0.42f))
        Text(":", fontSize = 12.sp, color = C.TextMuted)
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(0.58f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
                    visualTransformation = ThousandsVisualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = TextStyle(color = C.Text, fontSize = 14.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(C.Primary),
                    decorationBox = { inner ->
                        // contentAlignment=CenterEnd: cả placeholder lẫn innerTextField căn phải.
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.CenterEnd) {
                            if (value.isEmpty()) Text("0", color = C.TextMuted, fontSize = 13.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f)
                        .onGloballyPositioned { coords ->
                            fieldYInWindow = coords.positionInWindow().y
                            fieldHeightPx = coords.size.height.toFloat()
                        }
                        .onFocusChanged { state ->
                            if (state.isFocused) scope.launch {
                                kotlinx.coroutines.delay(280)   // chờ IME mở xong
                                val visibleH = focusCtx.screenHeightPx - focusCtx.imeBottomState.value
                                val targetY = visibleH / 2f - fieldHeightPx / 2f
                                val delta = fieldYInWindow - targetY
                                if (delta > 0f) runCatching { focusCtx.scrollState.animateScrollBy(delta) }
                            }
                        },
                )
                Text("đ", fontSize = 11.sp, color = C.TextMuted, modifier = Modifier.padding(start = 4.dp))
            }
            HorizontalDivider(color = C.Border, thickness = 1.dp)
        }
    }
}

/** Ctx truyền xuống ItemRow/ShipFieldRow để input scroll explicit (center theo screen - keyboard). */
data class FocusCenterCtx(
    val scrollState: ScrollState,
    val screenHeightPx: Float,
    val imeBottomState: androidx.compose.runtime.State<Float>,
)

