package vn.chat9.app.ui.modules.sale

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.di.AppContainer
import vn.chat9.app.data.vapi.dto.CreateOrderItem
import vn.chat9.app.data.vapi.dto.CreateOrderRequest
import vn.chat9.app.data.vapi.dto.CustomerDto
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.data.vapi.dto.RecentProductDto
import vn.chat9.app.data.vapi.dto.VariantSearchDto
import vn.chat9.app.data.vapi.dto.VariantUnitDto
import vn.chat9.app.data.vapi.dto.WarehouseDto
import vn.chat9.app.ui.explore.AdminColors
import vn.chat9.app.ui.modules.warehouse.PhotoZoomViewer
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sentinel kho = "Giao cho khách (giao thẳng)" trong dropdown kho đơn nhập (mirror web DROPSHIP_WH). */
private const val DROPSHIP_WH = -999L

private val moneyFmt: NumberFormat = NumberFormat.getNumberInstance(Locale("vi"))
private fun fmtMoney(n: Double): String = moneyFmt.format(Math.round(n))
private fun parseMoney(s: String): Double = s.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0
private fun trimZeros(n: Double): String = if (n == Math.floor(n)) n.toLong().toString() else n.toString()

/** Tên variant ưu tiên cột name; fallback attributes joined; fallback product. */
internal fun variantDisplay(v: VariantSearchDto, productName: String): String {
    if (!v.name.isNullOrBlank()) return v.name
    val attr = v.attributes?.entries?.filter { it.value.isNotBlank() }?.joinToString(", ") { it.value }
    return if (!attr.isNullOrBlank()) attr else productName
}

/**
 * Tạo đơn bán (Android) — port UI từ web SaleOrderFormView (Phase 1, Đức 2026-05-29).
 *
 * Gồm: chọn kho bán + KH picker + variant picker (search /v1/variants) + item row
 * (thumb, tên variant, qty, unit dropdown, × giá = thành tiền, swipe-delete) +
 * chip 5 SP hay mua + ship/COD + 2 nút Lưu nháp/Xác nhận.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleOrderForm(orderId: Long? = null, isPurchase: Boolean = false, allowEditAnyStatus: Boolean = false, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val userId = container.tokenManager.user?.id?.toLong()

    // Nhãn đối tác: đơn nhập = NCC, đơn bán = KH.
    val partyWord = if (isPurchase) "NCC" else "KH"
    val partyWordFull = if (isPurchase) "nhà cung cấp" else "khách hàng"

    // ===== state =====
    var selectedCustomer by remember { mutableStateOf<CustomerDto?>(null) }
    // Drop-ship (đơn nhập giao thẳng): khách nhận hàng + picker riêng.
    var dropshipCustomer by remember { mutableStateOf<CustomerDto?>(null) }
    var dropshipPickerOpen by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<OrderItemDraft>() }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var shipCustomer by remember { mutableStateOf("") }
    var shipCompany by remember { mutableStateOf("") }
    var codAmount by remember { mutableStateOf("") }
    var orderDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var datePickerOpen by remember { mutableStateOf(false) }
    // Đơn đang xem — đổi khi tap thumb 1 đơn khác trong dialog "đơn của khách" (mirror web router.push
    // + watcher: reload state khi id đổi). Khởi tạo = param, không đổi call site.
    var currentOrderId by remember { mutableStateOf(orderId) }
    // Chặn reload 1 lần khi autosave vừa tạo đơn nháp (currentOrderId đổi nhưng form đã đúng).
    var suppressReload by remember { mutableStateOf(false) }
    // Dialog "đơn hàng của khách" (tap tên KH) + dòng đang phóng thumb.
    var custOrdersOpen by remember { mutableStateOf(false) }

    // Edit/view existing order: load khi có orderId. canEdit = tạo mới HOẶC draft; allowEditAnyStatus
    // (dialog công nợ) → cho sửa mọi tình trạng trừ đã huỷ.
    var existingStatus by remember { mutableStateOf<String?>(null) }
    val canEdit = currentOrderId == null || existingStatus == "draft" ||
        (allowEditAnyStatus && existingStatus != null && existingStatus != "cancelled")
    // Sửa đơn ĐÃ non-draft → lưu qua per-item endpoint (giữ nguyên tình trạng).
    val editingNonDraft = allowEditAnyStatus && existingStatus != null && existingStatus != "draft" && existingStatus != "cancelled"
    // Ảnh đính kèm đơn (ảnh xác nhận giao/nhận) + snapshot item để diff khi sửa non-draft.
    var photos by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerUrl by remember { mutableStateOf<String?>(null) }   // ảnh đang preview (zoom/pan)
    var deliveryDate by remember { mutableStateOf<String?>(null) }   // ngày giao (completed_at) — caption preview
    val originalItems = remember { mutableStateListOf<OrigItemSnap>() }

    // Lưu nháp → Ở LẠI form (không thoát). Vừa TẠO mới → chuyển sang chế độ sửa đơn vừa tạo
    // (đổi currentOrderId + chặn reload 1 lần để không wipe form).
    val onDraftSaved: (Long) -> Unit = { id ->
        if (currentOrderId != id) { suppressReload = true; currentOrderId = id }
        existingStatus = "draft"
    }

    // ===== Keyboard handling: tap ngoài tắt bàn phím + scroll input vào giữa view
    // còn lại (= screen - keyboard). Công thức port từ WarehouseOrderDetail. =====
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scrollState = rememberScrollState()
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val screenHeightPx = view.rootView.height.toFloat()
    val appBarPx = with(density) { 48.dp.toPx() }   // SaleScreen app bar
    val imeBottomState = rememberUpdatedState(imeBottomPx)
    val focusCtx = FocusCenterCtx(scrollState, screenHeightPx, statusBarPx, appBarPx, imeBottomState)
    // Đẩy layout lên = 80% chiều cao bàn phím khi IME mở (Đức 2026-05-29).
    val pushUpDp = with(density) { (imeBottomPx * 0.8f).toDp() }

    // Kho bán
    var warehouses by remember { mutableStateOf<List<WarehouseDto>>(emptyList()) }
    var selectedWarehouseId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        try {
            val ws = container.vapi.listWarehouses().data ?: emptyList()
            warehouses = ws
            if (orderId == null) selectedWarehouseId = ws.firstOrNull { it.isDefault }?.id ?: ws.firstOrNull()?.id
        } catch (_: Exception) {}
    }
    // Đơn nhập chọn "Giao cho khách" → giao thẳng cho KH nhận (không nhập kho).
    val isDropship = isPurchase && selectedWarehouseId == DROPSHIP_WH

    // Load đơn existing (edit/view) → populate state. Key theo currentOrderId → tap thumb đơn khác
    // trong dialog "đơn của khách" sẽ nạp lại toàn bộ state (mirror web watcher route.params.id).
    LaunchedEffect(currentOrderId) {
        val oid = currentOrderId ?: return@LaunchedEffect
        // Bỏ qua lần reload do autosave vừa tạo đơn (giữ state đang nhập, không wipe).
        if (suppressReload) { suppressReload = false; return@LaunchedEffect }
        try {
            val o = container.vapi.getOrder(oid).data ?: return@LaunchedEffect
            existingStatus = o.status
            deliveryDate = o.completedAt
            o.party?.let { p ->
                // Đơn nhập: hiển thị tên rút gọn NCC; đơn bán: tên KH.
                val nm = if (isPurchase) (p.shortName?.takeIf { it.isNotBlank() } ?: p.name ?: "") else (p.name ?: "")
                selectedCustomer = CustomerDto(id = p.id, name = nm, phone = p.phone)
            }
            o.warehouseId?.let { selectedWarehouseId = it }
            // Đơn nhập giao thẳng → chọn "Giao cho khách" + khôi phục KH nhận (từ đơn bán liên kết).
            if (isPurchase && o.dropshipCustomerId != null) {
                selectedWarehouseId = DROPSHIP_WH
                val lp = o.linkedOrder?.party
                dropshipCustomer = CustomerDto(id = o.dropshipCustomerId, name = lp?.name?.takeIf { it.isNotBlank() } ?: "Khách nhận hàng", phone = lp?.phone)
            }
            o.orderedAt?.let { runCatching { orderDateMs = java.time.Instant.parse(it).toEpochMilli() } }
            notes = o.notes ?: ""
            shipCustomer = o.shippingFee?.takeIf { it > 0 }?.let { fmtMoney(it) } ?: ""
            shipCompany = o.actualShippingFee?.takeIf { it > 0 }?.let { fmtMoney(it) } ?: ""
            codAmount = o.codCollected?.takeIf { it > 0 }?.let { fmtMoney(it) } ?: ""
            items.clear()
            originalItems.clear()
            o.items.forEach { it2 ->
                val vName = it2.snapshot.variantName?.takeIf { s -> s.isNotBlank() } ?: it2.variantLabel.ifBlank { it2.productName }
                items.add(OrderItemDraft(
                    variantId = it2.variantId,
                    unitId = it2.unitId,
                    productName = it2.productName,
                    variantName = vName,
                    qty = it2.qtyUnit,
                    price = it2.unitPrice,
                    imageUrl = it2.imageUrl,
                    // Chỉ giữ 1 unit đã chọn (order item không kèm units list) → không đổi unit khi edit.
                    units = listOf(VariantUnitDto(id = it2.unitId, name = it2.unitName, conversionFactor = 1.0, price = it2.unitPrice, isBase = false, isDefaultSale = false)),
                    id = it2.id,
                ))
                originalItems.add(OrigItemSnap(it2.id, it2.variantId, it2.unitId, it2.qtyUnit, it2.unitPrice))
            }
            // Ảnh đính kèm đơn (ảnh xác nhận giao/nhận NV kho chụp).
            photos = try { container.vapi.listAttachments("order", oid, "photo", 50).data?.mapNotNull { it.url } ?: emptyList() } catch (_: Exception) { emptyList() }
        } catch (_: Exception) {}
    }

    // SP gợi ý ở thẻ mặt hàng:
    // - Đơn nhập giao thẳng: SP KHÁCH NHẬN hay mua (recentProducts của KH nhận).
    // - Đơn nhập thường: SP hay nhập từ NCC (supplierRecentProducts).
    // - Đơn bán: SP KH hay mua (recentProducts).
    var suggested by remember { mutableStateOf<List<RecentProductDto>>(emptyList()) }
    LaunchedEffect(selectedCustomer?.id, isDropship, dropshipCustomer?.id) {
        suggested = try {
            when {
                isDropship -> dropshipCustomer?.let { container.vapi.recentProducts(it.id, 5).data } ?: emptyList()
                isPurchase -> selectedCustomer?.let { container.vapi.supplierRecentProducts(it.id, 5).data } ?: emptyList()
                else -> selectedCustomer?.let { container.vapi.recentProducts(it.id, 5).data } ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    // Pickers — tạo mới mở KH luôn; edit/view không auto mở.
    var customerPickerOpen by remember { mutableStateOf(orderId == null) }
    var productPickerOpen by remember { mutableStateOf(false) }
    var pickerInitQuery by remember { mutableStateOf("") }
    var pickerProductId by remember { mutableStateOf<Long?>(null) }

    // ===== Autosave nháp (mirror web SaleOrderFormView) =====
    // Chọn SP đầu tiên → tạo đơn nháp ngay; đổi giá/SL/đơn vị → PUT item khi blur; xoá → DELETE.
    // Chỉ khi tạo mới / sửa đơn nháp; dialog công nợ (allowEditAnyStatus) → giữ lưu tay.
    val autosaveEnabled = !allowEditAnyStatus
    // Serialize autosave (create/add/update) → tránh tạo TRÙNG đơn khi chọn 2 SP liên tiếp.
    val autosaveMutex = remember { kotlinx.coroutines.sync.Mutex() }
    suspend fun autosaveDraft(vId: Long) {
        if (!autosaveEnabled) return
        autosaveMutex.withLock {
        val cust = selectedCustomer ?: return@withLock
        val idx = items.indexOfFirst { it.variantId == vId }
        if (idx < 0) return@withLock
        val d = items[idx]
        if (d.unitId == 0L || d.qty <= 0.0 || d.price < 0.0) return@withLock
        if (isDropship && dropshipCustomer == null) return@withLock   // giao thẳng cần khách nhận trước
        val line = CreateOrderItem(d.variantId, d.unitId, d.qty, d.price)
        try {
            val oid = currentOrderId
            if (oid != null && d.id != null) {
                container.vapi.updateOrderItem(oid, d.id!!, line)
            } else if (oid != null && d.id == null) {
                val newId = container.vapi.addOrderItem(oid, line).data?.item?.id
                val i2 = items.indexOfFirst { it.variantId == vId }
                if (i2 >= 0 && newId != null) items[i2] = items[i2].copy(id = newId)
            } else {
                // Tạo đơn nháp với chính SP này (SP đầu tiên).
                val req = CreateOrderRequest(
                    type = if (isPurchase) "purchase" else "sale",
                    partyType = if (isPurchase) "supplier" else "customer",
                    partyId = cust.id, status = "draft",
                    orderedAt = java.time.Instant.ofEpochMilli(orderDateMs).toString(),
                    warehouseId = if (isDropship) null else selectedWarehouseId,
                    dropshipCustomerId = if (isDropship) dropshipCustomer?.id else null,
                    shippingFee = if (isPurchase) null else parseMoney(shipCustomer).takeIf { it > 0 },
                    actualShippingFee = if (isPurchase) null else parseMoney(shipCompany).takeIf { it > 0 },
                    codCollected = if (isPurchase) null else parseMoney(codAmount).takeIf { it > 0 },
                    items = listOf(line),
                    notes = notes.ifBlank { null },
                    createdByUserId = userId,
                )
                val created = container.vapi.createOrder(req).data ?: return@withLock
                existingStatus = "draft"
                val newId = created.items.firstOrNull()?.id
                val i2 = items.indexOfFirst { it.variantId == vId }
                if (i2 >= 0 && newId != null) items[i2] = items[i2].copy(id = newId)
                // Set currentOrderId SAU khi map item; chặn reload để không wipe form.
                suppressReload = true
                currentOrderId = created.id
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Lưu tự động thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        }   // /autosaveMutex.withLock
    }
    // Trigger autosave (non-suspend) từ callback item.
    fun autosave(vId: Long) { scope.launch { autosaveDraft(vId) } }

    fun addVariant(v: VariantSearchDto) {
        if (items.any { it.variantId == v.id }) {
            Toast.makeText(context, "\"${variantDisplay(v, v.product?.name ?: "")}\" đã có trong đơn", Toast.LENGTH_SHORT).show()
            return
        }
        val units = v.units
        val defUnit = units.firstOrNull { it.isDefaultSale } ?: units.firstOrNull { it.isBase } ?: units.firstOrNull()
        scope.launch {
            var price = defUnit?.price ?: v.price ?: 0.0
            try {
                val lp = if (isPurchase) {
                    if (defUnit != null) container.vapi.supplierLastPrice(selectedCustomer!!.id, v.id, defUnit.id).data else null
                } else container.vapi.lastPrice(selectedCustomer!!.id, v.id, defUnit?.id).data
                if (lp?.unitPrice != null) price = lp.unitPrice
            } catch (_: Exception) {}
            items.add(OrderItemDraft(
                variantId = v.id,
                unitId = defUnit?.id ?: 0L,
                productName = v.product?.name ?: "",
                variantName = variantDisplay(v, v.product?.name ?: ""),
                qty = 1.0,
                price = price,
                imageUrl = v.image ?: v.product?.primaryImage?.url,
                units = units,
            ))
            // Picker giữ mở → cần xác nhận đã thêm (form bị sheet che).
            Toast.makeText(context, "Đã thêm \"${variantDisplay(v, v.product?.name ?: "")}\"", Toast.LENGTH_SHORT).show()
            // Chọn SP → tự lưu nháp ngay (SP đầu tiên tạo đơn; SP sau thêm item).
            autosaveDraft(v.id)
        }
    }

    // Box wrapper: form + pickers nằm CHUNG 1 Box → picker z-stack đè lên form
    // (overlay), không bị đẩy ra ngoài khi SaleOrderForm đặt trong Column của caller.
    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxSize().background(AdminColors.Bg)
            .padding(bottom = pushUpDp)   // đẩy lên 80% chiều cao bàn phím
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp)) {
            // ===== Card KH + Kho (bỏ title, padding bottom giảm 50% = 6dp) =====
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card)
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 6.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (selectedCustomer == null) Text("Chưa chọn $partyWordFull", color = AdminColors.TextMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic,
                            modifier = Modifier.clickable(enabled = canEdit) { customerPickerOpen = true })
                        // Tap tên đối tác → xem đơn hàng của đối tác đó (mirror web).
                        // Đơn nhập: tên NCC to hơn 2sp (16) + chiều cao dòng giảm ~15% (18sp).
                        else Text(selectedCustomer!!.name, color = AdminColors.Text,
                            fontSize = if (isPurchase) 16.sp else 14.sp,
                            lineHeight = if (isPurchase) 18.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { custOrdersOpen = true })
                    }
                    if (canEdit) Text(if (selectedCustomer == null) "Chọn $partyWord" else "Đổi $partyWord", color = AdminColors.Primary, fontSize = 12.sp,
                        modifier = Modifier.clickable { customerPickerOpen = true }.padding(4.dp))
                }
                HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Đơn bán: nhãn "Kho bán" + dropdown wrap-content. Đơn nhập: dropdown full-width
                    // căn giữa (nhãn "Giao về {kho}" + option "Giao cho khách"), ngày ở cuối hàng.
                    if (!isPurchase) {
                        Text("Kho bán", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                        WarehouseDropdown(warehouses, selectedWarehouseId, canEdit) { selectedWarehouseId = it }
                        Spacer(Modifier.weight(1f))
                    } else {
                        WarehouseDropdown(warehouses, selectedWarehouseId, canEdit, isPurchase = true, modifier = Modifier.weight(1f)) { selectedWarehouseId = it }
                        Spacer(Modifier.width(8.dp))
                    }
                    // Ngày đơn — tap mở DatePicker (chỉ canEdit).
                    val dateLabel = java.text.SimpleDateFormat("dd/MM/yyyy", Locale("vi")).format(java.util.Date(orderDateMs))
                    Text(dateLabel, color = if (canEdit) AdminColors.Primary else AdminColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(enabled = canEdit) { datePickerOpen = true }
                            .background(AdminColors.Primary.copy(alpha = if (canEdit) 0.08f else 0f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }

            // ===== Thẻ khách nhận hàng (giao thẳng) — chỉ khi đơn nhập chọn "Giao cho khách" =====
            if (isDropship) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(12.dp),
                ) {
                    Text("Khách nhận hàng (giao thẳng)", color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (dropshipCustomer == null) Text("Chưa chọn khách nhận", color = AdminColors.TextMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f))
                        else Text(dropshipCustomer!!.name, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (canEdit) Text(if (dropshipCustomer == null) "Chọn khách" else "Đổi khách", color = AdminColors.Primary, fontSize = 12.sp,
                            modifier = Modifier.clickable { dropshipPickerOpen = true }.padding(4.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== Card Items =====
            Card("") {
                // Header: title "Mặt hàng (N)" + chip SP hay mua CÙNG DÒNG (LazyRow vuốt
                // ngang, không wrap).
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Mặt hàng (${items.size})", color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (suggested.isNotEmpty() && canEdit) {
                        Spacer(Modifier.width(8.dp))
                        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(suggested, key = { it.productId }) { p ->
                                Row(
                                    Modifier.clip(RoundedCornerShape(16.dp)).background(AdminColors.Primary.copy(alpha = 0.1f))
                                        .clickable {
                                            pickerInitQuery = p.productName; pickerProductId = p.productId; productPickerOpen = true
                                        }.padding(horizontal = 10.dp, vertical = 2.dp),   // bg cao -20% (4→2)
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("+ ${p.productName}", color = AdminColors.Primary, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (items.isEmpty()) {
                    Text("Chưa có sản phẩm — chọn KH rồi nhấn Thêm SP / tap chip", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    Column {
                        items.forEachIndexed { idx, it ->
                            ItemRow(
                                draft = it,
                                focusCtx = focusCtx,
                                scope = scope,
                                canEdit = canEdit,
                                onDelete = {
                                    val removed = it
                                    items.removeAt(idx)
                                    // Xoá item đã lưu khỏi đơn nháp (đơn non-draft dùng diff ở submit → bỏ qua).
                                    val oid = currentOrderId
                                    if (autosaveEnabled && oid != null && removed.id != null) scope.launch {
                                        try { container.vapi.deleteOrderItem(oid, removed.id!!) }
                                        catch (e: Exception) { Toast.makeText(context, "Xoá item thất bại: ${e.message}", Toast.LENGTH_SHORT).show() }
                                    }
                                },
                                onQtyChange = { q -> items[idx] = it.copy(qty = q) },
                                onPriceChange = { p -> items[idx] = it.copy(price = p) },
                                onUnitChange = { u -> items[idx] = it.copy(unitId = u.id, price = u.price ?: it.price); autosave(it.variantId) },
                                onQtyCommit = { autosave(it.variantId) },
                                onPriceCommit = { autosave(it.variantId) },
                            )
                            if (idx < items.size - 1) HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.4f))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Footer: Thêm SP (trái, chỉ canEdit) + Tổng (phải)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canEdit) OutlinedButton(
                        onClick = {
                            if (selectedCustomer == null) Toast.makeText(context, "Chọn khách hàng trước", Toast.LENGTH_SHORT).show()
                            // Giữ query + productId lần tìm trước → mở lại hiện đúng list đã tìm.
                            else productPickerOpen = true
                        },
                        modifier = Modifier.height(32.dp),                         // -20% so default 40dp
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) { Text("+ Thêm SP", color = AdminColors.Primary, fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    val total = items.sumOf { it.qty * it.price }
                    Text("Tổng tiền hàng ", color = AdminColors.TextMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light)
                    Text("(1)", color = AdminColors.Text.copy(alpha = 0.39f), fontSize = 13.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light)
                    Text(": ", color = AdminColors.TextMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light)
                    Text(fmtMoney(total), color = AdminColors.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(" đ", color = Color(0xFF999900), fontSize = 11.sp)
                }
            }

            // ===== Card phí ship + COD (bỏ title, pt/pb gọn) — ẩn với đơn nhập =====
            if (!isPurchase) {
            Spacer(Modifier.height(12.dp))
            Card("", vPadding = 6.dp) {
                ShipRow("Phí ship KH", shipCustomer, focusCtx, scope, canEdit, marker = "(2)") { shipCustomer = it }
                ShipRow("Phí ship KHO", shipCompany, focusCtx, scope, canEdit) { shipCompany = it }
                ShipRow("Thu hộ", codAmount, focusCtx, scope, canEdit, marker = "(3)") { codAmount = it }
                // Tổng cộng = tổng tiền hàng + ship KH - thu hộ
                val grandTotal = items.sumOf { it.qty * it.price } + parseMoney(shipCustomer) - parseMoney(codAmount)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(0.42f), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tổng cộng ", color = AdminColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("(1) + (2) - (3)", color = AdminColors.Text.copy(alpha = 0.39f), fontSize = 11.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light, maxLines = 1)
                    }
                    Text(":", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Row(Modifier.weight(0.58f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                        Text(fmtMoney(grandTotal), color = AdminColors.Primary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(" đ", color = Color(0xFF999900), fontSize = 11.sp)
                    }
                }
            }
            }   // /if (!isPurchase) — card ship/COD

            Spacer(Modifier.height(12.dp))

            // ===== Card Ghi chú =====
            Card("Ghi chú") {
                BasicTextField(
                    value = notes, onValueChange = { notes = it },
                    readOnly = !canEdit,
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            if (notes.isEmpty()) Text(if (canEdit) "Ghi chú đơn (tùy chọn)" else "—", color = AdminColors.TextMuted, fontSize = 13.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ===== Ảnh đính kèm đơn (ảnh xác nhận giao/nhận) — chỉ hiện khi có =====
            if (photos.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Ảnh đính kèm (${photos.size})", color = AdminColors.TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    photos.forEach { url ->
                        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).clickable { viewerUrl = url })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ===== Nút lưu =====
            // Sửa đơn ĐÃ non-draft (dialog công nợ) → 1 nút "Lưu" (giữ tình trạng, per-item).
            // Tạo mới / draft → Lưu nháp | Xác nhận.
            if (canEdit && editingNonDraft) {
                Button(
                    onClick = { submit(scope, container, currentOrderId, userId, selectedCustomer, selectedWarehouseId, isPurchase, isDropship, dropshipCustomer?.id, orderDateMs, items, notes, parseMoney(shipCustomer), parseMoney(shipCompany), parseMoney(codAmount), existingStatus ?: "confirmed", true, originalItems.toList(), context, onDone, onDraftSaved) { saving = it } },
                    enabled = !saving && selectedCustomer != null && items.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Lưu")
                }
            } else if (canEdit) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { submit(scope, container, currentOrderId, userId, selectedCustomer, selectedWarehouseId, isPurchase, isDropship, dropshipCustomer?.id, orderDateMs, items, notes, parseMoney(shipCustomer), parseMoney(shipCompany), parseMoney(codAmount), "draft", false, emptyList(), context, onDone, onDraftSaved) { saving = it } },
                    enabled = !saving && selectedCustomer != null && items.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Lưu nháp") }
                Button(
                    onClick = { submit(scope, container, currentOrderId, userId, selectedCustomer, selectedWarehouseId, isPurchase, isDropship, dropshipCustomer?.id, orderDateMs, items, notes, parseMoney(shipCustomer), parseMoney(shipCompany), parseMoney(codAmount), "confirmed", false, emptyList(), context, onDone, onDraftSaved) { saving = it } },
                    enabled = !saving && selectedCustomer != null && items.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary),
                    modifier = Modifier.weight(1f),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Xác nhận")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ===== Pickers =====
    // Party picker: đơn nhập → chọn NCC (listSuppliers); đơn bán → chọn KH.
    if (customerPickerOpen) {
        CustomerPicker(isPurchase = isPurchase, onPick = { c -> selectedCustomer = c; customerPickerOpen = false }, onClose = { customerPickerOpen = false })
    }
    // Dropship picker: khách nhận hàng (giao thẳng) — LUÔN là KH, kể cả đang ở đơn nhập.
    if (dropshipPickerOpen) {
        CustomerPicker(isPurchase = false, onPick = { c -> dropshipCustomer = c; dropshipPickerOpen = false }, onClose = { dropshipPickerOpen = false })
    }
    // Dialog đơn hàng của đối tác (tap tên) — mirror web: lọc ngày (mặc định 15 ngày), thumb phóng, tap thumb mở đơn.
    selectedCustomer?.let { c ->
        if (custOrdersOpen) {
            CustomerOrdersDialog(
                customerId = c.id,
                customerName = c.name,
                isPurchase = isPurchase,
                onOpenOrder = { id -> custOrdersOpen = false; currentOrderId = id },
                onClose = { custOrdersOpen = false },
            )
        }
    }
    if (productPickerOpen && selectedCustomer != null) {
        VariantPicker(
            warehouseId = selectedWarehouseId,
            query = pickerInitQuery,
            // persist query lên parent → mở lại giữ list đã tìm. Gõ tay = tìm tự do → bỏ filter SP (chip).
            onQueryChange = { pickerInitQuery = it; pickerProductId = null },
            productId = pickerProductId,
            suggested = suggested,
            selectedIds = items.map { it.variantId }.toSet(),
            // Chọn → đóng dialog (thêm vào đơn). Query giữ lại nên mở "Thêm SP" lần sau hiện list cũ.
            onPick = { v -> addVariant(v); productPickerOpen = false },
            onClose = { productPickerOpen = false },
        )
    }
    if (datePickerOpen) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = orderDateMs)
        // Tự đóng sau 0.5s khi user chọn được ngày mới (không cần bấm OK).
        LaunchedEffect(dpState.selectedDateMillis) {
            val sel = dpState.selectedDateMillis
            if (sel != null && sel != orderDateMs) {
                delay(300)
                orderDateMs = sel
                datePickerOpen = false
            }
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text, primary = AdminColors.Primary, onPrimary = Color.White)) {
            DatePickerDialog(
                onDismissRequest = { datePickerOpen = false },
                confirmButton = { TextButton(onClick = { dpState.selectedDateMillis?.let { orderDateMs = it }; datePickerOpen = false }) { Text("OK", color = AdminColors.Primary) } },
                dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text("Huỷ", color = AdminColors.TextMuted) } },
                colors = DatePickerDefaults.colors(containerColor = AdminColors.Card),
            ) { DatePicker(state = dpState) }
        }
    }
    // Preview ảnh đính kèm — zoom/pan (tái dùng viewer màn kho).
    viewerUrl?.let {
        // Caption preview: tên KH + ngày giao (căn giữa dưới, ngoài vùng ảnh).
        val capName = selectedCustomer?.name
        val capDate = deliveryDate?.take(10)?.let { d ->
            runCatching {
                java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("vi"))
                    .format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d)!!)
            }.getOrNull()
        }
        val caption = listOfNotNull(capName, capDate).joinToString("  ·  ").ifBlank { null }
        PhotoZoomViewer(it, onClose = { viewerUrl = null }, caption = caption)
    }
    }   // đóng Box wrapper (form + pickers z-stack overlay)
}

/** Local draft mỗi dòng item. id = order_item id (đơn đã lưu) để diff per-item khi sửa non-draft. */
data class OrderItemDraft(
    val variantId: Long,
    val unitId: Long,
    val productName: String,
    val variantName: String,
    val qty: Double,
    val price: Double,
    val imageUrl: String?,
    val units: List<VariantUnitDto>,
    val id: Long? = null,
)

/** Snapshot item lúc mở đơn non-draft → so sánh add/update/delete từng item khi lưu. */
data class OrigItemSnap(val id: Long, val variantId: Long, val unitId: Long, val qty: Double, val price: Double)

@Composable
internal fun Card(title: String, vPadding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(horizontal = 12.dp, vertical = vPadding)) {
        if (title.isNotEmpty()) {
            Text(title, color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
        }
        content()
    }
}

/**
 * Dropdown chọn kho — dark mode. enabled=false → chỉ hiển thị (readonly).
 * isPurchase: đổi nhãn kho thành "Giao về {kho}", thêm option "Giao cho khách (giao thẳng)"
 * (sentinel [DROPSHIP_WH]), trigger full-width + căn giữa (mirror web NSelect wh-center).
 */
@Composable
private fun WarehouseDropdown(
    warehouses: List<WarehouseDto>,
    selectedId: Long?,
    enabled: Boolean = true,
    isPurchase: Boolean = false,
    modifier: Modifier = Modifier,
    onSelect: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = warehouses.firstOrNull { it.id == selectedId }
    val triggerText = when {
        isPurchase && selectedId == DROPSHIP_WH -> "Giao cho khách (giao thẳng)"
        isPurchase && current != null -> "Giao về ${current.name}"
        current != null -> current.name
        else -> "Chọn kho"
    }
    Box(modifier) {
        Row(
            (if (isPurchase) Modifier.fillMaxWidth() else Modifier).clickable(enabled = enabled) { open = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isPurchase) Arrangement.Center else Arrangement.Start,
        ) {
            Text(triggerText, color = AdminColors.Text, fontSize = 14.sp)
            if (enabled) Text(" ▾", color = AdminColors.TextMuted, fontSize = 12.sp)
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                warehouses.forEach { w ->
                    DropdownMenuItem(
                        text = { Text(if (isPurchase) "Giao về ${w.name}" else w.name, color = if (w.id == selectedId) AdminColors.Primary else AdminColors.Text) },
                        onClick = { onSelect(w.id); open = false },
                        colors = MenuDefaults.itemColors(textColor = AdminColors.Text),
                    )
                }
                if (isPurchase) DropdownMenuItem(
                    text = { Text("Giao cho khách (giao thẳng)", color = if (selectedId == DROPSHIP_WH) AdminColors.Primary else AdminColors.Text) },
                    onClick = { onSelect(DROPSHIP_WH); open = false },
                    colors = MenuDefaults.itemColors(textColor = AdminColors.Text),
                )
            }
        }
    }
}

@Composable
private fun ItemRow(
    draft: OrderItemDraft,
    focusCtx: FocusCenterCtx,
    scope: kotlinx.coroutines.CoroutineScope,
    canEdit: Boolean,
    onDelete: () -> Unit,
    onQtyChange: (Double) -> Unit,
    onPriceChange: (Double) -> Unit,
    onUnitChange: (VariantUnitDto) -> Unit,
    onQtyCommit: () -> Unit = {},
    onPriceCommit: () -> Unit = {},
) {
    // Swipe trái > 1/3 width → xoá (chỉ canEdit).
    var offsetX by remember(draft.variantId) { mutableStateOf(0f) }
    var rowWidth by remember { mutableStateOf(1f) }
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { rowWidth = it.width.toFloat() }
            .then(if (canEdit) Modifier.pointerInput(draft.variantId) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (-offsetX > rowWidth / 3f) onDelete() else offsetX = 0f },
                ) { _, dragAmount -> offsetX = (offsetX + dragAmount).coerceAtMost(0f) }
            } else Modifier),
    ) {
        // nền đỏ delete
        Box(Modifier.matchParentSize().background(AdminColors.Danger.copy(alpha = 0.25f)), contentAlignment = Alignment.CenterEnd) {
            Text("Xoá", color = AdminColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(end = 16.dp))
        }
        Row(
            Modifier.fillMaxWidth().offset { IntOffset(offsetX.toInt(), 0) }.background(AdminColors.Card).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (draft.imageUrl != null) AsyncImage(model = draft.imageUrl, contentDescription = null,
                modifier = Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)))
            else Box(Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(draft.variantName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                // 1 Row duy nhất: qty · unit · × · price · = với 4 spacer weight(1f)
                // đều → gap qty-unit = unit-× = ×-price = price-= BẰNG NHAU.
                // Total + đ cố định phải (không weight).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var qtyText by remember(draft.variantId) { mutableStateOf(trimZeros(draft.qty)) }
                    var qtyAtFocus by remember(draft.variantId) { mutableStateOf<String?>(null) }
                    BasicTextField(
                        value = qtyText,
                        onValueChange = { raw -> val f = raw.filter { c -> c.isDigit() || c == '.' }; qtyText = f; onQtyChange(f.toDoubleOrNull() ?: 0.0) },
                        readOnly = !canEdit,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(AdminColors.Primary),
                        // Snapshot lúc focus, blur → autosave nếu đổi (tránh PUT thừa).
                        modifier = Modifier.width(40.dp).centerOnFocus(focusCtx, scope, "qty-${draft.variantId}")
                            .onFocusChanged { st ->
                                if (st.isFocused) qtyAtFocus = qtyText
                                else if (qtyAtFocus != null) { if (qtyAtFocus != qtyText) onQtyCommit(); qtyAtFocus = null }
                            },
                    )
                    Spacer(Modifier.weight(1f))
                    UnitDropdown(draft.units, draft.unitId, canEdit, onUnitChange)
                    Spacer(Modifier.weight(1f))
                    Text("×", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    var priceText by remember(draft.variantId) { mutableStateOf(fmtMoney(draft.price)) }
                    var priceAtFocus by remember(draft.variantId) { mutableStateOf<String?>(null) }
                    BasicTextField(
                        value = priceText,
                        onValueChange = { raw -> val v = parseMoney(raw); priceText = if (v > 0) fmtMoney(v) else ""; onPriceChange(v) },
                        readOnly = !canEdit,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(AdminColors.Primary),
                        // Snapshot lúc focus, blur → autosave nếu đổi (tránh PUT thừa).
                        modifier = Modifier.widthIn(min = 56.dp).centerOnFocus(focusCtx, scope, "price-${draft.variantId}")
                            .onFocusChanged { st ->
                                if (st.isFocused) priceAtFocus = priceText
                                else if (priceAtFocus != null) { if (priceAtFocus != priceText) onPriceCommit(); priceAtFocus = null }
                            },
                    )
                    Spacer(Modifier.weight(1f))
                    Text("=", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    // Bỏ "đ" sau thành tiền mỗi dòng — chỉ giữ "đ" ở dòng Tổng tiền hàng.
                    Text(fmtMoney(draft.qty * draft.price), color = AdminColors.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun UnitDropdown(units: List<VariantUnitDto>, selectedId: Long, enabled: Boolean = true, onSelect: (VariantUnitDto) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = units.firstOrNull { it.id == selectedId }
    Box {
        Text(
            cur?.name ?: "—",
            color = AdminColors.TextMuted, fontSize = 12.sp, fontStyle = FontStyle.Italic,
            modifier = Modifier.clickable(enabled = enabled) { open = true }.padding(horizontal = 4.dp),
        )
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                units.forEach { u ->
                    DropdownMenuItem(
                        text = { Text(u.name, color = if (u.id == selectedId) AdminColors.Primary else AdminColors.Text) },
                        onClick = { onSelect(u); open = false },
                        colors = MenuDefaults.itemColors(textColor = AdminColors.Text),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShipRow(label: String, value: String, focusCtx: FocusCenterCtx, scope: kotlinx.coroutines.CoroutineScope, enabled: Boolean, marker: String = "", onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(0.42f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AdminColors.TextMuted, fontSize = 12.sp)
            if (marker.isNotEmpty()) Text(" $marker", color = AdminColors.Text.copy(alpha = 0.39f), fontSize = 11.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light)
        }
        Text(":", color = AdminColors.TextMuted, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(0.58f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = { raw -> val v = parseMoney(raw); onChange(if (v > 0) fmtMoney(v) else "") },
                    readOnly = !enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(AdminColors.Primary),
                    modifier = Modifier.weight(1f).centerOnFocus(focusCtx, scope, "ship-$label"),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.CenterEnd) {
                            if (value.isEmpty()) Text("0", color = AdminColors.TextMuted, fontSize = 13.sp)
                            inner()
                        }
                    },
                )
                Text(" đ", color = AdminColors.TextMuted, fontSize = 11.sp)
            }
            HorizontalDivider(color = AdminColors.Border)
        }
    }
}

// ===== Customer/Supplier picker =====
// isPurchase=true → chọn NCC (listSuppliers, sort theo tên rút gọn), map SupplierDto→CustomerDto
// (id/name=display/phone) để dùng chung state. isPurchase=false → chọn KH như cũ.
@Composable
internal fun CustomerPicker(isPurchase: Boolean = false, onPick: (CustomerDto) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val userId = container.tokenManager.user?.id?.toLong() ?: return

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CustomerDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val viCollator = remember { java.text.Collator.getInstance(Locale("vi")) }

    LaunchedEffect(query) {
        loading = true
        try {
            results = if (isPurchase) {
                if (query.isBlank()) {
                    // Mặc định: NCC sắp theo SỐ ĐƠN NHẬP 15 ngày gần nhất (giữ nguyên thứ tự BE trả).
                    (container.vapi.suppliersRecentByPurchases(limit = 100, days = 15).data ?: emptyList())
                        .map { CustomerDto(id = it.id, name = it.display, phone = it.phone) }
                } else {
                    delay(280)
                    (container.vapi.listSuppliers(search = query, active = true, perPage = 100).data ?: emptyList())
                        .sortedWith(compareBy(viCollator) { it.display })
                        .map { CustomerDto(id = it.id, name = it.display, phone = it.phone) }
                }
            } else if (query.isBlank()) container.vapi.recentCustomers(userId, 20).data ?: emptyList()
            else { delay(280); container.vapi.searchCustomers(query, 20).data ?: emptyList() }
        } catch (_: Exception) {}
        loading = false
    }

    PickerSheet(title = if (isPurchase) "Chọn nhà cung cấp" else "Chọn khách hàng", onClose = onClose) {
        SearchField(query, if (isPurchase) "Tìm NCC theo tên..." else "Tìm KH theo tên, SĐT...", autoFocus = true) { query = it }
        Spacer(Modifier.height(8.dp))
        if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(28.dp))
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(results, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        c.phone?.let { Text(it, color = AdminColors.TextMuted, fontSize = 12.sp) }
                    }
                }
                HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.4f))
            }
        }
    }
}

// ===== Variant picker (search /v1/variants) =====
@Composable
internal fun VariantPicker(
    warehouseId: Long?,
    query: String,
    onQueryChange: (String) -> Unit,
    productId: Long?,
    suggested: List<RecentProductDto>,
    selectedIds: Set<Long>,
    onPick: (VariantSearchDto) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container

    // query do parent giữ (persist khi đóng/mở lại); results nội bộ, tự nạp lại theo query.
    var results by remember { mutableStateOf<List<VariantSearchDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query, productId) {
        // Query rỗng + không filter product → hiện variants của 5 SP hay mua.
        if (query.length < 2 && productId == null) {
            if (suggested.isEmpty()) { results = emptyList(); return@LaunchedEffect }
            loading = true
            val all = mutableListOf<VariantSearchDto>()
            for (p in suggested) {
                try { all += container.vapi.listAllVariants(productId = p.productId, warehouseId = warehouseId, perPage = 20).data ?: emptyList() } catch (_: Exception) {}
            }
            results = all
            loading = false
            return@LaunchedEffect
        }
        loading = true
        if (productId == null) delay(280)
        try {
            results = container.vapi.listAllVariants(
                search = query.ifBlank { null }, productId = productId, warehouseId = warehouseId, perPage = 30,
            ).data ?: emptyList()
        } catch (_: Exception) {}
        loading = false
    }

    PickerSheet(title = "Chọn biến thể", onClose = onClose, fillHeight = true) {
        SearchField(query, "Tìm biến thể — tên / SKU...") { onQueryChange(it) }
        Spacer(Modifier.height(8.dp))
        if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(28.dp))
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(results, key = { it.id }) { v ->
                val picked = v.id in selectedIds
                Row(Modifier.fillMaxWidth().clickable { onPick(v) }.padding(0.5.dp), verticalAlignment = Alignment.CenterVertically) {
                    val img = v.image ?: v.product?.primaryImage?.url
                    if (img != null) AsyncImage(model = img, contentDescription = null, modifier = Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)))
                    else Box(Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(variantDisplay(v, v.product?.name ?: ""), color = if (picked) AdminColors.Success else AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                        v.sku?.let { Text(it, color = AdminColors.TextMuted, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Dấu tích cho variant đã có trong đơn.
                    if (picked) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Đã chọn", tint = AdminColors.Success, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    // 3 dòng tồn giống tab SP: Kho / số (theo đơn vị mặc định) / tên đơn vị
                    val defUnit = v.units.firstOrNull { it.isDefaultSale } ?: v.units.firstOrNull { it.isBase } ?: v.units.firstOrNull()
                    val factor = defUnit?.conversionFactor ?: 1.0
                    val stockInUnit = (v.stockBase ?: 0.0).let { if (factor > 0) it / factor else it }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Kho", color = AdminColors.TextMuted, fontSize = 11.sp)
                        Text(trimZeros(stockInUnit), color = if (stockInUnit > 0) AdminColors.Primary else AdminColors.TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        defUnit?.name?.let { Text(it, color = AdminColors.TextMuted, fontSize = 11.sp) }
                    }
                }
                HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.4f))
            }
        }
    }
}

// ===== shared picker UI =====
@Composable
private fun PickerSheet(title: String, onClose: () -> Unit, fillHeight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    // KHÔNG dùng imePadding() thuần: picker Box ở trong vùng content trên AppShell BottomNav nên
    // imePadding (inset mức cửa sổ) đẩy dư → hở 1 khoảng trên bàn phím. Pad đúng = imeBottom − 86dp
    // (giá trị tinh chỉnh thực tế cho overlay trong tab Khám phá: BottomNav + chênh lệch inset) →
    // đáy Box sát top bàn phím. fillHeight=true: dialog fillMaxHeight chạm bàn phím.
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val offsetPx = with(density) { 86.dp.toPx() }
    val padBottom = with(density) { (imeBottomPx - offsetPx).coerceAtLeast(0f).toDp() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).padding(bottom = padBottom).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .align(if (fillHeight) Alignment.TopCenter else Alignment.Center).padding(12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card)
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))   // viền mỏng sáng phân định vùng làm việc
                .padding(16.dp)
                .clickable(enabled = false, onClick = {}),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("Đóng", color = AdminColors.Primary, fontSize = 13.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ===== Dialog "đơn hàng của khách" (tap tên KH) — mirror web SaleOrderFormView =====
// Lọc theo khoảng ngày (mặc định 15 ngày gần nhất), mỗi dòng có thumb ảnh đính kèm; tap dòng
// → phóng thumb 3x (tâm cạnh phải) + mờ các dòng khác 65% + viền/quầng sáng trắng; tap thumb → mở đơn đó.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerOrdersDialog(
    customerId: Long,
    customerName: String,
    isPurchase: Boolean = false,
    onOpenOrder: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    val now = System.currentTimeMillis()
    var startMs by remember { mutableStateOf<Long?>(now - 15L * 86_400_000L) } // mặc định 15 ngày gần nhất
    var endMs by remember { mutableStateOf<Long?>(now) }
    var orders by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var zoomedId by remember { mutableStateOf<Long?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val ymdFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale("vi")) }
    val labelFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale("vi")) }

    LaunchedEffect(startMs, endMs) {
        loading = true
        orders = try {
            container.vapi.listOrders(
                type = if (isPurchase) "purchase" else "sale", partyId = customerId, invoiceOnly = "all", perPage = 100,
                dateFrom = startMs?.let { ymdFmt.format(Date(it)) },
                dateTo = endMs?.let { ymdFmt.format(Date(it)) },
            ).data ?: emptyList()
        } catch (_: Exception) { emptyList() }
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f)
                .align(Alignment.Center).padding(12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card)
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(16.dp)
                .clickable(enabled = false, onClick = {}),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Đơn hàng — $customerName", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Đóng", color = AdminColors.Primary, fontSize = 13.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Spacer(Modifier.height(10.dp))
            // Khoảng ngày — tap mở DateRangePicker (chọn 1 lần).
            val rangeLabel = "${startMs?.let { labelFmt.format(Date(it)) } ?: "…"}  →  ${endMs?.let { labelFmt.format(Date(it)) } ?: "…"}"
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                    .clickable { datePickerOpen = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(rangeLabel, color = AdminColors.Text, fontSize = 13.sp) }
            Spacer(Modifier.height(10.dp))

            when {
                loading -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
                orders.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { Text("Không có đơn hàng trong khoảng ngày này", color = AdminColors.TextMuted, fontSize = 13.sp) }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    // Chừa khoảng trên/dưới để thumb phóng của đơn đầu/cuối không bị khung cắt.
                    contentPadding = PaddingValues(vertical = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(orders, key = { _, o -> o.id }) { idx, o ->
                        CustomerOrderRow(
                            o = o,
                            zoomed = zoomedId == o.id,
                            dim = zoomedId != null && zoomedId != o.id,
                            onToggleZoom = {
                                zoomedId = if (zoomedId == o.id) null else o.id
                                if (zoomedId != null) scope.launch { listState.animateScrollToItem(idx) }
                            },
                            onOpenOrder = { onOpenOrder(o.id) },
                        )
                    }
                }
            }
        }
    }

    if (datePickerOpen) {
        val rangeState = rememberDateRangePickerState(initialSelectedStartDateMillis = startMs, initialSelectedEndDateMillis = endMs)
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text, primary = AdminColors.Primary, onPrimary = Color.White)) {
            DatePickerDialog(
                onDismissRequest = { datePickerOpen = false },
                confirmButton = {
                    TextButton(
                        onClick = { startMs = rangeState.selectedStartDateMillis; endMs = rangeState.selectedEndDateMillis; datePickerOpen = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("OK", color = AdminColors.Primary) }
                },
                colors = DatePickerDefaults.colors(containerColor = AdminColors.Card),
            ) {
                DateRangePicker(
                    state = rangeState,
                    modifier = Modifier.weight(1f),
                    title = {},
                    showModeToggle = false,
                    headline = {
                        val s = rangeState.selectedStartDateMillis; val e = rangeState.selectedEndDateMillis
                        Text(
                            text = (s?.let { labelFmt.format(Date(it)) } ?: "Bắt đầu") + "  –  " + (e?.let { labelFmt.format(Date(it)) } ?: "Kết thúc"),
                            color = AdminColors.Text, fontSize = 16.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CustomerOrderRow(
    o: OrderDto,
    zoomed: Boolean,
    dim: Boolean,
    onToggleZoom: () -> Unit,
    onOpenOrder: () -> Unit,
) {
    val statusColor = when (o.status) {
        "confirmed" -> AdminColors.Info
        "delivered", "completed" -> AdminColors.Success
        "cancelled" -> AdminColors.Danger
        else -> AdminColors.TextMuted
    }
    val statusText = when (o.status) {
        "draft" -> "Nháp"; "confirmed" -> "Đã xác nhận"
        "delivered" -> "Đã giao"; "completed" -> "Hoàn thành"
        "cancelled" -> "Huỷ"; else -> o.status
    }
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale("vi")) }
    val dateText = (o.orderedAt ?: o.createdAt)?.let { runCatching { dateFmt.format(Date(java.time.Instant.parse(it).toEpochMilli())) }.getOrNull() } ?: "—"

    Row(
        Modifier.fillMaxWidth()
            .alpha(if (dim) 0.65f else 1f)                // mờ nền các dòng khác 65%
            .background(AdminColors.Card, RoundedCornerShape(10.dp))
            .border(1.dp, AdminColors.Border, RoundedCornerShape(10.dp))
            .clickable { onToggleZoom() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.code, color = AdminColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("$dateText · ${o.items.size} mặt hàng", color = AdminColors.TextMuted, fontSize = 12.sp)
        }
        // Thumb ảnh đính kèm — tap dòng phóng; tap thumb mở chi tiết đơn.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.CenterEnd) {
            val thumb = o.thumbUrl
            if (thumb != null) {
                AsyncImage(
                    model = thumb, contentDescription = null,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).size(48.dp).clickable { onOpenOrder() },
                )
                // Ảnh phóng dựng trong Popup (cửa sổ riêng) → LUÔN nổi trên cùng, không bị dòng khác đè,
                // không bị LazyColumn cắt. Kích thước tự nhiên → viền 1px sắc nét (không bị scale nhân lên).
                // Vị trí: cạnh phải ảnh phóng trùng cạnh phải thumb (nở về trái), căn giữa dọc theo thumb.
                if (zoomed) {
                    val positionProvider = remember {
                        object : PopupPositionProvider {
                            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                                val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                val y = (anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                                return IntOffset(x, y)
                            }
                        }
                    }
                    Popup(
                        popupPositionProvider = positionProvider,
                        properties = PopupProperties(focusable = false),
                        onDismissRequest = onToggleZoom,
                    ) {
                        AsyncImage(
                            model = thumb, contentDescription = null,
                            modifier = Modifier
                                .shadow(10.dp, RoundedCornerShape(8.dp), spotColor = Color.White, ambientColor = Color.White) // quầng sáng trắng
                                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp))                        // viền trắng 1px
                                .clip(RoundedCornerShape(8.dp))
                                .size(158.dp)   // 48dp × 3.3
                                .clickable { onOpenOrder() },
                        )
                    }
                }
            } else {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    Text("—", color = AdminColors.TextMuted, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, placeholder: String, autoFocus: Boolean = false, onChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // autoFocus = mở dialog là focus ô tìm + bật bàn phím luôn (chờ sheet layout xong).
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(200)
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    BasicTextField(
        value = value, onValueChange = onChange,
        textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
        cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
        modifier = Modifier.focusRequester(focusRequester),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(12.dp)) {
                if (value.isEmpty()) Text(placeholder, color = AdminColors.TextMuted, fontSize = 13.sp)
                inner()
            }
        },
    )
}

// ===== submit =====
private fun submit(
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
    orderId: Long?,
    userId: Long?,
    customer: CustomerDto?,
    warehouseId: Long?,
    isPurchase: Boolean,
    isDropship: Boolean,
    dropshipCustomerId: Long?,
    orderDateMs: Long,
    items: List<OrderItemDraft>,
    notes: String,
    shipCustomer: Double,
    shipCompany: Double,
    cod: Double,
    status: String,
    nonDraft: Boolean,
    originalItems: List<OrigItemSnap>,
    context: android.content.Context,
    onDone: () -> Unit,
    onDraftSaved: (Long) -> Unit,
    setSaving: (Boolean) -> Unit,
) {
    if (customer == null || items.isEmpty()) return
    if (isDropship && dropshipCustomerId == null) { Toast.makeText(context, "Chọn khách nhận hàng", Toast.LENGTH_SHORT).show(); return }
    setSaving(true)
    scope.launch {
        try {
            val req = CreateOrderRequest(
                type = if (isPurchase) "purchase" else "sale",
                partyType = if (isPurchase) "supplier" else "customer",
                partyId = customer.id, status = status,
                orderedAt = java.time.Instant.ofEpochMilli(orderDateMs).toString(),
                // Giao thẳng → warehouse_id null (BE dùng kho mặc định) + dropship_customer_id.
                warehouseId = if (isDropship) null else warehouseId,
                dropshipCustomerId = if (isDropship) dropshipCustomerId else null,
                // Đơn nhập không có phí ship KH / thu hộ COD.
                shippingFee = if (isPurchase) null else shipCustomer.takeIf { it > 0 },
                actualShippingFee = if (isPurchase) null else shipCompany.takeIf { it > 0 },
                codCollected = if (isPurchase) null else cod.takeIf { it > 0 },
                items = items.map { CreateOrderItem(it.variantId, it.unitId, it.qty, it.price) },
                notes = notes.ifBlank { null },
                createdByUserId = userId,
            )
            if (nonDraft && orderId != null) {
                // Đơn non-draft: bulk update BỎ QUA items → field cấp đơn qua updateOrder (giữ status),
                // mặt hàng qua per-item endpoint (add/update/delete) theo diff với snapshot.
                container.vapi.updateOrder(orderId, req)
                val curIds = items.mapNotNull { it.id }.toSet()
                originalItems.filter { it.id !in curIds }.forEach { container.vapi.deleteOrderItem(orderId, it.id) }
                items.forEach { d ->
                    val payload = CreateOrderItem(d.variantId, d.unitId, d.qty, d.price)
                    val orig = originalItems.firstOrNull { it.id == d.id }
                    if (d.id != null && orig != null) {
                        if (orig.qty != d.qty || orig.price != d.price || orig.unitId != d.unitId || orig.variantId != d.variantId)
                            container.vapi.updateOrderItem(orderId, d.id, payload)
                    } else if (d.id == null) {
                        container.vapi.addOrderItem(orderId, payload)
                    }
                }
                Toast.makeText(context, "Đã lưu đơn", Toast.LENGTH_SHORT).show()
            } else {
                // Có orderId → cập nhật đơn (PUT); không → tạo mới (POST).
                val savedId = if (orderId != null) { container.vapi.updateOrder(orderId, req); orderId }
                              else container.vapi.createOrder(req).data?.id
                Toast.makeText(context, if (status == "draft") "Đã lưu nháp" else if (orderId != null) "Đã cập nhật" else "Đã tạo đơn", Toast.LENGTH_SHORT).show()
                // Lưu nháp → Ở LẠI form (không thoát). return@launch để bỏ qua onDone; finally vẫn setSaving(false).
                if (status == "draft" && savedId != null) { onDraftSaved(savedId); return@launch }
            }
            onDone()
        } catch (e: Exception) {
            Toast.makeText(context, "Lưu đơn thất bại: ${e.message}", Toast.LENGTH_LONG).show()
        } finally { setSaving(false) }
    }
}

/** Ctx scroll center input vào giữa "view còn lại" (= screen - keyboard). */
data class FocusCenterCtx(
    val scrollState: ScrollState,
    val screenHeightPx: Float,
    val statusBarPx: Float,
    val appBarPx: Float,
    val imeBottomState: androidx.compose.runtime.State<Float>,
)

/**
 * Khi focus input + bàn phím mở → scroll input vào GIỮA vùng hiển thị
 *   center = ((statusBar + appBar) + (screen - IME)) / 2
 * delay 280ms chờ IME mở xong + layout resize. positionInWindow đọc y tuyệt đối.
 */
@Composable
internal fun Modifier.centerOnFocus(ctx: FocusCenterCtx, scope: kotlinx.coroutines.CoroutineScope, key: Any): Modifier {
    var y by remember(key) { mutableStateOf(0f) }
    var h by remember(key) { mutableStateOf(0f) }
    return this
        .onGloballyPositioned { y = it.positionInWindow().y; h = it.size.height.toFloat() }
        .onFocusChanged { st ->
            if (st.isFocused) scope.launch {
                delay(280)
                val top = ctx.statusBarPx + ctx.appBarPx
                val bottom = ctx.screenHeightPx - ctx.imeBottomState.value
                val target = (top + bottom) / 2f - h / 2f
                val delta = y - target
                if (delta > 0f) runCatching { ctx.scrollState.animateScrollBy(delta) }
            }
        }
}
