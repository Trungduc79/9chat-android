package vn.chat9.app.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import vn.chat9.app.App
import vn.chat9.app.util.UrlSafety

/**
 * Render preview card cho message type='url'. Tap → mở browser.
 *
 * Flow:
 *   1. Hiện ngay placeholder (hostname only)
 *   2. Async fetch /api/v1/url/preview.php?url=... lấy OG metadata
 *   3. Update title/description/image khi có
 *
 * Cache trong-process: AppContainer giữ Map<url, UrlPreview>. Tránh
 * fetch lại khi cùng URL xuất hiện ở nhiều bubble (vd nhiều người
 * cùng share 1 link).
 */
@Composable
fun UrlPreviewCard(url: String) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container

    val cached = remember(url) { container.urlPreviewCache[url] }
    var preview by remember(url) { mutableStateOf(cached) }
    var guardOpen by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (preview != null) return@LaunchedEffect
        try {
            val res = container.api.getUrlPreview(url)
            if (res.success && res.data != null) {
                preview = res.data
                container.urlPreviewCache[url] = res.data
            }
        } catch (_: Exception) {}
    }

    val host = remember(url) {
        try { Uri.parse(url).host ?: url } catch (_: Exception) { url }
    }

    fun openUrl() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }

    fun handleClick() {
        // Skip dialog nếu host đã trust trước đó
        if (host.isNotBlank() && UrlSafety.isTrusted(context, host)) {
            openUrl()
        } else {
            guardOpen = true
        }
    }

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFAFAFA))
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(10.dp))
            .clickable { handleClick() },
    ) {
        // Image — chỉ hiện nếu có
        val image = preview?.image
        if (!image.isNullOrBlank()) {
            AsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                contentScale = ContentScale.Crop,
            )
        }

        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(10.dp),
        ) {
            Text(
                text = preview?.title?.takeIf { it.isNotBlank() } ?: host,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2C3E50),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = preview?.description?.takeIf { it.isNotBlank() } ?: url,
                fontSize = 12.sp,
                color = Color(0xFF5A6770),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val favicon = preview?.favicon
                    ?: "https://www.google.com/s2/favicons?domain=${Uri.encode(host)}&sz=32"
                AsyncImage(
                    model = favicon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = preview?.site_name?.takeIf { it.isNotBlank() } ?: host,
                    fontSize = 11.sp,
                    color = Color(0xFF8593A1),
                    maxLines = 1,
                )
            }
        }
    }

    if (guardOpen) {
        UrlGuardDialog(
            url = url,
            onDismiss = { guardOpen = false },
            onConfirm = { trusted ->
                if (trusted && host.isNotBlank()) UrlSafety.addTrust(context, host)
                guardOpen = false
                openUrl()
            },
        )
    }
}

/**
 * Dialog xác nhận trước khi mở URL ngoài. Hiện hostname đậm + full URL +
 * cờ đỏ tự động (HTTP, URL rút gọn, IP-address, punycode). Có checkbox
 * "Tin tưởng tên miền này" để skip lần sau cùng host.
 */
@Composable
private fun UrlGuardDialog(
    url: String,
    onDismiss: () -> Unit,
    onConfirm: (trusted: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val analysis = remember(url) { UrlSafety.analyze(url) }
    var trust by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            // Compose Dialog mặc định cap width ~280dp; tắt platform default
            // để fillMaxWidth(0.98f) bên dưới có hiệu lực thật.
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth(0.95f),
        ) {
            // Compact ~15%: padding 20→16, spacers + box padding giảm,
            // URL maxLines 4→3, flag padding 10→8, label fontSize giữ
            // (đọc rõ vẫn quan trọng).
            androidx.compose.foundation.layout.Column(modifier = Modifier.padding(16.dp)) {
                Text("Bạn sắp mở liên kết",
                     fontSize = 13.sp, color = Color(0xFF5A6770))
                Spacer(Modifier.height(3.dp))
                Text(analysis.host.ifBlank { "Liên kết bên ngoài" },
                     fontSize = 17.sp, fontWeight = FontWeight.Bold,
                     color = Color(0xFF1E3A8A), maxLines = 2,
                     overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))

                // Full URL trong khung mono — user verify ký tự
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFF4F6F8))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(url, fontSize = 12.sp, color = Color(0xFF5A6770),
                         fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                         maxLines = 3, overflow = TextOverflow.Ellipsis)
                }

                if (analysis.flags.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    analysis.flags.forEach { flag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFFF7E0))
                                .border(1.dp, Color(0xFFFFE199), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(flag.icon, fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.foundation.layout.Column {
                                Text(flag.label, fontSize = 13.sp,
                                     fontWeight = FontWeight.SemiBold,
                                     color = Color(0xFF7A4A00))
                                Text(flag.desc, fontSize = 11.sp,
                                     color = Color(0xFF6B5400), lineHeight = 14.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { trust = !trust }) {
                    Checkbox(checked = trust, onCheckedChange = { trust = it })
                    Text("Tin tưởng tên miền này", fontSize = 13.sp, color = Color(0xFF5A6770))
                }

                Spacer(Modifier.height(4.dp))
                // 3 nút weight(1f) chia đều, gap 4dp + contentPadding nhỏ
                // hơn default để text "Mở liên kết" (10 ký tự) đủ chỗ trong
                // button (default contentPadding=24dp×2 ngang).
                val tightPad = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 4.dp, vertical = 8.dp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        contentPadding = tightPad,
                    ) { Text("Huỷ", fontSize = 14.sp, maxLines = 1) }
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                            android.widget.Toast.makeText(context,
                                "Đã sao chép link", android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()  // đóng dialog sau khi copy (như nút Huỷ)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = tightPad,
                    ) { Text("Sao chép", fontSize = 14.sp, maxLines = 1) }
                    Button(
                        onClick = { onConfirm(trust) },
                        modifier = Modifier.weight(1f),
                        contentPadding = tightPad,
                        colors = if (analysis.isDanger)
                            ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                        else ButtonDefaults.buttonColors(containerColor = Color(0xFF3E1F91)),
                    ) {
                        Text(if (analysis.isDanger) "Vẫn mở" else "Mở liên kết",
                             fontSize = 14.sp, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
