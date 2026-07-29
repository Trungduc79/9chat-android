package vn.chat9.app.ui.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.VariantSearchDto
import vn.chat9.app.ui.modules.warehouse.VariantHistoryDialog
import java.text.NumberFormat
import java.util.Locale

private val moneyFmt = NumberFormat.getInstance(Locale("vi", "VN"))
private fun money(v: Double?): String = moneyFmt.format((v ?: 0.0).toLong()) + " đ"
private fun qty(v: Double?): String {
    val d = v ?: 0.0
    return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}

private val Green = Color(0xFF2F855A)

/**
 * Chi tiết sản phẩm READ-ONLY, mở từ thẻ product (deeplink 9chat://product/{variantId}).
 * Fetch 1 biến thể (GET /v1/variants/{id}) → tên/SKU/giá/tồn/ảnh + đơn vị; nút "Lịch sử
 * kho" tái dùng [VariantHistoryDialog]. Gate quyền đã chặn ở MainActivity.navigateTo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(variantId: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val vapi = (ctx.applicationContext as App).container.vapi
    val scope = rememberCoroutineScope()

    var variant by remember(variantId) { mutableStateOf<VariantSearchDto?>(null) }
    var loading by remember(variantId) { mutableStateOf(true) }
    var error by remember(variantId) { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(variantId) {
        loading = true; error = null
        scope.launch {
            try {
                variant = vapi.getVariant(variantId).data
                if (variant == null) error = "Không tìm thấy sản phẩm"
            } catch (e: Exception) {
                error = e.message ?: "Lỗi tải sản phẩm"
            } finally { loading = false }
        }
    }

    Scaffold(
        containerColor = Color(0xFFF4F5F7),
        topBar = {
            TopAppBar(
                title = { Text("Sản phẩm", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Đóng") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White, titleContentColor = Color(0xFF2C3E50)),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Green)
                error != null -> Text(error!!, Modifier.align(Alignment.Center), color = Color(0xFFDC2626))
                variant != null -> Body(variant!!, onHistory = { showHistory = true })
            }
        }
    }

    if (showHistory) variant?.let { VariantHistoryDialog(variant = it, onDismiss = { showHistory = false }) }
}

@Composable
private fun Body(v: VariantSearchDto, onHistory: () -> Unit) {
    val productName = v.product?.name?.trim()?.ifBlank { null } ?: v.name?.trim()?.ifBlank { null } ?: "SP #${v.id}"
    val attrVals = v.attributes?.values?.filter { it.isNotBlank() }.orEmpty()
    val title = if (attrVals.isNotEmpty()) "$productName (${attrVals.joinToString(" / ")})" else productName

    val baseUnit = v.units.firstOrNull { it.isBase }
    val saleUnit = v.units.firstOrNull { it.isDefaultSale } ?: baseUnit
    val price = saleUnit?.price ?: v.price
    // Tồn quy đổi sang ĐƠN VỊ BÁN mặc định (đơn vị đơn hàng) cho khớp thẻ + giá.
    val stockBase = v.stockBase ?: v.stock ?: 0.0
    val cf = (saleUnit?.conversionFactor ?: 1.0).takeIf { it > 0 } ?: 1.0
    val stock = stockBase / cf
    val unitName = saleUnit?.name?.takeIf { it.isNotBlank() }
    val image = v.image?.trim()?.ifBlank { null } ?: v.product?.primaryImage?.url

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (image != null) {
            AsyncImage(
                model = image, contentDescription = title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEDEFF2)),
            )
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SKU: ${v.sku ?: "#${v.id}"}", fontSize = 12.sp, color = Color(0xFF8593A1), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    val inStock = stockBase > 0.001
                    Text(
                        if (inStock) "Còn hàng" else "Hết hàng",
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (inStock) Green else Color(0xFF8593A1),
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (inStock) Color(0x1F2F855A) else Color(0x14000000))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(money(price), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Green)
                    Text("Tồn: ${qty(stock)}${if (unitName != null) " $unitName" else ""}", fontSize = 13.sp, color = Color(0xFF8593A1))
                }
            }
        }

        if (v.units.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Đơn vị tính", fontSize = 12.sp, color = Color(0xFF8593A1), fontWeight = FontWeight.Medium)
                    v.units.forEach { u ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(u.name, fontSize = 14.sp, color = Color(0xFF2C3E50), fontWeight = FontWeight.Medium)
                                if (u.isBase) Tag("gốc")
                                if (u.isDefaultSale) Tag("bán")
                            }
                            Text(
                                money(u.price) + (if (!u.isBase) " · x${qty(u.conversionFactor)}" else ""),
                                fontSize = 13.sp, color = Color(0xFF5B6570),
                            )
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onHistory, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
        ) { Text("Lịch sử kho ›", fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Green,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x1F2F855A)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
