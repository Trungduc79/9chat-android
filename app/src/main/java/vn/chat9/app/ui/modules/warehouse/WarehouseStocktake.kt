package vn.chat9.app.ui.modules.warehouse

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.roundToInt
import vn.chat9.app.ui.explore.DPad
import vn.chat9.app.ui.explore.DpadDir
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.CategoryDto
import vn.chat9.app.data.vapi.dto.ProductSearchDto
import vn.chat9.app.data.vapi.dto.StocktakeItemReq
import vn.chat9.app.data.vapi.dto.StocktakeRequest
import vn.chat9.app.data.vapi.dto.VariantSearchDto
import vn.chat9.app.data.vapi.dto.WarehouseDto
import vn.chat9.app.ui.explore.AdminColors
import vn.chat9.app.ui.explore.AdminPullToRefresh

/**
 * Tab Kiểm kho — gốc lấy từ Sale "Sản phẩm" (list biến thể + tồn theo kho), bổ sung
 * ô nhập SỐ ĐẾM thực tế → so lệch với tồn hệ thống.
 *
 * Header: [icon search] [dropdown dòng SP] [dropdown SP] [dropdown kho]. Nhấn icon search
 * → input bung ra che 2 dropdown dòng SP + SP (giữ dropdown kho). Dòng SP/SP/search mặc
 * định null, pull-reload cũng reset null. Dòng SP cascade → lọc dropdown SP + lọc list.
 *
 * "Lưu kiểm kho" tạm disable: chờ BE bút toán điều chỉnh kho.
 */
@Composable
fun WarehouseStocktake(warehouseId: Long?, warehouseName: String?) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var productId by remember { mutableStateOf<Long?>(null) }
    var selectedWarehouseId by remember { mutableStateOf(warehouseId) }

    var variants by remember { mutableStateOf<List<VariantSearchDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf<List<CategoryDto>>(emptyList()) }
    var products by remember { mutableStateOf<List<ProductSearchDto>>(emptyList()) }
    val counts = remember { mutableStateMapOf<Long, String>() }   // variantId → text số đếm
    val listState = rememberLazyListState()
    var dpadX by remember { mutableStateOf(0f) }                  // dịch ngang nút D-pad
    var dpadY by remember { mutableStateOf(0f) }                  // dịch dọc nút D-pad (kéo lên)
    var focusedFilter by remember { mutableStateOf(-1) }          // D-pad focus: -1 none, 0 dòng SP, 1 SP
    var saving by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0      // ẩn D-pad khi bàn phím hiện

    suspend fun load() {
        if (selectedWarehouseId == null) return
        loading = true
        try {
            variants = (container.vapi.listAllVariants(
                search = query.ifBlank { null }, productId = productId, categoryId = categoryId,
                warehouseId = selectedWarehouseId, perPage = 50,
            ).data ?: emptyList()).sortedByDescending { variantStockInUnit(it) }   // tồn cao → thấp
        } catch (_: Exception) {}
        loading = false
    }

    // Focus ô đếm → cuộn dòng đó lên ~1/3 trên (vùng trên bàn phím). delay chờ IME slide + recompose.
    fun centerOnFocus(index: Int) {
        scope.launch {
            kotlinx.coroutines.delay(260)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@launch
            val target = info.viewportSize.height * 0.32f
            listState.animateScrollBy((item.offset + item.size / 2f) - target)
        }
    }

    // D-pad: trái/phải chuyển focus dropdown (lần đầu → dòng SP); lên/xuống đổi giá trị dropdown đang focus.
    fun moveFocus(delta: Int) { focusedFilter = if (focusedFilter < 0) 0 else (focusedFilter + delta).coerceIn(0, 1) }
    fun cycleValue(delta: Int) {
        when (focusedFilter) {
            0 -> { val ids = listOf<Long?>(null) + categories.map { it.id }; val i = ids.indexOf(categoryId).coerceAtLeast(0); categoryId = ids[(i + delta).coerceIn(0, ids.lastIndex)]; productId = null }
            1 -> { val ids = listOf<Long?>(null) + products.map { it.id }; val i = ids.indexOf(productId).coerceAtLeast(0); productId = ids[(i + delta).coerceIn(0, ids.lastIndex)] }
        }
    }

    // Lưu kiểm kho: gom các dòng đã đếm (theo đơn vị mặc định) → POST /v1/stocktake.
    fun saveStocktake() {
        val items = counts.mapNotNull { (vid, txt) ->
            val q = txt.toDoubleOrNull() ?: return@mapNotNull null
            val v = variants.firstOrNull { it.id == vid } ?: return@mapNotNull null
            val u = v.units.firstOrNull { it.isDefaultSale } ?: v.units.firstOrNull { it.isBase } ?: v.units.firstOrNull()
            StocktakeItemReq(variantId = vid, qty = q, unitId = u?.id)
        }
        if (items.isEmpty()) { Toast.makeText(context, "Chưa nhập số đếm nào", Toast.LENGTH_SHORT).show(); return }
        scope.launch {
            saving = true
            try {
                val res = container.vapi.submitStocktake(
                    StocktakeRequest(warehouseId = selectedWarehouseId, userId = container.tokenManager.user?.id?.toLong(), items = items),
                )
                Toast.makeText(context, "Đã lưu kiểm kho: ${res.data?.count ?: 0} mặt hàng", Toast.LENGTH_SHORT).show()
                counts.clear()
                load()
            } catch (e: Exception) {
                Toast.makeText(context, "Lưu thất bại: ${e.message}", Toast.LENGTH_LONG).show()
            }
            saving = false
        }
    }

    // Danh mục + kho (1 lần).
    LaunchedEffect(Unit) {
        try { categories = container.vapi.listCategories().data ?: emptyList() } catch (_: Exception) {}
    }
    val warehouses = remember { mutableStateListOf<WarehouseDto>() }
    LaunchedEffect(Unit) {
        try { warehouses.addAll(container.vapi.listWarehouses().data ?: emptyList()) } catch (_: Exception) {}
    }
    // Cascade: dropdown SP theo danh mục đang chọn; sort theo tồn của kho đang chọn (BE).
    LaunchedEffect(categoryId, selectedWarehouseId) {
        products = try { container.vapi.searchProducts(categoryId = categoryId, warehouseId = selectedWarehouseId, perPage = 100).data ?: emptyList() } catch (_: Exception) { emptyList() }
    }
    // Reload variant khi filter đổi.
    LaunchedEffect(categoryId, productId, selectedWarehouseId) { load() }
    LaunchedEffect(query) { delay(280); load() }

    val currentWh = warehouses.firstOrNull { it.id == selectedWarehouseId }
    val selectedCat = categories.firstOrNull { it.id == categoryId }
    val selectedProd = products.firstOrNull { it.id == productId }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { searchFocus.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
      val maxDpadX = with(density) { (constraints.maxWidth - 246.dp.toPx() - 32.dp.toPx()).coerceAtLeast(0f) }
      val minDpadY = -(constraints.maxHeight * 0.5f)   // kéo lên tối đa 50% màn hình
      Column(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        Row(
            Modifier.fillMaxWidth().background(AdminColors.Card).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                modifier = Modifier.size(36.dp),
            ) { Icon(Icons.Default.Search, "Tìm", tint = if (searchOpen) AdminColors.Primary else AdminColors.TextMuted) }

            // Vùng giữa: search input (khi mở) HOẶC 2 dropdown dòng SP + SP.
            Box(Modifier.weight(1f)) {
                if (searchOpen) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        BasicTextField(
                            value = query, onValueChange = { query = it },
                            textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                            cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                            decorationBox = { inner -> if (query.isEmpty()) Text("Tìm biến thể...", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterDropdown(
                            text = selectedCat?.name ?: "Dòng SP",
                            options = listOf<Pair<Long?, String>>(null to "Tất cả dòng") + categories.map { it.id to it.name },
                            selectedId = categoryId,
                            onSelect = { categoryId = it; productId = null },   // đổi dòng → reset SP
                            modifier = Modifier.weight(1f),
                            focused = focusedFilter == 0,
                        )
                        Spacer(Modifier.width(6.dp))
                        FilterDropdown(
                            text = selectedProd?.name ?: "Sản phẩm",
                            options = listOf<Pair<Long?, String>>(null to "Tất cả SP") + products.map { it.id to it.name },
                            selectedId = productId,
                            onSelect = { productId = it },
                            modifier = Modifier.weight(1f),
                            focused = focusedFilter == 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            // Dropdown kho (luôn hiện).
            FilterDropdown(
                text = currentWh?.name ?: "Kho",
                options = warehouses.map { it.id as Long? to it.name },
                selectedId = selectedWarehouseId,
                onSelect = { selectedWarehouseId = it },
                modifier = Modifier.weight(0.5f),
            )
        }

        AdminPullToRefresh(
            isRefreshing = loading,
            onRefresh = { query = ""; searchOpen = false; categoryId = null; productId = null; scope.launch { load() } },
            modifier = Modifier.weight(1f),
        ) {
            if (loading && variants.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
            else if (variants.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Không có biến thể", color = AdminColors.TextMuted) }
            else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(variants, key = { _, it -> it.id }) { idx, v ->
                    StocktakeRow(v, counts[v.id] ?: "", onCountedChange = { counts[v.id] = it }, onFocus = { centerOnFocus(idx) })
                }
            }
        }

        // Thanh dưới: số mặt hàng đã đếm + nút Lưu (tạm disable — chờ BE bút toán điều chỉnh).
        val countedN = counts.count { (_, t) -> t.isNotBlank() }
        Row(
            Modifier.fillMaxWidth().background(AdminColors.Card).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Đã đếm: $countedN mặt hàng", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            val canSave = countedN > 0 && !saving
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (canSave) AdminColors.Primary else AdminColors.Primary.copy(alpha = 0.3f))
                    .clickable(enabled = canSave) { saveStocktake() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(if (saving) "Đang lưu..." else "Lưu kiểm kho", color = if (canSave) AdminColors.White else AdminColors.White.copy(alpha = 0.5f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
      }
      // D-pad điều hướng — gần thanh Lưu; ẩn khi bàn phím hiện, hiện lại khi tắt.
      if (!imeVisible) DPad(
          onDirection = { dir ->
              when (dir) {
                  DpadDir.LEFT -> moveFocus(-1)
                  DpadDir.RIGHT -> moveFocus(1)
                  DpadDir.UP -> cycleValue(-1)
                  DpadDir.DOWN -> cycleValue(1)
              }
          },
          onDrag = { dx, dy ->
              dpadX = (dpadX + dx).coerceIn(0f, maxDpadX)
              dpadY = (dpadY + dy).coerceIn(minDpadY, 0f)
          },
          modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 64.dp).offset { IntOffset(dpadX.roundToInt(), dpadY.roundToInt()) },
      )
    }
}

/** Tồn theo đơn vị mặc định (= stock_base / hệ số quy đổi) — dùng để hiển thị + sắp xếp. */
private fun variantStockInUnit(v: VariantSearchDto): Double {
    val u = v.units.firstOrNull { it.isDefaultSale } ?: v.units.firstOrNull { it.isBase } ?: v.units.firstOrNull()
    val factor = u?.conversionFactor ?: 1.0
    val sb = v.stockBase ?: 0.0
    return if (factor > 0) sb / factor else sb
}

/** Dropdown lọc inline (dark). id=null = chọn "tất cả" (xoá lọc). */
@Composable
private fun FilterDropdown(
    text: String,
    options: List<Pair<Long?, String>>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val neon = Color(0xFF00E5FF)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (focused) Modifier.shadow(12.dp, shape, ambientColor = neon, spotColor = neon) else Modifier)
                .clip(shape)
                .background(AdminColors.Bg)
                .then(if (focused) Modifier.border(2.dp, neon, shape) else Modifier)
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text, color = if (selectedId != null) AdminColors.Primary else AdminColors.TextMuted,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(" ▾", color = AdminColors.TextMuted, fontSize = 11.sp)
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, color = if (id == selectedId) AdminColors.Primary else AdminColors.Text) },
                        onClick = { onSelect(id); open = false },
                        colors = MenuDefaults.itemColors(textColor = AdminColors.Text),
                    )
                }
            }
        }
    }
}

@Composable
private fun StocktakeRow(v: VariantSearchDto, counted: String, onCountedChange: (String) -> Unit, onFocus: () -> Unit) {
    val defUnit = v.units.firstOrNull { it.isDefaultSale } ?: v.units.firstOrNull { it.isBase } ?: v.units.firstOrNull()
    val stockInUnit = variantStockInUnit(v)
    val unit = defUnit?.name ?: ""
    val name = if (!v.name.isNullOrBlank()) v.name
        else v.attributes?.values?.filter { it.isNotBlank() }?.joinToString(", ") ?: (v.product?.name ?: "")

    val countedNum = counted.toDoubleOrNull()
    val checked = countedNum != null && countedNum == stockInUnit

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Khớp (đã tích) → tên xám, ảnh mờ, "Khớp" vẫn xanh nổi.
        val thumbAlpha = if (checked) 0.45f else 1f
        val img = v.image ?: v.product?.primaryImage?.url
        if (img != null) AsyncImage(model = img, contentDescription = null, modifier = Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)).alpha(thumbAlpha))
        else Box(Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)).alpha(thumbAlpha), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Inventory2, null, tint = AdminColors.TextMuted, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (checked) AdminColors.TextMuted else AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            // Tồn: {sl} {đv}  |  Lệch {sl} {đv} (hiện khi đã nhập đếm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tồn: ${trimZeros(stockInUnit)} $unit", color = AdminColors.TextMuted, fontSize = 12.sp)
                if (countedNum != null) {
                    val diff = countedNum - stockInUnit
                    if (diff == 0.0) Text("  |  Khớp", color = AdminColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    else {
                        val label = if (diff < 0) "Thiếu" else "Dư"   // đếm < tồn = thiếu, đếm > tồn = dư
                        Text("  |  $label ${trimZeros(abs(diff))} $unit", color = if (diff < 0) AdminColors.Danger else AdminColors.Info, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // Phải: checkbox (trên) + ô đếm (dưới), căn giữa với nhau.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.5.dp)) {
            Checkbox(
                checked = checked,
                onCheckedChange = { c -> onCountedChange(if (c) trimZeros(stockInUnit) else "") },   // tích → đếm = tồn
                colors = CheckboxDefaults.colors(checkedColor = AdminColors.Primary, uncheckedColor = AdminColors.TextMuted, checkmarkColor = AdminColors.White),
                modifier = Modifier.size(28.dp),
            )
            Box(
                Modifier.width(64.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Bg).padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = counted,
                    onValueChange = { raw -> onCountedChange(raw.filter { c -> c.isDigit() || c == '.' }) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(AdminColors.Primary),
                    decorationBox = { inner -> if (counted.isEmpty()) Text("Đếm", color = AdminColors.TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()); inner() },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
                )
            }
        }
    }
}
