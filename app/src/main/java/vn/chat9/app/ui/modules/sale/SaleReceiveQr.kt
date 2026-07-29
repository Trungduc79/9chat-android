package vn.chat9.app.ui.modules.sale

import vn.chat9.app.ui.common.dialogGlow
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.CasherDto
import vn.chat9.app.ui.explore.AdminColors as C
import vn.chat9.app.ui.modules.warehouse.encodeQrBitmap
import vn.chat9.app.ui.modules.warehouse.renderQrShareBitmap
import vn.chat9.app.util.VietQr

/**
 * Dialog QR NHẬN TIỀN cho đơn hàng — khách quét để trả tiền vào TK của mình.
 *
 * Nội dung CK lấy từ NGUỒN CHUNG backend (customers/{id}/qr-content) = mã định danh
 * KH + đuôi ngẫu nhiên → GIỐNG hệt web (/business/orders, /sale); giao dịch về tự đối
 * soát đúng khách (signal bank_sign). TK nhận mặc định (is_default_receive) auto-chọn;
 * đổi được qua dropdown. QR dựng OFFLINE (zxing).
 */
@Composable
fun QrReceiveDialog(
    amount: Long,
    customerId: Long?,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as App).container }
    val scope = rememberCoroutineScope()

    var cashers by remember { mutableStateOf<List<CasherDto>>(emptyList()) }
    var casherId by remember { mutableStateOf<Long?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var content by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }

    val casher = cashers.firstOrNull { it.id == casherId }

    LaunchedEffect(Unit) {
        val list = runCatching { container.vapi.listCashers().data }.getOrNull().orEmpty()
            .filter { it.isActive && !it.bankBin.isNullOrBlank() && !it.bankAccount.isNullOrBlank() }
        cashers = list
        // Auto-chọn TK nhận mặc định (is_default_receive) → bớt thao tác.
        casherId = (list.firstOrNull { it.isDefaultReceive } ?: list.firstOrNull())?.id
        loading = false
    }

    LaunchedEffect(casherId, customerId) {
        val c = cashers.firstOrNull { it.id == casherId } ?: run { qrBitmap = null; return@LaunchedEffect }
        // NGUỒN CHUNG: nội dung CK do BE  sinh (mã định danh KH + đuôi), tự tạo mã nếu thiếu.
        val cont = if (customerId != null) {
            runCatching { container.vapi.customerQrContent(customerId).data?.content }.getOrNull().orEmpty()
        } else ""
        content = cont
        qrBitmap = runCatching {
            encodeQrBitmap(VietQr.buildPayload(c.bankBin!!, c.bankAccount!!, amount.takeIf { it > 0 }, cont))
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        // Dialog rộng 95% màn hình (usePlatformDefaultWidth=false để bỏ giới hạn mặc định).
        modifier = Modifier.fillMaxWidth(0.95f).dialogGlow(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("QR thanh toán đơn hàng", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = C.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    loading -> Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = C.Primary)
                    }
                    cashers.isEmpty() -> Text(
                        "Chưa có tài khoản ngân hàng nhận tiền (đủ BIN + số TK). Thêm/đặt mặc định ở Sổ quỹ.",
                        fontSize = 13.sp, color = C.TextMuted,
                    )
                    else -> {
                        // Tài khoản nhận (auto mặc định) — đổi qua dropdown.
                        Box {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Bg)
                                    .border(1.dp, C.Border, RoundedCornerShape(8.dp)).clickable { menuOpen = true }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    casher?.let { "${it.name} · ${it.bankName ?: ""} ${it.bankAccount}".trim() } ?: "Chọn tài khoản nhận",
                                    color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Icon(Icons.Default.ArrowDropDown, null, tint = C.TextMuted)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                cashers.forEach { c ->
                                    DropdownMenuItem(
                                        text = { Text("${c.name} · ${c.bankName ?: ""} ${c.bankAccount}".trim(), fontSize = 13.sp) },
                                        onClick = { casherId = c.id; menuOpen = false },
                                    )
                                }
                            }
                        }

                        qrBitmap?.let {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                // Ảnh QR chiếm TRỌN bề ngang vùng nội dung — khách chìa
                                // điện thoại cho người khác quét nên mã càng to càng dễ.
                                Image(
                                    it.asImageBitmap(), "Mã QR thanh toán",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp),
                                )
                            }
                        }

                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Nội dung CK", fontSize = 12.sp, color = C.TextMuted)
                                Text(content, fontSize = 12.sp, color = C.Text, fontWeight = FontWeight.Medium)
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Số tiền", fontSize = 12.sp, color = C.TextMuted)
                                Text(if (amount > 0) fmtVnd(amount) else "Tự nhập khi quét", fontSize = 12.sp, color = C.Text, fontWeight = FontWeight.Medium)
                            }
                        }

                        // Đúng 3 nút như web: Tải về · Copy · Chia sẻ.
                        // Cả 3 đều XUẤT ẢNH RA NGOÀI app → dùng bản có chú thích
                        // (STK + số tiền + nội dung). Ảnh hiển thị trong dialog vẫn
                        // là mã trần vì thông tin đã nằm ngay trên màn hình.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                val bmp = qrBitmap ?: return@OutlinedButton
                                val out = renderQrShareBitmap(bmp, casher?.bankAccount, amount, content)
                                val ok = saveBitmapToGallery(ctx, out, "qr-${content.ifBlank { "thanh-toan" }}")
                                Toast.makeText(ctx, if (ok) "Đã lưu ảnh QR" else "Lưu ảnh thất bại", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Tải về", color = C.Primary, fontSize = 13.sp) }
                            OutlinedButton(onClick = {
                                val bmp = qrBitmap ?: return@OutlinedButton
                                val out = renderQrShareBitmap(bmp, casher?.bankAccount, amount, content)
                                scope.launch { copyQrBitmap(ctx, out, content) }
                            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Copy", color = C.Primary, fontSize = 13.sp) }
                            Button(onClick = {
                                val bmp = qrBitmap ?: return@Button
                                val out = renderQrShareBitmap(bmp, casher?.bankAccount, amount, content)
                                scope.launch { shareQrBitmap(ctx, out, content) }
                            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Chia sẻ", fontSize = 13.sp) }
                        }
                    }
                }
            }
        },
        // Bỏ nút "Đóng" ở dưới cùng — đóng bằng chạm ngoài / nút back.
        confirmButton = {},
    )
}

private fun fmtVnd(v: Long): String =
    java.text.NumberFormat.getInstance(java.util.Locale("vi", "VN")).format(v) + " đ"

/** Copy ảnh QR vào clipboard qua content-URI (FileProvider) — dán được vào app khác. */
private suspend fun copyQrBitmap(ctx: Context, bmp: Bitmap, content: String) {
    try {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            val safe = content.replace(Regex("\\s+"), "-").ifBlank { "thanh-toan" }
            val file = File(dir, "qr-$safe.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newUri(ctx.contentResolver, "Mã QR", uri)
        cm.setPrimaryClip(clip)
        Toast.makeText(ctx, "Đã copy ảnh QR", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Không copy được ảnh — dùng Tải về / Chia sẻ", Toast.LENGTH_SHORT).show()
    }
}

/** Lưu bitmap ra cache + chia sẻ qua FileProvider (ACTION_SEND image/png). */
private suspend fun shareQrBitmap(ctx: Context, bmp: Bitmap, content: String) {
    try {
        val uri: Uri = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            val safe = content.replace(Regex("\\s+"), "-").ifBlank { "thanh-toan" }
            val file = File(dir, "qr-$safe.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "Chia sẻ mã QR").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (e: Exception) {
        Toast.makeText(ctx, "Không thể chia sẻ ảnh", Toast.LENGTH_SHORT).show()
    }
}
