package vn.chat9.app.ui.modules.accounting

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.EiLoginReq
import vn.chat9.app.data.vapi.dto.TctLoginReq
import vn.chat9.app.ui.explore.AdminColors

enum class VatPortal(val label: String) { TCT("TCT"), EI("EasyInvoice") }

/** Lỗi API → message BE (field "error"), không có thì fallback. */
private fun apiErrorMsg(e: Exception, fallback: String): String {
    if (e is retrofit2.HttpException) {
        val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
        val msg = try { org.json.JSONObject(body ?: "").optString("error") } catch (_: Exception) { "" }
        if (msg.isNotBlank()) return msg
    }
    return fallback
}

/** data URL (data:image/png;base64,...) → ImageBitmap. */
private fun decodeDataUrl(url: String): ImageBitmap? = try {
    val bytes = Base64.decode(url.substringAfter(","), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (_: Exception) { null }

/**
 * Overlay đăng nhập TCT / portal EI bằng captcha (credential lấy từ .env trên VPS) —
 * port TctLoginDialog / EasyInvoicePortalDialog của web. Gemini điền sẵn mã nếu đọc được.
 * Box-overlay (không Dialog) giống VatConfirmOverlay để giữ ẩn nav bar.
 */
@Composable
fun VatPortalLoginOverlay(portal: VatPortal, onClose: () -> Unit, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val vapi = (context.applicationContext as App).container.vapi
    val scope = rememberCoroutineScope()

    var loadingCaptcha by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var tctKey by remember { mutableStateOf<String?>(null) }
    var svg by remember { mutableStateOf<String?>(null) }          // TCT
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }  // EI
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadCaptcha() {
        loadingCaptcha = true
        error = null
        scope.launch {
            try {
                if (portal == VatPortal.TCT) {
                    val c = vapi.tctFetchCaptcha().data
                    tctKey = c?.key; svg = c?.svg; code = c?.suggestion ?: ""
                } else {
                    val c = vapi.eiFetchCaptcha().data
                    bitmap = c?.captcha?.let { decodeDataUrl(it) }; code = c?.suggestion ?: ""
                }
            } catch (e: Exception) {
                error = apiErrorMsg(e, "Không lấy được captcha ${portal.label}")
            } finally { loadingCaptcha = false }
        }
    }

    fun submit() {
        if (code.isBlank() || submitting) return
        submitting = true
        error = null
        scope.launch {
            try {
                if (portal == VatPortal.TCT) vapi.tctSubmitLogin(TctLoginReq(tctKey ?: "", code.trim()))
                else vapi.eiSubmitLogin(EiLoginReq(code.trim()))
                Toast.makeText(context, "Đã đăng nhập ${portal.label}", Toast.LENGTH_SHORT).show()
                onLoggedIn()
            } catch (e: Exception) {
                error = apiErrorMsg(e, "Đăng nhập ${portal.label} thất bại")
                loadCaptcha() // captcha cũ đã dùng → lấy mã mới
            } finally { submitting = false }
        }
    }

    LaunchedEffect(Unit) { loadCaptcha() }
    androidx.activity.compose.BackHandler(enabled = true) { if (!submitting) onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { if (!submitting) onClose() }) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(AdminColors.Card).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(20.dp).clickable(enabled = false) {},
        ) {
            Text("Đăng nhập ${portal.label}", color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)

            // Ảnh captcha (bấm để đổi mã)
            Box(
                Modifier.fillMaxWidth().padding(top = 14.dp).height(64.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.White).clickable(enabled = !loadingCaptcha && !submitting) { loadCaptcha() },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loadingCaptcha -> CircularProgressIndicator(color = AdminColors.Primary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    portal == VatPortal.TCT && svg != null -> AsyncImage(
                        model = ImageRequest.Builder(context).data(svg!!.toByteArray()).decoderFactory(SvgDecoder.Factory()).build(),
                        contentDescription = "Captcha", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                    portal == VatPortal.EI && bitmap != null -> Image(bitmap!!, "Captcha", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                    else -> Text("Bấm để lấy captcha", color = Color.Gray, fontSize = 12.sp)
                }
            }
            Text("Bấm vào ảnh để đổi mã", color = AdminColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))

            Box(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg).padding(horizontal = 10.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = code, onValueChange = { code = it },
                    textStyle = TextStyle(color = AdminColors.Text, fontSize = 15.sp), singleLine = true,
                    cursorBrush = SolidColor(AdminColors.Primary),
                    decorationBox = { inner -> if (code.isEmpty()) Text("Nhập mã captcha", color = AdminColors.TextMuted, fontSize = 14.sp); inner() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            error?.let { Text(it, color = AdminColors.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }

            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Huỷ", color = AdminColors.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(AdminColors.Bg)
                        .clickable(enabled = !submitting) { onClose() }.padding(vertical = 10.dp),
                )
                Text(
                    if (submitting) "Đang đăng nhập…" else "Đăng nhập", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (code.isBlank() || loadingCaptcha) AdminColors.Primary.copy(alpha = 0.4f) else AdminColors.Primary)
                        .clickable(enabled = !submitting && !loadingCaptcha && code.isNotBlank()) { submit() }.padding(vertical = 10.dp),
                )
            }
        }
    }
}
