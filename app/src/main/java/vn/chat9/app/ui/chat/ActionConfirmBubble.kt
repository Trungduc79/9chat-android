package vn.chat9.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.json.JSONObject
import vn.chat9.app.data.model.Message
import vn.chat9.app.util.UrlUtils

private data class AcField(
    val key: String,
    val label: String,
    val value: String,
    val editable: Boolean,
)

/**
 * Render tin "xác nhận hành động AI" (type=action_confirm). Panel: ảnh (vd
 * captcha) + các field AI đề xuất (sửa được) + nút Xác nhận/Từ chối. Bấm →
 * onRespond → POST messages/action-respond.php → vapi.
 *
 * Khoá (bỏ nút, hiện badge) khi status ≠ pending — lấy từ content.status (bền
 * qua reload, action-respond.php patch) HOẶC resolvedStatusOverride (realtime
 * action_resolved cập nhật tức thì) — hoặc quá expires_at.
 */
@Composable
fun ActionConfirmBubble(
    message: Message,
    currentUserId: Int,
    resolvedStatusOverride: String?,
    onRespond: (messageId: Int, actionKey: String, decision: String, values: Map<String, String>) -> Unit,
) {
    val json = remember(message.content) {
        try { JSONObject(message.content ?: "{}") } catch (_: Exception) { JSONObject() }
    }
    val actionId = json.optString("action_id")
    val title = json.optString("title").ifBlank { "Xác nhận hành động" }
    val prompt = json.optString("prompt")
    val imageUrl = json.optString("image_url").ifBlank { message.file_url ?: "" }
    val expiresAt = json.optLong("expires_at", 0L)
    val contentStatus = json.optString("status", "pending")
    // Panel giống nhau cho mọi thành viên; chỉ người nhận đích (target_user_id)
    // được nhấn nút + sửa field.
    val targetUserId = json.optInt("target_user_id", 0)
    val canPress = targetUserId == 0 || currentUserId == targetUserId

    val fields = remember(message.content) {
        val arr = json.optJSONArray("fields")
        buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("key").takeIf { it.isNotBlank() } ?: continue
                add(AcField(k, o.optString("label").ifBlank { k }, o.optString("value"), o.optBoolean("editable")))
            }
        }
    }
    // Giá trị người dùng đang chỉnh cho field editable (khởi tạo = value gốc).
    val edited = remember(message.id) {
        mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, it.value) } }
    }

    val nowSec = System.currentTimeMillis() / 1000
    val expired = expiresAt in 1 until nowSec
    val status = resolvedStatusOverride ?: contentStatus
    val locked = status != "pending" || expired

    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🤖", fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3E1F91))
        }

        if (prompt.isNotBlank()) {
            Text(prompt, fontSize = 13.sp, color = Color(0xFF5A6770), lineHeight = 18.sp)
        }

        val fullImg = UrlUtils.toFullUrl(imageUrl)
        if (fullImg != null) {
            AsyncImage(
                model = fullImg,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        }

        fields.forEach { f ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(f.label.uppercase(), fontSize = 11.sp, color = Color(0xFF8593A1))
                if (f.editable && !locked && canPress) {
                    OutlinedTextField(
                        value = edited[f.key] ?: f.value,
                        onValueChange = { edited[f.key] = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp, textAlign = TextAlign.Center,
                        ),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        (edited[f.key] ?: f.value).ifBlank { "—" },
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp, color = Color(0xFF2C3E50),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        when {
            status == "confirmed" -> AcBadge("✅ Đã xác nhận", Color(0xFF16A34A), Color(0x1A16A34A))
            status == "rejected" -> AcBadge("✖ Đã từ chối", Color(0xFFDC2626), Color(0x1ADC2626))
            expired -> AcBadge("⏱ Đã hết hạn", Color(0xFFA16207), Color(0x1AA16207))
            !canPress -> AcBadge("🔒 Chờ xác nhận", Color(0xFF8593A1), Color(0x14000000))
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onRespond(message.id, actionId, "reject", emptyMap()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Từ chối", fontSize = 13.sp, maxLines = 1) }
                    Button(
                        onClick = {
                            val values = fields.associate { it.key to (edited[it.key] ?: it.value) }
                            onRespond(message.id, actionId, "confirm", values)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E1F91)),
                    ) { Text("Xác nhận", fontSize = 13.sp, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun AcBadge(text: String, fg: Color, bg: Color) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
