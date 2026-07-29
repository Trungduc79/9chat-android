package vn.chat9.app.ui.modules.warehouse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.QrRefGroupRequest
import vn.chat9.app.data.vapi.dto.StaffDto
import vn.chat9.app.data.vapi.dto.QrRefRequest
import vn.chat9.app.data.vapi.dto.VietqrLookupRequest
import vn.chat9.app.ui.common.dialogGlow
import vn.chat9.app.ui.modules.sale.saveBitmapToGallery
import vn.chat9.app.util.VietQr
import vn.chat9.app.ui.explore.AdminColors as C

/**
 * Trả tiền ship bằng cách QUÉT QR NGƯỜI NHẬN — 5 bước tuần tự, có chờ thật:
 *
 *   ① quét QR người nhận
 *   ② lấy đủ thông tin (tên chủ TK + mã đối soát)
 *   ③ dựng mã QR trả tiền
 *   ④ lưu ảnh về máy — PHẢI xong mới sang bước ⑤
 *   ⑤ mở app ngân hàng
 *
 * Port từ web `ScanPayFlow.vue`, nhưng Android ĐƠN GIẢN HƠN ở đúng chỗ khó nhất:
 * `saveBitmapToGallery()` trả Boolean đồng bộ nên biết chắc ảnh đã lưu hay chưa.
 * Web không có tín hiệu đó (trình duyệt không phát sự kiện "tải xong") nên phải
 * đoán bằng thời gian và phải có đường lui bấm tay — ở đây bỏ được hết.
 *
 * Sinh QR OFFLINE bằng zxing: payload chứa số TK + số tiền + nội dung, đẩy qua
 * dịch vụ ngoài là rò dữ liệu tài chính.
 */

private enum class StepState { IDLE, RUN, OK, FAIL }

/** Bỏ dấu + thường hoá để tìm "duc" ra "Đức" (cùng cách các tab kế toán đang dùng). */
private fun noAccent(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
        .lowercase()

@Composable
fun ScanPayFlow(
    /** Khoản chi để xin mã đối soát. Null = QR không tự khớp được. */
    expenseId: Long?,
    /**
     * Trả GỘP nhiều khoản bằng MỘT lần chuyển khoản: cả nhóm mang chung một mã,
     * mỗi khoản giữ phần tiền riêng. Có giá trị (>=2) thì bỏ qua `expenseId` và
     * số tiền QR lấy theo `total` server trả về, không phải `amount`.
     */
    expenseIds: List<Long>? = null,
    /**
     * Quyết định TIÊU ĐỀ — hai loại phí khác bản chất:
     *  'advance' = ứng ship hộ khách (SHIP_ADV) → "Tạm ứng tiền ship"
     *  'expense' = chi phí kho tự chịu (SHIP_EXP) → "Trả tiền ship"
     * Có expenseIds (gộp) thì đè cả hai → "Trả gộp ship".
     */
    kind: String = "expense",
    amount: Long,
    note: String,
    bankAppId: String = "acb",
    bankAppName: String = "ACB",
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as App).container }
    val scope = rememberCoroutineScope()

    /**
     * App ngân hàng mở ở bước ⑤ — mã app theo dl.vietqr.io. ACB mặc định, đổi
     * được vì tiền có thể đi từ tài khoản ngân hàng khác.
     */
    val bankApps = listOf(
        "acb" to "ACB", "stb" to "STB", "mb" to "MB",
        "tcb" to "TCB", "ocb" to "OCB", "bidv" to "BIDV",
    )
    var bankApp by remember(bankAppId) { mutableStateOf(bankAppId) }
    var bankMenuOpen by remember { mutableStateOf(false) }
    val bankName = bankApps.firstOrNull { it.first == bankApp }?.second ?: bankAppName

    /**
     * Tự đóng sau 60s KHÔNG THAO TÁC.
     *
     * `touch()` gia hạn; luồng nhích sang bước mới cũng gọi (đang chạy = đang
     * dùng), nếu không thì dialog đóng ngang lúc đang dựng QR và người dùng mất
     * công làm lại. Chỉ khoảng lặng thật mới tính.
     */
    var lastTouch by remember { mutableStateOf(0L) }
    fun touch() { lastTouch += 1 }

    val dialogTitle = when {
        (expenseIds?.size ?: 0) > 1 -> "Trả gộp ship"
        kind == "advance" -> "Tạm ứng tiền ship"
        else -> "Trả tiền ship"
    }

    val stepKeys = listOf("scan", "info", "build", "save", "open")
    val stepLabel = mapOf(
        "scan" to "Quét mã QR người nhận",
        "info" to "Lấy thông tin người nhận + mã đối soát",
        "build" to "Tạo mã QR trả tiền",
        "save" to "Lưu ảnh về máy",
        "open" to "Mở App",
    )
    val steps = remember { mutableStateMapOf<String, StepState>().apply { stepKeys.forEach { put(it, StepState.IDLE) } } }
    val stepNote = remember { mutableStateMapOf<String, String>() }

    /** Nhóm >= 2 khoản → đi đường cấp mã gộp. */
    val isGroup = (expenseIds?.size ?: 0) > 1

    /**
     * Số tiền THẬT nhúng vào QR. QR gộp phải dùng `total` server tính (tổng
     * remaining_amount), không phải `amount` truyền vào — hai số lệch nhau ngay
     * khi có khoản đã trả một phần, mà matcher thì chặn đúng ở chỗ so tổng.
     */
    var payAmount by remember(amount) { mutableStateOf(amount) }

    var payeeName by remember { mutableStateOf<String?>(null) }
    var payeeAccount by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var finalContent by remember { mutableStateOf("") }
    var hasRefCode by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    // Chặn quét lặp: ML Kit bắn liên tục mỗi khung hình.
    var handled by remember { mutableStateOf(false) }

    var hasCamPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamPermission = granted
        if (granted) { steps["scan"] = StepState.RUN; scanning = true }
        else { steps["scan"] = StepState.FAIL; stepNote["scan"] = "chưa cho phép camera" }
    }

    // ===== Tab: 0 = quét mã (mặc định), 1 = chọn nhân viên =====
    var tab by remember { mutableStateOf(0) }
    var staffList by remember { mutableStateOf<List<StaffDto>>(emptyList()) }
    var staffLoading by remember { mutableStateOf(false) }
    var staffError by remember { mutableStateOf<String?>(null) }
    var staffId by remember { mutableStateOf<Long?>(null) }
    var staffQuery by remember { mutableStateOf(TextFieldValue("")) }
    /** List mở hay không = ô có focus hay không (bám theo onFocusChanged). */
    var staffListOpen by remember { mutableStateOf(false) }
    /** false = ô readOnly → chạm KHÔNG bật bàn phím. true = mở bàn phím + bôi đen. */
    var staffKeyboardOn by remember { mutableStateOf(false) }
    /** Chiều cao ô — mũi tên lấy đúng 50% chiều cao này. */
    var staffFieldHeight by remember { mutableStateOf(0.dp) }
    val staffFocus = remember { FocusRequester() }
    val staffScroll = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    fun reset() {
        stepKeys.forEach { steps[it] = StepState.IDLE; stepNote[it] = "" }
        payeeName = null; payeeAccount = null; qrBitmap = null
        finalContent = ""; hasRefCode = false; handled = false
        payAmount = amount
    }

    fun startScanning() {
        handled = false
        if (hasCamPermission) { steps["scan"] = StepState.RUN; scanning = true }
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    /** Chỉ giữ nhân viên ĐÃ có đủ BIN + số TK — thiếu thì không dựng được QR. */
    fun loadStaff() {
        if (staffList.isNotEmpty() || staffLoading) return
        staffLoading = true
        staffError = null
        scope.launch {
            val res = runCatching { container.vapi.staffList().data.orEmpty() }
            staffList = res.getOrNull()
                ?.filter { !it.bankBin.isNullOrBlank() && !it.bankAccount.isNullOrBlank() }
                .orEmpty()
            if (res.isFailure) {
                // Route gác permission 'user.read' — TK nhân viên kho thường bị 403.
                staffError = "Không tải được danh sách nhân viên (có thể tài khoản không đủ quyền)."
            }
            staffLoading = false
        }
    }

    fun openBankApp() {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dl.vietqr.io/pay?app=$bankApp")))
        }.onFailure {
            steps["open"] = StepState.FAIL
            stepNote["open"] = "không mở được app"
        }
    }

    /** ② → ⑤ sau khi quét được. */
    /**
     * @param knownName Tên chủ TK đã biết sẵn (chọn từ hồ sơ nhân viên) → BỎ QUA
     *   lần gọi VietQR. Lookup là API bên thứ 3 CÓ HẠN MỨC; hỏi lại thứ mình đã
     *   có trong DB là đốt quota vô ích.
     */
    fun runAfterScan(bankBin: String, accountNo: String, knownName: String? = null) {
        scope.launch {
            // ② CHỜ đủ thông tin rồi mới đi tiếp. Timeout để một API chậm không treo luồng.
            steps["info"] = StepState.RUN
            val nameRes = knownName ?: withTimeoutOrNull(8_000) {
                runCatching { container.vapi.vietqrLookup(VietqrLookupRequest(bankBin, accountNo)).data?.accountName }.getOrNull()
            }
            payeeName = nameRes
            if (nameRes == null) stepNote["info"] = "không tra được tên chủ TK"

            var content = normalizePayoutContent(note)
            if (isGroup) {
                val g = withTimeoutOrNull(8_000) {
                    runCatching {
                        // KHÔNG gửi note — BE tự dựng "GOP PHI SHIP - {tên khách} ORD-XXXXXX".
                        container.vapi.issueQrRefGroup(QrRefGroupRequest(expenseIds!!, null)).data
                    }.getOrNull()
                }
                if (g != null) {
                    content = normalizePayoutContent(g.content)
                    // Tổng do server chốt — QR phải mang ĐÚNG số này mới tự đối soát.
                    payAmount = g.total.toLong()
                    hasRefCode = true
                } else {
                    hasRefCode = false
                    stepNote["info"] = listOfNotNull(stepNote["info"], "không xin được mã đối soát gộp")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                }
            } else if (expenseId != null) {
                val res = withTimeoutOrNull(8_000) {
                    runCatching {
                        // KHÔNG gửi note: để BE tự lấy description của khoản chi
                        // ("Ứng tiền ship - Trung Đức [ORD-000466]" → "UNG TIEN SHIP -
                        // TRUNG DUC ORD-000466"). Client tự chế câu sẽ lệch giữa 4 màn
                        // và thiếu tên khách. `note` chỉ còn dùng cho nhánh dự phòng
                        // khi KHÔNG xin được mã.
                        container.vapi.issueQrRef(expenseId, QrRefRequest(amount.toDouble(), null)).data
                    }
                }
                val ref = res?.getOrNull()
                if (ref != null) {
                    content = normalizePayoutContent(ref.content)
                    hasRefCode = true
                } else {
                    // 409 = server TỪ CHỐI vì không còn gì để trả (NOTHING_TO_PAY)
                    // hoặc khoản đã huỷ. Đây KHÔNG phải lỗi tạm — đi tiếp là dựng
                    // QR mang đủ số tiền cho một khoản đã trả rồi → trả trùng.
                    // Dừng hẳn. Các lỗi khác (mạng, timeout) vẫn cho đi tiếp,
                    // chỉ mất phần tự đối soát.
                    val http = res?.exceptionOrNull() as? retrofit2.HttpException
                    if (http?.code() == 409) {
                        steps["info"] = StepState.FAIL
                        stepNote["info"] = "khoản này không còn phải trả — đã thanh toán/đối soát"
                        return@launch
                    }
                    hasRefCode = false
                    stepNote["info"] = listOfNotNull(stepNote["info"], "không xin được mã đối soát")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                }
            }
            finalContent = content
            steps["info"] = StepState.OK

            // ③ Dựng QR (offline)
            steps["build"] = StepState.RUN
            val bmp = runCatching {
                encodeQrBitmap(VietQr.buildPayload(bankBin, accountNo, payAmount, content))
            }.getOrNull()
            if (bmp == null) {
                steps["build"] = StepState.FAIL
                stepNote["build"] = "lỗi dựng mã"
                return@launch
            }
            qrBitmap = bmp   // hiển thị trong dialog: mã trần, thông tin đã có sẵn ở các dòng trên
            steps["build"] = StepState.OK

            // ④ Lưu ảnh — Android trả Boolean đồng bộ, biết chắc xong hay chưa.
            // Ảnh LƯU RA có thêm STK + số tiền + nội dung: ra khỏi app là mất ngữ
            // cảnh, người mở ảnh chỉ thấy ô vuông đen trắng.
            steps["save"] = StepState.RUN
            val fileName = "qr-" + (content.ifBlank { "tra-ship" }).replace(Regex("\\s+"), "-")
            val saved = saveBitmapToGallery(
                ctx,
                renderQrShareBitmap(bmp, accountNo, payAmount, content),
                fileName,
            )
            if (!saved) {
                steps["save"] = StepState.FAIL
                stepNote["save"] = "không lưu được ảnh"
                return@launch
            }
            steps["save"] = StepState.OK

            // ⑤ Chỉ mở app khi ảnh đã nằm trong máy.
            steps["open"] = StepState.RUN
            openBankApp()
            if (steps["open"] != StepState.FAIL) steps["open"] = StepState.OK
        }
    }

    fun onQrText(text: String) {
        if (handled) return
        val d = VietQr.parsePayload(text)
        if (d?.bankBin == null || d.accountNo == null) return  // không phải QR NH → quét tiếp
        handled = true
        scanning = false
        steps["scan"] = StepState.OK
        payeeAccount = "${d.bankBin} · ${d.accountNo}"
        runAfterScan(d.bankBin, d.accountNo)
    }

    /**
     * Chọn nhân viên → bỏ qua bước ①, đi thẳng ② → ⑤ bằng CÙNG luồng với quét mã.
     * Chỉ khác nguồn thông tin người nhận, nên không nhân bản luồng.
     */
    fun pickStaff(s: StaffDto) {
        val bin = s.bankBin ?: return
        val acc = s.bankAccount ?: return
        reset()
        scanning = false
        handled = true
        steps["scan"] = StepState.OK
        stepNote["scan"] = "chọn từ danh sách nhân viên"
        payeeAccount = "$bin · $acc"
        // Tên chủ TK đã có trong hồ sơ → không cần hỏi VietQR.
        runAfterScan(bin, acc, s.accountHolder?.takeIf { it.isNotBlank() } ?: s.name?.takeIf { it.isNotBlank() })
    }

    LaunchedEffect(Unit) { startScanning() }


    // Đếm lại mỗi khi có thao tác HOẶC luồng đổi bước; hết 60s im lặng thì đóng.
    LaunchedEffect(lastTouch, steps.toMap()) {
        kotlinx.coroutines.delay(60_000)
        onDismiss()
    }

    // Bề ngang dialog = 96% màn hình. AlertDialog mặc định bị platform ép về bề
    // ngang cố định, nên PHẢI tắt usePlatformDefaultWidth thì fillMaxWidth mới ăn.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val dialogWidth = screenWidthDp * 0.96f
    // Màn quét = 86% bề ngang DIALOG. Tính từ dialogWidth chứ không dùng
    // fillMaxWidth(0.86f): trong thân dialog đã bị trừ padding nên % ở đó sẽ ra
    // hẹp hơn con số mong muốn.
    val cameraWidth = dialogWidth * 0.86f

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(dialogWidth).dialogGlow(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = C.Card,
        title = {
            // Tên việc bên trái, số tiền bên phải. "đ" tách riêng để style khác
            // phần số: nhỏ hơn, mảnh, nghiêng, màu vàng.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(dialogTitle, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = C.Text)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatVndCompact(payAmount),
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = C.Success,
                    )
                    Text(
                        "đ",
                        fontSize = 11.sp, fontWeight = FontWeight.Light,
                        fontStyle = FontStyle.Italic, color = C.Warning,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Hai nguồn thông tin người nhận; từ bước ② trở đi dùng CHUNG luồng.
                // Kiểu "segment" như web: một khung pill, tab đang chọn là pill đặc bên trong.
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(C.Bg)
                        .padding(3.dp),
                ) {
                    listOf("Quét mã", "Nhân viên").forEachIndexed { i, label ->
                        val on = tab == i
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                            color = if (on) C.Text else C.TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(if (on) C.Card else Color.Transparent)
                                .clickable {
                                    if (tab == i) return@clickable
                                    tab = i
                                    reset()
                                    if (i == 0) {
                                        staffId = null
                                        focusManager.clearFocus()   // rời tab → đóng list + tắt bàn phím
                                        startScanning()
                                    } else { scanning = false; loadStaff() }
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                }

                // Chọn nhân viên giao hàng — chỉ người đã lưu đủ BIN + số TK.
                if (tab == 1 && steps["scan"] != StepState.OK) {
                    if (staffLoading) {
                        Text("Đang tải danh sách…", fontSize = 12.sp, color = C.TextMuted)
                    } else if (staffList.isEmpty()) {
                        Text(
                            staffError ?: "Chưa nhân viên nào lưu số tài khoản. Vào hồ sơ nhân viên bổ sung ngân hàng + số tài khoản rồi quay lại.",
                            fontSize = 12.sp, color = C.TextMuted,
                        )
                    } else {
                        // Dropdown chọn nhân viên (mirror NSelect filterable của web).
                        // CHÍNH ô chọn là ô tìm kiếm — gõ để lọc, không thêm ô thứ hai.
                        // Danh sách trải phẳng trước đây chiếm hết thân dialog, đẩy tiến
                        // trình 5 bước xuống khuất; gói vào dropdown thì giữ nguyên.
                        // Ô tìm nhân viên — DỰNG THEO ĐÚNG MẪU "+ Sản phẩm"
                        // (VariantSearchInline ở SaleOrderForm), mẫu duy nhất trong app
                        // đã chạy được tap-1-mở-list / tap-2-bàn-phím.
                        //
                        // Hai điểm cốt lõi mà bản DropdownMenu trước thiếu:
                        //  1. List là INLINE ngay dưới ô, KHÔNG phải popup. Popup là cửa
                        //     sổ riêng nên nuốt chạm và lệch tầng bàn phím.
                        //  2. Tap 1 = xin focus vào ô đang readOnly → có focus thì list mở
                        //     (theo onFocusChanged), readOnly chặn bàn phím. Tap 2 chỉ tắt
                        //     readOnly trên ô ĐÃ CÓ FOCUS SẴN → bàn phím lên chắc chắn.
                        val filtered = staffList.filter {
                            val q = noAccent(staffQuery.text.trim())
                            q.isEmpty() || noAccent(it.name.orEmpty()).contains(q)
                        }
                        val picked = staffList.firstOrNull { it.id == staffId }
                        val pickedLabel = picked?.name?.takeIf { it.isNotBlank() }
                            ?: picked?.let { "NV #${it.id}" }

                        // Bật bàn phím: chờ readOnly=false (recompose) rồi xin focus + show
                        // IME + bôi đen hết để gõ là thay luôn.
                        LaunchedEffect(staffKeyboardOn) {
                            if (staffKeyboardOn) {
                                runCatching { staffFocus.requestFocus() }
                                keyboard?.show()
                                if (staffQuery.text.isNotEmpty()) {
                                    staffQuery = staffQuery.copy(selection = TextRange(0, staffQuery.text.length))
                                }
                            }
                        }

                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(C.Bg)
                                    .border(1.dp, if (staffListOpen) C.Primary else C.Border, RoundedCornerShape(8.dp))
                                    .onGloballyPositioned { staffFieldHeight = with(density) { it.size.height.toDp() } }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.weight(1f)) {
                                    BasicTextField(
                                        value = staffQuery,
                                        onValueChange = { staffQuery = it },
                                        readOnly = !staffKeyboardOn,
                                        singleLine = true,
                                        textStyle = TextStyle(color = C.Text, fontSize = 14.sp),
                                        cursorBrush = if (staffKeyboardOn) SolidColor(C.Primary) else SolidColor(Color.Transparent),
                                        decorationBox = { inner ->
                                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                                if (staffQuery.text.isEmpty()) {
                                                    Text(
                                                        pickedLabel ?: "Chọn nhân viên giao hàng",
                                                        fontSize = 14.sp,
                                                        color = if (picked != null) C.Text else C.TextMuted,
                                                    )
                                                }
                                                inner()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().focusRequester(staffFocus)
                                            .onFocusChanged {
                                                staffListOpen = it.isFocused
                                                if (!it.isFocused) staffKeyboardOn = false
                                            },
                                    )
                                    // Overlay chặn chạm mặc định của ô — chạm thẳng vào ô sẽ tự
                                    // bật bàn phím, đúng thứ cần tránh ở lần chạm đầu.
                                    Box(
                                        Modifier.matchParentSize().pointerInput(Unit) {
                                            detectTapGestures(
                                                onTap = {
                                                    if (!staffListOpen) {
                                                        staffKeyboardOn = false
                                                        runCatching { staffFocus.requestFocus() }
                                                    } else if (!staffKeyboardOn) {
                                                        staffKeyboardOn = true
                                                    }
                                                },
                                            )
                                        },
                                    )
                                }
                                // Mũi tên = 50% chiều cao ô chứa nó.
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Mở danh sách nhân viên",
                                    tint = C.TextMuted,
                                    modifier = Modifier
                                        .size(if (staffFieldHeight > 0.dp) staffFieldHeight / 2 else 20.dp)
                                        .clickable {
                                            if (staffListOpen) focusManager.clearFocus()
                                            else runCatching { staffFocus.requestFocus() }
                                        },
                                )
                            }

                            if (staffListOpen) {
                                Column(
                                    Modifier.fillMaxWidth().padding(top = 6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(C.Card)
                                        .border(1.dp, C.Primary, RoundedCornerShape(8.dp))
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(staffScroll),
                                ) {
                                    if (filtered.isEmpty()) {
                                        Text(
                                            "Không tìm thấy nhân viên",
                                            fontSize = 13.sp, color = C.TextMuted,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        )
                                    }
                                    filtered.forEachIndexed { idx, s ->
                                        Text(
                                            s.name.orEmpty().ifBlank { "NV #" + s.id },
                                            fontSize = 13.sp,
                                            color = if (staffId == s.id) C.Primary else C.Text,
                                            modifier = Modifier.fillMaxWidth()
                                                .pointerInput(s.id) {
                                                    detectTapGestures {
                                                        staffId = s.id
                                                        staffQuery = TextFieldValue("")
                                                        focusManager.clearFocus()   // đóng list + tắt bàn phím
                                                        pickStaff(s)
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                        )
                                        if (idx < filtered.lastIndex) HorizontalDivider(color = C.Border)
                                    }
                                }
                            }
                        }
                    }
                }

                // Khung VUÔNG, hình phủ kín — PreviewView mặc định FIT sẽ chèn dải
                // đen hai bên vì khung hình camera không vuông. FILL_CENTER cắt bớt
                // rìa nhưng mã QR luôn ở giữa nên không ảnh hưởng việc quét.
                if (tab == 0 && scanning && hasCamPermission) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally)
                            .width(cameraWidth).height(cameraWidth)
                            .clip(RoundedCornerShape(10.dp)),
                    ) { CameraScanner(onText = ::onQrText) }
                }

                // Tiến trình 5 bước — luôn thấy đang ở đâu
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Bg),
                ) {
                    stepKeys.forEachIndexed { i, k ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                                when (steps[k]) {
                                    StepState.RUN -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = C.Primary)
                                    StepState.OK -> Text("✓", fontSize = 13.sp, color = C.Success)
                                    StepState.FAIL -> Text("!", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.Danger)
                                    else -> Text("${i + 1}", fontSize = 11.sp, color = C.TextMuted)
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stepLabel[k] ?: k,
                                fontSize = 12.sp,
                                color = if (steps[k] == StepState.IDLE) C.TextMuted else C.Text,
                                modifier = Modifier.weight(1f),
                            )
                            // Bước ⑤: chọn app ngân hàng — tiền có thể đi từ TK ngân hàng khác.
                            if (k == "open") {
                                Box {
                                    Text(
                                        "$bankName ▾",
                                        fontSize = 12.sp, color = C.Primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable { bankMenuOpen = true }
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                    DropdownMenu(
                                        expanded = bankMenuOpen,
                                        onDismissRequest = { bankMenuOpen = false },
                                        modifier = Modifier
                                            .background(C.Card)
                                            .border(1.dp, C.Border, RoundedCornerShape(4.dp)),
                                    ) {
                                        bankApps.forEachIndexed { idx, (id, nm) ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        nm,
                                                        color = if (id == bankApp) C.Primary else C.Text,
                                                        fontSize = 13.sp,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                },
                                                onClick = { bankApp = id; bankMenuOpen = false; touch() },
                                            )
                                            // Kẻ ngăn giữa các dòng, trừ dòng cuối.
                                            if (idx < bankApps.lastIndex) HorizontalDivider(color = C.Border)
                                        }
                                    }
                                }
                            }
                            stepNote[k]?.takeIf { it.isNotBlank() }?.let {
                                Text(it, fontSize = 10.sp, color = C.Warning)
                            }
                        }
                    }
                }

                payeeAccount?.let {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(C.Warning.copy(alpha = 0.12f)).padding(10.dp),
                    ) {
                        Text("Tiền sẽ chuyển tới", fontSize = 10.sp, color = C.TextMuted)
                        Text(payeeName ?: "— chưa tra được tên —", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.Text)
                        Text(it, fontSize = 11.sp, color = C.TextMuted)
                    }
                }

                qrBitmap?.let {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(it.asImageBitmap(), contentDescription = "Mã QR chuyển khoản", modifier = Modifier.size(150.dp))
                        Text(finalContent, fontSize = 10.sp, color = C.TextMuted)
                    }
                }

                if ((expenseId != null || isGroup) && steps["info"] == StepState.OK && !hasRefCode) {
                    Text(
                        "Không có mã đối soát — chuyển xong phải khớp tay ở Dòng tiền.",
                        fontSize = 11.sp, color = C.Warning,
                    )
                }
                if (steps["save"] == StepState.OK) {
                    Text(
                        "Đã lưu ảnh QR. Trong app $bankName chọn quét từ thư viện ảnh rồi chọn ảnh vừa lưu.",
                        fontSize = 11.sp, color = C.Success,
                    )
                }
            }
        },
        confirmButton = {
            // Nút đóng dạng pill viền, góc phải dưới cùng (web dùng y hệt).
            if (steps["save"] == StepState.OK || steps["open"] == StepState.FAIL) {
                TextButton(onClick = { openBankApp(); touch() }) { Text("Mở lại app $bankName", color = C.Primary) }
            }
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(50),
                border = BorderStroke(0.5.dp, C.Border),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("Đóng", color = C.TextMuted, fontSize = 13.sp) }
        },
    )
}

/** Preview camera + phân tích khung hình bằng ML Kit (chính xác hơn zxing với QR mờ). */
@Composable
private fun CameraScanner(onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            // FILL_CENTER: phủ kín khung vuông, không để dải đen (mặc định là FIT_CENTER).
            val previewView = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val providerFuture = ProcessCameraProvider.getInstance(c)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(c)) { proxy: ImageProxy ->
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { codes ->
                            codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue?.let(onText)
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(c))
            previewView
        },
    )
}

/** Dựng bitmap QR offline bằng zxing. */
internal fun encodeQrBitmap(payload: String, size: Int = 512): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    return bmp
}

/**
 * Ảnh QR ĐỂ LƯU/CHIA SẺ: mã QR + 2 dòng chú thích bên dưới.
 *
 *   STK nhận: 0927276710 | Số tiền: 105.000
 *   {nội dung chuyển khoản}
 *
 * Người nhận ảnh chỉ thấy một ô vuông đen trắng, không biết tiền đi đâu và bao
 * nhiêu cho tới khi quét. Ghi thẳng vào ảnh để đối chiếu được TRƯỚC khi quét, và
 * để ảnh lưu trong máy sau này còn tra ra được là của giao dịch nào.
 *
 * Dùng chung cho MỌI chỗ lưu/chia sẻ ảnh QR — đừng tự vẽ lại ở màn khác.
 *
 * @param amount null hoặc <= 0 → bỏ phần "Số tiền" (QR để người trả tự nhập).
 */
internal fun renderQrShareBitmap(
    qr: Bitmap,
    accountNo: String?,
    amount: Long?,
    content: String?,
): Bitmap {
    val pad = 24
    val lineGap = 10
    val infoSize = 26f
    val contentSize = 24f

    val infoText = buildString {
        if (!accountNo.isNullOrBlank()) append("STK nhận: ").append(accountNo)
        if (amount != null && amount > 0) {
            if (isNotEmpty()) append("  |  ")
            append("Số tiền: ")
            append(java.text.NumberFormat.getInstance(java.util.Locale("vi", "VN")).format(amount))
        }
    }
    val contentText = content?.trim().orEmpty()

    val infoPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        textSize = infoSize
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    // Dòng STK|số tiền phải nằm GỌN trong bề ngang ảnh QR: đo thật rồi co cỡ chữ
    // xuống cho vừa, thay vì để cỡ cố định (số tiền dài là tràn/nhìn quá to).
    if (infoText.isNotEmpty()) {
        val avail = (qr.width - pad * 2).toFloat()
        val w = infoPaint.measureText(infoText)
        if (w > avail) infoPaint.textSize = infoSize * (avail / w)
    }
    val contentPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.DKGRAY
        textSize = contentSize
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Nội dung CK có thể dài hơn bề ngang ảnh → xuống dòng theo từ, không cắt cụt.
    val maxWidth = (qr.width - pad * 2).toFloat()
    val contentLines = wrapText(contentText, contentPaint, maxWidth)

    val infoH = if (infoText.isEmpty()) 0 else (infoPaint.fontSpacing + lineGap).toInt()
    val contentH = contentLines.sumOf { (contentPaint.fontSpacing + 2).toInt() }
    val extra = if (infoH + contentH == 0) 0 else pad + infoH + contentH + pad

    val out = Bitmap.createBitmap(qr.width, qr.height + extra, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(AndroidColor.WHITE)
    canvas.drawBitmap(qr, 0f, 0f, null)

    var y = qr.height.toFloat() + pad
    val cx = qr.width / 2f
    if (infoText.isNotEmpty()) {
        y += infoPaint.textSize
        canvas.drawText(infoText, cx, y, infoPaint)
        y += lineGap
    }
    contentLines.forEach {
        y += contentPaint.textSize
        canvas.drawText(it, cx, y, contentPaint)
        y += 2
    }
    return out
}

/** Ngắt dòng theo TỪ để không cắt cụt giữa chữ. */
private fun wrapText(text: String, paint: android.graphics.Paint, maxWidth: Float): List<String> {
    if (text.isBlank()) return emptyList()
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var cur = StringBuilder()
    words.forEach { w ->
        val candidate = if (cur.isEmpty()) w else "$cur $w"
        if (paint.measureText(candidate) <= maxWidth || cur.isEmpty()) {
            cur = StringBuilder(candidate)
        } else {
            lines += cur.toString()
            cur = StringBuilder(w)
        }
    }
    if (cur.isNotEmpty()) lines += cur.toString()
    return lines
}

/**
 * Chuẩn hoá nội dung CK — port `normalizePayoutContent` bên web.
 * Bỏ dấu tiếng Việt, bỏ ký tự đặc biệt nhưng GIỮ '-' (để mã đơn ORD-000466 còn
 * đọc được), giữ nguyên hoa/thường. Trần 50 ký tự.
 */
/** `max` PHẢI khớp CONTENT_MAX của BE — lệch thì cắt lại chuỗi BE đã dựng. */
internal fun normalizePayoutContent(raw: String?, max: Int = 70): String {
    val ascii = java.text.Normalizer.normalize(raw.orEmpty(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
    return ascii.replace(Regex("[^A-Za-z0-9 -]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .uppercase()   // MỌI nội dung CK đều viết hoa — khớp sanitizeNote() của BE
        .take(max)
}

/** 25000 → "25.000 đ" */
/**
 * CHỈ số, KHÔNG kèm "đ" — header tự vẽ chữ "đ" riêng để style khác phần số
 * (nhỏ hơn, mảnh, nghiêng, vàng). Gắn sẵn ở đây sẽ ra hai chữ "đ".
 */
private fun formatVndCompact(v: Long): String =
    java.text.NumberFormat.getInstance(java.util.Locale("vi", "VN")).format(v)
