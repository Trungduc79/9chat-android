package vn.chat9.app.ui.modules.sale

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.di.AppContainer
import vn.chat9.app.data.vapi.dto.CreateOrderItem
import vn.chat9.app.data.vapi.dto.CreateOrderRequest
import vn.chat9.app.data.vapi.dto.CustomerDto
import vn.chat9.app.data.vapi.dto.ProductSearchDto
import vn.chat9.app.data.vapi.dto.VariantSearchDto
import vn.chat9.app.ui.explore.AdminColors
import java.text.NumberFormat
import java.util.Locale

/**
 * Tạo đơn bán mới — KH + items + 2 nút (Lưu nháp / Xác nhận).
 *
 * MVP scope (Đức 2026-05-26): tự fill last_price khi add item; KHÔNG live-edit
 * autosave + reorder + drag (làm sau).
 *
 * Flow: chọn KH → search SP → tap variant → BE last_price(KH, variant) → push
 * vào items với price + qty=1 + default sale unit. Submit POST /v1/orders với
 * created_by_user_id = TokenManager.user.id (BE filter "đơn của tôi" theo cột này).
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

    // Pickers state
    var customerPickerOpen by remember { mutableStateOf(true) }   // KH bắt buộc → mở luôn
    var productPickerOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            // ===== Card KH =====
            Card("Khách hàng") {
                if (selectedCustomer == null) {
                    Text("Chưa chọn", color = AdminColors.TextMuted, fontSize = 13.sp,
                        modifier = Modifier.clickable { customerPickerOpen = true }.padding(8.dp))
                } else {
                    Row(Modifier.fillMaxWidth().clickable { customerPickerOpen = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(selectedCustomer!!.name, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            selectedCustomer!!.phone?.let { Text(it, color = AdminColors.TextMuted, fontSize = 12.sp) }
                        }
                        Text("Đổi", color = AdminColors.Primary, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== Card Items =====
            Card("Mặt hàng (${items.size})") {
                if (items.isEmpty()) {
                    Text("Chưa có sản phẩm — nhấn + để thêm", color = AdminColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                } else {
                    Column {
                        items.forEachIndexed { idx, it ->
                            ItemRow(it, onDelete = { items.removeAt(idx) }, onQtyChange = { q -> items[idx] = it.copy(qty = q) }, onPriceChange = { p -> items[idx] = it.copy(price = p) })
                            if (idx < items.size - 1) HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.5f))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { if (selectedCustomer != null) productPickerOpen = true else Toast.makeText(context, "Chọn khách hàng trước", Toast.LENGTH_SHORT).show() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("+ Thêm sản phẩm", color = AdminColors.Primary) }
            }

            Spacer(Modifier.height(12.dp))

            // ===== Card Ghi chú =====
            Card("Ghi chú") {
                BasicTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(8.dp)) {
                            if (notes.isEmpty()) Text("Ghi chú đơn (tùy chọn)", color = AdminColors.TextMuted, fontSize = 13.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ===== Total + actions =====
            val total = items.sumOf { it.qty * it.price }
            val fmt = NumberFormat.getNumberInstance(Locale("vi"))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tổng cộng", color = AdminColors.TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${fmt.format(total.toLong())} đ", color = AdminColors.Primary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(12.dp))

            // 2 nút: Lưu nháp + Xác nhận
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { submit(scope, container, userId, selectedCustomer, items, notes, "draft", context, onDone) { saving = it } },
                    enabled = !saving && selectedCustomer != null && items.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Lưu nháp") }
                Button(
                    onClick = { submit(scope, container, userId, selectedCustomer, items, notes, "confirmed", context, onDone) { saving = it } },
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
        CustomerPicker(
            onPick = { c -> selectedCustomer = c; customerPickerOpen = false },
            onClose = { customerPickerOpen = false },
        )
    }
    if (productPickerOpen && selectedCustomer != null) {
        ProductPicker(
            customerId = selectedCustomer!!.id,
            onPick = { v, p, lastPrice, lastUnitId ->
                items.add(OrderItemDraft(
                    variantId = v.id,
                    unitId = lastUnitId ?: v.defaultUnitId ?: 0L,
                    productName = p.name,
                    variantLabel = v.name ?: v.attributes?.entries?.joinToString(", ") { "${it.key}: ${it.value}" } ?: "",
                    qty = 1.0,
                    price = lastPrice ?: v.price ?: 0.0,
                    imageUrl = v.image ?: p.primaryImage?.url,
                ))
                productPickerOpen = false
            },
            onClose = { productPickerOpen = false },
        )
    }
}

/** Local draft mỗi dòng item trên form (không gửi field thừa lên BE). */
data class OrderItemDraft(
    val variantId: Long,
    val unitId: Long,
    val productName: String,
    val variantLabel: String,
    val qty: Double,
    val price: Double,
    val imageUrl: String?,
)

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(12.dp)) {
        Text(title, color = AdminColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ItemRow(it: OrderItemDraft, onDelete: () -> Unit, onQtyChange: (Double) -> Unit, onPriceChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(it.productName, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (it.variantLabel.isNotBlank()) Text(it.variantLabel, color = AdminColors.TextMuted, fontSize = 11.sp)
        }
        // qty input nhỏ
        var qtyText by remember(it.variantId) { mutableStateOf(it.qty.toLong().toString()) }
        BasicTextField(
            value = qtyText,
            onValueChange = { raw ->
                val f = raw.filter { c -> c.isDigit() }
                qtyText = f
                onQtyChange(f.toDoubleOrNull() ?: 0.0)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(AdminColors.Primary),
            decorationBox = { inner ->
                Box(Modifier.width(48.dp).padding(vertical = 4.dp), Alignment.Center) { inner() }
            },
        )
        Text("×", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        val fmt = NumberFormat.getNumberInstance(Locale("vi"))
        Text("${fmt.format(it.price.toLong())} đ", color = AdminColors.Text, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Text("✕", color = AdminColors.Danger, fontSize = 16.sp, modifier = Modifier.clickable { onDelete() }.padding(8.dp))
    }
}

// ===== Customer picker =====
@Composable
private fun CustomerPicker(onPick: (CustomerDto) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val userId = container.tokenManager.user?.id?.toLong() ?: return

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CustomerDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Load recent + debounce search
    LaunchedEffect(query) {
        loading = true
        try {
            results = if (query.isBlank()) {
                container.vapi.recentCustomers(userId, 20).data ?: emptyList()
            } else {
                delay(280)
                container.vapi.searchCustomers(query, 20).data ?: emptyList()
            }
        } catch (_: Exception) {}
        loading = false
    }

    PickerSheet(title = "Chọn khách hàng", onClose = onClose) {
        SearchField(query, "Tìm KH theo tên, SĐT...") { query = it }
        Spacer(Modifier.height(8.dp))
        if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(28.dp))
        } else LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
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

// ===== Product picker =====
@Composable
private fun ProductPicker(
    customerId: Long,
    onPick: (VariantSearchDto, ProductSearchDto, Double?, Long?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ProductSearchDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        loading = true
        delay(280)
        try { results = container.vapi.searchProducts(query, 20).data ?: emptyList() } catch (_: Exception) {}
        loading = false
    }

    PickerSheet(title = "Chọn sản phẩm", onClose = onClose) {
        SearchField(query, "Tìm SP theo tên / SKU...") { query = it }
        Spacer(Modifier.height(8.dp))
        if (query.length < 2) Text("Gõ ≥2 ký tự để tìm", color = AdminColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        else if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(28.dp))
        } else LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            results.forEach { p ->
                items(p.variants, key = { v -> v.id }) { v ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            // Fetch last-price KH-variant trước khi push vào items (background, không block UI).
                            scope.launch {
                                var lastPrice: Double? = null
                                var lastUnit: Long? = null
                                try {
                                    val lp = container.vapi.lastPrice(customerId, v.id).data
                                    lastPrice = lp?.unitPrice
                                    lastUnit = lp?.unitId
                                } catch (_: Exception) {}
                                onPick(v, p, lastPrice, lastUnit)
                            }
                        }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = AdminColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val attrs = v.name ?: v.attributes?.entries?.joinToString(", ") { "${it.key}: ${it.value}" } ?: ""
                            if (attrs.isNotBlank()) Text(attrs, color = AdminColors.TextMuted, fontSize = 11.sp)
                            v.price?.let {
                                val fmt = NumberFormat.getNumberInstance(Locale("vi"))
                                Text("${fmt.format(it.toLong())} đ", color = AdminColors.Primary, fontSize = 12.sp)
                            }
                        }
                    }
                    HorizontalDivider(color = AdminColors.Border.copy(alpha = 0.4f))
                }
            }
        }
    }
}

// ===== shared picker UI =====
@Composable
private fun PickerSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Full-screen modal dark — không dùng BottomSheet để tránh dependency phụ.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(AdminColors.Card).padding(16.dp)
                .clickable(enabled = false, onClick = {})       // chặn click qua sheet
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
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
        cursorBrush = SolidColor(AdminColors.Primary),
        singleLine = true,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(12.dp),
            ) {
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
    items: List<OrderItemDraft>,
    notes: String,
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
                type = "sale",
                partyType = "customer",
                partyId = customer.id,
                status = status,
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
