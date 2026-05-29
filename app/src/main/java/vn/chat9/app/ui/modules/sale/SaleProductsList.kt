package vn.chat9.app.ui.modules.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.VariantSearchDto
import vn.chat9.app.data.vapi.dto.WarehouseDto
import vn.chat9.app.ui.explore.AdminColors

/**
 * Tab Sản phẩm (Android) — port web SaleProductsView. Search biến thể (70%) +
 * dropdown kho (30%). Tồn theo đơn vị mặc định, 3 dòng (Kho / số / tên đơn vị).
 */
@Composable
fun SaleProductsList() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container

    var query by remember { mutableStateOf("") }
    var variants by remember { mutableStateOf<List<VariantSearchDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var warehouses by remember { mutableStateOf<List<WarehouseDto>>(emptyList()) }
    var selectedWarehouseId by remember { mutableStateOf<Long?>(null) }

    suspend fun load() {
        loading = true
        try {
            variants = container.vapi.listAllVariants(search = query.ifBlank { null }, warehouseId = selectedWarehouseId, perPage = 50).data ?: emptyList()
        } catch (_: Exception) {}
        loading = false
    }

    // Load kho + default → load variants.
    LaunchedEffect(Unit) {
        try {
            val ws = container.vapi.listWarehouses().data ?: emptyList()
            warehouses = ws
            selectedWarehouseId = ws.firstOrNull { it.isDefault }?.id ?: ws.firstOrNull()?.id
        } catch (_: Exception) {}
        load()
    }
    // Search debounce + reload khi đổi kho.
    LaunchedEffect(query, selectedWarehouseId) {
        delay(280)
        load()
    }

    Column(Modifier.fillMaxSize().background(AdminColors.Bg)) {
        // Header: search 70% + dropdown kho 30%
        Row(Modifier.fillMaxWidth().background(AdminColors.Card).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.7f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AdminColors.Primary), singleLine = true,
                    decorationBox = { inner -> if (query.isEmpty()) Text("Tìm biến thể...", color = AdminColors.TextMuted, fontSize = 13.sp); inner() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(0.3f)) {
                WarehouseDropdownInline(warehouses, selectedWarehouseId) { selectedWarehouseId = it }
            }
        }

        if (loading && variants.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AdminColors.Primary) }
        else if (variants.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Không có biến thể", color = AdminColors.TextMuted) }
        else {
            // Màu số tồn theo KHO đang chọn (mỗi kho 1 màu nổi, dễ phân biệt).
            val whIdx = warehouses.indexOfFirst { it.id == selectedWarehouseId }.coerceAtLeast(0)
            val stockColor = WAREHOUSE_STOCK_COLORS[whIdx % WAREHOUSE_STOCK_COLORS.size]
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(variants, key = { it.id }) { v -> VariantRow(v, stockColor) }
            }
        }
    }
}

// Palette màu tồn theo kho — đều nổi, tương phản tốt trên nền tối.
private val WAREHOUSE_STOCK_COLORS = listOf(
    androidx.compose.ui.graphics.Color(0xFF4CB782),   // xanh lá
    androidx.compose.ui.graphics.Color(0xFF4AA3F2),   // xanh dương
    androidx.compose.ui.graphics.Color(0xFFF2994A),   // cam
    androidx.compose.ui.graphics.Color(0xFFB388FF),   // tím
    androidx.compose.ui.graphics.Color(0xFFF25287),   // hồng
    androidx.compose.ui.graphics.Color(0xFF26C6DA),   // cyan
)

@Composable
private fun VariantRow(v: VariantSearchDto, stockColor: androidx.compose.ui.graphics.Color) {
    val units = v.units
    val defUnit = units.firstOrNull { it.isDefaultSale } ?: units.firstOrNull { it.isBase } ?: units.firstOrNull()
    val factor = defUnit?.conversionFactor ?: 1.0
    val stockBase = v.stockBase ?: 0.0
    val stockInUnit = if (factor > 0) stockBase / factor else stockBase
    val name = if (!v.name.isNullOrBlank()) v.name
        else v.attributes?.values?.filter { it.isNotBlank() }?.joinToString(", ") ?: (v.product?.name ?: "")

    Row(
        // Giảm chiều cao thẻ tối đa (thumb giữ 55dp → padding vertical 0, chỉ horizontal).
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AdminColors.Card).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumb 62dp (+5% so 59). Padding vertical 0 → chiều cao thẻ = thumb.
        val img = v.image ?: v.product?.primaryImage?.url
        if (img != null) AsyncImage(model = img, contentDescription = null, modifier = Modifier.size(62.dp).clip(RoundedCornerShape(6.dp)))
        else Box(Modifier.size(62.dp).clip(RoundedCornerShape(6.dp)).background(AdminColors.Border.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Inventory2, null, tint = AdminColors.TextMuted, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(name, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        // 3 dòng center: Kho / số (màu theo kho) / đơn vị. Gap giảm còn 1dp.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Kho", color = AdminColors.TextMuted, fontSize = 11.sp)
            Text(trimZeros(stockInUnit), color = if (stockInUnit > 0) stockColor else AdminColors.TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            defUnit?.name?.let { Text(it, color = AdminColors.TextMuted, fontSize = 11.sp) }
        }
    }
}

/** Dropdown kho inline (dark) cho header. */
@Composable
private fun WarehouseDropdownInline(warehouses: List<WarehouseDto>, selectedId: Long?, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = warehouses.firstOrNull { it.id == selectedId }
    Box {
        Row(Modifier.clickable { open = true }.clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(current?.name ?: "Kho", color = AdminColors.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Text(" ▾", color = AdminColors.TextMuted, fontSize = 11.sp)
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

private fun trimZeros(n: Double): String {
    val r = Math.round(n * 1000.0) / 1000.0
    return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
}
