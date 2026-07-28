package vn.chat9.app.ui.transaction

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.chat9.app.App
import vn.chat9.app.data.vapi.VapiApiService
import vn.chat9.app.data.vapi.dto.MoneyTransactionDto
import vn.chat9.app.ui.explore.AdminColors
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

private val moneyFmt = NumberFormat.getNumberInstance(Locale("vi"))
private fun fmtMoney(n: Double): String = moneyFmt.format(n.toLong())

/** Cache bin → tên viết tắt NH cho cả session (banks đổi rất ít, BE cache 24h). */
object BankNames {
    private var map: Map<String, String> = emptyMap()
    suspend fun ensureLoaded(vapi: VapiApiService) {
        if (map.isNotEmpty()) return
        val list = runCatching { vapi.listBanks().data ?: emptyList() }.getOrDefault(emptyList())
        if (list.isNotEmpty()) map = list.associate { it.bin to it.shortName }
    }
    fun shortNameOf(bin: String?): String? = bin?.takeIf { it.isNotBlank() }?.let { map[it] }
}

private fun casherTypeLabel(t: String): String = when (t) {
    "main" -> "Tài khoản chính"; "bank_account" -> "Tài khoản ngân hàng"
    "petty_cash" -> "Quỹ tiền mặt"; "safe" -> "Két sắt"; "mobile" -> "Ví điện tử"; else -> t
}

private fun moneyTxTypeLabel(t: String): String = when (t) {
    "pending" -> "Chờ phân loại"; "cust_bank_in" -> "KH chuyển khoản"; "cust_cash_in" -> "KH trả tiền mặt"
    "cust_cash_out" -> "Hoàn/đảo tiền mặt KH"; "sup_bank_out" -> "Trả NCC (bank)"; "sup_cash_out" -> "Trả NCC (cash)"
    "bank_fee" -> "Phí ngân hàng"; "internal_transfer" -> "Chuyển nội bộ"; "cash_deposit" -> "Nộp tiền vào TK"
    "cash_withdrawal" -> "Rút tiền mặt"; else -> t
}

/** GD trả ship là sup_bank_out; đổi nhãn theo mục đích thực (purpose_label). */
private fun txTypeTag(tx: MoneyTransactionDto): String = when (tx.purposeLabel) {
    "Chi phí" -> "Ghi trả phí"; "Chi hộ KH" -> "Tạm ứng"; else -> moneyTxTypeLabel(tx.type)
}

private fun parseDate(s: String): java.util.Date? = try {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s.replace('T', ' ').take(19))
} catch (_: Exception) { null }

/** Dòng Ngày trong drawer: "HH:mm  dd/MM/yyyy". */
private fun fmtTxTime(s: String): String =
    parseDate(s)?.let { SimpleDateFormat("HH:mm  dd/MM/yyyy", Locale.US).format(it) } ?: s

/** Ngày cho text copy/chia sẻ: "dd/MM/yyyy - HH:mm". */
private fun fmtShareDate(s: String): String =
    parseDate(s)?.let { SimpleDateFormat("dd/MM/yyyy - HH:mm", Locale.US).format(it) } ?: s

/** TK nhận suy từ meta.payee_* → "{số TK} - {tên NH}"; trống → "—". */
private fun payeeText(t: MoneyTransactionDto, bankShort: String?): String {
    val acc = t.meta?.payeeAccount?.takeIf { it.isNotBlank() } ?: return "—"
    return acc + (bankShort?.let { " - $it" } ?: "")
}

/** Text gửi nhanh thông tin chuyển khoản. */
private fun transferText(t: MoneyTransactionDto, bankShort: String?): String {
    val lines = mutableListOf("Ngày: ${fmtShareDate(t.date)}", "Số tiền: ${fmtMoney(t.amount)} đ")
    t.meta?.payeeAccount?.takeIf { it.isNotBlank() }?.let { acc ->
        lines += "Tài khoản nhận: $acc" + (bankShort?.let { " - $it" } ?: "")
    }
    (t.description ?: t.reference)?.takeIf { it.isNotBlank() }?.let {
        lines += "Nội dung: "
        lines += it
    }
    return lines.joinToString("\n")
}

/**
 * Drawer chi tiết GD tiền DÙNG CHUNG (mirror web MoneyTxDetailDrawer) — tích xanh
 * phí ship ở đơn hàng và tab Kế toán/Dòng tiền. Tự nạp cashers (tên sổ quỹ) + banks
 * (tên NH từ BIN). Header có Copy/Chia sẻ, dưới cùng nút Đóng.
 */
@Composable
fun MoneyTxDetailSheet(tx: MoneyTransactionDto, feeLabel: String? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val vapi = (ctx.applicationContext as App).container.vapi

    var casherLabel by remember(tx.id) { mutableStateOf<String?>(null) }
    var banksReady by remember(tx.id) { mutableStateOf(false) }
    LaunchedEffect(tx.id) {
        BankNames.ensureLoaded(vapi)
        banksReady = true
        tx.casherId?.let { cid ->
            runCatching { vapi.listCashers(1, 100).data }.getOrNull()
                ?.firstOrNull { it.id == cid }
                ?.let { casherLabel = "${it.name} (${casherTypeLabel(it.type)})" }
        }
    }
    val bankShort = if (banksReady) BankNames.shortNameOf(tx.meta?.payeeBankBin) else null
    val isOut = tx.direction == "out"

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose),
    ) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(12.dp)
                .clip(RoundedCornerShape(16.dp)).background(AdminColors.Card)
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .heightIn(max = 560.dp).verticalScroll(rememberScrollState())
                .padding(16.dp)
                .clickable(enabled = false, onClick = {}),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: tiêu đề trái + Copy/Chia sẻ phải (bỏ dấu X).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tx.code.ifBlank { feeLabel ?: "Giao dịch" },
                    color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeaderAction(Icons.Default.ContentCopy, "Copy") { copyText(ctx, transferText(tx, bankShort)) }
                    HeaderAction(Icons.Default.Share, "Chia sẻ") { shareText(ctx, tx.code, transferText(tx, bankShort)) }
                }
            }
            HorizontalDivider(color = AdminColors.Border, thickness = 1.dp)

            feeLabel?.let { Row2("Khoản phí", it) }
            Row2("Ngày", fmtTxTime(tx.date))
            Row2(
                "Số tiền",
                (if (tx.direction == "in") "+" else if (isOut) "−" else "") + "${fmtMoney(tx.amount)} đ",
                if (tx.direction == "in") AdminColors.Success else AdminColors.Danger,
            )
            if (isOut) Row2("Tài khoản nhận", payeeText(tx, bankShort))
            Row2("Loại", txTypeTag(tx))
            tx.purposeDetail?.takeIf { it.isNotBlank() }?.let { Row2("Đã phân bổ", it, AdminColors.Primary) }
            casherLabel?.let { Row2("Sổ quỹ", it) }
            if (!tx.bankName.isNullOrBlank() || !tx.bankAccount.isNullOrBlank())
                Row2("Ngân hàng", "${tx.bankName ?: ""} ${tx.bankAccount ?: ""}".trim())

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Nội dung", color = AdminColors.TextMuted, fontSize = 12.sp)
                Text(tx.description ?: tx.reference ?: "—", color = AdminColors.Text, fontSize = 14.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!tx.debtCalc.isNullOrBlank()) MiniTag("Đã chốt công nợ", AdminColors.Danger)
                if (!tx.moneyCalc.isNullOrBlank()) MiniTag("Đã chốt sổ quỹ", AdminColors.Danger)
                if (tx.autoMatched) MiniTag("Tự đối soát", AdminColors.Success)
            }

            // Nút đóng: trong nội dung, căn giữa.
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Đóng", color = AdminColors.Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClose() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .background(AdminColors.Bg).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, label, tint = AdminColors.Text, modifier = Modifier.size(14.dp))
        Text(label, color = AdminColors.Text, fontSize = 12.sp)
    }
}

@Composable
private fun Row2(label: String, value: String, valueColor: Color = AdminColors.Text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AdminColors.TextMuted, fontSize = 13.sp)
        Text(
            value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun MiniTag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Chuyển khoản", text))
    Toast.makeText(ctx, "Đã copy thông tin chuyển khoản", Toast.LENGTH_SHORT).show()
}

private fun shareText(ctx: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Chia sẻ"))
}
