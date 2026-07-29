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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.chat9.app.App
import vn.chat9.app.R
import vn.chat9.app.data.vapi.dto.CreateOrderItem
import vn.chat9.app.data.vapi.dto.CreateOrderRequest
import vn.chat9.app.data.vapi.dto.CustomerDto
import vn.chat9.app.data.vapi.dto.DeliveredItem
import vn.chat9.app.data.vapi.dto.FulfillRequest
import vn.chat9.app.data.vapi.dto.VariantSearchDto
import vn.chat9.app.ui.common.NumEditHint
import vn.chat9.app.ui.common.hasPermission
import vn.chat9.app.ui.explore.AdminColors as C
import vn.chat9.app.ui.modules.sale.CustomerPicker
import vn.chat9.app.ui.modules.sale.VariantSearchInline
import vn.chat9.app.ui.modules.sale.variantDisplay

/**
 * Đơn giao hàng NHANH (auto-save "chụp ảnh trước, giao sau"). Mirror web
 * WarehouseQuickDeliveryView. Đính ảnh → TỰ tạo đơn NHÁP (rỗng khách/hàng OK) +
 * upload ảnh; sửa khách/mặt hàng/ship/ghi chú (khi đã có nháp) → tự PUT cập nhật.
 * Nút "Giao hàng" (đủ khách + ≥1 hàng + ≥1 ảnh) → confirm + fulfill → về list.
 *
 * editOrderId != null → mở EDIT đơn nháp có sẵn (từ card đơn nháp ở list).
 */
private class QPhoto(val model: Any, var attachmentId: Long?) // model = Uri (local) | String url (server)

private class QRow(
    val variantId: Long,
    val unitId: Long,
    val productName: String,
    val variantPairs: List<Pair<String, String>>,   // (tên nhóm phân loại, giá trị)
    val variantFallback: String,                     // khi pairs rỗng
    val unitLabel: String,
    val image: String?,
    val stock: Double,
    val unitPrice: Double,
)

private val qMoneyFmt = java.text.NumberFormat.getInstance(java.util.Locale("vi"))
private fun qFmtMoney(n: Double): String = qMoneyFmt.format(Math.round(n))
private fun qMoneyToDouble(s: String): Double? = s.filter { it.isDigit() }.toDoubleOrNull()

/** Nhập tiền "theo nghìn" (mirror màn sale): thập phân/<1000 → ×1000; ≥4 chữ số → giữ. */
private fun qExpandMoney(raw: String): Double? {
    val cleaned = raw.filter { it.isDigit() || it == '.' || it == ',' }
    if (cleaned.isEmpty()) return null
    val sep = cleaned.count { it == '.' || it == ',' }
    val value = when {
        sep >= 2 -> cleaned.filter { it.isDigit() }.toDoubleOrNull() ?: return null
        sep == 1 -> (cleaned.replace(',', '.').toDoubleOrNull() ?: return null) * 1000
        else -> { val n = cleaned.toDoubleOrNull() ?: return null; if (n < 1000) n * 1000 else n }
    }
    if (value.isNaN() || value.isInfinite()) return null
    return Math.round(value).toDouble()
}

/** Dòng nhập tiền (ship/thu hộ) — tap→RAW+select-all+hint số đã bung, blur→bung format. Mirror sale ShipRow. */
@Composable
private fun QMoneyRow(label: String, value: String, focusCtx: FocusCenterCtx, scope: kotlinx.coroutines.CoroutineScope, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(0.42f))
        Text(":", color = C.TextMuted, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(0.58f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var tfv by remember(value) { mutableStateOf(TextFieldValue(value)) }
                var atFocus by remember { mutableStateOf<String?>(null) }
                var focused by remember { mutableStateOf(false) }
                var fieldY by remember { mutableStateOf(0f) }
                var fieldH by remember { mutableStateOf(0f) }
                Box(Modifier.weight(1f)) {
                    NumEditHint(focused, qExpandMoney(tfv.text)?.let { qFmtMoney(it) })
                    BasicTextField(
                        value = tfv,
                        onValueChange = { raw -> val f = raw.text.filter { c -> c.isDigit() || c == '.' || c == ',' }; tfv = if (f == raw.text) raw else TextFieldValue(f, TextRange(f.length)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = TextStyle(color = C.Text, fontSize = 14.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(C.Primary),
                        decorationBox = { inner -> Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.CenterEnd) { if (tfv.text.isEmpty()) Text("0", color = C.TextMuted, fontSize = 13.sp); inner() } },
                        modifier = Modifier.fillMaxWidth()
                            .onGloballyPositioned { fieldY = it.positionInWindow().y; fieldH = it.size.height.toFloat() }
                            .onFocusChanged { st ->
                                if (st.isFocused) {
                                    focused = true; atFocus = tfv.text
                                    tfv = TextFieldValue(qExpandMoney(tfv.text)?.takeIf { it > 0 }?.let { trimZeros(it) } ?: "0")   // ô = 0 → hiện "0" để select
                                    scope.launch { kotlinx.coroutines.delay(60); tfv = tfv.copy(selection = TextRange(0, tfv.text.length)) }
                                    scope.launch {
                                        kotlinx.coroutines.delay(280)
                                        val top = focusCtx.statusBarPx + focusCtx.topTabBarPx
                                        val bot = focusCtx.screenHeightPx - focusCtx.imeBottomState.value - focusCtx.confirmBtnState.value
                                        val d = fieldY - ((top + bot) / 2f - fieldH / 2f)
                                        if (d > 0f) runCatching { focusCtx.scrollState.animateScrollBy(d) }
                                    }
                                } else if (atFocus != null) {
                                    focused = false
                                    val v = qExpandMoney(tfv.text)
                                    tfv = TextFieldValue(if (v != null && v > 0) qFmtMoney(v) else "")
                                    onChange(tfv.text); atFocus = null
                                }
                            },
                    )
                }
                Text(" đ", color = C.TextMuted, fontSize = 11.sp)
            }
            HorizontalDivider(color = C.Border)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WarehouseQuickOrderScreen(
    editOrderId: Long?,
    warehouseId: Long?,
    warehouseName: String?,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val repo = container.warehouseRepo
    val scope = rememberCoroutineScope()

    // Chỉ tài khoản có quyền DUYỆT đơn (order.approve — admin) mới giao hàng luôn.
    // NV chỉ có quyền kho (thiếu order.approve) → chỉ LƯU NHÁP, đơn giữ trạng thái "Đợi duyệt".
    val canApprove = hasPermission("order.approve")

    var draftId by remember { mutableStateOf(editOrderId) }
    var draftCode by remember { mutableStateOf("") }
    var savingDraft by remember { mutableStateOf(false) }
    var savingManual by remember { mutableStateOf(false) }
    var delivering by remember { mutableStateOf(false) }
    var loadingEdit by remember { mutableStateOf(editOrderId != null) }
    val saveMutex = remember { Mutex() }

    var partyId by remember { mutableStateOf<Long?>(null) }
    var partyName by remember { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }

    val rows = remember { mutableStateListOf<QRow>() }
    val qtyByVariant = remember { mutableStateMapOf<Long, String>() }   // SL theo variantId (observable)
    val checkedByVariant = remember { mutableStateMapOf<Long, Boolean>() } // dấu tích kiểm (giống màn giao)
    var note by remember { mutableStateOf("") }
    var confirmDateMs by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var shipCustomer by remember { mutableStateOf("") }
    var shipCompany by remember { mutableStateOf("") }
    var codAmount by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<QPhoto>() }
    var viewerUrl by remember { mutableStateOf<String?>(null) }

    fun buildReq(items: List<CreateOrderItem>?) = CreateOrderRequest(
        type = "sale",
        partyType = "customer",
        partyId = partyId,
        warehouseId = warehouseId,
        status = "draft",
        orderedAt = confirmDateMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
        shippingFee = qMoneyToDouble(shipCustomer),
        actualShippingFee = qMoneyToDouble(shipCompany),
        codCollected = qMoneyToDouble(codAmount),
        notes = note.ifBlank { null },
        items = items,
    )

    // Tạo (lần đầu) hoặc cập nhật đơn nháp theo state hiện tại — serialize qua mutex.
    suspend fun doSave() {
        saveMutex.withLock {
            savingDraft = true
            try {
                val items = rows.filter { (qtyByVariant[it.variantId]?.toDoubleOrNull() ?: 0.0) > 0.0 }
                    .map { CreateOrderItem(it.variantId, it.unitId, qtyByVariant[it.variantId]!!.toDouble(), it.unitPrice) }
                val id = draftId
                if (id == null) {
                    val created = container.vapi.createOrder(buildReq(items)).data
                        ?: throw IllegalStateException("không tạo được nháp")
                    draftId = created.id
                    draftCode = created.code
                } else {
                    // update draft cho replace items → CHỈ gửi khi ≥1 (BE items min:1); rỗng → null (giữ nguyên).
                    container.vapi.updateOrder(id, buildReq(items.ifEmpty { null }))
                }
            } finally {
                savingDraft = false
            }
        }
    }
    fun autoSaveIfDraft() {
        if (draftId != null) scope.launch {
            try { doSave() } catch (e: Exception) { Toast.makeText(context, "Lưu nháp lỗi: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    // Chọn ảnh → tạo/cập nhật nháp rồi upload từng ảnh (auto-lưu, không cần bấm lưu).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val newEntries = uris.map { QPhoto(it, null) }
        photos.addAll(newEntries)
        scope.launch {
            try {
                doSave()
                val oid = draftId ?: return@launch
                for ((i, uri) in uris.withIndex()) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) newEntries[i].attachmentId = repo.uploadPhoto(oid, bytes)?.id
                }
                Toast.makeText(context, "Đã lưu nháp${if (draftCode.isNotBlank()) " $draftCode" else ""}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Lưu ảnh lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun removePhoto(i: Int) {
        val p = photos[i]
        val aid = p.attachmentId
        if (aid != null) scope.launch { try { repo.deletePhoto(aid) } catch (_: Exception) {} }
        photos.removeAt(i)
    }

    fun addVariant(v: VariantSearchDto) {
        if (rows.any { it.variantId == v.id }) {
            Toast.makeText(context, "Đã có trong đơn", Toast.LENGTH_SHORT).show(); return
        }
        val units = v.units
        val defUnit = units.firstOrNull { it.isDefaultSale } ?: units.firstOrNull { it.isBase } ?: units.firstOrNull()
        scope.launch {
            var price = defUnit?.price ?: v.price ?: 0.0
            val cid = partyId
            if (cid != null && defUnit != null) {
                try { container.vapi.lastPrice(cid, v.id, defUnit.id).data?.unitPrice?.let { price = it } } catch (_: Exception) {}
            }
            rows.add(
                QRow(
                    variantId = v.id, unitId = defUnit?.id ?: 0L,
                    productName = v.product?.name ?: (v.name ?: "SP #${v.id}"),
                    variantPairs = v.attributes?.entries?.filter { it.value.isNotBlank() }?.map { it.key to it.value } ?: emptyList(),
                    variantFallback = variantDisplay(v, v.product?.name ?: ""),
                    unitLabel = defUnit?.name ?: "",
                    image = v.image ?: v.product?.primaryImage?.url,
                    stock = v.stockBase ?: v.stock ?: 0.0,
                    unitPrice = price,
                ),
            )
            qtyByVariant[v.id] = "1"
            autoSaveIfDraft()
        }
    }
    // Tap trong dropdown: chưa có → thêm; đã có → bỏ chọn (xoá khỏi đơn). Giữ dropdown mở.
    fun toggleVariant(v: VariantSearchDto) {
        val idx = rows.indexOfFirst { it.variantId == v.id }
        if (idx >= 0) {
            qtyByVariant.remove(v.id); checkedByVariant.remove(v.id); rows.removeAt(idx); autoSaveIfDraft()
        } else addVariant(v)
    }

    // Prefill khi EDIT đơn nháp có sẵn.
    LaunchedEffect(editOrderId) {
        val id = editOrderId ?: return@LaunchedEffect
        try {
            val o = repo.getOrder(id)
            if (o != null) {
                draftId = o.id; draftCode = o.code
                partyId = o.party?.id; partyName = o.partyName
                note = o.notes ?: ""
                confirmDateMs = o.orderedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis()
                shipCustomer = o.shippingFee?.takeIf { it > 0 }?.let { qFmtMoney(it) } ?: ""
                shipCompany = o.actualShippingFee?.takeIf { it > 0 }?.let { qFmtMoney(it) } ?: ""
                codAmount = o.codCollected?.takeIf { it > 0 }?.let { qFmtMoney(it) } ?: ""
                rows.clear(); qtyByVariant.clear()
                o.items.forEach {
                    rows.add(
                        QRow(
                            variantId = it.variantId, unitId = it.unitId,
                            productName = it.productName,
                            variantPairs = it.variantPairs,
                            variantFallback = it.snapshot.variantName ?: "—",
                            unitLabel = it.unitName, image = it.imageUrl, stock = it.stockUnit ?: 0.0,
                            unitPrice = it.unitPrice,
                        ),
                    )
                    qtyByVariant[it.variantId] = trimZeros(it.qtyUnit)
                }
                photos.clear()
                repo.photos(id).forEach { photos.add(QPhoto(it.url ?: "", it.id)) }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Không tải được đơn nháp: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            loadingEdit = false
        }
    }

    val chosen = rows.filter { (qtyByVariant[it.variantId]?.toDoubleOrNull() ?: 0.0) > 0.0 }
    val canDeliver = draftId != null && partyId != null && chosen.isNotEmpty() && photos.isNotEmpty() && !delivering
    val canSaveDraft = (chosen.isNotEmpty() || photos.isNotEmpty() || partyId != null) && !savingManual

    // NV kho (không quyền duyệt): lưu nháp thủ công → đơn giữ trạng thái "Đợi duyệt".
    fun saveDraftManual() {
        if (savingManual) return
        scope.launch {
            savingManual = true
            try {
                doSave()
                Toast.makeText(context, "Đã lưu nháp${if (draftCode.isNotBlank()) " $draftCode" else ""} — chờ duyệt", Toast.LENGTH_LONG).show()
                onSaved(); onClose()
            } catch (e: Exception) {
                Toast.makeText(context, "Lưu nháp lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                savingManual = false
            }
        }
    }

    fun deliver() {
        val oid = draftId ?: return
        scope.launch {
            delivering = true
            try {
                doSave()
                container.vapi.confirmOrder(oid)
                val order = repo.getOrder(oid)
                val deliver = order?.items?.map { DeliveredItem(it.id, it.qtyUnit) } ?: emptyList()
                val u = container.tokenManager.user
                repo.fulfill(
                    oid,
                    FulfillRequest(
                        confirmedByUserId = u?.id?.toLong(),
                        confirmedByName = u?.username,
                        items = deliver,
                        shippingFee = qMoneyToDouble(shipCustomer),
                        actualShippingFee = qMoneyToDouble(shipCompany),
                        codAmount = qMoneyToDouble(codAmount),
                        codCasherId = null,
                        completedAt = confirmDateMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
                    ),
                )
                Toast.makeText(context, "Đã giao đơn ${draftCode}", Toast.LENGTH_LONG).show()
                onSaved(); onClose()
            } catch (e: Exception) {
                Toast.makeText(context, "Giao hàng thất bại: ${e.message}", Toast.LENGTH_LONG).show()
                delivering = false
            }
        }
    }

    // ── Keyboard center scroll ctx (mirror bu-order) ──
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val systemNavBarPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val screenHeightPx = LocalView.current.rootView.height.toFloat()
    val topTabBarPx = with(density) { 48.dp.toPx() }
    val bottomNavPx = with(density) { 64.dp.toPx() }
    val bottomPadDp = 100.dp
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
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scrollState).padding(bottom = bottomPadDp)) {
            // Top bar: Back + tiêu đề (thay banner trạng thái).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = C.Text,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = !delivering) { onClose() }.padding(8.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Tạo đơn giao hàng", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }

            // Khách + ngày
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Chưa chọn: "Chọn khách" (nghiêng) + pen. Đã chọn: tên khách + pen. Click pen → dialog.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (partyId != null) partyName else "Chọn khách",
                            color = if (partyId != null) C.Text else C.TextMuted,
                            fontStyle = if (partyId != null) FontStyle.Normal else FontStyle.Italic,
                            fontWeight = if (partyId != null) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Edit, "Chọn khách", tint = C.Primary,
                            modifier = Modifier.size(18.dp).clickable(enabled = !delivering) { pickerOpen = true },
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
            }

            // Mặt hàng — thẻ đầy đủ header như màn đơn đã xác nhận (ẢNH | MẶT HÀNG | SL | KHO | ✓).
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(23.dp)) {
                        Text("ẢNH", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.width(55.dp))
                        Spacer(Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text("MẶT HÀNG", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(6.dp))
                            Text("SL", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("KHO", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.width(68.dp))
                            Spacer(Modifier.width(10.dp))
                            val allChecked = rows.isNotEmpty() && rows.all { checkedByVariant[it.variantId] == true }
                            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_checklist_all),
                                    contentDescription = "Chọn tất cả",
                                    tint = if (allChecked) C.Primary else C.TextSecondary,
                                    modifier = Modifier.size(22.dp).clickable { val v = !allChecked; rows.forEach { checkedByVariant[it.variantId] = v } },
                                )
                            }
                        }
                    }
                    if (rows.isEmpty()) {
                        Text("Chưa có mặt hàng — có thể thêm sau.", color = C.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                    }
                    rows.forEachIndexed { idx, r ->
                        key(r.variantId) {
                            HorizontalDivider(Modifier.padding(vertical = 2.dp), color = C.Border)
                            var offsetX by remember { mutableStateOf(0f) }
                            var rowWidth by remember { mutableStateOf(1f) }
                            Box(
                                Modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width.toFloat() }
                                    .pointerInput(r.variantId) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = { if (-offsetX > rowWidth / 3f) { qtyByVariant.remove(r.variantId); checkedByVariant.remove(r.variantId); rows.removeAt(idx); autoSaveIfDraft() } else offsetX = 0f },
                                        ) { _, drag -> offsetX = (offsetX + drag).coerceAtMost(0f) }
                                    },
                            ) {
                                Box(Modifier.matchParentSize().background(C.Danger.copy(alpha = 0.25f)), contentAlignment = Alignment.CenterEnd) {
                                    Text("Xoá", color = C.Danger, fontSize = 13.sp, modifier = Modifier.padding(end = 16.dp))
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().offset { IntOffset(offsetX.toInt(), 0) }.background(C.Card).padding(vertical = 3.dp),
                                ) {
                                    if (r.image != null) {
                                        AsyncImage(model = r.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(55.dp).clip(RoundedCornerShape(7.dp)))
                                    } else {
                                        Box(Modifier.size(55.dp).clip(RoundedCornerShape(7.dp)).background(C.Border.copy(alpha = 0.3f)))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(r.productName, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(3.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            val variantText = buildAnnotatedString {
                                                val nameStyle = SpanStyle(color = C.TextMuted.copy(alpha = 0.65f), fontSize = 11.7.sp, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic)
                                                if (r.variantPairs.isEmpty()) withStyle(SpanStyle(color = C.Text)) { append(r.variantFallback) }
                                                else r.variantPairs.forEachIndexed { i, (name, value) ->
                                                    if (i > 0) withStyle(SpanStyle(color = C.TextMuted)) { append(", ") }
                                                    withStyle(nameStyle) { append("$name: ") }
                                                    withStyle(SpanStyle(color = C.Text)) { append(value) }
                                                }
                                            }
                                            Text(variantText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Spacer(Modifier.width(6.dp))
                                            Box(Modifier.width(56.dp)) {
                                                var qtyTfv by remember(r.variantId) { mutableStateOf(TextFieldValue(qtyByVariant[r.variantId] ?: "")) }
                                                var qtyFocused by remember(r.variantId) { mutableStateOf(false) }
                                                var fieldY by remember(r.variantId) { mutableStateOf(0f) }
                                                var fieldH by remember(r.variantId) { mutableStateOf(0f) }
                                                NumEditHint(qtyFocused, qtyTfv.text)
                                                BasicTextField(
                                                    value = qtyTfv,
                                                    onValueChange = { raw -> val f = raw.text.filter { c -> c.isDigit() || c == '.' }; qtyTfv = if (f == raw.text) raw else TextFieldValue(f, TextRange(f.length)); qtyByVariant[r.variantId] = f; autoSaveIfDraft() },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                    singleLine = true,
                                                    textStyle = TextStyle(color = C.Text, fontSize = 16.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
                                                    cursorBrush = SolidColor(C.Primary),
                                                    decorationBox = { inner -> Column { Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) { inner() }; HorizontalDivider(thickness = 0.5.dp, color = C.Primary.copy(alpha = 0.5f)) } },
                                                    modifier = Modifier.fillMaxWidth()
                                                        .onGloballyPositioned { fieldY = it.positionInWindow().y; fieldH = it.size.height.toFloat() }
                                                        .onFocusChanged { st ->
                                                            if (st.isFocused) {
                                                                qtyFocused = true
                                                                scope.launch { kotlinx.coroutines.delay(60); qtyTfv = qtyTfv.copy(selection = TextRange(0, qtyTfv.text.length)) }
                                                                scope.launch {
                                                                    kotlinx.coroutines.delay(280)
                                                                    val top = focusCtx.statusBarPx + focusCtx.topTabBarPx
                                                                    val bot = focusCtx.screenHeightPx - focusCtx.imeBottomState.value - focusCtx.confirmBtnState.value
                                                                    val d = fieldY - ((top + bot) / 2f - fieldH / 2f)
                                                                    if (d > 0f) runCatching { focusCtx.scrollState.animateScrollBy(d) }
                                                                }
                                                            } else qtyFocused = false
                                                        },
                                                )
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Box(Modifier.width(68.dp), contentAlignment = Alignment.Center) {
                                                Text("${trimZeros(r.stock)} ${r.unitLabel}", fontSize = 13.sp, color = C.TextMuted.copy(alpha = 0.6f), maxLines = 1, softWrap = false)
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                                WhCheckbox(checked = checkedByVariant[r.variantId] == true, enabled = true, onCheckedChange = { checkedByVariant[r.variantId] = it }, checkedColor = C.Primary, borderColor = C.TextMuted)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Dòng cuối (border-top): "+ Sản phẩm" INLINE dùng chung + Tổng SL căn cột SL.
                    HorizontalDivider(Modifier.padding(top = 4.dp), color = C.Border)
                    Spacer(Modifier.height(6.dp))
                    VariantSearchInline(
                        warehouseId = warehouseId,
                        selectedIds = rows.map { it.variantId }.toSet(),
                        onToggle = { v -> toggleVariant(v) },
                    ) {
                        Spacer(Modifier.width(6.dp))
                        Text("Tổng SL:", color = C.TextMuted, fontStyle = FontStyle.Italic, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        val totalQty = rows.sumOf { qtyByVariant[it.variantId]?.toDoubleOrNull() ?: 0.0 }
                        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                            Text(trimZeros(totalQty), color = C.Text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        Spacer(Modifier.width(68.dp))   // cột KHO (trống)
                        Spacer(Modifier.width(10.dp))
                        Spacer(Modifier.width(24.dp))   // cột ✓ (trống)
                    }
                }
            }

            // Phí ship + thu hộ
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    QMoneyRow("Phí ship KH", shipCustomer, focusCtx, scope) { shipCustomer = it; autoSaveIfDraft() }
                    QMoneyRow("Phí ship KHO", shipCompany, focusCtx, scope) { shipCompany = it; autoSaveIfDraft() }
                    QMoneyRow("Thu hộ", codAmount, focusCtx, scope) { codAmount = it; autoSaveIfDraft() }
                }
            }

            // Ảnh (đính = tự lưu)
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(23.dp)) {
                        Text("Ảnh xác nhận — đính là TỰ lưu nháp", fontSize = 11.sp, color = C.Primary, modifier = Modifier.weight(1f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !delivering) { picker.launch("image/*") }.padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null, tint = C.Primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Chọn / chụp", color = C.Primary, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 8.dp), color = C.Border)
                    if (photos.isEmpty()) {
                        Text("Chưa có ảnh", color = C.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(((photos.size + 2) / 3 * 110).dp), userScrollEnabled = false) {
                            itemsIndexed(photos) { i, p ->
                                Box(Modifier.padding(2.dp)) {
                                    AsyncImage(model = p.model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).clickable { viewerUrl = p.model.toString() })
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).clickable { removePhoto(i) },
                                    ) { Icon(Icons.Default.Close, "Xoá", tint = Color.White, modifier = Modifier.size(18.dp).padding(2.dp)) }
                                }
                            }
                        }
                    }
                }
            }

            // Ghi chú (cuối)
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
        }

        // Bar đáy: Đóng + Giao hàng
        Surface(shadowElevation = 8.dp, color = C.Card, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onClose, enabled = !delivering && !savingManual, modifier = Modifier.height(48.dp)) { Text("Đóng") }
                    // Admin (order.approve): giao hàng luôn. NV kho: chỉ lưu nháp (đơn Đợi duyệt).
                    if (canApprove) {
                        Button(
                            onClick = { deliver() },
                            enabled = canDeliver,
                            colors = ButtonDefaults.buttonColors(containerColor = C.Primary),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            if (delivering) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("Giao hàng")
                        }
                    } else {
                        Button(
                            onClick = { saveDraftManual() },
                            enabled = canSaveDraft,
                            colors = ButtonDefaults.buttonColors(containerColor = C.Primary),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            if (savingManual) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("Lưu nháp")
                        }
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        CustomerPicker(isPurchase = false, onPick = { c: CustomerDto -> partyId = c.id; partyName = c.name; pickerOpen = false; autoSaveIfDraft() }, onClose = { pickerOpen = false })
    }
    if (datePickerOpen) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = confirmDateMs)
        MaterialTheme(
            colorScheme = darkColorScheme(surface = C.Card, surfaceVariant = C.Card, onSurface = C.Text, onSurfaceVariant = C.TextMuted, primary = C.Primary, onPrimary = Color.White),
        ) {
            DatePickerDialog(
                onDismissRequest = { datePickerOpen = false },
                confirmButton = { TextButton(onClick = { datePickerState.selectedDateMillis?.let { confirmDateMs = it }; datePickerOpen = false; autoSaveIfDraft() }) { Text("OK", color = C.Primary) } },
                dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text("Huỷ", color = C.TextMuted) } },
                colors = DatePickerDefaults.colors(containerColor = C.Card),
            ) { DatePicker(state = datePickerState) }
        }
    }
    viewerUrl?.let { url -> PhotoZoomViewer(url = url, onClose = { viewerUrl = null }) }
}
