package vn.chat9.app.ui.modules.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.chat9.app.App
import vn.chat9.app.ui.explore.AdminColors

/**
 * Module "Bán hàng" — entry tab Khám phá → Sale. 3 tab: Đơn hàng / Sản phẩm /
 * Khách hàng (mirror web SaleLayout). Tạo đơn = overlay full-screen.
 *
 * Permission gate ở [ModuleRegistry]: sale.create_order OR sale.view_orders.
 */
private enum class SaleTab(val label: String) { ORDERS("Đơn bán"), PURCHASES("Đơn nhập"), PRODUCTS("Sản phẩm"), CUSTOMERS("Khách hàng") }

@Composable
fun SaleScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(SaleTab.ORDERS) }
    var creating by remember { mutableStateOf(false) }
    var viewingOrderId by remember { mutableStateOf<Long?>(null) }   // tap đơn → chi tiết/edit
    var viewingPurchase by remember { mutableStateOf(false) }        // đơn đang tạo/xem là đơn nhập?

    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    // Quyền tạo đơn: ẩn nút nếu thiếu order.create (UX — server cũng chặn thật qua X-Staff-Phone).
    val perms by container.permissions.state.collectAsState()
    val canCreate = perms.bypass_all || "order.create" in perms.permissions

    // Menu 3 chấm header đơn nhập đã lưu
    var headerMenuOpen by remember { mutableStateOf(false) }
    var headerBusy by remember { mutableStateOf(false) }
    var viewingStatus by remember { mutableStateOf<String?>(null) }   // tình trạng đơn đang xem (gate "Xóa")
    var viewingDropship by remember { mutableStateOf(false) }          // đơn giao thẳng? (cảnh báo xoá kèm đơn bán)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Ảnh gửi NCC: giữ bitmap + uri để preview trong dialog trước khi Copy/Chia sẻ/Tải.
    var supImgBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var supImgUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var supImgSize by remember { mutableStateOf("") }
    var supImgTitle by remember { mutableStateOf("Đơn nhập hàng") }

    // Nạp tình trạng đơn nhập đang xem → chỉ hiện "Xóa" khi đơn NHÁP.
    LaunchedEffect(viewingOrderId, viewingPurchase, creating) {
        if (viewingPurchase && !creating && viewingOrderId != null) {
            val o = try { container.vapi.getOrder(viewingOrderId!!).data } catch (_: Exception) { null }
            viewingStatus = o?.status
            viewingDropship = o?.dropshipCustomerId != null
        } else { viewingStatus = null; viewingDropship = false }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (creating || viewingOrderId != null) { creating = false; viewingOrderId = null; viewingPurchase = false } else onBack()
    }

    // Overlay tạo đơn / chi tiết đơn (full-screen) — dùng chung SaleOrderForm.
    if (creating || viewingOrderId != null) {
        Column(
            Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()
                // Vuốt phải (>90px) → về trang gốc (list). Item row tự consume vuốt-trái-xoá
                // nên không xung đột; vuốt phải ở vùng trống của form sẽ thoát.
                .pointerInput(Unit) {
                    var dragAccum = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = { if (dragAccum > 90f) { creating = false; viewingOrderId = null; viewingPurchase = false }; dragAccum = 0f },
                        onDragCancel = { dragAccum = 0f },
                    ) { _, dx -> dragAccum += dx }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().background(AdminColors.Card).height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { creating = false; viewingOrderId = null; viewingPurchase = false }) { Icon(Icons.Default.ArrowBack, "Quay lại", tint = AdminColors.Text) }
                val headerTitle = when {
                    creating && viewingPurchase -> "Tạo đơn nhập"
                    creating -> "Tạo đơn bán"
                    else -> "Chi tiết đơn"
                }
                Text(headerTitle, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                // Tùy chọn 3 chấm — chỉ đơn nhập ĐÃ LƯU (đang xem chi tiết): Gửi NCC (ảnh) / chia sẻ / xóa.
                val curId = viewingOrderId
                if (viewingPurchase && !creating && curId != null) {
                    Box {
                        IconButton(onClick = { headerMenuOpen = true }, enabled = !headerBusy) {
                            if (headerBusy) CircularProgressIndicator(Modifier.size(20.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                            else Icon(Icons.Default.MoreVert, "Tùy chọn", tint = AdminColors.Text)
                        }
                        DropdownMenu(
                            expanded = headerMenuOpen,
                            onDismissRequest = { headerMenuOpen = false },
                            modifier = Modifier
                                .background(AdminColors.Card)
                                .border(0.5.dp, AdminColors.Border, RoundedCornerShape(8.dp)),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Gửi NCC", color = AdminColors.Text) },
                                leadingIcon = { Icon(Icons.Default.Image, null, tint = AdminColors.Text, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    headerMenuOpen = false
                                    scope.launch {
                                        headerBusy = true
                                        try {
                                            val od = container.vapi.getOrder(curId).data
                                            val data = container.vapi.supplierImageItems(curId).data
                                            if (data == null || data.items.isEmpty()) {
                                                Toast.makeText(context, "Đơn chưa có mặt hàng", Toast.LENGTH_SHORT).show()
                                                return@launch
                                            }
                                            val dateStr = poDateStr(od?.orderedAt)
                                            val note = od?.notes?.trim().orEmpty()
                                            val (bmp, uri, sizeStr) = withContext(Dispatchers.Default) {
                                                val b = renderSupplierBitmap(data, dateStr, note)
                                                val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                                                val file = java.io.File(dir, "don-nhap-${data.orderCode.ifBlank { "po" }}.png")
                                                java.io.FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                                val u = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                Triple(b, u, fmtBytesA(file.length()))
                                            }
                                            // Hiện dialog preview — người dùng tự chọn Copy/Chia sẻ/Tải.
                                            supImgTitle = if (data.supplierShortName.isNotBlank()) "Đơn nhập hàng - ${data.supplierShortName}" else "Đơn nhập hàng"
                                            supImgBitmap = bmp; supImgUri = uri; supImgSize = sizeStr
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Tạo ảnh thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
                                        } finally { headerBusy = false }
                                    }
                                },
                            )
                            HorizontalDivider(color = AdminColors.Border)
                            DropdownMenuItem(
                                text = { Text("Chia sẻ", color = AdminColors.Text) },
                                leadingIcon = { Icon(Icons.Default.Share, null, tint = AdminColors.Text, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    headerMenuOpen = false
                                    scope.launch {
                                        try {
                                            val code = container.vapi.getOrder(curId).data?.code ?: ""
                                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_TEXT, "Đơn nhập $code")
                                            }
                                            context.startActivity(android.content.Intent.createChooser(send, "Chia sẻ đơn nhập"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Chia sẻ thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            )
                            // Xóa đơn nhập — CHỈ khi đơn đang NHÁP.
                            if (viewingStatus == "draft") {
                                HorizontalDivider(color = AdminColors.Border)
                                DropdownMenuItem(
                                    text = { Text("Xóa đơn nhập", color = Color(0xFFE88080)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFE88080), modifier = Modifier.size(20.dp)) },
                                    onClick = { headerMenuOpen = false; showDeleteConfirm = true },
                                )
                            }
                        }
                    }
                }
            }

            // Dialog xác nhận xóa đơn nhập (drop-ship → BE tự xoá kèm đơn bán liên kết).
            if (showDeleteConfirm) {
                val delId = viewingOrderId
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Xóa đơn nhập", color = AdminColors.Text) },
                    text = {
                        Text(
                            "Xóa đơn nhập này?" + (if (viewingDropship) " Đơn giao thẳng sẽ xoá kèm đơn bán liên kết." else "") + " Không thể hoàn tác.",
                            color = AdminColors.TextMuted,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            if (delId != null) scope.launch {
                                headerBusy = true
                                try {
                                    container.vapi.deleteOrder(delId)
                                    Toast.makeText(context, "Đã xóa đơn nhập", Toast.LENGTH_SHORT).show()
                                    creating = false; viewingOrderId = null; viewingPurchase = false
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Xóa đơn nhập thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally { headerBusy = false }
                            }
                        }) { Text("Xóa", color = Color(0xFFE88080)) }
                    },
                    dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Hủy", color = AdminColors.TextMuted) } },
                    containerColor = AdminColors.Card,
                )
            }

            // Dialog preview ảnh gửi NCC — người dùng tự chọn Copy / Chia sẻ / Tải ảnh.
            val previewBmp = supImgBitmap
            val previewUri = supImgUri
            if (previewBmp != null && previewUri != null) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { supImgBitmap = null; supImgUri = null },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    // Viền sáng "lan tỏa" CHỈ render sau khi dialog đã hiện (fade-in từ 0).
                    var glowOn by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { glowOn = true }
                    val glow by animateFloatAsState(if (glowOn) 1f else 0f, animationSpec = tween(450), label = "glow")
                    androidx.compose.foundation.layout.Column(
                        Modifier.fillMaxWidth(0.95f)
                            .shadow((18 * glow).dp, RoundedCornerShape(12.dp), ambientColor = AdminColors.Primary, spotColor = AdminColors.Primary, clip = false)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AdminColors.Card)
                            .border(1.dp, AdminColors.Primary.copy(alpha = 0.7f * glow), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Text(supImgTitle, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.foundation.Image(
                            bitmap = previewBmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(0.95f).align(Alignment.CenterHorizontally).heightIn(max = 460.dp).clip(RoundedCornerShape(6.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            TextButton(onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "Ảnh gửi NCC", previewUri))
                                Toast.makeText(context, "Đã copy ảnh — dán vào chat NCC", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Default.ContentCopy, null, tint = AdminColors.Primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Copy", color = AdminColors.Primary) }
                            TextButton(onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(android.content.Intent.EXTRA_STREAM, previewUri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(send, "Gửi NCC").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Icon(Icons.Default.Share, null, tint = AdminColors.Text, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Chia sẻ", color = AdminColors.Text) }
                            TextButton(onClick = {
                                val ok = saveBitmapToGallery(context, previewBmp, "don-nhap")
                                Toast.makeText(context, if (ok) "Đã lưu ảnh vào thư viện" else "Lưu ảnh thất bại", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Default.Download, null, tint = AdminColors.Text, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Tải ảnh", color = AdminColors.Text) }
                        }
                        if (supImgSize.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Dung lượng: $supImgSize", color = AdminColors.TextMuted, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }

            SaleOrderForm(orderId = viewingOrderId, isPurchase = viewingPurchase, onDone = { creating = false; viewingOrderId = null; viewingPurchase = false })
        }
        return
    }

    Column(Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()) {
        // Tab bar 3 tab (BỎ nút back — dùng system back để thoát module).
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = AdminColors.Card,
            contentColor = AdminColors.Primary,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            SaleTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label, fontSize = 13.sp, color = if (tab == t) AdminColors.Primary else AdminColors.TextMuted) },
                )
            }
        }

        // Vuốt trái/phải đổi tab (threshold 80px). Vuốt xuống reload trong từng list.
        var dragAccum by remember { mutableStateOf(0f) }
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(tab) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAccum < -80f && tab.ordinal < SaleTab.entries.lastIndex) tab = SaleTab.entries[tab.ordinal + 1]
                        else if (dragAccum > 80f) {
                            if (tab.ordinal > 0) tab = SaleTab.entries[tab.ordinal - 1]
                            else onBack()   // tab đầu (Đơn hàng) vuốt phải → thoát module về Khám phá
                        }
                        dragAccum = 0f
                    },
                ) { _, dx -> dragAccum += dx }
            },
        ) {
            when (tab) {
                SaleTab.ORDERS -> {
                    SaleOrdersList(onTapOrder = { viewingOrderId = it; viewingPurchase = false })
                    if (canCreate) {
                        FloatingActionButton(
                            onClick = { creating = true; viewingPurchase = false },
                            containerColor = AdminColors.Primary,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) { Icon(Icons.Default.Add, "Tạo đơn bán") }
                    }
                }
                SaleTab.PURCHASES -> {
                    SalePurchasesList(onTapOrder = { viewingOrderId = it; viewingPurchase = true })
                    if (canCreate) {
                        FloatingActionButton(
                            onClick = { creating = true; viewingPurchase = true },
                            containerColor = Color(0xFFEC4899),   // hồng — phân biệt với đơn bán
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) { Icon(Icons.Default.Add, "Tạo đơn nhập") }
                    }
                }
                SaleTab.PRODUCTS -> SaleProductsList()
                SaleTab.CUSTOMERS -> SaleCustomersList()
            }
        }
    }
}
