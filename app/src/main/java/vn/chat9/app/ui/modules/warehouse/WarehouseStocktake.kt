package vn.chat9.app.ui.modules.warehouse

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import com.google.gson.Gson
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.CategoryDto
import vn.chat9.app.data.vapi.dto.ProductSearchDto
import vn.chat9.app.data.vapi.dto.StocktakeItemReq
import vn.chat9.app.data.vapi.dto.StocktakeReportDto
import vn.chat9.app.data.vapi.dto.StocktakeRequest
import vn.chat9.app.data.vapi.dto.StocktakeResolveReq
import vn.chat9.app.data.vapi.dto.StocktakeSessionDto
import vn.chat9.app.data.vapi.dto.StocktakeSessionLineDto
import vn.chat9.app.data.vapi.dto.StocktakeSwapDto
import vn.chat9.app.data.vapi.dto.StocktakeSwapReq
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
fun WarehouseStocktake(
    warehouseId: Long?,
    warehouseName: String?,
    dpadVisible: Boolean = true,
    onHideDpad: () -> Unit = {},
) {
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

    // Cache số đếm/tích kiểm theo kho — giữ tới khi LƯU kiểm kho hoặc quá 24h (giống web).
    val gson = remember { Gson() }
    val prefs = remember(context) { context.getSharedPreferences("stocktake_cache", Context.MODE_PRIVATE) }
    fun loadCounts(whId: Long?) {
        counts.clear()
        val raw = prefs.getString(stocktakeKey(whId), null) ?: return
        val entry = runCatching { gson.fromJson(raw, StocktakeCacheEntry::class.java) }.getOrNull()
        if (entry != null && System.currentTimeMillis() - entry.ts < STOCKTAKE_TTL) {
            counts.putAll(entry.counts)
        } else {
            prefs.edit().remove(stocktakeKey(whId)).apply() // quá hạn → dọn
        }
    }
    fun persistCounts(whId: Long?) {
        if (counts.isEmpty()) prefs.edit().remove(stocktakeKey(whId)).apply()
        else prefs.edit().putString(stocktakeKey(whId), gson.toJson(StocktakeCacheEntry(System.currentTimeMillis(), counts.toMap()))).apply()
    }
    val listState = rememberLazyListState()
    var dpadX by remember { mutableStateOf(0f) }                  // dịch ngang nút D-pad
    var dpadY by remember { mutableStateOf(0f) }                  // dịch dọc nút D-pad (kéo lên)
    var focusedFilter by remember { mutableStateOf(-1) }          // D-pad focus: -1 none, 0 dòng SP, 1 SP
    var saving by remember { mutableStateOf(false) }
    var historyVariant by remember { mutableStateOf<VariantSearchDto?>(null) }   // dialog lịch sử
    // Báo cáo kiểm kho hôm nay (sai lệch đã LƯU).
    var reportOpen by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<StocktakeReportDto?>(null) }
    var reportLoading by remember { mutableStateOf(false) }
    // Phiên xử lý sai lệch (pending → resolve manual → chốt).
    var sessionOpen by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<StocktakeSessionDto?>(null) }
    var sessionBusy by remember { mutableStateOf(false) }
    var reasonLine by remember { mutableStateOf<StocktakeSessionLineDto?>(null) }   // dialog nhập lý do
    var reasonMode by remember { mutableStateOf("manual") }                         // "manual" | "writeoff"
    var buLine by remember { mutableStateOf<StocktakeSessionLineDto?>(null) }       // dialog đơn bù
    var buMode by remember { mutableStateOf("order") }                              // "order" (KH) | "purchase" (NCC)
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0      // ẩn D-pad khi bàn phím hiện

    suspend fun load() {
        if (selectedWarehouseId == null) return
        loading = true
        try {
            variants = (container.vapi.listAllVariants(
                search = query.ifBlank { null }, productId = productId, categoryId = categoryId,
                warehouseId = selectedWarehouseId, perPage = 50,
            ).data ?: emptyList()).let { arrangeVariants(it, query.isNotBlank()) }   // nhóm theo SP, ẩn SL=0 (trừ khi search)
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
                val s = container.vapi.openStocktakeSession(
                    StocktakeRequest(warehouseId = selectedWarehouseId, userId = container.tokenManager.user?.id?.toLong(), items = items),
                ).data
                counts.clear()
                persistCounts(selectedWarehouseId) // đã vào phiên → xoá cache đếm
                when {
                    s == null -> Toast.makeText(context, "Lưu thất bại", Toast.LENGTH_LONG).show()
                    s.status == "closed" -> {   // khớp hết → tự chốt
                        Toast.makeText(context, "Đã lưu kiểm kho: khớp hết", Toast.LENGTH_SHORT).show(); load()
                    }
                    else -> {                    // có lệch → mở màn xử lý (tồn giữ nguyên, biến thể khoá xuất)
                        session = s; sessionOpen = true
                        Toast.makeText(context, "${s.summary.pending} mặt hàng sai lệch cần xử lý", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Lưu thất bại: ${e.message}", Toast.LENGTH_LONG).show()
            }
            saving = false
        }
    }

    // Xử lý sai lệch: resolve manual / bỏ / chốt / huỷ phiên.
    fun resolveLine(line: StocktakeSessionLineDto, type: String, reason: String) {
        val s = session ?: return
        scope.launch {
            sessionBusy = true
            try {
                session = container.vapi.resolveStocktakeLine(
                    s.id, line.id,
                    StocktakeResolveReq(type = type, reason = reason.ifBlank { null }, userId = container.tokenManager.user?.id?.toLong()),
                ).data
                reasonLine = null
            } catch (e: Exception) { Toast.makeText(context, "Xử lý thất bại: ${e.message}", Toast.LENGTH_SHORT).show() }
            sessionBusy = false
        }
    }
    fun undoResolve(line: StocktakeSessionLineDto) {
        val s = session ?: return
        scope.launch {
            sessionBusy = true
            try { session = container.vapi.unresolveStocktakeLine(s.id, line.id).data }
            catch (e: Exception) { Toast.makeText(context, "Bỏ xử lý thất bại: ${e.message}", Toast.LENGTH_SHORT).show() }
            sessionBusy = false
        }
    }
    fun applySwap(swap: StocktakeSwapDto) {
        val s = session ?: return
        scope.launch {
            sessionBusy = true
            try {
                session = container.vapi.swapStocktakeSession(
                    s.id, StocktakeSwapReq(shortageVariantId = swap.shortageVariantId, surplusVariantId = swap.surplusVariantId, qtyBase = swap.qtyBase),
                ).data
                Toast.makeText(context, "Đã ghép chuyển đổi biến thể", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, "Chuyển đổi thất bại: ${e.message}", Toast.LENGTH_SHORT).show() }
            sessionBusy = false
        }
    }
    fun closeSession() {
        val s = session ?: return
        scope.launch {
            sessionBusy = true
            try {
                container.vapi.closeStocktakeSession(s.id)
                Toast.makeText(context, "Đã chốt phiên — tồn đã cập nhật, gỡ khoá xuất", Toast.LENGTH_SHORT).show()
                session = null; sessionOpen = false; load()
            } catch (e: Exception) { Toast.makeText(context, "Chốt thất bại: ${e.message}", Toast.LENGTH_LONG).show() }
            sessionBusy = false
        }
    }
    /**
     * Đếm lại tồn hệ thống cho các dòng chưa xử lý.
     *
     * Dùng sau khi đã sửa các đơn bán/nhập sai (tồn đổi mà KHÔNG qua fulfill):
     * biến thể nào tồn đã khớp số đếm tay thì hết lệch và tự rời danh sách.
     */
    fun recountSession() {
        val s = session ?: return
        scope.launch {
            sessionBusy = true
            try {
                val fresh = container.vapi.recountStocktakeSession(s.id).data
                if (fresh != null) session = fresh
                val r = fresh?.recount
                val msg = when {
                    r == null -> "Đã đếm lại"
                    r.cleared > 0 -> "Hết lệch ${r.cleared} biến thể"
                    r.updated > 0 -> "Cập nhật số ${r.updated} biến thể — vẫn còn lệch"
                    else -> "Đã đếm lại — tồn không đổi"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Đếm lại thất bại: ${e.message}", Toast.LENGTH_LONG).show()
            }
            sessionBusy = false
        }
    }

    fun discardSession() {
        val s = session ?: return
        scope.launch {
            sessionBusy = true
            try {
                container.vapi.discardStocktakeSession(s.id)
                Toast.makeText(context, "Đã huỷ phiên — gỡ khoá xuất, tồn giữ nguyên", Toast.LENGTH_SHORT).show()
                session = null; sessionOpen = false
            } catch (e: Exception) { Toast.makeText(context, "Huỷ thất bại: ${e.message}", Toast.LENGTH_LONG).show() }
            sessionBusy = false
        }
    }

    // Nhấn tên/ảnh biến thể trong dialog xử lý → mở lịch sử xuất nhập biến thể đó.
    // Ưu tiên biến thể đã nạp ở list kiểm kho; không có (đã lọc SL=0) → fetch theo product_id.
    fun openLineHistory(l: StocktakeSessionLineDto) {
        variants.firstOrNull { it.id == l.variantId }?.let { historyVariant = it; return }
        scope.launch {
            val fetched = runCatching {
                container.vapi.listAllVariants(productId = l.productId, warehouseId = selectedWarehouseId, perPage = 100).data
            }.getOrNull()?.firstOrNull { it.id == l.variantId }
            if (fetched != null) historyVariant = fetched
            else Toast.makeText(context, "Không có lịch sử biến thể", Toast.LENGTH_SHORT).show()
        }
    }

    // Xem báo cáo kiểm kho hôm nay (sai lệch đã LƯU của kho này).
    fun openReport() {
        reportOpen = true
        reportLoading = true
        scope.launch {
            report = try {
                container.vapi.stocktakeReport(selectedWarehouseId).data
            } catch (e: Exception) {
                Toast.makeText(context, "Không tải được báo cáo: ${e.message}", Toast.LENGTH_SHORT).show()
                null
            }
            reportLoading = false
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
    // Khôi phục cache số đếm khi mở / đổi kho.
    LaunchedEffect(selectedWarehouseId) { loadCounts(selectedWarehouseId) }
    // Phiên kiểm kê đang MỞ của kho (cho tiếp tục xử lý dở).
    LaunchedEffect(selectedWarehouseId) {
        session = try { container.vapi.openStocktakeSessionForWarehouse(selectedWarehouseId).data } catch (_: Exception) { null }
    }

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

        // Banner: có phiên kiểm kê chưa chốt (biến thể lệch đang khoá xuất) → tiếp tục.
        session?.let { s ->
            if (!sessionOpen && s.status == "open") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp)).background(AdminColors.Warning.copy(alpha = 0.12f))
                        .border(1.dp, AdminColors.Warning.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable { sessionOpen = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, null, tint = AdminColors.Warning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Phiên kiểm kê chưa chốt · ${s.summary.pending} sai lệch chờ xử lý (biến thể lệch đang khoá xuất)",
                        color = AdminColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Tiếp tục", color = AdminColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
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
                    // Hết variant 1 SP → gạch ngang phân định (dài 50% căn giữa, trắng 50%, 1px).
                    if (idx > 0 && variants[idx - 1].product?.id != v.product?.id) {
                        Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxWidth(0.5f).height(1.dp).background(AdminColors.White.copy(alpha = 0.5f)))
                        }
                    }
                    StocktakeRow(v, counts[v.id] ?: "", onCountedChange = { counts[v.id] = it; persistCounts(selectedWarehouseId) }, onFocus = { centerOnFocus(idx) }, onOpenHistory = { historyVariant = v })
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
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .border(1.dp, AdminColors.Primary, RoundedCornerShape(8.dp))
                    .clickable { openReport() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Xem báo cáo", color = AdminColors.Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(8.dp))
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
      // D-pad điều hướng — gần thanh Lưu; ẩn khi bàn phím hiện hoặc khi tắt qua menu 3 chấm / dấu X.
      if (dpadVisible && !imeVisible) Box(
          Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 64.dp).offset { IntOffset(dpadX.roundToInt(), dpadY.roundToInt()) },
      ) {
          DPad(
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
          )
          // Dấu X đỏ góc phải-trên vòng D-pad → ẩn nhanh.
          Box(
              Modifier.align(Alignment.TopEnd).offset(x = (-28).dp, y = 28.dp)
                  .size(30.dp).clip(RoundedCornerShape(50)).background(AdminColors.Danger)
                  .clickable { onHideDpad() },
              contentAlignment = Alignment.Center,
          ) {
              Icon(Icons.Default.Close, contentDescription = "Ẩn D-pad", tint = AdminColors.White, modifier = Modifier.size(18.dp))
          }
      }
      // Dialog báo cáo kiểm kho hôm nay (dưới); click dòng → mở lịch sử (vẽ đè lên).
      if (reportOpen) StocktakeReportDialog(
          report, reportLoading,
          onOpenHistory = { historyVariant = it },
          onDismiss = { reportOpen = false },
      )
      // Dialog xử lý sai lệch kiểm kê (pending → resolve manual → chốt).
      if (sessionOpen) StocktakeResolveDialog(
          session = session, busy = sessionBusy,
          onResolveClick = { reasonMode = "manual"; reasonLine = it },
          onWriteoffClick = { reasonMode = "writeoff"; reasonLine = it },
          onBuClick = { line, mode -> buMode = mode; buLine = line },
          onSwap = { applySwap(it) },
          onUndo = { undoResolve(it) },
          onOpenHistory = { openLineHistory(it) },
          onClose = { closeSession() },
          onDiscard = { discardSession() },
          onRecount = { recountSession() },
          onDismiss = { sessionOpen = false },
      )
      // Dialog nhập lý do (manual / writeoff) — vẽ TRÊN dialog xử lý.
      reasonLine?.let { rl ->
          StocktakeReasonDialog(
              line = rl, busy = sessionBusy, mode = reasonMode,
              suggestedReason = buildStocktakeHints(session)[rl.variantId]?.reason,
              onConfirm = { reason -> resolveLine(rl, reasonMode, reason) },
              onDismiss = { reasonLine = null },
          )
      }
      // Màn tạo đơn bù (order = KH / purchase = NCC) — full-screen, lấy đúng UI màn giao đơn.
      // Vẽ TRÊN dialog xử lý (opaque, phủ hết); Hủy/Xong → về lại dialog xử lý.
      val curSession = session
      val bl = buLine
      if (bl != null && curSession != null) {
          WarehouseBuOrderScreen(
              session = curSession,
              mode = if (buMode == "order") "order" else "purchase",
              warehouseName = warehouseName,
              initialLineId = bl.id,   // dòng đã bấm → điền SL, dòng khác = 0
              onCancel = { buLine = null },
              onDone = { updated ->
                  buLine = null
                  if (updated != null) session = updated
                  else scope.launch { runCatching { container.vapi.getStocktakeSession(curSession.id).data }.getOrNull()?.let { session = it } }
              },
          )
      }
      // Dialog lịch sử biến thể (click ảnh/tên hoặc dòng báo cáo) — vẽ TRÊN CÙNG.
      historyVariant?.let { hv ->
          VariantHistoryDialog(variant = hv, onDismiss = { historyVariant = null })
      }
    }
}

/** Danh sách sai lệch kiểm kho hôm nay (đã LƯU, dedupe lần đếm cuối, bỏ khớp). */
@Composable
private fun StocktakeReportDialog(
    report: StocktakeReportDto?,
    loading: Boolean,
    onOpenHistory: (VariantSearchDto) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.95f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          // Nút đóng NGOÀI dialog, phía trên bên phải
          Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
              Box(
                  Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(50))
                      .background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onDismiss).padding(6.dp),
                  contentAlignment = Alignment.Center,
              ) {
                  Icon(Icons.Default.Close, contentDescription = "Đóng", tint = AdminColors.Text, modifier = Modifier.size(20.dp))
              }
          }
          Column(
            Modifier.fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}.padding(horizontal = 4.dp, vertical = 10.dp),
          ) {
            Text(
                "Báo cáo kiểm kho hôm nay", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AdminColors.Primary)
                }
                report == null -> Text("Không tải được báo cáo", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                else -> {
                    Text(
                        "${report.date} · ${report.summary.discrepancies} sai lệch / ${report.summary.counted} mặt hàng đã đếm",
                        color = AdminColors.TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                    )
                    if (report.items.isEmpty()) {
                        Text("Không có sai lệch trong ngày", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(report.items, key = { _, it -> it.variantId }) { i, it ->
                                Column(Modifier.fillMaxWidth()) {
                                    // Kẻ ngang ngăn cách giữa các sản phẩm (gom theo SP giống màn kiểm kho)
                                    if (i > 0 && report.items[i - 1].productId != it.productId) {
                                        Box(
                                            Modifier.fillMaxWidth(0.5f).align(Alignment.CenterHorizontally)
                                                .padding(vertical = 3.dp).height(1.dp).background(AdminColors.Warning),
                                        )
                                        // Gap dưới kẻ ngang bằng gap trên (spacedBy 4 + padding 3)
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(0.97f).align(Alignment.CenterHorizontally).heightIn(min = 62.dp)
                                            .clip(RoundedCornerShape(10.dp)).background(AdminColors.Bg)
                                            .border(1.dp, AdminColors.Border, RoundedCornerShape(10.dp))
                                            .clickable(enabled = it.variant != null) { it.variant?.let(onOpenHistory) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Thumb trái (~95% chiều cao dòng chuẩn)
                                        Box(
                                            Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Card)
                                                .border(1.dp, AdminColors.Border, RoundedCornerShape(6.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (!it.imageUrl.isNullOrBlank()) {
                                                AsyncImage(model = it.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                                            } else {
                                                Icon(Icons.Default.Inventory2, null, tint = AdminColors.TextMuted, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(it.variantName, color = AdminColors.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    "Tồn HT ${trimZeros(it.systemQty)} → Đếm ${trimZeros(it.countedQty)} ${it.unit}",
                                                    color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                                                )
                                                val neg = it.diff < 0
                                                Text(
                                                    if (neg) "Thiếu ${trimZeros(abs(it.diff))} ${it.unit}" else "Dư ${trimZeros(it.diff)} ${it.unit}",
                                                    color = if (neg) AdminColors.Danger else AdminColors.Info, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
          }
        }
    }
}

/** Gợi ý chẩn đoán (P1) theo biến thể: text hiển thị + lý do gợi ý (pre-fill). */
private data class StkHint(val text: String, val reason: String)
private fun buildStocktakeHints(session: StocktakeSessionDto?): Map<Long, StkHint> {
    val map = HashMap<Long, StkHint>()
    for (p in session?.analysis ?: emptyList()) {
        for (s in p.swaps) {
            map[s.shortageVariantId] = StkHint(
                "Nghi nhầm biến thể: chuyển đổi với \"${s.surplusName}\" (thừa ${trimZeros(s.qty)} ${s.unit})",
                "Nghi giao/nhận nhầm biến thể với \"${s.surplusName}\"",
            )
            map[s.surplusVariantId] = StkHint(
                "Nghi nhầm biến thể: chuyển đổi với \"${s.shortageName}\" (thiếu ${trimZeros(s.qty)} ${s.unit})",
                "Nghi giao/nhận nhầm biến thể với \"${s.shortageName}\"",
            )
        }
        for (r in p.residuals) {   // residual ghi đè (phần chưa giải thích bằng swap)
            if (r.kind == "shortage") {
                val cand = r.candidates.joinToString(", ") { it.name }
                map[r.variantId] = StkHint(
                    "Thiếu lẻ — nghi quên đơn bán / thất thoát" + if (cand.isNotBlank()) " · KH gần mua: $cand" else "",
                    "Nghi thiếu đơn bán / thất thoát",
                )
            } else {
                map[r.variantId] = StkHint("Thừa lẻ — nghi quên đơn nhập / hoàn hàng", "Nghi thừa — quên đơn nhập / hoàn hàng")
            }
        }
    }
    return map
}

/** Màn xử lý sai lệch kiểm kê: gom SP, resolve manual (điều chỉnh có lý do), chốt/huỷ phiên. */
@Composable
private fun StocktakeResolveDialog(
    session: StocktakeSessionDto?,
    busy: Boolean,
    onResolveClick: (StocktakeSessionLineDto) -> Unit,
    onWriteoffClick: (StocktakeSessionLineDto) -> Unit,
    onBuClick: (StocktakeSessionLineDto, String) -> Unit,
    onSwap: (StocktakeSwapDto) -> Unit,
    onUndo: (StocktakeSessionLineDto) -> Unit,
    onOpenHistory: (StocktakeSessionLineDto) -> Unit,
    onClose: () -> Unit,
    onDiscard: () -> Unit,
    onRecount: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lines = session?.lines?.filter { it.status != "matched" } ?: emptyList()
    val pending = session?.summary?.pending ?: 0
    val hints = buildStocktakeHints(session)
    // Swap 1 chạm: cặp đối xứng tin cậy CAO → map biến thể → swap.
    val swaps = HashMap<Long, StocktakeSwapDto>()
    for (p in session?.analysis ?: emptyList()) for (s in p.swaps) if (s.confidence == "high") {
        swaps[s.shortageVariantId] = s; swaps[s.surplusVariantId] = s
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth(0.95f), horizontalAlignment = Alignment.CenterHorizontally) {
            // Nút đóng ngoài dialog, trên bên phải.
            Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Box(
                    Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onDismiss).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Đóng", tint = AdminColors.Text, modifier = Modifier.size(20.dp)) }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(16.dp))
                    .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .clickable(enabled = false) {}.padding(horizontal = 4.dp, vertical = 10.dp),
            ) {
                Text(
                    "Xử lý sai lệch kiểm kê", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                if (session != null) {
                    Text(
                        "$pending chờ xử lý · ${session.summary.resolved} đã xử lý · ${session.summary.matched} khớp · biến thể lệch đang khoá xuất",
                        color = AdminColors.TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                    )
                    if (lines.isEmpty()) {
                        Text("Không có sai lệch", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(lines, key = { _, it -> it.id }) { i, l ->
                                Column(Modifier.fillMaxWidth()) {
                                    if (i > 0 && lines[i - 1].productId != l.productId) {
                                        Box(
                                            Modifier.fillMaxWidth(0.5f).align(Alignment.CenterHorizontally)
                                                .padding(vertical = 3.dp).height(1.dp).background(AdminColors.Warning),
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(0.97f).align(Alignment.CenterHorizontally).heightIn(min = 58.dp)
                                            .clip(RoundedCornerShape(10.dp)).background(AdminColors.Bg)
                                            .border(1.dp, AdminColors.Border, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Card)
                                                .border(1.dp, AdminColors.Border, RoundedCornerShape(6.dp))
                                                .clickable { onOpenHistory(l) },   // ảnh → lịch sử xuất nhập biến thể
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (!l.imageUrl.isNullOrBlank()) AsyncImage(model = l.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                                            else Icon(Icons.Default.Inventory2, null, tint = AdminColors.TextMuted, modifier = Modifier.size(18.dp))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            // Tên biến thể → mở lịch sử xuất nhập biến thể (giống list kiểm kho).
                                            Text(l.variantName, color = AdminColors.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onOpenHistory(l) })
                                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    "Tồn HT ${trimZeros(l.systemQty)} → Đếm ${trimZeros(l.countedQty)} ${l.unit}",
                                                    color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                                                )
                                                val neg = l.diff < 0
                                                Text(
                                                    if (neg) "Thiếu ${trimZeros(abs(l.diff))} ${l.unit}" else "Dư ${trimZeros(l.diff)} ${l.unit}",
                                                    color = if (neg) AdminColors.Danger else AdminColors.Info, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                                )
                                            }
                                            if (l.status == "resolved" && !(l.resolutionMeta?.get("reason") as? String).isNullOrBlank()) {
                                                Text("Lý do: ${l.resolutionMeta?.get("reason")}", color = AdminColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                            } else if (l.status != "resolved") {
                                                hints[l.variantId]?.let { h ->
                                                    Text("💡 ${h.text}", color = AdminColors.Warning, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                                                }
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        if (l.status == "resolved") {
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("Đã xử lý", color = AdminColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                                Text("Bỏ", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.clickable(enabled = !busy) { onUndo(l) }.padding(top = 2.dp))
                                            }
                                        } else {
                                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                swaps[l.variantId]?.let { sw ->
                                                    Box(
                                                        Modifier.clip(RoundedCornerShape(8.dp)).background(AdminColors.Info.copy(alpha = 0.15f))
                                                            .border(1.dp, AdminColors.Info, RoundedCornerShape(8.dp))
                                                            .clickable(enabled = !busy) { onSwap(sw) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    ) { Text("Chuyển đổi", color = AdminColors.Info, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                                                }
                                                // Đơn bán bù: dòng THIẾU (nghi quên xuất đơn KH).
                                                if (l.diff < 0) Box(
                                                    Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, AdminColors.Success.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                                        .clickable(enabled = !busy) { onBuClick(l, "order") }.padding(horizontal = 10.dp, vertical = 6.dp),
                                                ) { Text("Đơn bù", color = AdminColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                                                // Nhập bù: dòng THỪA (nghi quên nhập / hoàn hàng).
                                                if (l.diff > 0) Box(
                                                    Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, AdminColors.Info.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                                        .clickable(enabled = !busy) { onBuClick(l, "purchase") }.padding(horizontal = 10.dp, vertical = 6.dp),
                                                ) { Text("Nhập bù", color = AdminColors.Info, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                                                Box(
                                                    Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, AdminColors.Primary, RoundedCornerShape(8.dp))
                                                        .clickable(enabled = !busy) { onResolveClick(l) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                                ) { Text("Xử lý", color = AdminColors.Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                                                // Thất thoát: chỉ dòng THIẾU (ghi lỗ giá vốn khi chốt).
                                                if (l.diff < 0) Box(
                                                    Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, AdminColors.Danger.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                                        .clickable(enabled = !busy) { onWriteoffClick(l) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                                ) { Text("Thất thoát", color = AdminColors.Danger, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Footer: huỷ phiên / chốt phiên.
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, AdminColors.Danger.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .clickable(enabled = !busy) { onDiscard() }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) { Text("Huỷ phiên", color = AdminColors.Danger, fontSize = 13.sp) }
                        Spacer(Modifier.width(8.dp))
                        // Đếm lại: sau khi sửa đơn sai, tồn đã đổi → đối chiếu lại,
                        // biến thể nào khớp rồi thì rời danh sách xử lý sai lệch.
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .border(1.dp, AdminColors.Border, RoundedCornerShape(8.dp))
                                .clickable(enabled = !busy) { onRecount() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "Đếm lại",
                                color = if (busy) AdminColors.TextMuted.copy(alpha = 0.5f) else AdminColors.TextMuted,
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        val canClose = pending == 0 && !busy
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (canClose) AdminColors.Primary else AdminColors.Primary.copy(alpha = 0.3f))
                                .clickable(enabled = canClose) { onClose() }.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                if (pending > 0) "Còn $pending chưa xử lý" else "Chốt phiên",
                                color = if (canClose) AdminColors.White else AdminColors.White.copy(alpha = 0.5f),
                                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Dialog nhập lý do điều chỉnh (manual resolve). */
@Composable
private fun StocktakeReasonDialog(
    line: StocktakeSessionLineDto,
    busy: Boolean,
    mode: String = "manual",
    suggestedReason: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Pre-fill: lý do đã ghi > gợi ý engine > rỗng.
    var reason by remember { mutableStateOf((line.resolutionMeta?.get("reason") as? String) ?: suggestedReason ?: "") }
    val neg = line.diff < 0
    val writeoff = mode == "writeoff"
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.9f).clip(RoundedCornerShape(16.dp)).background(AdminColors.Card)
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}.padding(16.dp),
        ) {
            Text(if (writeoff) "Ghi thất thoát / hao hụt" else "Điều chỉnh tồn có lý do", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(
                "${line.variantName} · ${if (neg) "Thiếu ${trimZeros(abs(line.diff))}" else "Dư ${trimZeros(line.diff)}"} ${line.unit}",
                color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                if (writeoff) "Khi chốt phiên, tồn giảm về số đếm và ghi một khoản LỖ = giá vốn số hàng thiếu (phi tiền mặt)."
                else "Khi chốt phiên, tồn sẽ áp về ${trimZeros(line.countedQty)} ${line.unit}.",
                color = if (writeoff) AdminColors.Danger else AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                    .border(1.dp, AdminColors.Border, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = reason, onValueChange = { reason = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary),
                    decorationBox = { inner -> if (reason.isEmpty()) Text(if (writeoff) "Nguyên nhân thất thoát (mất, hỏng, vỡ...)" else "Lý do (đếm lại, điều chỉnh...)", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Huỷ", color = AdminColors.TextMuted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 6.dp))
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (writeoff) AdminColors.Danger else AdminColors.Primary)
                        .clickable(enabled = !busy) { onConfirm(reason) }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(if (writeoff) "Ghi thất thoát" else "Xác nhận", color = AdminColors.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/**
 * Sắp xếp: gom theo sản phẩm, sản phẩm có TỔNG tồn (base) lớn hơn lên trước; trong mỗi SP
 * variant tồn lớn hơn lên trước. Ẩn variant SL=0 (trừ khi đang search → keepZero).
 * So bằng stock_base cho nhất quán đơn vị.
 */
private fun arrangeVariants(list: List<VariantSearchDto>, keepZero: Boolean): List<VariantSearchDto> {
    val totalByProduct = list.groupBy { it.product?.id ?: 0L }
        .mapValues { (_, vs) -> vs.sumOf { it.stockBase ?: 0.0 } }
    return list
        .filter { keepZero || (it.stockBase ?: 0.0) > 0.0 }
        .sortedWith(
            compareByDescending<VariantSearchDto> { totalByProduct[it.product?.id ?: 0L] ?: 0.0 }
                .thenBy { it.product?.id ?: 0L }
                .thenByDescending { it.stockBase ?: 0.0 },
        )
}

// ===== Cache số đếm kiểm kho (per kho, TTL 24h) =====
private const val STOCKTAKE_TTL = 24L * 60 * 60 * 1000
private fun stocktakeKey(whId: Long?) = "counts_${whId ?: 0L}"
private data class StocktakeCacheEntry(val ts: Long, val counts: Map<Long, String>)

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
private fun StocktakeRow(v: VariantSearchDto, counted: String, onCountedChange: (String) -> Unit, onFocus: () -> Unit, onOpenHistory: () -> Unit) {
    val defUnit = v.units.firstOrNull { it.isDefaultSale } ?: v.units.firstOrNull { it.isBase } ?: v.units.firstOrNull()
    val stockInUnit = variantStockInUnit(v)
    val unit = defUnit?.name ?: ""
    val name = if (!v.name.isNullOrBlank()) v.name
        else v.attributes?.values?.filter { it.isNotBlank() }?.joinToString(", ") ?: (v.product?.name ?: "")

    val countedNum = counted.toDoubleOrNull()
    val checked = countedNum != null && countedNum == stockInUnit

    Row(
        // Giảm padding dọc (6→3) để ảnh to hơn mà KHÔNG tăng chiều cao dòng.
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Khớp (đã tích) → tên xám, ảnh mờ, "Khớp" vẫn xanh nổi.
        val thumbAlpha = if (checked) 0.45f else 1f
        val img = v.image ?: v.product?.primaryImage?.url
        if (img != null) AsyncImage(model = img, contentDescription = null, modifier = Modifier.size(66.dp).clip(RoundedCornerShape(6.dp)).clickable { onOpenHistory() }.alpha(thumbAlpha))
        else Box(Modifier.size(66.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)).clickable { onOpenHistory() }.alpha(thumbAlpha), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Inventory2, null, tint = AdminColors.TextMuted, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).clickable { onOpenHistory() }) {
            Text(name, color = if (checked) AdminColors.TextMuted else AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            // Tồn: {sl} {đv}  |  Lệch {sl} {đv} (hiện khi đã nhập đếm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // "Tồn:" xám — SỐ trắng 100% + to hơn 2sp — đơn vị xám; nới khoảng cách 2 bên số.
                Text("Tồn:", color = AdminColors.TextMuted, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(trimZeros(stockInUnit), color = AdminColors.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Text(unit, color = AdminColors.TextMuted, fontSize = 12.sp)
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
