package vn.chat9.app.ui.modules.sale

import vn.chat9.app.ui.common.dialogGlow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import vn.chat9.app.ui.modules.warehouse.ScanPayFlow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
fun SaleScreen(onBack: () -> Unit, initialOrderId: Long? = null) {
    var tab by remember { mutableStateOf(SaleTab.ORDERS) }
    var creating by remember { mutableStateOf(false) }
    // Hoist scroll-state 4 tab ở SaleScreen (sống suốt khi đổi tab / vào chi tiết) → giữ vị trí
    // cuộn từng tab, chỉ reset khi rời module. Mirror scroll-memory của web.
    val ordersListState = rememberLazyListState()
    val purchasesListState = rememberLazyListState()
    val productsListState = rememberLazyListState()
    val customersListState = rememberLazyListState()
    var viewingOrderId by remember { mutableStateOf(initialOrderId) } // tap đơn → chi tiết/edit; init khi mở thẳng từ thẻ
    var viewingPurchase by remember { mutableStateOf(false) }        // đơn đang tạo/xem là đơn nhập?
    var viewingDebtLocked by remember { mutableStateOf(false) }      // đơn đang xem đã chốt công nợ → badge header
    var viewingStatus by remember { mutableStateOf<String?>(null) }  // trạng thái đơn đang xem → badge header + gate "Xóa"
    // Đổi đơn đang xem → reset badge; SaleOrderForm sẽ báo lại qua callback sau khi load.
    LaunchedEffect(viewingOrderId) { viewingDebtLocked = false }

    // Mở THẲNG 1 đơn từ thẻ (initialOrderId): back thoát về nơi gọi (chat) thay
    // vì về list; + xác định đơn nhập/bán để form hiện đúng nhãn.
    val exitOrderView: () -> Unit = {
        if (initialOrderId != null) onBack()
        else { creating = false; viewingOrderId = null; viewingPurchase = false }
    }

    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    // Mở thẳng 1 đơn từ thẻ: xác định đơn nhập/bán để form hiện đúng nhãn.
    LaunchedEffect(Unit) {
        if (initialOrderId != null) {
            val o = try { container.vapi.getOrder(initialOrderId).data } catch (_: Exception) { null }
            if (o != null) viewingPurchase = o.isPurchase
        }
    }

    // Quyền tạo đơn: ẩn nút nếu thiếu order.create (UX — server cũng chặn thật qua X-Staff-Phone).
    val perms by container.permissions.state.collectAsState()
    val canCreate = perms.bypass_all || "order.create" in perms.permissions

    // Menu 3 chấm header đơn nhập đã lưu
    var headerMenuOpen by remember { mutableStateOf(false) }
    var headerBusy by remember { mutableStateOf(false) }
    var viewingDropship by remember { mutableStateOf(false) }          // đơn giao thẳng? (cảnh báo xoá kèm đơn bán)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // ===== Gộp ship: trả CẢ HAI loại phí ship bằng MỘT lần chuyển khoản =====
    // Hai khoản mang chung một mã đối soát, mỗi khoản giữ phần tiền riêng; khi GD
    // về, matcher gom theo mã rồi tất toán từng khoản ĐÚNG TRỤC của nó (ứng ship →
    // customer_debt, chi phí kho → expense) trong cùng một settleMoneyOut.
    var shipGroupOpen by remember { mutableStateOf(false) }
    var shipGroupIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var shipGroupAmount by remember { mutableStateOf(0L) }
    var shipGroupNote by remember { mutableStateOf("") }
    // Ảnh gửi NCC: giữ bitmap + uri để preview trong dialog trước khi Copy/Chia sẻ/Tải.
    var supImgBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var supImgUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var supImgSize by remember { mutableStateOf("") }
    var supImgTitle by remember { mutableStateOf("Đơn nhập hàng") }
    // Ảnh gửi KHÁCH (đơn bán): cache data để render lại khi tắt ảnh đính kèm; ratio đặt nút ×.
    var custImgData by remember { mutableStateOf<vn.chat9.app.data.vapi.dto.SupplierImageDto?>(null) }
    var custImgName by remember { mutableStateOf("") }
    var custImgDate by remember { mutableStateOf("") }
    var custIncludeAttach by remember { mutableStateOf(true) }
    var custAttachTopRatio by remember { mutableStateOf<Float?>(null) }   // null = ảnh NCC hoặc không có ảnh đính kèm
    var custAttachRightRatio by remember { mutableStateOf<Float?>(null) }  // mép phải ảnh đính kèm (đặt tâm nút ×)

    // Render + lưu JPEG ảnh khách → cập nhật preview (dùng cho menu "Gửi đơn" và nút × tắt ảnh).
    val buildCustomerImg: suspend (vn.chat9.app.data.vapi.dto.SupplierImageDto, String, String, Boolean) -> Unit = { d, name, date, include ->
        val (bmp, topRatio, rightRatio) = withContext(Dispatchers.Default) { renderCustomerBitmap(d, name, date, include) }
        val (uri, sizeStr) = withContext(Dispatchers.Default) {
            val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
            val file = java.io.File(dir, "don-${d.orderCode.ifBlank { "x" }}.jpg")
            java.io.FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) to fmtBytesA(file.length())
        }
        supImgBitmap = bmp; supImgUri = uri; supImgSize = sizeStr
        custAttachTopRatio = if (include) topRatio else null
        custAttachRightRatio = if (include) rightRatio else null
    }

    // "Gửi đơn" ảnh khách (suspend) — nạp đơn + mặt hàng rồi mở dialog preview; tự toast lỗi/rỗng, không ném.
    suspend fun doSendOrder(oid: Long) {
        try {
            val od = container.vapi.getOrder(oid).data
            val data = container.vapi.supplierImageItems(oid).data
            if (data == null || data.items.isEmpty()) {
                Toast.makeText(context, "Đơn chưa có mặt hàng", Toast.LENGTH_SHORT).show()
                return
            }
            val custName = data.partyName.ifBlank { "Khách lẻ" }
            val dateStr = poDateStr(od?.orderedAt)
            custImgData = data; custImgName = custName; custImgDate = dateStr; custIncludeAttach = true
            supImgTitle = "Đơn hàng - $custName"
            buildCustomerImg(data, custName, dateStr, true)
        } catch (e: Exception) {
            Toast.makeText(context, "Tạo ảnh thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    // Menu 3 chấm: spinner ở icon menu (headerBusy). Nút Send trong form dùng spinner riêng của nút.
    fun sendOrderImage(oid: Long) { scope.launch { headerBusy = true; try { doSendOrder(oid) } finally { headerBusy = false } } }

    // Nạp tình trạng đơn nhập đang xem → chỉ hiện "Xóa" khi đơn NHÁP.
    LaunchedEffect(viewingOrderId, viewingPurchase, creating) {
        if (viewingPurchase && !creating && viewingOrderId != null) {
            val o = try { container.vapi.getOrder(viewingOrderId!!).data } catch (_: Exception) { null }
            viewingStatus = o?.status
            viewingDropship = o?.dropshipCustomerId != null
        } else { viewingStatus = null; viewingDropship = false }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (creating || viewingOrderId != null) exitOrderView() else onBack()
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
                        onDragEnd = { if (dragAccum > 90f) { exitOrderView() }; dragAccum = 0f },
                        onDragCancel = { dragAccum = 0f },
                    ) { _, dx -> dragAccum += dx }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().background(AdminColors.Card).height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { exitOrderView() }) { Icon(Icons.Default.ArrowBack, "Quay lại", tint = AdminColors.Text) }
                val headerTitle = when {
                    creating && viewingPurchase -> "Tạo đơn nhập"
                    creating -> "Tạo đơn bán"
                    else -> "Chi tiết đơn"
                }
                Text(headerTitle, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                // Badge "Đã chốt công nợ" ngay trên header khi xem đơn đã chốt (thay dòng riêng trong form).
                if (!creating && viewingDebtLocked) {
                    Text("Đã chốt công nợ", color = AdminColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(4.dp))
                            .background(AdminColors.Primary.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                // Badge TRẠNG THÁI đơn — bên phải, sát nút 3 chấm.
                if (!creating && viewingStatus != null) {
                    val st = viewingStatus!!
                    val stColor = when (st) {
                        "confirmed", "processing", "shipped" -> AdminColors.Warning
                        "delivered", "completed", "received" -> if (viewingDebtLocked) AdminColors.Success else AdminColors.Warning
                        "cancelled", "refunded" -> AdminColors.Danger
                        else -> AdminColors.TextMuted   // draft + khác
                    }
                    val stLabel = when (st) {
                        "draft" -> "Nháp"; "confirmed" -> "Đã xác nhận"; "processing" -> "Đang xử lý"; "shipped" -> "Đang giao"
                        "delivered" -> "Đã giao"; "received" -> "Đã nhận"; "completed" -> "Hoàn thành"
                        "cancelled" -> "Huỷ"; "refunded" -> "Hoàn tiền"; else -> st
                    }
                    Text(stLabel, color = stColor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(4.dp))
                            .background(stColor.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                // Tùy chọn 3 chấm — đơn ĐÃ LƯU: đơn nhập → Gửi NCC/chia sẻ/xóa; đơn bán → Gửi đơn (ảnh khách).
                val curId = viewingOrderId
                if (!creating && curId != null) {
                    Box {
                        IconButton(onClick = { headerMenuOpen = true }, enabled = !headerBusy) {
                            if (headerBusy) CircularProgressIndicator(Modifier.size(20.dp), color = AdminColors.Primary, strokeWidth = 2.dp)
                            else Icon(Icons.Default.MoreVert, "Tùy chọn", tint = AdminColors.Text)
                        }
                        DropdownMenu(
                            expanded = headerMenuOpen,
                            onDismissRequest = { headerMenuOpen = false },
                            offset = androidx.compose.ui.unit.DpOffset(x = (-24).dp, y = 4.dp),   // lùi khỏi lề phải cho dễ nhìn
                            modifier = Modifier
                                .background(AdminColors.Card)
                                .border(0.5.dp, AdminColors.Border, RoundedCornerShape(8.dp)),
                        ) {
                          if (!viewingPurchase) {
                            // Đơn bán → "Gửi đơn": ảnh đơn hàng gửi khách (có giá + tổng + ảnh giao hàng).
                            DropdownMenuItem(
                                text = { Text("Gửi đơn", color = AdminColors.Text) },
                                leadingIcon = { Icon(Icons.Default.Image, null, tint = AdminColors.Text, modifier = Modifier.size(20.dp)) },
                                onClick = { headerMenuOpen = false; sendOrderImage(curId) },
                            )
                            HorizontalDivider(color = AdminColors.Border)
                            DropdownMenuItem(
                                text = { Text("Gộp ship", color = AdminColors.Text) },
                                leadingIcon = { Icon(Icons.Default.QrCodeScanner, null, tint = AdminColors.Text, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    headerMenuOpen = false
                                    scope.launch {
                                        headerBusy = true
                                        try {
                                            // Mọi điều kiện do BE quyết (đã giao, phí > 0, khoản chi
                                            // còn phải trả) — ở đây chỉ hiện lý do BE trả về.
                                            val sp = container.vapi.shipPayables(curId).data
                                            if (sp == null || !sp.group.payable) {
                                                Toast.makeText(context, sp?.group?.reason ?: "Không gộp được", Toast.LENGTH_LONG).show()
                                                return@launch
                                            }
                                            val advanceId = sp.customer.expenseId
                                            val companyId = sp.company.expenseId
                                            if (advanceId == null || companyId == null) {
                                                Toast.makeText(context, "Thiếu khoản chi để gộp — dùng nút QR trên từng dòng phí", Toast.LENGTH_LONG).show()
                                                return@launch
                                            }
                                            shipGroupIds = listOf(advanceId, companyId)
                                            // Tổng BE chốt; ScanPayFlow còn ghi đè lần nữa bằng `total`
                                            // của lượt cấp mã gộp.
                                            shipGroupAmount = (sp.group.total ?: 0.0).toLong()
                                            shipGroupNote = "Tra ship gop ${container.vapi.getOrder(curId).data?.code.orEmpty()}".trim()
                                            shipGroupOpen = true
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Không mở được gộp ship: ${e.message}", Toast.LENGTH_SHORT).show()
                                        } finally { headerBusy = false }
                                    }
                                },
                            )
                          } else {
                            DropdownMenuItem(
                                text = { Text("Gửi NCC", color = AdminColors.Text) },
                                leadingIcon = { Icon(Icons.Default.Image, null, tint = AdminColors.Text, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    headerMenuOpen = false
                                    custImgData = null; custAttachTopRatio = null; custAttachRightRatio = null   // ảnh NCC, không phải ảnh khách
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
                          }   // /else (đơn nhập)
                        }
                    }
                }
            }

            // Gộp ship: quét QR người nhận → 1 mã chung cho 2 khoản → lưu ảnh → mở app NH.
            if (shipGroupOpen) {
                ScanPayFlow(
                    expenseId = null,
                    expenseIds = shipGroupIds,
                    amount = shipGroupAmount,
                    note = shipGroupNote,
                    onDismiss = { shipGroupOpen = false },
                )
            }

            // Dialog xác nhận xóa đơn nhập (drop-ship → BE tự xoá kèm đơn bán liên kết).
            if (showDeleteConfirm) {
                val delId = viewingOrderId
                AlertDialog(
                    modifier = Modifier.dialogGlow(),
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
                                    exitOrderView()
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
                val isCust = custImgData != null            // ảnh khách (JPEG + nút × ảnh giao hàng)
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { supImgBitmap = null; supImgUri = null; custImgData = null; custAttachTopRatio = null; custAttachRightRatio = null },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    val dismiss = { supImgBitmap = null; supImgUri = null; custImgData = null; custAttachTopRatio = null; custAttachRightRatio = null }
                    // Tắt scrim mặc định của Dialog + phủ toàn màn để TỰ vẽ lớp tối (khoét lỗ vùng dialog).
                    val dialogView = LocalView.current
                    LaunchedEffect(Unit) {
                        (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                            w.setDimAmount(0f)
                            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                            // Ẩn navigation bar khi dialog hiện (cửa sổ Dialog là window riêng nên phải set tại đây).
                            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                                hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            }
                        }
                    }
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { dismiss() } },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Scrim tối phủ TOÀN BỘ màn (kể cả sau dialog).
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Color.Black.copy(alpha = 0.55f))
                        }
                        // Viền sáng "lan tỏa" CHỈ render sau khi dialog đã hiện (fade-in từ 0).
                        var glowOn by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { glowOn = true }
                        val glow by animateFloatAsState(if (glowOn) 1f else 0f, animationSpec = tween(450), label = "glow")
                        androidx.compose.foundation.layout.Column(
                            Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f)   // cố định cao dialog để chia tỉ lệ vùng ảnh
                                .pointerInput(Unit) { detectTapGestures { } }   // tap trên card KHÔNG đóng dialog
                                .shadow((18 * glow).dp, RoundedCornerShape(12.dp), ambientColor = AdminColors.Primary, spotColor = AdminColors.Primary, clip = false)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.86f))   // nền đen 86%
                                .border(1.dp, AdminColors.Primary.copy(alpha = 0.7f * glow), RoundedCornerShape(12.dp))
                                .padding(bottom = 6.dp),
                        ) {
                        val density = LocalDensity.current
                        var imgBoxW by remember { mutableStateOf(0) }
                        // Header: tiêu đề, KHÔNG nền (trong suốt).
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(supImgTitle, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                        Spacer(Modifier.height(3.dp))
                        // Box 2 (weight 1f): ảnh cuộn + 3 nút tác vụ OVERLAY đáy ảnh (không nền, không khoảng cách).
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                        // Ảnh — KHÔNG nền, phủ hết box 2 (dialog − header − padding đáy); tràn thì cuộn dọc.
                        Box(
                            Modifier.fillMaxSize().padding(horizontal = 6.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        ) {
                            Box(Modifier.fillMaxWidth().onGloballyPositioned { imgBoxW = it.size.width }) {
                                androidx.compose.foundation.Image(
                                    bitmap = previewBmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                                )
                                // Nút × — TÂM đặt đúng góc trên-phải ảnh đính kèm (dùng top+right ratio).
                                val topR = custAttachTopRatio; val rightR = custAttachRightRatio
                                if (isCust && topR != null && rightR != null && imgBoxW > 0) {
                                    val drawnH = imgBoxW * previewBmp.height.toFloat() / previewBmp.width.toFloat()
                                    val cornerXDp = with(density) { (rightR * imgBoxW).toDp() }
                                    val cornerYDp = with(density) { (topR * drawnH).toDp() }
                                    // Badge 30dp (×3 so với 10dp cũ); offset trừ nửa cạnh để TÂM rơi đúng góc.
                                    IconButton(
                                        onClick = { scope.launch { custIncludeAttach = false; buildCustomerImg(custImgData!!, custImgName, custImgDate, false) } },
                                        modifier = Modifier.offset(x = cornerXDp - 15.dp, y = cornerYDp - 15.dp).size(30.dp),
                                    ) {
                                        Box(
                                            Modifier.size(30.dp).background(Color(0x66000000), CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) { Icon(Icons.Default.Close, "Bỏ ảnh giao hàng", tint = Color.White, modifier = Modifier.size(24.dp)) }
                                    }
                                }
                            }
                        }
                        // Hàng 3 nút tác vụ — OVERLAY đáy giữa ảnh, nền trong suốt, viền pill.
                        val pillShape = RoundedCornerShape(50)
                        val pillPad = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        val pillBg = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x99CCCCCC))   // nền xám nhẹ
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            OutlinedButton(shape = pillShape, contentPadding = pillPad, colors = pillBg, modifier = Modifier.height(32.dp), border = androidx.compose.foundation.BorderStroke(1.dp, AdminColors.Border), onClick = {
                                val fname = if (isCust) "don-${custImgData?.orderCode ?: ""}" else "don-nhap"
                                val ok = saveBitmapToGallery(context, previewBmp, fname, jpeg = isCust)
                                Toast.makeText(context, if (ok) "Đã lưu ảnh vào thư viện" else "Lưu ảnh thất bại", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Default.Download, null, tint = AdminColors.Text, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Tải ảnh", color = AdminColors.Text, fontSize = 13.sp) }
                            OutlinedButton(shape = pillShape, contentPadding = pillPad, colors = pillBg, modifier = Modifier.height(32.dp), border = androidx.compose.foundation.BorderStroke(1.dp, AdminColors.Border), onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = if (isCust) "image/jpeg" else "image/png"
                                    putExtra(android.content.Intent.EXTRA_STREAM, previewUri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(send, "Chia sẻ đơn").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Icon(Icons.Default.Share, null, tint = AdminColors.Text, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Chia sẻ", color = AdminColors.Text, fontSize = 13.sp) }
                            OutlinedButton(shape = pillShape, contentPadding = pillPad, colors = pillBg, modifier = Modifier.height(32.dp), border = androidx.compose.foundation.BorderStroke(1.dp, AdminColors.Primary.copy(alpha = 0.6f)), onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "Ảnh đơn hàng", previewUri))
                                Toast.makeText(context, "Đã copy ảnh — dán vào chat", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Default.ContentCopy, null, tint = AdminColors.Primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Copy", color = AdminColors.Primary, fontSize = 13.sp) }
                        }
                        }   // /Box 2
                        // Dung lượng — KHÔNG nền, cao vừa đủ 1 dòng.
                        if (supImgSize.isNotEmpty()) {
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier.align(Alignment.CenterHorizontally)
                                    .padding(3.dp),
                            ) { Text("Dung lượng: $supImgSize", color = AdminColors.TextMuted, fontSize = 11.sp) }
                        }
                    }
                    }   // /Box scrim (khoét lỗ vùng dialog)
                }
            }

            // Chi tiết đơn: cho sửa cả đơn đã xác nhận/đã giao (allowEditAnyStatus) — BE + cờ khoá từng
            // trường (ship đã báo/chi, ứng CK) là chốt chặn. Tạo mới đơn nháp không truyền cờ (autosave).
            // onSend: chỉ đơn bán ĐÃ LƯU (không tạo mới, không đơn nhập) → nút Send "Gửi đơn" trong form.
            val sendCb: (suspend () -> Unit)? = if (!viewingPurchase && !creating && viewingOrderId != null) ({ viewingOrderId?.let { doSendOrder(it) } }) else null
            SaleOrderForm(orderId = viewingOrderId, isPurchase = viewingPurchase, allowEditAnyStatus = !creating && viewingOrderId != null, onDebtLockedChange = { viewingDebtLocked = it }, onStatusChange = { viewingStatus = it }, onSend = sendCb, onDone = { exitOrderView() })
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
                    SaleOrdersList(onTapOrder = { viewingOrderId = it; viewingPurchase = false }, listState = ordersListState)
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
                    SalePurchasesList(onTapOrder = { viewingOrderId = it; viewingPurchase = true }, listState = purchasesListState)
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
                SaleTab.PRODUCTS -> SaleProductsList(listState = productsListState)
                SaleTab.CUSTOMERS -> SaleCustomersList(listState = customersListState)
            }
        }
    }
}
