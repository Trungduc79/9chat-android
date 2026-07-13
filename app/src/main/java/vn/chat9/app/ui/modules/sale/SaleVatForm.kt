package vn.chat9.app.ui.modules.sale

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import vn.chat9.app.ui.common.NumEditHint
import vn.chat9.app.ui.explore.AdminColors
import java.text.NumberFormat
import java.util.Locale

// ===== Định dạng giá thập phân (đơn giá HĐ VAT có số lẻ, vd 16.761,90) =====
private fun fmtPriceVat(n: Double): String {
    val f = NumberFormat.getNumberInstance(Locale("vi"))
    f.maximumFractionDigits = 2
    f.minimumFractionDigits = if (n == Math.floor(n)) 0 else 2
    return f.format(n)
}
private fun parsePriceVat(s: String): Double {
    val t = s.filter { it.isDigit() || it == ',' }        // giữ số + phẩy, bỏ chấm phân nhóm
    val parts = t.split(',')
    val num = if (parts.size > 1) parts[0] + "." + parts.drop(1).joinToString("") else parts[0]
    return num.toDoubleOrNull() ?: 0.0
}
/**
 * Nhập tắt theo nghìn: gõ 850 → 850.000. CHỈ áp cho số NGUYÊN < 1000 — HĐ VAT có giá lẻ thật
 * (16.761,90) nên KHÔNG ×1000 khi có phần thập phân.
 */
private fun expandVatPrice(n: Double): Double =
    if (n > 0 && n < 1000 && n == Math.floor(n)) n * 1000 else n

private fun round2(n: Double): Double = Math.round(n * 100.0) / 100.0
private fun round4(n: Double): Double = Math.round(n * 10000.0) / 10000.0
private fun trimQty(n: Double): String = if (n == Math.floor(n)) n.toLong().toString() else n.toString()

/**
 * Form HĐ VAT (Android · Phase 3B) — port SaleVatFormView. Tạo/sửa đơn `is_invoice_only`
 * + chọn đơn vị mua (vat_info) + Upload PO AI + Xem trước ảnh HĐ + Ký phát hành EI + Đồng bộ.
 *
 * Reuse từ [SaleOrderForm]: CustomerPicker, VariantPicker, UnitDropdown, Card, FocusCenterCtx,
 * centerOnFocus, OrderItemDraft. Item row riêng ([VatItemRow]) để hỗ trợ đơn giá thập phân.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleVatForm(orderId: Long? = null, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val view = LocalView.current

    var currentOrderId by remember { mutableStateOf(orderId) }
    var selectedCustomer by remember { mutableStateOf<CustomerDto?>(null) }
    val items = remember { mutableStateListOf<OrderItemDraft>() }
    var suggested by remember { mutableStateOf<List<RecentProductDto>>(emptyList()) }   // SP mua gần đây của KH
    var orderDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var status by remember { mutableStateOf<String?>(null) }

    // VAT panel
    var vatInfos by remember { mutableStateOf<List<VatInfoDto>>(emptyList()) }
    var selectedVatInfoId by remember { mutableStateOf<Long?>(null) }
    var priceType by remember { mutableStateOf("inclusive") }   // inclusive | exclusive
    var buyerName by remember { mutableStateOf("") }
    var includePhone by remember { mutableStateOf(false) }
    var poNumber by remember { mutableStateOf("") }              // orders.reference — số PO khách
    var lastAutoBuyerName by remember { mutableStateOf("") }     // chuỗi auto lần trước (để biết user đã sửa tay chưa)

    var linkedVat by remember { mutableStateOf<VatOutputInvoiceDto?>(null) }
    val signed = linkedVat?.signed == true
    val canEdit = !signed && (currentOrderId == null || status == "draft")

    // Tên người mua tự điền "{Tên đơn vị} - {Số PO}" khi ĐƠN VỊ mua bật cờ buyer_name_with_po.
    // Không có số PO → để trống. Kế toán sửa tay rồi thì không ghi đè nữa.
    val autoBuyerName = run {
        val vi = vatInfos.firstOrNull { it.id == selectedVatInfoId }
        val po = poNumber.trim()
        if (vi?.buyerNameWithPo == true && po.isNotBlank()) "${vi.legalName.orEmpty()} - $po" else ""
    }
    LaunchedEffect(autoBuyerName, canEdit) {
        if (!canEdit) return@LaunchedEffect
        if (buyerName.isBlank() || buyerName == lastAutoBuyerName) {
            buyerName = autoBuyerName
            lastAutoBuyerName = autoBuyerName
        }
    }

    var customerPickerOpen by remember { mutableStateOf(orderId == null) }
    var productPickerOpen by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    var pickerProductId by remember { mutableStateOf<Long?>(null) }
    var addVatOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }

    // Busy flags
    var saving by remember { mutableStateOf(false) }
    var autoSaving by remember { mutableStateOf(false) }   // đang tự lưu đơn (debounce sau khi sửa)
    var poUploading by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    // Menu 3 chấm header (đơn đã lưu): sao chép / xoá.
    var headerMenuOpen by remember { mutableStateOf(false) }
    var headerBusy by remember { mutableStateOf(false) }
    var confirmDeleteVat by remember { mutableStateOf(false) }

    // Menu 3 chấm thẻ HĐ VAT (HĐ nháp EI chưa ký): đổi đơn vị mua / tên người mua / SĐT / hình thức giá.
    var vatMenuOpen by remember { mutableStateOf(false) }
    var updatingDraft by remember { mutableStateOf(false) }
    var unitPickerOpen by remember { mutableStateOf(false) }
    var buyerNameOpen by remember { mutableStateOf(false) }
    var pricePickerOpen by remember { mutableStateOf(false) }
    var itemsPriceMenuOpen by remember { mutableStateOf(false) }   // menu giá trên thẻ mặt hàng
    var displayIncVat by remember { mutableStateOf(true) }         // chỉ đổi HIỂN THỊ bảng: true=giá gộp VAT, false=giá net

    // Preview
    var previewOpen by remember { mutableStateOf(false) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var signing by remember { mutableStateOf(false) }
    var vatBlocked by remember { mutableStateOf<String?>(null) }   // chi tiết 422 VAT_ISSUE_BLOCKED (gate 3 kiểm tra)
    var creatingDraft by remember { mutableStateOf(false) }
    var copyingImage by remember { mutableStateOf(false) }
    var sharingImage by remember { mutableStateOf(false) }
    var shortages by remember { mutableStateOf<List<VatShortageDto>>(emptyList()) }         // thiếu XNT trong preview
    var cardShortages by remember { mutableStateOf<List<VatShortageDto>>(emptyList()) }     // thiếu XNT hiển thị trong card (HĐ nháp chưa ký)
    // Cờ khóa cứng /settings/vat-guards: bật → KHÔNG cho lưu giá dưới giá vốn (hoàn nguyên ngay).
    var blockPrice by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        blockPrice = try { container.vapi.vatIssueGuards().data?.blockPrice == true } catch (_: Exception) { false }
    }

    // ===== Giá vốn cache (prefetch) → so giá xuất TẠI CHỖ, phản hồi ngay khi nhập giá =====
    // Giá vốn FIFO chỉ phụ thuộc tên hàng + đơn vị HĐ (không phụ thuộc giá bán) → nạp 1 lần
    // khi mở đơn / thêm SP là đủ. Server vẫn gate lại lúc ký phát hành.
    val costBasis = remember { mutableStateMapOf<Long, VatCostBasisDto>() }
    val variantKey = items.joinToString(",") { it.variantId.toString() }
    LaunchedEffect(variantKey) {
        val missing = items.map { it.variantId }.filter { it != 0L && it !in costBasis }.distinct()
        if (missing.isEmpty()) return@LaunchedEffect
        try {
            container.vapi.vatCostBasis(VatCostBasisReq(missing)).data?.items?.forEach { costBasis[it.variantId] = it }
        } catch (_: Exception) { /* im lặng — vẫn còn gate lúc ký */ }
    }

    // So 1 dòng với giá vốn đã cache. Giá vốn về từ BE theo ĐƠN VỊ HĐ (vd Hộp) → quy đổi ngược
    // về ĐƠN VỊ ĐANG NHẬP trên đơn (vd Thùng); cảnh báo cũng hiện theo đơn vị đó.
    // Dùng GIÁ SẼ LƯU (đã bung nghìn): đang gõ 790 → so với 790.000, không phải 790.
    fun localPriceIssue(d: OrderItemDraft): VatPriceIssueDto? {
        val cb = costBasis[d.variantId] ?: return null
        val costNet = cb.costNet ?: return null
        val unit = d.units.firstOrNull { it.id == d.unitId }
        val cf = unit?.conversionFactor ?: 1.0
        val ratio = cf / (cb.conversionFactor.takeIf { it > 0 } ?: 1.0)   // số đơn vị HĐ trong 1 đơn vị đang nhập
        if (ratio <= 0) return null
        // Giá nhập trên đơn đã gộp VAT khi chọn "đã gồm VAT" → giá vốn cũng gross-up cho cùng gốc.
        val inclusive = priceType == "inclusive"
        val sale = expandVatPrice(d.price)
        if (sale <= 0) return null
        val cost = (if (inclusive) costNet * (1 + (cb.costVatRate ?: 0.0) / 100) else costNet) * ratio
        if (sale >= cost - 0.0001) return null
        return VatPriceIssueDto(d.variantId, d.productName, unit?.name ?: cb.invoiceUnit, sale, cost, cost - sale, priceType)
    }

    // Gợi ý ô giá trống = GIÁ NHẬP (giá vốn) quy về đơn vị đang chọn + phương thức giá.
    fun costPlaceholder(d: OrderItemDraft): String? {
        val cb = costBasis[d.variantId] ?: return null
        val costNet = cb.costNet ?: return null
        val cf = d.units.firstOrNull { it.id == d.unitId }?.conversionFactor ?: 1.0
        val ratio = cf / (cb.conversionFactor.takeIf { it > 0 } ?: 1.0)
        val cost = (if (priceType == "inclusive") costNet * (1 + (cb.costVatRate ?: 0.0) / 100) else costNet) * ratio
        return if (cost > 0) fmtPriceVat(cost) else null
    }

    // Cảnh báo hiển thị = tính TẠI CHỖ (đổi ngay khi nhập giá, không đợi server).
    val cardPriceIssues: List<VatPriceIssueDto> = items.mapNotNull { localPriceIssue(it) }
    // Dòng chưa từng nhập HĐ VAT → không so được giá (hiện nhãn vàng, không chặn).
    val noCostItems: List<String> = items.filter { it.variantId != 0L && costBasis[it.variantId]?.costNet == null }
        .map { it.productName }
    var eiPreviewUrl by remember { mutableStateOf<String?>(null) }                          // ảnh HĐ trên EI (data URL) để xem full-screen
    var eiLoadingPreview by remember { mutableStateOf(false) }

    // Keyboard center helper (reuse focusCtx của SaleOrderForm).
    val scrollState = rememberScrollState()
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val screenHeightPx = view.rootView.height.toFloat()
    val appBarPx = with(density) { 48.dp.toPx() }
    val imeBottomState = rememberUpdatedState(imeBottomPx)
    val focusCtx = FocusCenterCtx(scrollState, screenHeightPx, statusBarPx, appBarPx, imeBottomState)
    val pushUpDp = with(density) { (imeBottomPx * 0.8f).toDp() }

    suspend fun loadVatInfo(customerId: Long, autoSelect: Boolean) {
        vatInfos = try { container.vapi.listCustomerVatInfo(customerId).data ?: emptyList() } catch (_: Exception) { emptyList() }
        if (autoSelect) selectedVatInfoId = vatInfos.firstOrNull()?.id
    }

    suspend fun refreshLinkedVat(invId: Long?) {
        if (invId == null) { linkedVat = null; return }
        linkedVat = try { container.vapi.getVatOutputInvoice(invId).data } catch (_: Exception) { null }
        linkedVat?.vatInfoId?.let { selectedVatInfoId = it }
    }

    // Nạp cảnh báo thiếu XNT cho card. KHÔNG cần đợi có HĐ nháp EI — đơn đã lưu là check được.
    // Đã KÝ → thôi, không cảnh báo nữa. (Giá xuất<nhập tính tại chỗ từ cache, không gọi API.)
    suspend fun reloadCardShortages() {
        val id = currentOrderId
        if (id == null || linkedVat?.signed == true) { cardShortages = emptyList(); return }
        cardShortages = try { container.vapi.vatStockCheck(id).data?.shortages ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    // Sửa ĐƠN GIÁ → lưu (debounce) + đồng bộ EI; KHÔNG đụng tồn. Giá thấp đã bị chặn ngay lúc
    // rời ô nhập (cache giá vốn) nên tới đây giá luôn hợp lệ.
    val priceKey = items.joinToString("|") { "${it.variantId}:${it.price}" }
    LaunchedEffect(priceKey, priceType, currentOrderId) {
        val id = currentOrderId ?: return@LaunchedEffect
        if (!canEdit || items.isEmpty()) return@LaunchedEffect
        delay(900)
        autoSaving = true
        try {
            container.vapi.updateOrder(id, CreateOrderRequest(
                type = "sale", isInvoiceOnly = true, partyType = "customer",
                partyId = selectedCustomer?.id ?: return@LaunchedEffect, status = "draft",
                orderedAt = java.time.Instant.ofEpochMilli(orderDateMs).toString(),
                reference = poNumber.trim().ifBlank { null },
                items = items.filter { it.variantId != 0L && it.qty > 0 }
                    .map { CreateOrderItem(it.variantId, it.unitId, it.qty, it.price) },
            ))
            // Có HĐ nháp EI → đẩy lại cho khớp giá mới (không reload tồn).
            if (linkedVat != null && linkedVat?.signed != true) {
                try { container.vapi.updateVatDraft(id, VatDraftReq(priceType, true, selectedVatInfoId)) } catch (_: Exception) {}
            }
        } catch (_: Exception) { /* im lặng — không spam khi gõ */
        } finally { autoSaving = false }
    }

    // Load đơn existing / vừa tạo từ PO.
    LaunchedEffect(currentOrderId) {
        val oid = currentOrderId ?: return@LaunchedEffect
        try {
            val o = container.vapi.getOrder(oid).data ?: return@LaunchedEffect
            status = o.status
            includePhone = o.vatIncludePhone
            poNumber = o.reference.orEmpty()
            buyerName = o.vatBuyerName.orEmpty()
            lastAutoBuyerName = buyerName   // tên đã lưu = coi như auto → cho phép cập nhật lại
            priceType = if (o.meta?.vatPriceType == "exclusive") "exclusive" else "inclusive"
            o.party?.let { selectedCustomer = CustomerDto(id = it.id, name = it.name ?: "", phone = it.phone) }
            o.orderedAt?.let { runCatching { orderDateMs = java.time.Instant.parse(it).toEpochMilli() } }
            items.clear()
            o.items.forEach { it2 ->
                val vName = it2.snapshot.variantName?.takeIf { s -> s.isNotBlank() } ?: it2.variantLabel.ifBlank { it2.productName }
                // Dùng full units của variant (eager-load) → đổi đơn vị + tự quy đổi SL/giá được như web.
                val fullUnits = it2.variant?.units?.takeIf { u -> u.isNotEmpty() }
                    ?: listOf(VariantUnitDto(id = it2.unitId, name = it2.unitName, conversionFactor = 1.0, price = it2.unitPrice, isBase = false, isDefaultSale = false))
                items.add(OrderItemDraft(
                    variantId = it2.variantId, unitId = it2.unitId, productName = it2.productName, variantName = vName,
                    qty = it2.qtyUnit, price = it2.unitPrice, imageUrl = it2.imageUrl,
                    units = fullUnits,
                    id = it2.id,
                ))
            }
            o.party?.id?.let { pid ->
                loadVatInfo(pid, autoSelect = o.vatOutputInvoiceId == null)
                suggested = try { container.vapi.recentProducts(pid, 5).data ?: emptyList() } catch (_: Exception) { emptyList() }
            }
            refreshLinkedVat(o.vatOutputInvoiceId)
            reloadCardShortages()
        } catch (_: Exception) {}
    }

    fun addVariant(v: VariantSearchDto) {
        if (items.any { it.variantId == v.id }) {
            Toast.makeText(context, "\"${variantDisplay(v, v.product?.name ?: "")}\" đã có trong đơn", Toast.LENGTH_SHORT).show(); return
        }
        val units = v.units
        // Chọn đơn vị + đơn giá giống màn web /accounting/vat/new: đơn vị bán mặc định
        // (is_default_sale) > base > đầu DS; đơn giá = last price của KH ?? giá đơn vị ?? giá variant.
        val defUnit = units.firstOrNull { it.isDefaultSale } ?: units.firstOrNull { it.isBase } ?: units.firstOrNull()
        scope.launch {
            var price = defUnit?.price ?: v.price ?: 0.0
            try {
                val lp = container.vapi.lastPrice(selectedCustomer!!.id, v.id, defUnit?.id).data
                if (lp?.unitPrice != null) price = lp.unitPrice
            } catch (_: Exception) {}
            items.add(OrderItemDraft(v.id, defUnit?.id ?: 0L, v.product?.name ?: "", variantDisplay(v, v.product?.name ?: ""), 1.0, price, v.image ?: v.product?.primaryImage?.url, units))
            Toast.makeText(context, "Đã thêm \"${variantDisplay(v, v.product?.name ?: "")}\"", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Đổi khách thủ công → định giá lại dòng theo khách mới: đơn giá + ĐƠN VỊ lấy từ HĐ VAT
     * gần nhất của khách đó (SL quy đổi giữ lượng cơ bản), fallback giá bán gần nhất.
     * Dòng BE không trả về (không có căn cứ) thì giữ nguyên.
     */
    suspend fun repriceItemsFor(c: CustomerDto) {
        val rows = items.filter { it.variantId != 0L && it.unitId != 0L }
        if (rows.isEmpty()) return
        try {
            val req = PoRepriceReq(c.id, rows.map { PoRepriceItemReq(it.variantId, it.unitId, it.qty) })
            val priced = container.vapi.poReprice(req).data?.items ?: return
            var n = 0
            for (p in priced) {
                val idx = items.indexOfFirst { it.variantId == p.variantId }
                if (idx < 0) continue
                items[idx] = items[idx].copy(unitId = p.unitId, qty = p.qtyUnit, price = p.unitPrice)
                n++
            }
            if (n > 0) Toast.makeText(context, "Đã cập nhật giá $n mặt hàng theo khách ${c.name}", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { /* giữ nguyên giá cũ */ }
    }

    // Lưu đơn (create/update is_invoice_only) → trả id. Set tên người mua + SĐT + đồng bộ HĐ nháp nếu có.
    suspend fun saveOrder(): Long? {
        val cust = selectedCustomer ?: run { Toast.makeText(context, "Chọn khách hàng trước", Toast.LENGTH_SHORT).show(); return null }
        val valid = items.filter { it.variantId != 0L && it.qty > 0 }
        if (valid.isEmpty()) { Toast.makeText(context, "Chưa có mặt hàng", Toast.LENGTH_SHORT).show(); return null }
        val payload = CreateOrderRequest(
            type = "sale", isInvoiceOnly = true, partyType = "customer", partyId = cust.id, status = "draft",
            orderedAt = java.time.Instant.ofEpochMilli(orderDateMs).toString(),
            reference = poNumber.trim().ifBlank { null },   // số PO khách
            items = valid.map { CreateOrderItem(it.variantId, it.unitId, it.qty, it.price) },
        )
        val id = currentOrderId
        val order = if (id != null) container.vapi.updateOrder(id, payload).data else container.vapi.createOrder(payload).data
        order ?: return null
        currentOrderId = order.id
        try { container.vapi.setVatBuyerName(order.id, VatBuyerNameReq(buyerName.trim().ifBlank { null })) } catch (_: Exception) {}
        try { container.vapi.setVatIncludePhone(order.id, VatIncludePhoneReq(includePhone)) } catch (_: Exception) {}
        if (order.vatOutputInvoiceId != null) {
            try { container.vapi.updateVatDraft(order.id, VatDraftReq(priceType, true, selectedVatInfoId)); refreshLinkedVat(order.vatOutputInvoiceId) } catch (_: Exception) {}
        }
        return order.id
    }

    fun openPreview() {
        if (selectedVatInfoId == null) { Toast.makeText(context, "Chọn đơn vị mua (MST) trước", Toast.LENGTH_SHORT).show(); return }
        scope.launch {
            saving = true
            val id = try { saveOrder() } finally { saving = false }
            if (id == null) return@launch
            previewOpen = true; previewLoading = true; previewPages = emptyList(); shortages = emptyList()
            try {
                try { container.vapi.touchOrder(id) } catch (_: Exception) {}
                previewPages = container.vapi.vatDraftImages(id, priceType, selectedVatInfoId).data?.pages?.map { it.image } ?: emptyList()
                shortages = try { container.vapi.vatStockCheck(id).data?.shortages ?: emptyList() } catch (_: Exception) { emptyList() }
            } catch (_: Exception) {
                Toast.makeText(context, "Không tải được xem trước HĐ", Toast.LENGTH_SHORT).show(); previewOpen = false
            } finally { previewLoading = false }
        }
    }

    // Tạo HĐ nháp trên EI (giữ preview mở → nút đổi sang "Ký phát hành").
    fun doCreateDraft() {
        val id = currentOrderId ?: return
        if (selectedVatInfoId == null) { Toast.makeText(context, "Chọn đơn vị mua (MST) trước", Toast.LENGTH_SHORT).show(); return }
        scope.launch {
            creatingDraft = true
            try {
                val inv = container.vapi.createVatDraft(id, VatDraftReq(priceType, true, selectedVatInfoId)).data
                linkedVat = inv
                container.vapi.getOrder(id).data?.let { status = it.status }
                reloadCardShortages()
                Toast.makeText(context, "Đã tạo HĐ nháp trên EI. Mã tra cứu: ${inv?.eiLookupCode ?: "—"}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Tạo HĐ nháp thất bại", Toast.LENGTH_SHORT).show()
            } finally { creatingDraft = false }
        }
    }

    // Copy ảnh HĐ (trang 1) vào clipboard qua FileProvider.
    fun copyImage() {
        val dataUrl = previewPages.firstOrNull() ?: return
        scope.launch {
            copyingImage = true
            try {
                val b64 = dataUrl.substringAfter("base64,", dataUrl)
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                val file = java.io.File(dir, "vat_preview.png"); file.writeBytes(bytes)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val clip = android.content.ClipData.newUri(context.contentResolver, "Hóa đơn VAT", uri)
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(context, "Đã copy ảnh HĐ vào clipboard", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Copy ảnh thất bại", Toast.LENGTH_SHORT).show()
            } finally { copyingImage = false }
        }
    }

    // Share ảnh HĐ (trang 1) qua chooser hệ thống (ACTION_SEND image/png).
    fun shareImage() {
        val dataUrl = previewPages.firstOrNull() ?: return
        scope.launch {
            sharingImage = true
            try {
                val bytes = Base64.decode(dataUrl.substringAfter("base64,", dataUrl), Base64.DEFAULT)
                val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                val file = java.io.File(dir, "vat_share.png"); file.writeBytes(bytes)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(send, "Chia sẻ hóa đơn").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(context, "Chia sẻ thất bại", Toast.LENGTH_SHORT).show()
            } finally { sharingImage = false }
        }
    }

    // Xem HĐ đã phát hành TRỰC TIẾP trên EI: tải ảnh PNG render sẵn → xem full-screen zoom.
    fun openEiPreview() {
        val invId = linkedVat?.id ?: return
        scope.launch {
            eiLoadingPreview = true
            try {
                val bytes = container.vapi.vatOutputPreviewImage(invId).bytes()
                eiPreviewUrl = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (_: Exception) {
                Toast.makeText(context, "Không tải được ảnh HĐ trên EI", Toast.LENGTH_SHORT).show()
            } finally { eiLoadingPreview = false }
        }
    }

    fun doSign() {
        val id = currentOrderId ?: return
        scope.launch {
            signing = true
            try {
                val inv = container.vapi.issueVat(id, VatDraftReq(priceType, true, selectedVatInfoId)).data
                linkedVat = inv
                Toast.makeText(context, "Đã ký phát hành. Số HĐ: ${inv?.number ?: "—"} · CQT: ${inv?.cqtCode ?: "—"}", Toast.LENGTH_LONG).show()
                container.vapi.getOrder(id).data?.let { status = it.status }
                previewOpen = false
                // Đồng bộ EI nền (không chặn UI).
                launch { try { container.vapi.syncEasyInvoice(SyncEiReq(inv?.issueDate?.take(10), null)) } catch (_: Exception) {} }
            } catch (e: retrofit2.HttpException) {
                val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                val j = try { org.json.JSONObject(body ?: "") } catch (_: Exception) { null }
                if (j?.optString("code") == "VAT_ISSUE_BLOCKED") {
                    vatBlocked = vatBlockedText(j.optJSONObject("errors"))
                } else {
                    Toast.makeText(context, j?.optString("error").orEmpty().ifBlank { "Ký phát hành thất bại. Kiểm tra quyền & đăng nhập EI." }, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Ký phát hành thất bại. Kiểm tra quyền & đăng nhập EI.", Toast.LENGTH_LONG).show()
            } finally { signing = false }
        }
    }

    fun syncEI() {
        scope.launch {
            syncing = true
            try {
                container.vapi.syncEasyInvoice()
                currentOrderId?.let { oid -> container.vapi.getOrder(oid).data?.let { refreshLinkedVat(it.vatOutputInvoiceId) } }
                Toast.makeText(context, "Đã đồng bộ EasyInvoice", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Đồng bộ EI thất bại", Toast.LENGTH_SHORT).show()
            } finally { syncing = false }
        }
    }

    // Cập nhật HĐ nháp trên EI: re-send tên người mua / SĐT / đơn vị mua / hình thức giá (mirror web).
    fun onUpdateDraft() {
        val id = currentOrderId ?: return
        if (linkedVat == null || updatingDraft) return
        scope.launch {
            updatingDraft = true
            try {
                try { container.vapi.setVatBuyerName(id, VatBuyerNameReq(buyerName.trim().ifBlank { null })) } catch (_: Exception) {}
                try { container.vapi.setVatIncludePhone(id, VatIncludePhoneReq(includePhone)) } catch (_: Exception) {}
                container.vapi.updateVatDraft(id, VatDraftReq(priceType, true, selectedVatInfoId))
                refreshLinkedVat(container.vapi.getOrder(id).data?.vatOutputInvoiceId)
                reloadCardShortages()
                Toast.makeText(context, "Đã cập nhật HĐ nháp trên EasyInvoice", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Cập nhật HĐ nháp thất bại", Toast.LENGTH_SHORT).show()
            } finally { updatingDraft = false }
        }
    }

    // Sao chép sang HĐ VAT nháp mới → mở luôn đơn mới trong form.
    fun doCopyOrder() {
        val id = currentOrderId ?: return
        if (headerBusy) return
        scope.launch {
            headerBusy = true
            try {
                val res = container.vapi.copyOrder(id).data ?: throw Exception("no-order")
                Toast.makeText(context, "Đã sao chép sang HĐ VAT nháp ${res.code}", Toast.LENGTH_SHORT).show()
                linkedVat = null; items.clear(); currentOrderId = res.id   // trigger reload
            } catch (_: Exception) {
                Toast.makeText(context, "Sao chép HĐ VAT thất bại", Toast.LENGTH_SHORT).show()
            } finally { headerBusy = false }
        }
    }

    // Sao chép HĐ VAT → đơn bán thường (approve=true → duyệt luôn). Đơn thường không ở tab VAT → về list.
    fun doCopyToNormal(approve: Boolean) {
        val id = currentOrderId ?: return
        if (headerBusy) return
        scope.launch {
            headerBusy = true
            try {
                val res = container.vapi.copyOrderToNormal(id).data ?: throw Exception("no-order")
                if (approve) {
                    container.vapi.confirmOrder(res.id)
                    Toast.makeText(context, "Đã tạo + duyệt đơn bán thường ${res.code}", Toast.LENGTH_LONG).show()
                } else Toast.makeText(context, "Đã tạo đơn bán thường nháp ${res.code}", Toast.LENGTH_LONG).show()
                onDone()
            } catch (_: Exception) {
                Toast.makeText(context, "Tạo đơn bán thường thất bại", Toast.LENGTH_SHORT).show()
            } finally { headerBusy = false }
        }
    }

    // Xoá HĐ này: có HĐ nháp EI → xoá bản nháp EI trước (strict), rồi xoá order. HĐ đã ký thì ẩn nút.
    fun doDeleteVat() {
        val id = currentOrderId ?: return
        if (headerBusy || signed) return
        scope.launch {
            headerBusy = true
            try {
                linkedVat?.let { container.vapi.deleteVatOutputInvoice(it.id) }
                container.vapi.deleteOrder(id)
                Toast.makeText(context, "Đã xoá hóa đơn.", Toast.LENGTH_SHORT).show()
                onDone()
            } catch (_: Exception) {
                Toast.makeText(context, "Xoá hóa đơn thất bại. HĐ nháp EI có thể đang khoá.", Toast.LENGTH_LONG).show()
            } finally { headerBusy = false }
        }
    }

    // Upload PO → tạo đơn nháp → load.
    val poLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            poUploading = true
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("empty")
                if (bytes.size > 1024 * 1024) { Toast.makeText(context, "File PO không được lớn hơn 1MB.", Toast.LENGTH_SHORT).show(); return@launch }
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
                val part = okhttp3.MultipartBody.Part.createFormData("file", "po", body)
                // Đã chọn khách trước khi upload → BE dùng luôn khách này (giá/đơn vị theo
                // lịch sử HĐ của khách), không để AI đoán.
                val custPart = selectedCustomer?.id?.let {
                    it.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                }
                val res = container.vapi.poDraft(part, custPart).data
                val newId = res?.order?.id
                if (newId == null) { Toast.makeText(context, "Không đọc được PO", Toast.LENGTH_SHORT).show(); return@launch }
                val unresolvedCount = res.unresolved?.size ?: 0
                val msg = buildString {
                    append(res.matchedCustomer?.name?.let { "Khách: $it" } ?: "Chưa khớp khách")
                    if (unresolvedCount > 0) append(" · $unresolvedCount mặt hàng chưa nhận diện")
                }
                Toast.makeText(context, "Đã tạo HĐ nháp từ PO. $msg", Toast.LENGTH_LONG).show()
                items.clear(); currentOrderId = newId   // trigger reload
            } catch (e: retrofit2.HttpException) {
                val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                val err = try { org.json.JSONObject(body ?: "").optString("error") } catch (_: Exception) { null }
                Toast.makeText(context, err.orEmpty().ifBlank { "Không đọc được PO (HTTP ${e.code()})" }, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Không đọc được PO: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            } finally { poUploading = false }
        }
    }

    Box(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        // Vuốt phải → đóng form (về list VAT), KHÔNG để AccountingScreen bắt (vuốt VAT ordinal 0 = thoát module).
        Column(
            Modifier.fillMaxSize().padding(bottom = pushUpDp).pointerInput(Unit) {
                var acc = 0f
                detectHorizontalDragGestures(onDragEnd = { if (acc > 90f) onDone(); acc = 0f }, onDragCancel = { acc = 0f }) { _, dx -> acc += dx }
            },
        ) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(12.dp)) {
                // Card KH: [back] tên KH ✎ (trái) · Upload PO (tạo mới) · ngày (phải).
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(horizontal = 8.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ArrowBack, "Đóng", tint = AdminColors.Text, modifier = Modifier.clickable { onDone() }.padding(4.dp).size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Row(Modifier.weight(1f).clickable(enabled = canEdit) { customerPickerOpen = true }, verticalAlignment = Alignment.CenterVertically) {
                            if (selectedCustomer == null) Text("Chọn khách hàng", color = AdminColors.Primary, fontSize = 14.sp, fontStyle = FontStyle.Italic, maxLines = 1)
                            else Text(selectedCustomer!!.name, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (canEdit) Text("  ✎", color = AdminColors.Primary, fontSize = 13.sp)
                        }
                        if (currentOrderId == null) {
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(AdminColors.Primary).clickable(enabled = !poUploading) { poLauncher.launch("*/*") }.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (poUploading) CircularProgressIndicator(Modifier.size(13.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(Icons.Default.UploadFile, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (poUploading) "Đang đọc…" else "PO", color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        // Đang tự lưu đơn → quay vòng nhỏ cạnh ngày.
                        if (autoSaving) {
                            CircularProgressIndicator(Modifier.padding(end = 6.dp).size(14.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                        }
                        val dateLabel = java.text.SimpleDateFormat("dd/MM/yyyy", Locale("vi")).format(java.util.Date(orderDateMs))
                        Text(dateLabel, color = if (canEdit) AdminColors.Primary else AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(enabled = canEdit) { datePickerOpen = true }.background(AdminColors.Primary.copy(alpha = if (canEdit) 0.08f else 0f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 4.dp))

                        // Menu 3 chấm (chỉ đơn đã lưu): 3 lựa chọn sao chép + xoá HĐ (ẩn xoá khi đã phát hành).
                        if (currentOrderId != null) Box {
                            if (headerBusy) CircularProgressIndicator(Modifier.padding(start = 4.dp).size(18.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                            else Icon(Icons.Default.MoreVert, "Tùy chọn", tint = AdminColors.Text, modifier = Modifier.clickable { headerMenuOpen = true }.padding(start = 2.dp).size(22.dp))
                            MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
                                DropdownMenu(expanded = headerMenuOpen, onDismissRequest = { headerMenuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Sao chép HĐ VAT (nháp)", color = AdminColors.Text) }, onClick = { headerMenuOpen = false; doCopyOrder() })
                                    HorizontalDivider(color = AdminColors.Border)
                                    DropdownMenuItem(text = { Text("Sao chép → đơn bán thường (nháp)", color = AdminColors.Text) }, onClick = { headerMenuOpen = false; doCopyToNormal(false) })
                                    HorizontalDivider(color = AdminColors.Border)
                                    DropdownMenuItem(text = { Text("Sao chép → đơn bán thường + duyệt", color = AdminColors.Text) }, onClick = { headerMenuOpen = false; doCopyToNormal(true) })
                                    if (!signed) {
                                        HorizontalDivider(color = AdminColors.Border)
                                        DropdownMenuItem(text = { Text("Xoá HĐ này", color = AdminColors.Danger) }, onClick = { headerMenuOpen = false; confirmDeleteVat = true })
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ===== Card Hóa đơn VAT (định danh) — GIỮA KH & mặt hàng khi đã có HĐ nháp/phát hành =====
                linkedVat?.let { vat ->
                    Card("") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Hóa đơn VAT", color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            PoNumberField(poNumber, canEdit, Modifier.weight(1f)) { poNumber = it }
                            // Chưa ký → icon đồng bộ + menu 3 chấm. Đã phát hành → khoá, không action.
                            if (!signed) {
                                if (syncing) CircularProgressIndicator(Modifier.padding(4.dp).size(18.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, "Đồng bộ", tint = AdminColors.Primary, modifier = Modifier.clickable(enabled = !updatingDraft) { syncEI() }.padding(4.dp).size(20.dp))
                                Box {
                                    if (updatingDraft) CircularProgressIndicator(Modifier.padding(start = 2.dp, end = 4.dp).size(18.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                                    else Icon(Icons.Default.MoreVert, "Tùy chọn HĐ", tint = AdminColors.Text, modifier = Modifier.clickable(enabled = !syncing) { vatMenuOpen = true }.padding(start = 2.dp).size(20.dp))
                                    MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
                                        DropdownMenu(expanded = vatMenuOpen, onDismissRequest = { vatMenuOpen = false }) {
                                            DropdownMenuItem(text = { Text("Thay đơn vị mua HĐ", color = AdminColors.Text) }, onClick = { vatMenuOpen = false; unitPickerOpen = true })
                                            HorizontalDivider(color = AdminColors.Border)
                                            DropdownMenuItem(text = { Text("Tên người mua trên HĐ", color = AdminColors.Text) }, onClick = { vatMenuOpen = false; buyerName = vat.buyerName ?: ""; buyerNameOpen = true })
                                            HorizontalDivider(color = AdminColors.Border)
                                            DropdownMenuItem(text = { Text(if (includePhone) "✓ SĐT khách trên HĐ" else "SĐT khách trên HĐ", color = AdminColors.Text) }, onClick = { vatMenuOpen = false; includePhone = !includePhone; onUpdateDraft() })
                                            HorizontalDivider(color = AdminColors.Border)
                                            DropdownMenuItem(text = { Text("Đổi hình thức giá", color = AdminColors.Text) }, onClick = { vatMenuOpen = false; pricePickerOpen = true })
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = AdminColors.Border, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(8.dp))
                        // Tên đơn vị mua (căn giữa) + đường kẻ 68%.
                        Text(vat.buyerName ?: "—", color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth(0.68f).height(1.dp).background(Color.White.copy(alpha = 0.4f)).align(Alignment.CenterHorizontally))
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Card Items (thẻ mặt hàng — mirror đơn bán: header có border-bottom, tổng có border-top)
                Card("") {
                    // Header: "Mặt hàng (N)" + chip SP mua gần đây (scroll ngang).
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Mặt hàng (${items.size})", color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        if (suggested.isNotEmpty() && canEdit) {
                            Spacer(Modifier.width(8.dp))
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                suggested.forEach { p ->
                                    Text("+ ${p.productName}", color = AdminColors.Primary, fontSize = 12.sp, maxLines = 1,
                                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(AdminColors.Primary.copy(alpha = 0.1f))
                                            .clickable { pickerQuery = p.productName; pickerProductId = p.productId; productPickerOpen = true }
                                            .padding(horizontal = 10.dp, vertical = 2.dp))
                                }
                            }
                        } else Spacer(Modifier.weight(1f))
                        // Menu 3 chấm: CHỈ đổi hiển thị đơn giá bảng (net/gross) cho khớp tổng — chỉ khi đã có HĐ. KHÔNG đụng HĐ EI.
                        if (linkedVat != null) Box {
                            Icon(Icons.Default.MoreVert, "Hiển thị giá", tint = AdminColors.Text, modifier = Modifier.clickable { itemsPriceMenuOpen = true }.padding(start = 2.dp).size(20.dp))
                            MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
                                DropdownMenu(expanded = itemsPriceMenuOpen, onDismissRequest = { itemsPriceMenuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text((if (!displayIncVat) "✓ " else "") + "Đơn giá chưa bao gồm VAT", color = AdminColors.Text) },
                                        onClick = { itemsPriceMenuOpen = false; displayIncVat = false },
                                    )
                                    HorizontalDivider(color = AdminColors.Border)
                                    DropdownMenuItem(
                                        text = { Text((if (displayIncVat) "✓ " else "") + "Đơn giá đã bao gồm VAT", color = AdminColors.Text) },
                                        onClick = { itemsPriceMenuOpen = false; displayIncVat = true },
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = AdminColors.Border, modifier = Modifier.padding(top = 8.dp))

                    // Đã có HĐ → hiển thị đơn giá theo HĐ EI (net/gross), KHÔNG lấy giá lưu (đơn có thể lưu net
                    // hoặc gross) để toggle luôn có tác dụng. Khớp index dòng; lệch số dòng → phân bổ theo tỉ trọng.
                    val invItems = linkedVat?.items ?: emptyList()
                    val sameCount = linkedVat != null && invItems.size == items.size
                    val sumStored = items.sumOf { it.qty * it.price }

                    if (items.isEmpty()) Text("Chưa có mặt hàng — chọn KH rồi Thêm SP hoặc Upload PO", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                    else items.forEachIndexed { idx, it ->
                        if (idx > 0) HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.4f))
                        // Giá theo HĐ (đọc-only) CHỈ khi đã khoá (đã ký / non-draft) — như web.
                        // Còn sửa được thì hiện giá GỐC trên đơn để nhập trực tiếp.
                        val displayPrice: Double? = if (linkedVat != null && !canEdit) {
                            val lineAmt = if (sameCount) (if (displayIncVat) invItems[idx].total else invItems[idx].subtotal)
                                else {
                                    val invAmt = if (displayIncVat) linkedVat!!.total else linkedVat!!.subtotal
                                    if (sumStored > 0) invAmt * (it.qty * it.price) / sumStored else it.qty * it.price
                                }
                            if (it.qty != 0.0) lineAmt / it.qty else it.price
                        } else null
                        VatItemRow(it, focusCtx, scope, canEdit, displayPrice = displayPrice, pricePlaceholder = costPlaceholder(it),
                            onDelete = { items.removeAt(idx) },
                            onQtyChange = { q -> items[idx] = it.copy(qty = q) },
                            onPriceChange = { p -> items[idx] = it.copy(price = p) },
                            // Rời ô giá → so NGAY với giá vốn cache. Bị chặn → trả giá cũ để ô hiển
                            // thị hoàn nguyên, không gửi giá xấu lên server.
                            onPriceCommit = { prev, newPrice ->
                                val bad = if (blockPrice) localPriceIssue(it.copy(price = newPrice)) else null
                                if (bad != null) {
                                    Toast.makeText(
                                        context,
                                        "Chặn giá thấp: ${bad.itemName} bán ${fmtPriceVat(bad.salePrice)} < giá nhập ${fmtPriceVat(bad.costPrice)}. Đã hoàn về giá cũ.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    items[idx] = it.copy(price = prev)
                                    prev
                                } else newPrice
                            },
                            onUnitChange = { u ->
                                val oldU = it.units.firstOrNull { x -> x.id == it.unitId }
                                val cfOld = oldU?.conversionFactor ?: 1.0; val cfNew = u.conversionFactor
                                if (cfOld > 0 && cfNew > 0 && cfOld != cfNew) items[idx] = it.copy(unitId = u.id, qty = round4(it.qty * cfOld / cfNew), price = round2(it.price * cfNew / cfOld))
                                else items[idx] = it.copy(unitId = u.id, price = u.price ?: it.price)
                            },
                        )
                    }
                    // Footer: nút +Thêm SP; "Tổng cộng" live CHỈ khi chưa có HĐ (đã có HĐ → tổng ở breakdown dưới).
                    if (canEdit || linkedVat != null) {
                        HorizontalDivider(color = AdminColors.Border, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (canEdit) OutlinedButton(
                                onClick = { if (selectedCustomer == null) Toast.makeText(context, "Chọn khách hàng trước", Toast.LENGTH_SHORT).show() else { pickerProductId = null; productPickerOpen = true } },
                                modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) { Text("+ Thêm SP", color = AdminColors.Primary, fontSize = 13.sp) }
                            // Chú thích chế độ đơn giá đang hiển thị (mờ, mảnh, nghiêng, căn phải) — chỉ khi đã có HĐ.
                            if (linkedVat != null) {
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (displayIncVat) "Đơn giá đã bao gồm VAT" else "Đơn giá chưa bao gồm VAT",
                                    color = AdminColors.TextMuted.copy(alpha = 0.7f), fontSize = 11.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light,
                                )
                            }
                            if (linkedVat == null) {
                                Spacer(Modifier.weight(1f))
                                val total = items.sumOf { it.qty * it.price }
                                Text("Tổng cộng: ", color = AdminColors.TextMuted, fontSize = 13.sp)
                                Text(fmtPriceVat(total), color = AdminColors.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(" đ", color = GOLD_VAT2, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light, fontSize = 12.sp)
                            }
                        }
                    }

                    // ĐÃ có HĐ: breakdown tiền + nút xem HĐ EI + cảnh báo thiếu XNT — đáy thẻ mặt hàng.
                    linkedVat?.let { vat ->
                        HorizontalDivider(color = AdminColors.Border, modifier = Modifier.padding(top = 12.dp))
                        Spacer(Modifier.height(8.dp))
                        DetailMoneyRow("Tổng tiền hàng (chưa thuế)", vat.subtotal)
                        vatRateRows(vat.items).forEach { (rate, amount) -> DetailMoneyRow("VAT (${fmtRateVat(rate)}%)", amount) }
                        HorizontalDivider(color = AdminColors.Border, modifier = Modifier.padding(top = 4.dp))
                        val totalNote = if (priceType == "exclusive") "Chưa bao gồm VAT" else "Đã bao gồm VAT"
                        DetailMoneyRow("Tổng cộng ($totalNote):", vat.total, emphasize = true)
                        Spacer(Modifier.height(12.dp))
                        if (signed) {
                            Button(
                                onClick = { openEiPreview() }, enabled = !eiLoadingPreview && vat.eiLookupCode != null,
                                colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary), modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (eiLoadingPreview) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                else Text("Xem HĐ trên EasyInvoice")
                            }
                            Text("Hóa đơn đã phát hành — chỉ xem, không sửa/đồng bộ/xóa.", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
                        } else {
                            Button(
                                onClick = { openPreview() }, enabled = !saving,
                                colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary), modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                else Text("Xem HĐ EI (ký phát hành)")
                            }
                            if (cardPriceIssues.isNotEmpty()) PriceIssueBox(cardPriceIssues, priceType, Modifier.fillMaxWidth().padding(top = 10.dp))
                            if (noCostItems.isNotEmpty()) NoCostBox(noCostItems, Modifier.fillMaxWidth().padding(top = 10.dp))
                            if (cardShortages.isNotEmpty()) ShortageBox(cardShortages, Modifier.fillMaxWidth().padding(top = 10.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ===== Card Hóa đơn VAT (tạo mới) — CHỈ khi CHƯA có HĐ nháp. Đã có HĐ → định danh ở trên, tổng tiền ở đáy thẻ mặt hàng. =====
                if (linkedVat == null) {
                    Card("") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Hóa đơn VAT", color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            PoNumberField(poNumber, canEdit, Modifier.weight(1f)) { poNumber = it }
                            if (canEdit) Text(
                                "+ Thêm đơn vị", color = Color.White, fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(0.5.dp, AdminColors.Border, RoundedCornerShape(8.dp))
                                    .clickable(enabled = selectedCustomer != null) { addVatOpen = true }.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        HorizontalDivider(color = AdminColors.Border, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(8.dp))
                        PriceTypeDropdown(priceType, canEdit) { priceType = it }
                        Spacer(Modifier.height(10.dp))
                        Text("Xuất HĐ cho (đơn vị mua)", color = AdminColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        VatInfoDropdown(vatInfos, selectedVatInfoId, canEdit) { selectedVatInfoId = it }
                        if (selectedCustomer != null && vatInfos.isEmpty()) Text("⚠ Khách chưa có MST — nhấn \"+ Thêm đơn vị\".", color = Color(0xFFE2A03F), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(12.dp))
                        // Giá xuất < giá nhập — cảnh báo ngay khi sửa giá, chưa cần tạo HĐ nháp.
                        if (cardPriceIssues.isNotEmpty()) PriceIssueBox(cardPriceIssues, priceType, Modifier.fillMaxWidth().padding(bottom = 10.dp))
                        if (noCostItems.isNotEmpty()) NoCostBox(noCostItems, Modifier.fillMaxWidth().padding(bottom = 10.dp))
                        Button(
                            onClick = { openPreview() }, enabled = canEdit && !saving && selectedCustomer != null && items.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary), modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("Xem trước HĐ")
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }

        // Pickers (overlay z-stack)
        if (customerPickerOpen) CustomerPicker(
            onPick = { c ->
                val changed = selectedCustomer?.id != c.id
                selectedCustomer = c; customerPickerOpen = false
                scope.launch { loadVatInfo(c.id, autoSelect = true) }
                scope.launch { suggested = try { container.vapi.recentProducts(c.id, 5).data ?: emptyList() } catch (_: Exception) { emptyList() } }
                if (changed) scope.launch { repriceItemsFor(c) }
            },
            onClose = { customerPickerOpen = false },
        )
        if (productPickerOpen && selectedCustomer != null) VariantPicker(
            warehouseId = null, query = pickerQuery, onQueryChange = { pickerQuery = it; pickerProductId = null }, productId = pickerProductId,
            suggested = suggested, selectedIds = items.map { it.variantId }.toSet(),
            onPick = { v -> addVariant(v); productPickerOpen = false }, onClose = { productPickerOpen = false },
        )
        if (addVatOpen) AddVatInfoOverlay(
            onClose = { addVatOpen = false },
            onAdd = { req ->
                val cust = selectedCustomer ?: return@AddVatInfoOverlay
                scope.launch {
                    try {
                        val v = container.vapi.attachCustomerVatInfo(cust.id, req).data
                        loadVatInfo(cust.id, autoSelect = false); selectedVatInfoId = v?.id; addVatOpen = false
                        Toast.makeText(context, "Đã thêm đơn vị mua", Toast.LENGTH_SHORT).show()
                        if (linkedVat != null) onUpdateDraft()   // HĐ nháp EI đang có → đẩy đơn vị mới lên EI
                    } catch (_: Exception) { Toast.makeText(context, "Thêm đơn vị mua thất bại", Toast.LENGTH_SHORT).show() }
                }
            },
        )

        // Preview overlay (ảnh HĐ zoom/pan + Tạo nháp EI / Ký phát hành + Copy + Share)
        if (previewOpen) VatPreviewOverlay(
            loading = previewLoading, pages = previewPages, shortages = shortages,
            hasLinkedVat = linkedVat != null, signing = signing, creatingDraft = creatingDraft, copyingImage = copyingImage, sharingImage = sharingImage,
            onClose = { if (!signing && !creatingDraft) previewOpen = false },
            onCreateDraft = { doCreateDraft() }, onSign = { doSign() }, onCopy = { copyImage() }, onShare = { shareImage() },
        )

        // Xem HĐ đã phát hành trên EI (ảnh full-screen zoom/pan + Tải file + Chia sẻ ảnh).
        eiPreviewUrl?.let { url -> BitmapZoomViewer(url, onClose = { eiPreviewUrl = null }, downloadName = linkedVat?.let { vatFileName(it) }) }

        // Xác nhận xoá HĐ (có HĐ nháp EI → kiểm tra EasyInvoice trước; đã ký thì không tới đây).
        if (confirmDeleteVat) VatFormConfirmOverlay(
            title = "Xoá hóa đơn này?",
            message = if (linkedVat != null)
                "Hệ thống kiểm tra trên EasyInvoice trước; chỉ khi HĐ đang là nháp và cho phép xoá thì mới xoá ở cả EasyInvoice và admin. Không hoàn tác được."
            else "Xoá đơn HĐ VAT nháp này. Không hoàn tác được.",
            onCancel = { confirmDeleteVat = false },
            onConfirm = { confirmDeleteVat = false; doDeleteVat() },
        )

        // Menu HĐ nháp: đổi đơn vị mua / tên người mua / hình thức giá → push lại EI qua onUpdateDraft.
        if (unitPickerOpen) VatUnitPickerOverlay(
            list = vatInfos, selectedId = selectedVatInfoId,
            onPick = { unitPickerOpen = false; selectedVatInfoId = it; onUpdateDraft() },
            onAddNew = { unitPickerOpen = false; addVatOpen = true },
            onClose = { unitPickerOpen = false },
        )
        if (buyerNameOpen) VatBuyerNameOverlay(
            initial = buyerName,
            onSave = { buyerNameOpen = false; buyerName = it; onUpdateDraft() },
            onClose = { buyerNameOpen = false },
        )
        if (pricePickerOpen) VatPricePickerOverlay(
            current = priceType,
            onPick = { pricePickerOpen = false; priceType = it; onUpdateDraft() },
            onClose = { pricePickerOpen = false },
        )

        // Date picker ngày HĐ
        if (datePickerOpen) {
            val dp = rememberDatePickerState(initialSelectedDateMillis = orderDateMs)
            MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text, primary = AdminColors.Primary, onPrimary = Color.White)) {
                DatePickerDialog(
                    onDismissRequest = { datePickerOpen = false },
                    confirmButton = { TextButton(onClick = { dp.selectedDateMillis?.let { orderDateMs = it }; datePickerOpen = false }) { Text("OK", color = AdminColors.Primary) } },
                    dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text("Huỷ", color = AdminColors.TextMuted) } },
                    colors = DatePickerDefaults.colors(containerColor = AdminColors.Card),
                ) { DatePicker(state = dp) }
            }
        }

        // Gate 3 kiểm tra trước phát hành chặn cứng → dialog liệt kê chi tiết.
        if (vatBlocked != null) {
            MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text, primary = AdminColors.Primary, onPrimary = Color.White)) {
                AlertDialog(
                    onDismissRequest = { vatBlocked = null },
                    confirmButton = { TextButton(onClick = { vatBlocked = null }) { Text("Đã hiểu", color = AdminColors.Primary) } },
                    title = { Text("Chưa thể phát hành hóa đơn", color = AdminColors.Text) },
                    text = { Text(vatBlocked ?: "", color = AdminColors.Text, fontSize = 13.sp) },
                    containerColor = AdminColors.Card,
                )
            }
        }
    }
}

/** Overlay chọn đơn vị mua (vat_info) cho HĐ nháp — radio list + "+ Thêm đơn vị". */
@Composable
private fun VatUnitPickerOverlay(list: List<VatInfoDto>, selectedId: Long?, onPick: (Long) -> Unit, onAddNew: () -> Unit, onClose: () -> Unit) {
    fun label(v: VatInfoDto) = "${v.shortName ?: v.legalName}${v.taxCode?.let { " · $it" } ?: ""}"
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(16.dp).clickable(enabled = false) {},
        ) {
            Text("Đơn vị mua trên HĐ", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (list.isEmpty()) Text("Khách chưa có đơn vị mua nào.", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            list.forEach { v ->
                val sel = v.id == selectedId
                Text(label(v), color = if (sel) AdminColors.Primary else AdminColors.Text, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) AdminColors.Primary.copy(alpha = 0.12f) else AdminColors.Bg)
                        .clickable { onPick(v.id) }.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            Text("+ Thêm đơn vị mua", color = AdminColors.Primary, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(8.dp))
                    .border(0.5.dp, AdminColors.Border, RoundedCornerShape(8.dp)).clickable { onAddNew() }.padding(vertical = 10.dp),
                textAlign = TextAlign.Center)
        }
    }
}

/** Overlay sửa tên người mua ghi trên HĐ. */
@Composable
private fun VatBuyerNameOverlay(initial: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(16.dp).clickable(enabled = false) {},
        ) {
            Text("Tên người mua trên HĐ", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(AdminColors.Primary),
                    decorationBox = { inner -> if (text.isEmpty()) Text("Để trống = dùng tên đơn vị mua", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Huỷ", color = AdminColors.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).clickable { onClose() }.padding(vertical = 10.dp))
                Text("Lưu", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Primary).clickable { onSave(text) }.padding(vertical = 10.dp))
            }
        }
    }
}

/** Overlay đổi hình thức giá HĐ: đã gồm thuế (inclusive) / chưa gồm thuế (exclusive). */
@Composable
private fun VatPricePickerOverlay(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(16.dp).clickable(enabled = false) {},
        ) {
            Text("Đổi hình thức giá", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            listOf("inclusive" to "Giá đã gồm thuế", "exclusive" to "Giá chưa gồm thuế").forEach { (k, lbl) ->
                val sel = current == k
                Text((if (sel) "✓ " else "") + lbl, color = if (sel) AdminColors.Primary else AdminColors.Text, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) AdminColors.Primary.copy(alpha = 0.12f) else AdminColors.Bg)
                        .clickable { onPick(k) }.padding(horizontal = 12.dp, vertical = 12.dp))
            }
        }
    }
}

/** Overlay xác nhận (Box-overlay, giữ ẩn nav bar) — dùng cho menu 3 chấm header. */
@Composable
private fun VatFormConfirmOverlay(title: String, message: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onCancel)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(20.dp).clickable(enabled = false) {},
        ) {
            Text(title, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(message, color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Huỷ", color = AdminColors.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).clickable { onCancel() }.padding(vertical = 10.dp))
                Text("Xoá", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Danger).clickable { onConfirm() }.padding(vertical = 10.dp))
            }
        }
    }
}

private val WARN_VAT = Color(0xFFE2A03F)

/** Format 422 VAT_ISSUE_BLOCKED (errors=categories) → text nhiều dòng, chỉ nhóm đang chặn cứng. */
private fun vatBlockedText(errors: org.json.JSONObject?): String {
    if (errors == null) return "Vi phạm điều kiện kiểm tra trước phát hành."
    val nf = java.text.NumberFormat.getInstance(java.util.Locale("vi"))
    fun m(v: Double) = nf.format(v.toLong())
    fun items(key: String) = errors.optJSONObject(key)?.takeIf { it.optBoolean("block") }?.optJSONArray("items")
    val out = StringBuilder()
    items("stock")?.let { arr ->
        out.append("• Thiếu tồn XNT:\n")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("unit").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            out.append("   – ${o.optString("item_name")}$u: thiếu ${m(o.optDouble("shortage_qty"))} (còn ${m(o.optDouble("available_qty"))}/${m(o.optDouble("requested_qty"))})\n")
        }
    }
    items("price")?.let { arr ->
        out.append("• Giá xuất < giá nhập (FIFO HĐ VAT):\n")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("unit").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            out.append("   – ${o.optString("item_name")}$u: bán ${m(o.optDouble("sale_price"))} < vốn ${m(o.optDouble("cost_price"))} (lỗ ${m(o.optDouble("diff"))})\n")
        }
    }
    items("unit")?.let { arr ->
        out.append("• Lệch đơn vị:\n")
        for (i in 0 until arr.length()) out.append("   – ${arr.optString(i)}\n")
    }
    return out.toString().trimEnd().ifBlank { "Vi phạm điều kiện kiểm tra trước phát hành." }
}

/** Text thiếu tồn để copy/share (gộp mọi HĐ nháp) — mẫu: "N. Tên (Đơn vị)\n# thiếu (Đơn vị)". */
private fun shortageText(list: List<VatShortageDto>): String =
    "Thiếu tồn kho HĐ nháp chờ ký:\n" + list.mapIndexed { i, s ->
        val u = s.unit?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        "${i + 1}. ${s.itemName}$u\n# ${trimQty(s.shortageQty)}$u"
    }.joinToString("\n")

/** Box cảnh báo giá xuất < giá nhập — ĐỎ, đặt TRÊN box thiếu tồn. So theo phương thức giá. */
@Composable
private fun PriceIssueBox(issues: List<VatPriceIssueDto>, priceType: String, modifier: Modifier = Modifier) {
    val nf = remember { java.text.NumberFormat.getInstance(java.util.Locale("vi")) }
    val red = Color(0xFFEB5757)
    Column(modifier.clip(RoundedCornerShape(8.dp)).border(0.6.dp, red.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).background(red.copy(alpha = 0.08f)).padding(8.dp)) {
        Text(
            "⚠ Giá xuất thấp hơn giá nhập (${if (priceType == "inclusive") "đã gồm VAT" else "chưa gồm VAT"})",
            color = red, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        issues.forEach { p ->
            val u = p.unit?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            Column(Modifier.padding(top = 3.dp)) {
                Text("${p.itemName}$u", color = AdminColors.Text, fontSize = 11.sp)
                Text(
                    "Nhập ${nf.format(p.costPrice.toLong())} > ${nf.format(p.salePrice.toLong())} Bán ( Lỗ ${nf.format(p.diff.toLong())} )",
                    color = AdminColors.Text, fontSize = 11.sp,
                )
            }
        }
    }
}

/** Box "chưa có giá nhập" — VÀNG: mặt hàng chưa từng nhập HĐ VAT → không so được giá (không chặn). */
@Composable
private fun NoCostBox(names: List<String>, modifier: Modifier = Modifier) {
    val amber = Color(0xFFF2C94C)
    Column(modifier.clip(RoundedCornerShape(8.dp)).border(0.6.dp, amber.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).background(amber.copy(alpha = 0.08f)).padding(8.dp)) {
        Text("⚠ Chưa có giá nhập (thế giới VAT)", color = amber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text("${names.joinToString(", ")} — không kiểm tra được giá xuất.", color = AdminColors.TextMuted, fontSize = 11.sp)
    }
}

/**
 * Box cảnh báo thiếu XNT — làm giống web: tiêu đề "Thiếu tồn kho — gộp mọi HĐ nháp (N SP)"
 * + nút Copy · Chia sẻ; mỗi dòng "Tên (Đơn vị): cần X, còn Y, thiếu Z (Đơn vị)".
 */
@Composable
private fun ShortageBox(shortages: List<VatShortageDto>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun copy() {
        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("Thiếu tồn", shortageText(shortages)))
        Toast.makeText(context, "Đã copy danh sách thiếu tồn", Toast.LENGTH_SHORT).show()
    }
    fun share() {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, shortageText(shortages)) }
        runCatching { context.startActivity(android.content.Intent.createChooser(send, "Chia sẻ thiếu tồn").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    Column(modifier.clip(RoundedCornerShape(8.dp)).border(0.5.dp, WARN_VAT.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).background(WARN_VAT.copy(alpha = 0.06f)).padding(8.dp)) {
        // Dòng trên cùng: text cảnh báo (trái) + nút Copy · Chia sẻ (phải).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("⚠ Thiếu tồn kho — các HĐ nháp", color = WARN_VAT, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("Copy", color = Color.White.copy(alpha = 0.82f), fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(0.5.dp, AdminColors.Border, RoundedCornerShape(8.dp)).clickable { copy() }.padding(horizontal = 10.dp, vertical = 3.dp))
            Spacer(Modifier.width(8.dp))
            Text("Chia sẻ", color = Color.White.copy(alpha = 0.82f), fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(0.5.dp, AdminColors.Border, RoundedCornerShape(8.dp)).clickable { share() }.padding(horizontal = 10.dp, vertical = 3.dp))
        }
        Spacer(Modifier.height(6.dp))
        shortages.forEach { s ->
            val u = s.unit?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            Row(Modifier.padding(top = 1.dp)) {
                Text("•  ", color = AdminColors.TextMuted, fontSize = 11.sp)
                Text(buildAnnotatedStringShortage(s.itemName, u, trimQty(s.requestedQty), trimQty(s.availableQty), trimQty(s.shortageQty)), fontSize = 11.sp)
            }
        }
    }
}

private fun buildAnnotatedStringShortage(name: String, unit: String, need: String, have: String, short: String) =
    androidx.compose.ui.text.buildAnnotatedString {
        withStyle(androidx.compose.ui.text.SpanStyle(color = AdminColors.Text)) { append(name) }
        withStyle(androidx.compose.ui.text.SpanStyle(color = AdminColors.TextMuted)) { append(unit) }
        withStyle(androidx.compose.ui.text.SpanStyle(color = AdminColors.TextMuted)) { append(": cần ") }
        withStyle(androidx.compose.ui.text.SpanStyle(color = AdminColors.Text, fontWeight = FontWeight.Medium)) { append(need) }
        withStyle(androidx.compose.ui.text.SpanStyle(color = AdminColors.TextMuted)) { append(", còn ") }
        withStyle(androidx.compose.ui.text.SpanStyle(color = AdminColors.Text, fontWeight = FontWeight.Medium)) { append(have) }
        withStyle(androidx.compose.ui.text.SpanStyle(color = AdminColors.TextMuted)) { append(", ") }
        withStyle(androidx.compose.ui.text.SpanStyle(color = WARN_VAT, fontWeight = FontWeight.Medium)) { append("thiếu $short") }
        withStyle(androidx.compose.ui.text.SpanStyle(color = AdminColors.TextMuted)) { append(unit) }
    }

/** 1 dòng chi tiết HĐ: nhãn trái (xám) · giá trị phải. */
@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false, valueColor: Color = AdminColors.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = if (mono) FontWeight.Medium else FontWeight.Normal)
    }
}

private val nfVnd = NumberFormat.getNumberInstance(Locale("vi"))
/** Thuế suất hiển thị: 5.0 → "5", 4.5 → "4.5". */
private fun fmtRateVat(rate: Double): String = if (rate == Math.floor(rate)) rate.toLong().toString() else rate.toString()
/** Gộp VAT theo thuế suất từ items HĐ (vat_breakdown BE để null) — sắp tăng dần theo suất. */
private fun vatRateRows(items: List<VatOutputItemDto>): List<Pair<Double, Double>> {
    val map = LinkedHashMap<Double, Double>()
    items.forEach { map[it.vatRate] = (map[it.vatRate] ?: 0.0) + it.vatAmount }
    return map.entries.sortedBy { it.key }.map { it.key to it.value }
}

/** Dòng tiền HĐ: label trái · số tiền phải (VND làm tròn). emphasize = dòng Tổng cộng. */
@Composable
private fun DetailMoneyRow(label: String, amount: Double, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(nfVnd.format(Math.round(amount)), color = if (emphasize) Color(0xFF2DD4BF) else AdminColors.Text,
            fontSize = if (emphasize) 18.sp else 12.sp, fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal)
        Text(" đ", color = GOLD_VAT2, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light, fontSize = if (emphasize) 13.sp else 11.sp)
    }
}

private val GOLD_VAT2 = Color(0xFFD4AF37)

/**
 * Item row HĐ VAT — layout ĐÚNG như [ItemRow] đơn bán (thumb 59dp + qty · đơn vị · × · giá · =
 * · thành tiền + swipe trái xoá), chỉ khác: đơn giá THẬP PHÂN (fmtPriceVat/parsePriceVat).
 * qty/price re-init khi đổi đơn vị (key theo unitId).
 */
@Composable
private fun VatItemRow(
    draft: OrderItemDraft, focusCtx: FocusCenterCtx, scope: kotlinx.coroutines.CoroutineScope, canEdit: Boolean, displayPrice: Double? = null,
    onDelete: () -> Unit, onQtyChange: (Double) -> Unit, onPriceChange: (Double) -> Unit, onUnitChange: (VariantUnitDto) -> Unit,
    // Rời ô giá: (giá cũ, giá mới) → trả GIÁ ĐƯỢC CHẤP NHẬN. Bị chặn (giá < giá vốn + block_price)
    // → trả giá cũ, ô hiển thị hoàn nguyên theo.
    onPriceCommit: (prevPrice: Double, newPrice: Double) -> Double = { _, new -> new },
    // Gợi ý khi ô giá trống: giá nhập (giá vốn) quy về đơn vị đang chọn.
    pricePlaceholder: String? = null,
) {
    var offsetX by remember(draft.variantId) { mutableStateOf(0f) }
    var rowWidth by remember { mutableStateOf(1f) }
    Box(
        Modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width.toFloat() }
            .then(if (canEdit) Modifier.pointerInput(draft.variantId) {
                detectHorizontalDragGestures(onDragEnd = { if (-offsetX > rowWidth / 3f) onDelete() else offsetX = 0f }) { _, dx -> offsetX = (offsetX + dx).coerceAtMost(0f) }
            } else Modifier),
    ) {
        Box(Modifier.matchParentSize().background(AdminColors.Danger.copy(alpha = 0.25f)), contentAlignment = Alignment.CenterEnd) {
            Text("Xoá", color = AdminColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(end = 16.dp))
        }
        Row(
            Modifier.fillMaxWidth().offset { IntOffset(offsetX.toInt(), 0) }.background(AdminColors.Card).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (draft.imageUrl != null) AsyncImage(model = draft.imageUrl, contentDescription = null, modifier = Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)))
            else Box(Modifier.size(59.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(draft.variantName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var qtyTfv by remember(draft.variantId, draft.unitId) { mutableStateOf(TextFieldValue(trimQty(draft.qty))) }
                    var qtyFocused by remember(draft.variantId, draft.unitId) { mutableStateOf(false) }
                    Box {
                        NumEditHint(qtyFocused, qtyTfv.text)
                        BasicTextField(
                            value = qtyTfv, onValueChange = { raw -> val f = raw.text.filter { c -> c.isDigit() || c == '.' }; qtyTfv = if (f == raw.text) raw else TextFieldValue(f, TextRange(f.length)); onQtyChange(f.toDoubleOrNull() ?: 0.0) },
                            readOnly = !canEdit, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                            textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(AdminColors.Primary),
                            modifier = Modifier.width(40.dp).centerOnFocus(focusCtx, scope, "vqty-${draft.variantId}-${draft.unitId}")
                                .onFocusChanged { st -> if (st.isFocused) { qtyFocused = true; scope.launch { delay(60); qtyTfv = qtyTfv.copy(selection = TextRange(0, qtyTfv.text.length)) } } else qtyFocused = false },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    UnitDropdown(draft.units, draft.unitId, canEdit, onUnitChange)
                    Spacer(Modifier.weight(1f))
                    Text("×", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (displayPrice != null) {
                        // Hiển thị đơn giá NET (đọc-only) — chế độ "chưa VAT" của bảng.
                        Text(fmtPriceVat(displayPrice), color = AdminColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 60.dp))
                    } else {
                        var priceTfv by remember(draft.variantId, draft.unitId) { mutableStateOf(TextFieldValue(if (draft.price > 0) fmtPriceVat(draft.price) else "")) }
                        var priceFocused by remember(draft.variantId, draft.unitId) { mutableStateOf(false) }
                        var priceBefore by remember(draft.variantId, draft.unitId) { mutableStateOf(draft.price) }
                        Box(contentAlignment = Alignment.Center) {
                            // Hint hiện SỐ SẼ LƯU (đã bung theo nghìn): gõ 850 → hint 850.000.
                            NumEditHint(priceFocused, priceTfv.text.takeIf { it.isNotEmpty() }?.let { fmtPriceVat(expandVatPrice(parsePriceVat(it))) })
                            // Chưa nhập giá → gợi ý GIÁ NHẬP (giá vốn) theo đơn vị đang chọn.
                            if (priceTfv.text.isEmpty() && pricePlaceholder != null) Text(
                                pricePlaceholder, color = AdminColors.TextMuted.copy(alpha = 0.5f),
                                fontSize = 15.sp, textAlign = TextAlign.Center,
                            )
                            BasicTextField(
                                value = priceTfv, onValueChange = { raw -> priceTfv = raw; onPriceChange(parsePriceVat(raw.text)) },
                                readOnly = !canEdit, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                                textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                                cursorBrush = SolidColor(AdminColors.Primary),
                                modifier = Modifier.widthIn(min = 60.dp).centerOnFocus(focusCtx, scope, "vprice-${draft.variantId}-${draft.unitId}")
                                    .onFocusChanged { st ->
                                        if (st.isFocused) {
                                            priceFocused = true
                                            priceBefore = draft.price      // giá trước khi sửa (để hoàn nguyên)
                                            scope.launch { delay(60); priceTfv = priceTfv.copy(selection = TextRange(0, priceTfv.text.length)) }
                                        } else {
                                            priceFocused = false
                                            // Rời ô → chốt giá đã bung, hiển thị lại theo định dạng vi-VN.
                                            val expanded = expandVatPrice(parsePriceVat(priceTfv.text))
                                            onPriceChange(expanded)
                                            val accepted = if (expanded != priceBefore) onPriceCommit(priceBefore, expanded) else expanded
                                            priceTfv = TextFieldValue(if (accepted > 0) fmtPriceVat(accepted) else "")
                                            if (accepted != expanded) onPriceChange(accepted)
                                        }
                                    },
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("=", color = AdminColors.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(fmtPriceVat(draft.qty * (displayPrice ?: draft.price)), color = AdminColors.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Dropdown hình thức giá (giống web NSelect): Giá đã gồm thuế | Giá chưa gồm thuế. */
@Composable
private fun PriceTypeDropdown(value: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val opts = listOf("inclusive" to "Giá đã gồm thuế", "exclusive" to "Giá chưa gồm thuế")
    val curLabel = opts.firstOrNull { it.first == value }?.second ?: "Giá đã gồm thuế"
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                .clickable(enabled = enabled) { open = true }.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(curLabel, color = AdminColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (enabled) Text("▾", color = AdminColors.TextMuted, fontSize = 12.sp)
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                opts.forEach { (k, lbl) ->
                    DropdownMenuItem(text = { Text(lbl, color = if (k == value) AdminColors.Primary else AdminColors.Text) }, onClick = { onSelect(k); open = false }, colors = MenuDefaults.itemColors(textColor = AdminColors.Text))
                }
            }
        }
    }
}

/** Ô chọn đơn vị mua (vat_info) — CẢ Ô click mở dropdown (không chỉ chữ). Full width. */
@Composable
private fun VatInfoDropdown(list: List<VatInfoDto>, selectedId: Long?, enabled: Boolean, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = list.firstOrNull { it.id == selectedId }
    fun label(v: VatInfoDto?) = v?.let { "${it.shortName ?: it.legalName}${it.taxCode?.let { c -> " · $c" } ?: ""}" } ?: "Chọn đơn vị mua / MST"
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                .clickable(enabled = enabled) { open = true }.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(cur), color = if (cur == null) AdminColors.TextMuted else AdminColors.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (enabled) Text("▾", color = AdminColors.TextMuted, fontSize = 12.sp)
        }
        MaterialTheme(colorScheme = darkColorScheme(surface = AdminColors.Card, onSurface = AdminColors.Text)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (list.isEmpty()) DropdownMenuItem(text = { Text("Khách chưa có đơn vị mua", color = AdminColors.TextMuted) }, onClick = { open = false })
                list.forEach { v ->
                    DropdownMenuItem(text = { Text(label(v), color = if (v.id == selectedId) AdminColors.Primary else AdminColors.Text) }, onClick = { onSelect(v.id); open = false }, colors = MenuDefaults.itemColors(textColor = AdminColors.Text))
                }
            }
        }
    }
}

/**
 * Overlay thêm đơn vị mua (vat_info) — 2 tab như admin: Công ty/Hộ KD (business) | Cá nhân (personal).
 * Business: nhập MST → nút "Tra cứu" gọi /v1/lookup/tax-code → tự fill tên + tên ngắn + địa chỉ.
 * Personal: CCCD 12 số = MST cá nhân, nhập tay họ tên (không có provider tra cứu).
 */
@Composable
private fun AddVatInfoOverlay(onClose: () -> Unit, onAdd: (AttachVatInfoReq) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("business") }
    var taxCode by remember { mutableStateOf("") }
    var legalName by remember { mutableStateOf("") }
    var shortName by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var looking by remember { mutableStateOf(false) }
    val business = type == "business"

    fun lookup() {
        if (taxCode.isBlank() || looking) return
        scope.launch {
            looking = true
            try {
                val d = container.vapi.lookupTaxCode(taxCode.trim()).data
                if (d?.legalName.isNullOrBlank()) Toast.makeText(context, "Không có thông tin cho MST $taxCode", Toast.LENGTH_SHORT).show()
                else { legalName = d.legalName ?: ""; shortName = d.shortName ?: ""; address = d.address ?: ""; Toast.makeText(context, "Đã lấy: ${d.legalName}", Toast.LENGTH_SHORT).show() }
            } catch (_: Exception) { Toast.makeText(context, "Lỗi tra cứu MST", Toast.LENGTH_SHORT).show() }
            finally { looking = false }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(20.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(16.dp).clickable(enabled = false) {},
        ) {
            Text("Thêm đơn vị mua", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            // Tab business / personal
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("business" to "Công ty / Hộ KD", "personal" to "Cá nhân").forEach { (k, lbl) ->
                    val sel = type == k
                    Text(lbl, color = if (sel) AdminColors.Primary else AdminColors.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (sel) AdminColors.Primary.copy(alpha = 0.12f) else AdminColors.Bg)
                            .clickable { type = k }.padding(vertical = 8.dp))
                }
            }
            // MST / CCCD + nút tra cứu (chỉ business)
            Text(if (business) "Mã số thuế" else "Số CCCD (12 số)", color = AdminColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 10.dp)) {
                    BasicTextField(
                        value = taxCode, onValueChange = { taxCode = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(AdminColors.Primary),
                        decorationBox = { inner -> if (taxCode.isEmpty()) Text(if (business) "0312345678" else "079123456789", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (business) Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(AdminColors.Primary.copy(alpha = 0.15f)).clickable(enabled = !looking && taxCode.isNotBlank()) { lookup() }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (looking) CircularProgressIndicator(Modifier.size(14.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                    else Text("Tra cứu", color = AdminColors.Primary, fontSize = 13.sp)
                }
            }
            // Tên công ty / Họ tên
            Text(if (business) "Tên công ty" else "Họ tên", color = AdminColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = legalName, onValueChange = { legalName = it }, singleLine = true,
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(AdminColors.Primary),
                    decorationBox = { inner -> if (legalName.isEmpty()) Text(if (business) "Tên công ty / hộ KD" else "Họ và tên", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (address.isNotBlank()) Text(address, color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Button(
                onClick = { if (taxCode.isNotBlank() && legalName.isNotBlank()) onAdd(AttachVatInfoReq(type = type, taxCode = taxCode.trim(), legalName = legalName.trim(), shortName = shortName.ifBlank { null }, address = address.ifBlank { null })) },
                colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary), modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Thêm") }
        }
    }
}

/**
 * Overlay xem trước HĐ: ảnh từng trang (tap → zoom/pan full-screen) + cảnh báo thiếu tồn +
 * 2 nút footer: [Tạo nháp trên EI]/[Ký phát hành] (đổi theo hasLinkedVat) | [Copy ảnh HĐ].
 */
@Composable
private fun VatPreviewOverlay(
    loading: Boolean, pages: List<String>, shortages: List<VatShortageDto>,
    hasLinkedVat: Boolean, signing: Boolean, creatingDraft: Boolean, copyingImage: Boolean, sharingImage: Boolean,
    onClose: () -> Unit, onCreateDraft: () -> Unit, onSign: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit,
) {
    var zoomPage by remember { mutableStateOf<String?>(null) }
    // Tiêu thụ vuốt ngang → chặn AccountingScreen đổi tab / form đóng khi đang xem preview.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).pointerInput(Unit) { detectHorizontalDragGestures { _, _ -> } }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Close, "Đóng", tint = Color.White, modifier = Modifier.clickable { onClose() }.padding(8.dp))
                Text("Xem trước hóa đơn", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                if (pages.size >= 2) Text("${pages.size} trang", color = Color(0xFFE2A03F), fontSize = 12.sp)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
                else if (pages.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Không có trang nào", color = Color.White) }
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    if (shortages.isNotEmpty()) ShortageBox(shortages, Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    Text("Chạm ảnh để phóng to", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                    pages.forEach { p ->
                        Base64Image(p, Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { zoomPage = p })
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            Column(Modifier.fillMaxWidth().background(AdminColors.Card).navigationBarsPadding().padding(12.dp)) {
                // Nút chính: chưa có nháp EI → Tạo nháp; đã có → Ký phát hành (KHÓA nếu thiếu XNT).
                val busy = signing || creatingDraft
                val blockedByStock = hasLinkedVat && shortages.isNotEmpty()
                Button(
                    onClick = { if (hasLinkedVat) onSign() else onCreateDraft() },
                    enabled = !busy && !loading && pages.isNotEmpty() && !blockedByStock,
                    colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary), modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(if (hasLinkedVat) (if (blockedByStock) "Thiếu tồn — không thể phát hành" else "Ký phát hành") else "Tạo nháp trên EI")
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (!copyingImage) onCopy() }, enabled = !copyingImage && pages.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        if (copyingImage) CircularProgressIndicator(Modifier.size(16.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                        else Text("Copy ảnh", color = AdminColors.Primary)
                    }
                    OutlinedButton(onClick = { if (!sharingImage) onShare() }, enabled = !sharingImage && pages.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        if (sharingImage) CircularProgressIndicator(Modifier.size(16.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                        else Text("Chia sẻ", color = AdminColors.Primary)
                    }
                }
            }
        }
    }
    // Full-screen zoom/pan 1 trang.
    zoomPage?.let { BitmapZoomViewer(it, onClose = { zoomPage = null }) }
}

/** Giải mã ảnh data-URL base64 → ImageBitmap (cache theo chuỗi). */
@Composable
private fun rememberBase64Bitmap(dataUrl: String) = remember(dataUrl) {
    runCatching {
        val b64 = dataUrl.substringAfter("base64,", dataUrl)
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun Base64Image(dataUrl: String, modifier: Modifier = Modifier) {
    val bmp = rememberBase64Bitmap(dataUrl)
    if (bmp != null) Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = modifier)
    else Box(modifier.height(120.dp).background(Color(0x22FFFFFF)), Alignment.Center) { Text("Không tải được trang", color = Color.White, fontSize = 12.sp) }
}

/**
 * Số PO của khách (orders.reference) — gõ ngay cạnh tiêu đề thẻ: "Hóa đơn VAT - PO12345".
 * Nguồn để ghép tên người mua khi đơn vị mua bật cờ buyer_name_with_po.
 */
@Composable
private fun PoNumberField(value: String, canEdit: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Row(modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (value.isNotBlank() || canEdit) {
            Text("- ", color = AdminColors.TextMuted, fontSize = 12.sp)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true, readOnly = !canEdit,
                textStyle = TextStyle(color = AdminColors.Primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(AdminColors.Primary),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text("Số PO", color = AdminColors.TextMuted, fontSize = 12.sp, fontStyle = FontStyle.Italic)
                    inner()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Xem 1 trang HĐ full-screen: pinch-zoom (1-8x) + pan + nút đóng.
 * downloadName != null (HĐ đã phát hành) → hiện thêm nút [Tải file] + [Chia sẻ ảnh].
 */
@Composable
private fun BitmapZoomViewer(dataUrl: String, onClose: () -> Unit, downloadName: String? = null) {
    val context = LocalContext.current
    val bmp = rememberBase64Bitmap(dataUrl)
    Box(Modifier.fillMaxSize().background(Color(0xF2000000)).pointerInput(Unit) { detectHorizontalDragGestures { _, _ -> } }) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        if (bmp != null) Image(
            bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.Center).fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale <= 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                    }
                },
        )
        Box(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(40.dp).clip(RoundedCornerShape(20.dp))
                .background(Color(0x33FFFFFF)).clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, "Đóng", tint = Color.White, modifier = Modifier.size(22.dp)) }
        if (downloadName != null) Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("⬇  Tải file", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(9999.dp)).background(AdminColors.Primary).clickable { saveImageToDownloads(context, dataUrl, downloadName) }.padding(horizontal = 18.dp, vertical = 10.dp))
            Text("↗  Chia sẻ", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(9999.dp)).background(Color(0x33FFFFFF)).clickable { shareImageFile(context, dataUrl, downloadName) }.padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

/** Slug tên (bỏ dấu, đ→d, gộp gạch) — khớp quy ước filename admin. */
private fun slugVat(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** Tên file HĐ (.png) theo quy ước admin: {đơn-vị}-{DD-MM-YYYY}-HD{số}. */
private fun vatFileName(vat: VatOutputInvoiceDto): String {
    val buyer = slugVat(vat.buyerName ?: "khach").ifBlank { "khach" }
    val num = vat.number?.trim()?.takeIf { it.isNotBlank() }?.let { "HD$it" } ?: "DRAFT"
    val date = vat.issueDate?.take(10)?.let { runCatching {
        java.text.SimpleDateFormat("dd-MM-yyyy", Locale.US).format(java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)!!)
    }.getOrNull() } ?: ""
    val base = listOf(buyer, date, num).filter { it.isNotBlank() }.joinToString("-")
    return base.replaceFirstChar { it.uppercase() } + ".png"
}

private fun decodeVatData(dataUrl: String): ByteArray = Base64.decode(dataUrl.substringAfter("base64,", dataUrl), Base64.DEFAULT)

/** Lưu ảnh HĐ vào thư mục Tải xuống (MediaStore, API 29+). */
private fun saveImageToDownloads(context: android.content.Context, dataUrl: String, name: String) {
    try {
        val bytes = decodeVatData(dataUrl)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("insert null")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(context, "Đã tải: $name", Toast.LENGTH_LONG).show()
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            java.io.File(dir, name).writeBytes(bytes)
            Toast.makeText(context, "Đã tải: $name", Toast.LENGTH_LONG).show()
        }
    } catch (_: Exception) {
        Toast.makeText(context, "Tải file thất bại", Toast.LENGTH_SHORT).show()
    }
}

/** Chia sẻ ảnh HĐ (PNG) qua chooser hệ thống, dùng tên file quy ước. */
private fun shareImageFile(context: android.content.Context, dataUrl: String, name: String) {
    try {
        val bytes = decodeVatData(dataUrl)
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, name); file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(send, "Chia sẻ hóa đơn").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, "Chia sẻ thất bại", Toast.LENGTH_SHORT).show()
    }
}
