package vn.chat9.app.ui.modules.sale

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
@Composable
fun SaleOrderForm(onDone: () -> Unit) {
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

    // Kho bán
    var warehouses by remember { mutableStateOf<List<WarehouseDto>>(emptyList()) }
    var selectedWarehouseId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        try {
            val ws = container.vapi.listWarehouses().data ?: emptyList()
            warehouses = ws
            selectedWarehouseId = ws.firstOrNull { it.isDefault }?.id ?: ws.firstOrNull()?.id
        } catch (_: Exception) {}
    }

    // SP hay mua của KH
    var suggested by remember { mutableStateOf<List<RecentProductDto>>(emptyList()) }
    LaunchedEffect(selectedCustomer?.id) {
        val c = selectedCustomer ?: return@LaunchedEffect
        suggested = try { container.vapi.recentProducts(c.id, 5).data ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    // Pickers
    var customerPickerOpen by remember { mutableStateOf(true) }   // KH bắt buộc → mở luôn
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

    Box(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            // ===== Card KH + Kho =====
            Card("Khách hàng & kho") {
                Row(Modifier.fillMaxWidth().clickable { customerPickerOpen = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (selectedCustomer == null) Text("Chưa chọn khách hàng", color = AdminColors.TextMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic)
                        else {
                            Text(selectedCustomer!!.name, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            selectedCustomer!!.phone?.let { Text(it, color = AdminColors.TextMuted, fontSize = 12.sp) }
                        }
                    }
                    Text(if (selectedCustomer == null) "Chọn KH" else "Đổi KH", color = AdminColors.Primary, fontSize = 12.sp)
                }
                HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kho bán", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                    WarehouseDropdown(warehouses, selectedWarehouseId) { selectedWarehouseId = it }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== Card Items =====
            Card("Mặt hàng (${items.size})") {
                // Chip SP hay mua
                if (suggested.isNotEmpty()) {
                    LazyRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(suggested, key = { it.productId }) { p ->
                            Row(
                                Modifier.clip(RoundedCornerShape(16.dp)).background(AdminColors.Primary.copy(alpha = 0.1f))
                                    .clickable {
                                        pickerInitQuery = p.productName; pickerProductId = p.productId; productPickerOpen = true
                                    }.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("+ ${p.productName}", color = AdminColors.Primary, fontSize = 12.sp, maxLines = 1)
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
                // Footer: Thêm SP (trái) + Tổng (phải)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        if (selectedCustomer == null) Toast.makeText(context, "Chọn khách hàng trước", Toast.LENGTH_SHORT).show()
                        else { pickerInitQuery = ""; pickerProductId = null; productPickerOpen = true }
                    }) { Text("+ Thêm SP", color = AdminColors.Primary, fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    val total = items.sumOf { it.qty * it.price }
                    Text("Tổng ", color = AdminColors.TextMuted, fontSize = 13.sp)
                    Text(fmtMoney(total), color = AdminColors.Primary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(" đ", color = Color(0xFF999900), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== Card phí ship + COD =====
            Card("Phí ship & thu hộ") {
                ShipRow("Phí ship KH", shipCustomer) { shipCustomer = it }
                ShipRow("Phí ship KHO", shipCompany) { shipCompany = it }
                ShipRow("Thu hộ", codAmount) { codAmount = it }
            }

            Spacer(Modifier.height(12.dp))

            // ===== Card Ghi chú =====
            Card("Ghi chú") {
                BasicTextField(
                    value = notes, onValueChange = { notes = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            if (notes.isEmpty()) Text("Ghi chú đơn (tùy chọn)", color = AdminColors.TextMuted, fontSize = 13.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ===== 2 nút =====
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { submit(scope, container, userId, selectedCustomer, selectedWarehouseId, items, notes, parseMoney(shipCustomer), parseMoney(shipCompany), parseMoney(codAmount), "draft", context, onDone) { saving = it } },
                    enabled = !saving && selectedCustomer != null && items.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Lưu nháp") }
                Button(
                    onClick = { submit(scope, container, userId, selectedCustomer, selectedWarehouseId, items, notes, parseMoney(shipCustomer), parseMoney(shipCompany), parseMoney(codAmount), "confirmed", context, onDone) { saving = it } },
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
            onPick = { v -> addVariant(v); productPickerOpen = false },
            onClose = { productPickerOpen = false },
        )
    }
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
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(12.dp)) {
        Text(title, color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** Dropdown chọn kho — dark mode. */
@Composable
private fun WarehouseDropdown(warehouses: List<WarehouseDto>, selectedId: Long?, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = warehouses.firstOrNull { it.id == selectedId }
    Box {
        Row(Modifier.clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(current?.name ?: "Chọn kho", color = AdminColors.Text, fontSize = 14.sp)
            Text(" ▾", color = AdminColors.TextMuted, fontSize = 12.sp)
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
    onDelete: () -> Unit,
    onQtyChange: (Double) -> Unit,
    onPriceChange: (Double) -> Unit,
    onUnitChange: (VariantUnitDto) -> Unit,
) {
    // Swipe trái > 1/3 width → xoá.
    var offsetX by remember(draft.variantId) { mutableStateOf(0f) }
    var rowWidth by remember { mutableStateOf(1f) }
    Box(
        Modifier.fillMaxWidth()
            .onSizeChangedWidth { rowWidth = it.toFloat() }
            .pointerInput(draft.variantId) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (-offsetX > rowWidth / 3f) onDelete() else offsetX = 0f },
                ) { _, dragAmount -> offsetX = (offsetX + dragAmount).coerceAtMost(0f) }
            },
    ) {
        // nền đỏ delete
        Box(Modifier.matchParentSize().background(AdminColors.Danger.copy(alpha = 0.25f)), contentAlignment = Alignment.CenterEnd) {
            Text("Xoá", color = AdminColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(end = 16.dp))
        }
        Row(
            Modifier.fillMaxWidth().offsetX(offsetX).background(AdminColors.Card).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (draft.imageUrl != null) AsyncImage(model = draft.imageUrl, contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
            else Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(draft.variantName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // qty
                    var qtyText by remember(draft.variantId) { mutableStateOf(trimZeros(draft.qty)) }
                    BasicTextField(
                        value = qtyText,
                        onValueChange = { raw -> val f = raw.filter { c -> c.isDigit() || c == '.' }; qtyText = f; onQtyChange(f.toDoubleOrNull() ?: 0.0) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(AdminColors.Primary),
                        modifier = Modifier.width(40.dp),
                    )
                    // unit dropdown
                    UnitDropdown(draft.units, draft.unitId, onUnitChange)
                    Text(" × ", color = AdminColors.TextMuted, fontSize = 12.sp)
                    // price
                    var priceText by remember(draft.variantId) { mutableStateOf(fmtMoney(draft.price)) }
                    BasicTextField(
                        value = priceText,
                        onValueChange = { raw -> val v = parseMoney(raw); priceText = if (v > 0) fmtMoney(v) else ""; onPriceChange(v) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(AdminColors.Primary),
                        modifier = Modifier.weight(1f),
                    )
                    Text(" = ", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Text(fmtMoney(draft.qty * draft.price), color = AdminColors.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(" đ", color = Color(0xFF999900), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun UnitDropdown(units: List<VariantUnitDto>, selectedId: Long, onSelect: (VariantUnitDto) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = units.firstOrNull { it.id == selectedId }
    Box {
        Text(
            cur?.name ?: "—",
            color = AdminColors.TextMuted, fontSize = 12.sp, fontStyle = FontStyle.Italic,
            modifier = Modifier.clickable { open = true }.padding(horizontal = 4.dp),
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
private fun ShipRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(0.42f))
        Text(":", color = AdminColors.TextMuted, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(0.58f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = { raw -> val v = parseMoney(raw); onChange(if (v > 0) fmtMoney(v) else "") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(AdminColors.Primary),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.CenterEnd) {
                            if (value.isEmpty()) Text("0", color = AdminColors.TextMuted, fontSize = 13.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
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
    onPick: (VariantSearchDto) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container

    var query by remember { mutableStateOf(initQuery) }
    var results by remember { mutableStateOf<List<VariantSearchDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query, productId) {
        if (query.length < 2 && productId == null) { results = emptyList(); return@LaunchedEffect }
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
        if (query.length < 2 && productId == null) Text("Gõ ≥2 ký tự để tìm", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        else if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
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
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(AdminColors.Card).padding(16.dp)
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
    userId: Long?,
    customer: CustomerDto?,
    warehouseId: Long?,
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
                warehouseId = warehouseId,
                shippingFee = shipCustomer.takeIf { it > 0 },
                actualShippingFee = shipCompany.takeIf { it > 0 },
                codCollected = cod.takeIf { it > 0 },
                items = items.map { CreateOrderItem(it.variantId, it.unitId, it.qty, it.price) },
                notes = notes.ifBlank { null },
                createdByUserId = userId,
            )
            container.vapi.createOrder(req)
            Toast.makeText(context, if (status == "draft") "Đã lưu nháp" else "Đã tạo đơn", Toast.LENGTH_SHORT).show()
            onDone()
        } catch (e: Exception) {
            Toast.makeText(context, "Tạo đơn thất bại: ${e.message}", Toast.LENGTH_LONG).show()
        } finally { setSaving(false) }
    }
}

// Helper modifiers
private fun Modifier.offsetX(x: Float): Modifier = this.then(
    androidx.compose.ui.layout.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.placeRelative(x.toInt(), 0) }
    },
)
private fun Modifier.onSizeChangedWidth(cb: (Int) -> Unit): Modifier = this.then(
    androidx.compose.ui.layout.onSizeChanged { cb(it.width) },
)
