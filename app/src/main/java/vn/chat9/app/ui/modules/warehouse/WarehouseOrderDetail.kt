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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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

    // Phí ship + Thu hộ — NV kho chốt lúc giao (init từ order, có thể sửa).
    var shipCustomer by remember { mutableStateOf("") }   // Phí ship KH → order.shipping_fee → công nợ
    var shipCompany by remember { mutableStateOf("") }    // Phí ship KHO → actual_shipping_fee → chi phí
    var codAmount by remember { mutableStateOf("") }      // Thu hộ → cust_cash_in
    var codCasherId by remember { mutableStateOf<Long?>(null) }
    var cashers by remember { mutableStateOf<List<CasherDto>>(emptyList()) }

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
            photos = repo.photos(orderId)
        } catch (_: Exception) {
            Toast.makeText(ctx, "Không tải được đơn", Toast.LENGTH_SHORT).show()
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        try { cashers = repo.listCashers() } catch (_: Exception) {}
    }
    // Quỹ tiền mặt active (loại bank_account) cho dropdown COD.
    val cashCashers = cashers.filter { it.isActive && it.type != "bank_account" }
    LaunchedEffect(cashCashers) {
        if (codCasherId == null) codCasherId = cashCashers.firstOrNull { it.isDefault }?.id ?: cashCashers.firstOrNull()?.id
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
    // COD>0 phải chọn quỹ (chỉ áp đơn bán); nếu không có quỹ tiền mặt active → chặn.
    val codNum = codAmount.toDoubleOrNull() ?: 0.0
    val codValid = isPurchase || codNum <= 0.0 || codCasherId != null
    val canConfirm = photos.isNotEmpty() && allChecked && totalDelivered > 0 && !hasOverStock && codValid

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
        val codCasher = if (!isPurchase && (cod ?: 0.0) > 0) codCasherId else null
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
                        codCasherId = codCasher,
                    ),
                )
                val base = if (isPurchase) "Đã xác nhận nhập hàng" else "Đã xác nhận giao hàng"
                val msg = res?.remainderOrder?.code?.let { "$base. Đơn còn lại: $it" } ?: base
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                onClose()
            } catch (_: Exception) {
                Toast.makeText(ctx, "Xác nhận thất bại", Toast.LENGTH_SHORT).show()
            }
            confirming = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(C.Bg).pointerInput(orderId, siblingIds) {
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = if (canFulfill && canConfirm) 96.dp else 12.dp)) {
            if (loading || o == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(color = C.Primary)
                }
                return@Column
            }

            InfoCard(o, isPurchase)

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
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = C.Border)
                        ItemRow(
                            item = it2, canFulfill = canFulfill,
                            delivered = delivered[it2.id] ?: it2.qtyUnit,
                            checkedVal = (checked[it2.id] == true) && !blocked(it2),
                            blocked = blocked(it2),
                            onStep = { d -> delivered[it2.id] = ((delivered[it2.id] ?: it2.qtyUnit) + d).coerceAtLeast(0.0) },
                            onCheck = { v -> checked[it2.id] = v },
                        )
                    }
                }
            }

            // Phí ship + Thu hộ — NV kho chốt lúc giao (chỉ đơn còn xác nhận).
            // Đơn bán: 3 hàng (Phí ship KH / Phí ship KHO / Thu hộ + chọn quỹ).
            // Đơn nhập: chỉ 1 hàng Phí ship KHO.
            if (canFulfill) {
                Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (isPurchase) "Phí ship" else "Phí ship + Thu hộ", fontSize = 11.sp, color = C.TextMuted, fontWeight = FontWeight.Medium)
                        HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 8.dp), color = C.Border)
                        if (!isPurchase) {
                            ShipRow("Phí ship KH", shipCustomer) { shipCustomer = it }
                            Spacer(Modifier.height(6.dp))
                        }
                        ShipRow("Phí ship KHO", shipCompany) { shipCompany = it }
                        if (!isPurchase) {
                            Spacer(Modifier.height(6.dp))
                            ShipRow("Thu hộ", codAmount) { codAmount = it }
                            // Quỹ thu COD — chỉ hiện khi nhập số > 0 hoặc đang yêu cầu chọn.
                            if ((codAmount.toDoubleOrNull() ?: 0.0) > 0.0) {
                                Spacer(Modifier.height(6.dp))
                                CasherSelect(
                                    cashers = cashCashers,
                                    currentId = codCasherId,
                                    onSelect = { codCasherId = it },
                                )
                            }
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

        // Nút xác nhận chỉ hiện (trượt lên từ đáy) khi ĐỦ điều kiện; chưa đủ → ẩn
        // xuống dưới, không chiếm view. Lý do chưa đủ đã thể hiện ở checkbox / "Tồn" đỏ.
        AnimatedVisibility(
            visible = canFulfill && canConfirm,
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
}

@Composable
private fun InfoCard(o: OrderDto, isPurchase: Boolean) {
    Surface(shape = RoundedCornerShape(12.dp), color = C.Card, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.partyName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = C.Text, modifier = Modifier.weight(1f))
                Text(o.code, color = C.Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                        "Xác nhận bởi ${f.byName ?: "—"} · ${fmtDateTime(f.at ?: o.completedAt)}",
                        fontSize = 12.sp, color = C.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: OrderItemDto, canFulfill: Boolean, delivered: Double, checkedVal: Boolean,
    blocked: Boolean, onStep: (Double) -> Unit, onCheck: (Boolean) -> Unit,
) {
    val faint = C.Border.copy(alpha = 0.5f) // nút ± mờ thêm 50%
    Column {
        Text(item.productName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.Text)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Tên nhóm phân loại: viết thường, mờ, KHÔNG nghiêng, ": " + giá trị trắng (chuẩn web).
            val variantText = buildAnnotatedString {
                val pairs = item.variantPairs
                if (pairs.isEmpty()) withStyle(SpanStyle(color = C.TextMuted)) { append("—") }
                else pairs.forEachIndexed { i, (name, value) ->
                    if (i > 0) withStyle(SpanStyle(color = C.TextMuted)) { append(", ") }
                    withStyle(SpanStyle(color = C.TextMuted)) { append("$name: ") }
                    withStyle(SpanStyle(color = C.Text)) { append(value) }
                }
            }
            Text(variantText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (canFulfill) {
                OutlinedIconButton(onClick = { onStep(-1.0) }, modifier = Modifier.size(40.dp), border = BorderStroke(1.dp, faint), colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = faint)) { Text("−", fontSize = 18.sp) }
                Text(trimZeros(delivered), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = C.Text, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 44.dp))
                OutlinedIconButton(onClick = { onStep(1.0) }, modifier = Modifier.size(40.dp), border = BorderStroke(1.dp, faint), colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = faint)) { Text("+", fontSize = 18.sp) }
            } else {
                Text(trimZeros(item.qtyUnit), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = C.Text)
            }
            Spacer(Modifier.width(6.dp))
            // "Tồn N đơn vị" (bỏ dấu :) cùng 1 dòng, no wrap.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Đơn đã hoàn thành (không còn xác nhận): KHÔNG hiển thị tồn kho.
                if (canFulfill) item.stockUnit?.let {
                    Text("Tồn ${trimZeros(it)}", fontSize = 13.sp, color = if (blocked) C.Danger else C.TextMuted, maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(3.dp))
                }
                Text(item.unitName, fontSize = 13.sp, color = C.TextMuted, maxLines = 1, softWrap = false)
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

/** Hàng nhập tiền VND đơn giản: label trái + ô số bên phải (filter chỉ digit). */
@Composable
private fun ShipRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = C.TextMuted, modifier = Modifier.weight(0.42f))
        Text(":", fontSize = 13.sp, color = C.TextMuted, modifier = Modifier.padding(end = 6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
            placeholder = { Text("0", color = C.TextMuted, fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = TextStyle(color = C.Text, fontSize = 14.sp, textAlign = TextAlign.End),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.Primary,
                unfocusedBorderColor = C.Border,
                focusedTextColor = C.Text,
                unfocusedTextColor = C.Text,
                cursorColor = C.Primary,
            ),
            modifier = Modifier.weight(0.58f).height(48.dp),
        )
    }
}

/** Dropdown chọn quỹ thu (chỉ quỹ tiền mặt active). */
@Composable
private fun CasherSelect(cashers: List<CasherDto>, currentId: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val name = cashers.firstOrNull { it.id == currentId }?.name ?: "Chọn quỹ thu tiền"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("→ Quỹ thu", fontSize = 13.sp, color = C.TextMuted, modifier = Modifier.weight(0.42f))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(0.58f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                border = BorderStroke(1.dp, C.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Text),
            ) {
                Text(name, color = if (currentId == null) C.TextMuted else C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(Icons.Default.ArrowDropDown, null, tint = C.TextMuted)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (cashers.isEmpty()) {
                    DropdownMenuItem(text = { Text("(Chưa có quỹ tiền mặt active)", color = C.TextMuted) }, onClick = { expanded = false })
                } else cashers.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelect(c.id); expanded = false })
                }
            }
        }
    }
}
