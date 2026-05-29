package vn.chat9.app.ui.modules.sale

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import vn.chat9.app.data.vapi.dto.RecentProductDto
import vn.chat9.app.data.vapi.dto.VariantSearchDto
import vn.chat9.app.data.vapi.dto.VariantUnitDto
import vn.chat9.app.data.vapi.dto.WarehouseDto
import vn.chat9.app.ui.explore.AdminColors
import java.text.NumberFormat
import java.util.Locale

private val moneyFmt: NumberFormat = NumberFormat.getNumberInstance(Locale("vi"))
private fun fmtMoney(n: Double): String = moneyFmt.format(Math.round(n))
private fun parseMoney(s: String): Double = s.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0
private fun trimZeros(n: Double): String = if (n == Math.floor(n)) n.toLong().toString() else n.toString()

/** Tên variant ưu tiên cột name; fallback attributes joined; fallback product. */
private fun variantDisplay(v: VariantSearchDto, productName: String): String {
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
fun SaleOrderForm(orderId: Long? = null, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val userId = container.tokenManager.user?.id?.toLong()

    // ===== state =====
    var selectedCustomer by remember { mutableStateOf<CustomerDto?>(null) }
    val items = remember { mutableStateListOf<OrderItemDraft>() }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var shipCustomer by remember { mutableStateOf("") }
    var shipCompany by remember { mutableStateOf("") }
    var codAmount by remember { mutableStateOf("") }
    var orderDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var datePickerOpen by remember { mutableStateOf(false) }

    // Edit/view existing order: load khi có orderId. canEdit = tạo mới HOẶC draft.
    var existingStatus by remember { mutableStateOf<String?>(null) }
    val canEdit = orderId == null || existingStatus == "draft"

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

    // Load đơn existing (edit/view) → populate state.
    LaunchedEffect(orderId) {
        val oid = orderId ?: return@LaunchedEffect
        try {
            val o = container.vapi.getOrder(oid).data ?: return@LaunchedEffect
            existingStatus = o.status
            o.party?.let { selectedCustomer = CustomerDto(id = it.id, name = it.name ?: "", phone = it.phone) }
            o.warehouseId?.let { selectedWarehouseId = it }
            o.orderedAt?.let { runCatching { orderDateMs = java.time.Instant.parse(it).toEpochMilli() } }
            notes = o.notes ?: ""
            shipCustomer = o.shippingFee?.takeIf { it > 0 }?.let { fmtMoney(it) } ?: ""
            shipCompany = o.actualShippingFee?.takeIf { it > 0 }?.let { fmtMoney(it) } ?: ""
            codAmount = o.codCollected?.takeIf { it > 0 }?.let { fmtMoney(it) } ?: ""
            items.clear()
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
                ))
            }
        } catch (_: Exception) {}
    }

    // SP hay mua của KH
    var suggested by remember { mutableStateOf<List<RecentProductDto>>(emptyList()) }
    LaunchedEffect(selectedCustomer?.id) {
        val c = selectedCustomer ?: return@LaunchedEffect
        suggested = try { container.vapi.recentProducts(c.id, 5).data ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    // Pickers — tạo mới mở KH luôn; edit/view không auto mở.
    var customerPickerOpen by remember { mutableStateOf(orderId == null) }
    var productPickerOpen by remember { mutableStateOf(false) }
    var pickerInitQuery by remember { mutableStateOf("") }
    var pickerProductId by remember { mutableStateOf<Long?>(null) }

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
                val lp = container.vapi.lastPrice(selectedCustomer!!.id, v.id, defUnit?.id).data
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
                Row(Modifier.fillMaxWidth().clickable(enabled = canEdit) { customerPickerOpen = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (selectedCustomer == null) Text("Chưa chọn khách hàng", color = AdminColors.TextMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic)
                        else Text(selectedCustomer!!.name, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    if (canEdit) Text(if (selectedCustomer == null) "Chọn KH" else "Đổi KH", color = AdminColors.Primary, fontSize = 12.sp)
                }
                HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kho bán", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                    WarehouseDropdown(warehouses, selectedWarehouseId, canEdit) { selectedWarehouseId = it }
                    Spacer(Modifier.weight(1f))
                    // Ngày đơn — tap mở DatePicker (chỉ canEdit).
                    val dateLabel = java.text.SimpleDateFormat("dd/MM/yyyy", Locale("vi")).format(java.util.Date(orderDateMs))
                    Text(dateLabel, color = if (canEdit) AdminColors.Primary else AdminColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(enabled = canEdit) { datePickerOpen = true }
                            .background(AdminColors.Primary.copy(alpha = if (canEdit) 0.08f else 0f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp))
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
                                onDelete = { items.removeAt(idx) },
                                onQtyChange = { q -> items[idx] = it.copy(qty = q) },
                                onPriceChange = { p -> items[idx] = it.copy(price = p) },
                                onUnitChange = { u -> items[idx] = it.copy(unitId = u.id, price = u.price ?: it.price) },
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
                            else { pickerInitQuery = ""; pickerProductId = null; productPickerOpen = true }
                        },
                        modifier = Modifier.height(32.dp),                         // -20% so default 40dp
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) { Text("+ Thêm SP", color = AdminColors.Primary, fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    val total = items.sumOf { it.qty * it.price }
                    Text("Tổng ", color = AdminColors.TextMuted, fontSize = 13.sp)
                    Text(fmtMoney(total), color = AdminColors.Primary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(" đ", color = Color(0xFF999900), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== Card phí ship + COD (bỏ title, pt/pb gọn) =====
            Card("", vPadding = 6.dp) {
                ShipRow("Phí ship KH", shipCustomer, focusCtx, scope, canEdit) { shipCustomer = it }
                ShipRow("Phí ship KHO", shipCompany, focusCtx, scope, canEdit) { shipCompany = it }
                ShipRow("Thu hộ", codAmount, focusCtx, scope, canEdit) { codAmount = it }
            }

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

            Spacer(Modifier.height(16.dp))

            // ===== 2 nút (chỉ canEdit: tạo mới hoặc đơn draft) =====
            if (canEdit) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { submit(scope, container, orderId, userId, selectedCustomer, selectedWarehouseId, orderDateMs, items, notes, parseMoney(shipCustomer), parseMoney(shipCompany), parseMoney(codAmount), "draft", context, onDone) { saving = it } },
                    enabled = !saving && selectedCustomer != null && items.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Lưu nháp") }
                Button(
                    onClick = { submit(scope, container, orderId, userId, selectedCustomer, selectedWarehouseId, orderDateMs, items, notes, parseMoney(shipCustomer), parseMoney(shipCompany), parseMoney(codAmount), "confirmed", context, onDone) { saving = it } },
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
    if (customerPickerOpen) {
        CustomerPicker(onPick = { c -> selectedCustomer = c; customerPickerOpen = false }, onClose = { customerPickerOpen = false })
    }
    if (productPickerOpen && selectedCustomer != null) {
        VariantPicker(
            warehouseId = selectedWarehouseId,
            initQuery = pickerInitQuery,
            productId = pickerProductId,
            suggested = suggested,
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
    }   // đóng Box wrapper (form + pickers z-stack overlay)
}

/** Local draft mỗi dòng item. */
data class OrderItemDraft(
    val variantId: Long,
    val unitId: Long,
    val productName: String,
    val variantName: String,
    val qty: Double,
    val price: Double,
    val imageUrl: String?,
    val units: List<VariantUnitDto>,
)

@Composable
private fun Card(title: String, vPadding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(horizontal = 12.dp, vertical = vPadding)) {
        if (title.isNotEmpty()) {
            Text(title, color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
        }
        content()
    }
}

/** Dropdown chọn kho — dark mode. enabled=false → chỉ hiển thị (readonly). */
@Composable
private fun WarehouseDropdown(warehouses: List<WarehouseDto>, selectedId: Long?, enabled: Boolean = true, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = warehouses.firstOrNull { it.id == selectedId }
    Box {
        Row(Modifier.clickable(enabled = enabled) { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(current?.name ?: "Chọn kho", color = AdminColors.Text, fontSize = 14.sp)
            if (enabled) Text(" ▾", color = AdminColors.TextMuted, fontSize = 12.sp)
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                warehouses.forEach { w ->
                    DropdownMenuItem(
                        text = { Text(w.name, color = if (w.id == selectedId) AdminColors.Primary else AdminColors.Text) },
                        onClick = { onSelect(w.id); open = false },
                        colors = MenuDefaults.itemColors(textColor = AdminColors.Text),
                    )
                }
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
            Modifier.fillMaxWidth().offset { IntOffset(offsetX.toInt(), 0) }.background(AdminColors.Card).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (draft.imageUrl != null) AsyncImage(model = draft.imageUrl, contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
            else Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(draft.variantName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                // 1 Row duy nhất: qty · unit · × · price · = với 4 spacer weight(1f)
                // đều → gap qty-unit = unit-× = ×-price = price-= BẰNG NHAU.
                // Total + đ cố định phải (không weight).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var qtyText by remember(draft.variantId) { mutableStateOf(trimZeros(draft.qty)) }
                    BasicTextField(
                        value = qtyText,
                        onValueChange = { raw -> val f = raw.filter { c -> c.isDigit() || c == '.' }; qtyText = f; onQtyChange(f.toDoubleOrNull() ?: 0.0) },
                        readOnly = !canEdit,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(AdminColors.Primary),
                        modifier = Modifier.width(40.dp).centerOnFocus(focusCtx, scope, "qty-${draft.variantId}"),
                    )
                    Spacer(Modifier.weight(1f))
                    UnitDropdown(draft.units, draft.unitId, canEdit, onUnitChange)
                    Spacer(Modifier.weight(1f))
                    Text("×", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    var priceText by remember(draft.variantId) { mutableStateOf(fmtMoney(draft.price)) }
                    BasicTextField(
                        value = priceText,
                        onValueChange = { raw -> val v = parseMoney(raw); priceText = if (v > 0) fmtMoney(v) else ""; onPriceChange(v) },
                        readOnly = !canEdit,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(AdminColors.Primary),
                        modifier = Modifier.widthIn(min = 56.dp).centerOnFocus(focusCtx, scope, "price-${draft.variantId}"),
                    )
                    Spacer(Modifier.weight(1f))
                    Text("=", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(fmtMoney(draft.qty * draft.price), color = AdminColors.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(" đ", color = Color(0xFF999900), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun UnitDropdown(units: List<VariantUnitDto>, selectedId: Long, enabled: Boolean = true, onSelect: (VariantUnitDto) -> Unit) {
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
private fun ShipRow(label: String, value: String, focusCtx: FocusCenterCtx, scope: kotlinx.coroutines.CoroutineScope, enabled: Boolean, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(0.42f))
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

// ===== Customer picker =====
@Composable
private fun CustomerPicker(onPick: (CustomerDto) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val userId = container.tokenManager.user?.id?.toLong() ?: return

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CustomerDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(query) {
        loading = true
        try {
            results = if (query.isBlank()) container.vapi.recentCustomers(userId, 20).data ?: emptyList()
            else { delay(280); container.vapi.searchCustomers(query, 20).data ?: emptyList() }
        } catch (_: Exception) {}
        loading = false
    }

    PickerSheet(title = "Chọn khách hàng", onClose = onClose) {
        SearchField(query, "Tìm KH theo tên, SĐT...") { query = it }
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
private fun VariantPicker(
    warehouseId: Long?,
    initQuery: String,
    productId: Long?,
    suggested: List<RecentProductDto>,
    onPick: (VariantSearchDto) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container

    var query by remember { mutableStateOf(initQuery) }
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

    PickerSheet(title = "Chọn biến thể", onClose = onClose) {
        SearchField(query, "Tìm biến thể — tên / SKU...") { query = it }
        Spacer(Modifier.height(8.dp))
        if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(28.dp))
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(results, key = { it.id }) { v ->
                Row(Modifier.fillMaxWidth().clickable { onPick(v) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val img = v.image ?: v.product?.primaryImage?.url
                    if (img != null) AsyncImage(model = img, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(5.dp)))
                    else Box(Modifier.size(40.dp).clip(RoundedCornerShape(5.dp)).background(AdminColors.Border.copy(alpha = 0.3f)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(variantDisplay(v, v.product?.name ?: ""), color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                        v.sku?.let { Text(it, color = AdminColors.TextMuted, fontSize = 11.sp) }
                    }
                    v.price?.let { Text("${fmtMoney(it)} đ", color = AdminColors.Primary, fontSize = 12.sp) }
                }
                HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.4f))
            }
        }
    }
}

// ===== shared picker UI =====
@Composable
private fun PickerSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // imePadding trên Box → khi bàn phím mở, vùng Box co lại = screen - IME; dialog
    // align Center nằm giữa vùng còn lại → KHÔNG bị bàn phím che. Hiện ở giữa-trên
    // gần khu thao tác (nút Thêm SP), không phải bottom sheet sát đáy.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).imePadding().clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(12.dp)
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

@Composable
private fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value, onValueChange = onChange,
        textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
        cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
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
    orderDateMs: Long,
    items: List<OrderItemDraft>,
    notes: String,
    shipCustomer: Double,
    shipCompany: Double,
    cod: Double,
    status: String,
    context: android.content.Context,
    onDone: () -> Unit,
    setSaving: (Boolean) -> Unit,
) {
    if (customer == null || items.isEmpty()) return
    setSaving(true)
    scope.launch {
        try {
            val req = CreateOrderRequest(
                type = "sale", partyType = "customer", partyId = customer.id, status = status,
                orderedAt = java.time.Instant.ofEpochMilli(orderDateMs).toString(),
                warehouseId = warehouseId,
                shippingFee = shipCustomer.takeIf { it > 0 },
                actualShippingFee = shipCompany.takeIf { it > 0 },
                codCollected = cod.takeIf { it > 0 },
                items = items.map { CreateOrderItem(it.variantId, it.unitId, it.qty, it.price) },
                notes = notes.ifBlank { null },
                createdByUserId = userId,
            )
            // Có orderId → cập nhật đơn (PUT); không → tạo mới (POST).
            if (orderId != null) container.vapi.updateOrder(orderId, req) else container.vapi.createOrder(req)
            Toast.makeText(context, if (status == "draft") "Đã lưu nháp" else if (orderId != null) "Đã cập nhật" else "Đã tạo đơn", Toast.LENGTH_SHORT).show()
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
private fun Modifier.centerOnFocus(ctx: FocusCenterCtx, scope: kotlinx.coroutines.CoroutineScope, key: Any): Modifier {
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
