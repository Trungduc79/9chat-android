package vn.chat9.app.ui.modules.warehouse

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.CustomerDto
import vn.chat9.app.data.vapi.dto.DeliveredItem
import vn.chat9.app.data.vapi.dto.FulfillRequest
import vn.chat9.app.data.vapi.dto.LinkOrderReq
import vn.chat9.app.data.vapi.dto.StocktakeBuItemReq
import vn.chat9.app.data.vapi.dto.StocktakeBuOrderReq
import vn.chat9.app.data.vapi.dto.StocktakeSessionDto
import vn.chat9.app.ui.explore.AdminColors as C
import vn.chat9.app.ui.modules.sale.CustomerPicker

/**
 * Màn TẠO ĐƠN BÙ kiểm kho — lấy đúng UI phần chi tiết đơn giao ([WarehouseOrderDetail]):
 * card đối tác + ngày, card mặt hàng (thumb + tên + SL bù + cột THIẾU/THỪA), ghi chú,
 * card phí ship/thu hộ/giảm (tái dùng [ShipFieldRow]), card ảnh xác nhận, bar đáy Hủy + Tạo.
 *
 * order = đơn bán bù (dòng THIẾU, chọn KH) / purchase = nhập bù (dòng THỪA, chọn NCC).
 * Submit: createStocktakeBuOrder (nháp→duyệt) → upload ảnh → fulfill (trừ/cộng tồn, vượt khoá)
 * → linkStocktakeBuOrder → trả session mới cho màn kiểm kho refresh. Giá BE tự lấy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WarehouseBuOrderScreen(
    session: StocktakeSessionDto,
    mode: String,               // "order" | "purchase"
    warehouseName: String?,
    initialLineId: Long?,       // dòng đã bấm "Đơn bù" → điền SL = |lệch|, dòng khác = 0 (giống web)
    onCancel: () -> Unit,
    onDone: (StocktakeSessionDto?) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val repo = container.warehouseRepo
    val scope = rememberCoroutineScope()
    val isPurchase = mode == "purchase"

    // Mặt hàng cần bù = dòng pending đúng chiều (thiếu diff<0 sale / thừa diff>0 purchase).
    val lines = remember(session.id) {
        session.lines.filter { it.status == "pending" && (if (isPurchase) it.diff > 0 else it.diff < 0) }
    }
    // SL bù theo line.id — CHỈ dòng đã bấm điền |mức lệch|, dòng khác để trống (hiện 0). Giống web.
    val qtyByLine = remember(session.id) {
        mutableStateMapOf<Long, String>().apply {
            lines.forEach { put(it.id, if (it.id == initialLineId) trimZeros(abs(it.diff)) else "") }
        }
    }
    // Dòng đã swipe-xoá khỏi đơn bù (không gửi). Không đụng phiên kiểm kho.
    val removedLineIds = remember(session.id) { mutableStateListOf<Long>() }
    val visibleLines = lines.filter { it.id !in removedLineIds }

    var party by remember { mutableStateOf<CustomerDto?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var confirmDateMs by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    var datePickerOpen by remember { mutableStateOf(false) }
    val defaultNote = remember(session.id) {
        val d = java.time.LocalDate.now()
        val dmy = "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)
        "${if (isPurchase) "Đơn nhập bù" else "Đơn bù"} kiểm kho - Phiên #${session.id} - Ngày $dmy"
    }
    var note by remember { mutableStateOf(defaultNote) }
    var shipCustomer by remember { mutableStateOf("") }   // raw digit string
    var shipCompany by remember { mutableStateOf("") }
    var codAmount by remember { mutableStateOf("") }
    var discount by remember { mutableStateOf("") }
    val photoUris = remember { mutableStateListOf<Uri>() }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) photoUris.addAll(uris)
    }

    // ── Điều kiện gửi (mirror web blockReason) ──
    val chosenItems = visibleLines.filter { (qtyByLine[it.id]?.toDoubleOrNull() ?: 0.0) > 0.0 }
    val blockReason = when {
        party == null -> if (isPurchase) "Chọn nhà cung cấp" else "Chọn khách hàng"
        chosenItems.isEmpty() -> "Nhập SL bù ít nhất 1 mặt hàng"
        photoUris.isEmpty() -> "Cần ít nhất 1 ảnh xác nhận"
        else -> ""
    }
    val canSubmit = blockReason.isEmpty() && !creating

    fun submit() {
        val p = party ?: return
        scope.launch {
            creating = true
            try {
                // 1. Tạo đơn bù (nháp→duyệt) qua endpoint kiểm kho (products:write).
                val req = StocktakeBuOrderReq(
                    type = if (isPurchase) "purchase" else "sale",
                    partyId = p.id,
                    note = note.ifBlank { null },
                    discountAmount = if (isPurchase) null else discount.filter { it.isDigit() }.toDoubleOrNull(),
                    items = chosenItems.map { StocktakeBuItemReq(it.variantId, it.unitId, qtyByLine[it.id]!!.toDouble()) },
                )
                val res = container.vapi.createStocktakeBuOrder(session.id, req).data
                val oid = res?.orderId?.takeIf { it > 0L } ?: throw IllegalStateException("không tạo được đơn")
                // 2. Upload ảnh (thoả cổng ảnh của fulfill).
                for (uri in photoUris) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) repo.uploadPhoto(oid, bytes)
                }
                // 3. Giao (fulfill) đủ SL + ship/thu hộ/ngày → trừ/cộng tồn (vượt khoá vì stocktake_bu).
                val order = repo.getOrder(oid)
                val deliver = order?.items?.map { DeliveredItem(it.id, it.qtyUnit) } ?: emptyList()
                val u = container.tokenManager.user
                repo.fulfill(
                    oid,
                    FulfillRequest(
                        confirmedByUserId = u?.id?.toLong(),
                        confirmedByName = u?.username,
                        items = deliver,
                        shippingFee = if (isPurchase) null else shipCustomer.toDoubleOrNull(),
                        actualShippingFee = shipCompany.toDoubleOrNull(),
                        codAmount = if (isPurchase) null else codAmount.toDoubleOrNull(),
                        codCasherId = null,
                        completedAt = confirmDateMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
                    ),
                )
                // 4. Link phiên → resolve dòng đủ bù (type='linked').
                val updated = try { container.vapi.linkStocktakeBuOrder(session.id, LinkOrderReq(oid)).data } catch (_: Exception) { null }
                Toast.makeText(context, "Đã tạo & giao đơn bù ${res.orderCode ?: ""}", Toast.LENGTH_LONG).show()
                onDone(updated)
            } catch (e: Exception) {
                Toast.makeText(context, "Tạo đơn bù thất bại: ${e.message}", Toast.LENGTH_LONG).show()
                creating = false
            }
        }
    }

    // ── Keyboard center scroll ctx (công thức Đức, mirror WarehouseOrderDetail) ──
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val systemNavBarPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val screenHeightPx = LocalView.current.rootView.height.toFloat()
    val topTabBarPx = with(density) { 48.dp.toPx() }
    val bottomNavPx = with(density) { 64.dp.toPx() }
    val bottomPadDp = 100.dp                                   // chừa chỗ bar đáy (blocker + nút)
    val bottomPadPx = with(density) { bottomPadDp.toPx() }
    val scrollState = rememberScrollState()
    val effectiveImePadDp = with(density) { (imeBottomPx - bottomNavPx - systemNavBarPx).coerceAtLeast(0f).toDp() }
    val imeBottomState = rememberUpdatedState(imeBottomPx)
    val confirmBtnState = rememberUpdatedState(bottomPadPx)
    val focusCtx = FocusCenterCtx(scrollState, screenHeightPx, statusBarPx, topTabBarPx, imeBottomState, confirmBtnState)

    Box(
        Modifier.fillMaxSize().background(C.Bg)
            .padding(bottom = effectiveImePadDp)
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(top = 8.dp, bottom = bottomPadDp)) {
            // ── Thẻ đối tác + ngày (giống thẻ đầu màn giao đơn) ──
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.Bg)
                                .border(1.dp, C.Border, RoundedCornerShape(8.dp))
                                .clickable(enabled = !creating) { pickerOpen = true }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        ) {
                            Text(
                                party?.name ?: (if (isPurchase) "Chọn nhà cung cấp…" else "Chọn khách hàng…"),
                                color = if (party != null) C.Text else C.TextMuted,
                                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val dateLabel = confirmDateMs?.let {
                            java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("vi")).format(java.util.Date(it))
                        } ?: "—"
                        Text(
                            dateLabel, color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { datePickerOpen = true }
                                .background(C.Primary.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            WhBadge(if (isPurchase) "Nhập bù" else "Xuất bù", if (isPurchase) C.Info else C.Warning)
                        }
                        Text(if (isPurchase) "Nhập về ${warehouseName ?: "—"}" else "Xuất từ ${warehouseName ?: "—"}", fontSize = 13.sp, color = C.TextSecondary)
                    }
                }
            }

            // ── Mặt hàng (thumb + tên + SL bù + cột THIẾU/THỪA) ──
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(23.dp)) {
                        Text("ẢNH", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.width(55.dp))
                        Spacer(Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text("MẶT HÀNG", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(6.dp))
                            Text("SL BÙ", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isPurchase) "THỪA" else "THIẾU", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.width(68.dp))
                        }
                    }
                    if (visibleLines.isEmpty()) {
                        Text(
                            if (lines.isEmpty()) "Không còn mặt hàng ${if (isPurchase) "thừa" else "thiếu"} chờ bù."
                            else "Đã bỏ hết mặt hàng — vuốt lại hoặc Hủy.",
                            color = C.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    // Vuốt trái > 1/3 để bỏ mặt hàng khỏi đơn bù (không đụng phiên kiểm kho).
                    visibleLines.forEach { l ->
                        key(l.id) {
                            HorizontalDivider(Modifier.padding(vertical = 2.dp), color = C.Border)
                            var offsetX by remember { mutableStateOf(0f) }
                            var rowWidth by remember { mutableStateOf(1f) }
                            Box(
                                Modifier.fillMaxWidth()
                                    .onSizeChanged { rowWidth = it.width.toFloat() }
                                    .pointerInput(l.id) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = { if (-offsetX > rowWidth / 3f) removedLineIds.add(l.id) else offsetX = 0f },
                                        ) { _, dragAmount -> offsetX = (offsetX + dragAmount).coerceAtMost(0f) }
                                    },
                            ) {
                                Box(Modifier.matchParentSize().background(C.Danger.copy(alpha = 0.25f)), contentAlignment = Alignment.CenterEnd) {
                                    Text("Xoá", color = C.Danger, fontSize = 13.sp, modifier = Modifier.padding(end = 16.dp))
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().offset { IntOffset(offsetX.toInt(), 0) }.background(C.Card).padding(vertical = 3.dp),
                                ) {
                                    if (l.imageUrl != null) {
                                        AsyncImage(model = l.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(55.dp).clip(RoundedCornerShape(7.dp)))
                                    } else {
                                        Box(Modifier.size(55.dp).clip(RoundedCornerShape(7.dp)).background(C.Border.copy(alpha = 0.3f)))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(l.productName ?: "SP #${l.variantId}", color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(l.variantName.ifBlank { "—" }, color = C.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                                            BasicTextField(
                                                value = qtyByLine[l.id] ?: "",
                                                onValueChange = { s -> qtyByLine[l.id] = s.filter { it.isDigit() || it == '.' } },
                                                textStyle = TextStyle(color = C.Text, fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                cursorBrush = SolidColor(C.Primary),
                                                decorationBox = { inner ->
                                                    Box(contentAlignment = Alignment.Center) {
                                                        if ((qtyByLine[l.id] ?: "").isEmpty()) Text("0", color = C.TextMuted, fontSize = 14.sp)
                                                        inner()
                                                    }
                                                },
                                            )
                                            HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = C.Primary.copy(alpha = 0.5f))
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${trimZeros(abs(l.diff))} ${l.unit}",
                                            color = if (isPurchase) C.Info else C.Danger, fontSize = 12.sp,
                                            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(68.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Ghi chú (điền sẵn, cho sửa) ──
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Ghi chú", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Bg)
                            .border(1.dp, C.Border, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = note,
                            onValueChange = { note = it },
                            textStyle = TextStyle(color = C.Text, fontSize = 13.sp),
                            cursorBrush = SolidColor(C.Primary),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        )
                    }
                }
            }

            // ── Phí ship + thu hộ + giảm cả đơn (tái dùng ShipFieldRow màn giao đơn) ──
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (!isPurchase) ShipFieldRow("Phí ship KH", shipCustomer, focusCtx) { shipCustomer = it }
                    ShipFieldRow("Phí ship KHO", shipCompany, focusCtx) { shipCompany = it }
                    if (!isPurchase) {
                        ShipFieldRow("Thu hộ", codAmount, focusCtx) { codAmount = it }
                        ShipFieldRow("Giảm cả đơn", discount, focusCtx) { discount = it }
                    }
                }
            }

            // ── Ảnh xác nhận (bắt buộc ≥1) ──
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(23.dp)) {
                        Text("Ảnh xác nhận (bắt buộc ≥1)", fontSize = 11.sp, color = C.TextMuted, modifier = Modifier.weight(1f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !creating) { picker.launch("image/*") }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null, tint = C.Primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Chọn / chụp", color = C.Primary, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 8.dp), color = C.Border)
                    if (photoUris.isEmpty()) {
                        Text("Chưa có ảnh", color = C.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(((photoUris.size + 2) / 3 * 110).dp), userScrollEnabled = false) {
                            itemsIndexed(photoUris) { i, uri ->
                                Box(Modifier.padding(2.dp)) {
                                    AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).clickable { viewerUrl = uri.toString() })
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).clickable { photoUris.removeAt(i) },
                                    ) { Icon(Icons.Default.Close, "Xoá", tint = Color.White, modifier = Modifier.size(18.dp).padding(2.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Bar đáy: blocker text + Hủy + Tạo & giao ──
        Surface(shadowElevation = 8.dp, color = C.Card, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (blockReason.isNotEmpty()) {
                    Text(blockReason, color = C.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onCancel, enabled = !creating, modifier = Modifier.height(48.dp)) { Text("Hủy") }
                    Button(
                        onClick = { submit() },
                        enabled = canSubmit,
                        colors = ButtonDefaults.buttonColors(containerColor = C.Primary),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        if (creating) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Tạo & giao đơn bù")
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        CustomerPicker(isPurchase = isPurchase, onPick = { c -> party = c; pickerOpen = false }, onClose = { pickerOpen = false })
    }

    if (datePickerOpen) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = confirmDateMs)
        MaterialTheme(
            colorScheme = darkColorScheme(surface = C.Card, surfaceVariant = C.Card, onSurface = C.Text, onSurfaceVariant = C.TextMuted, primary = C.Primary, onPrimary = Color.White),
        ) {
            DatePickerDialog(
                onDismissRequest = { datePickerOpen = false },
                confirmButton = { TextButton(onClick = { datePickerState.selectedDateMillis?.let { confirmDateMs = it }; datePickerOpen = false }) { Text("OK", color = C.Primary) } },
                dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text("Huỷ", color = C.TextMuted) } },
                colors = DatePickerDefaults.colors(containerColor = C.Card),
            ) { DatePicker(state = datePickerState) }
        }
    }

    viewerUrl?.let { url -> PhotoZoomViewer(url = url, onClose = { viewerUrl = null }) }
}
