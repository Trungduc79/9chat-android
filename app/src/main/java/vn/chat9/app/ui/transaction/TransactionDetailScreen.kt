package vn.chat9.app.ui.transaction

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.vapi.dto.MoneyTransactionDto
import java.text.NumberFormat
import java.util.Locale

private val moneyFmt = NumberFormat.getInstance(Locale("vi", "VN"))
private val Cyan = Color(0xFF0891B2)
private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFDC2626)

/**
 * Chi tiết giao dịch ngân hàng READ-ONLY, mở từ thẻ transaction
 * (deeplink 9chat://transaction/{id}). Fetch GET /v1/money-transactions/{id}.
 * Gate quyền transaction.read đã chặn ở MainActivity.navigateTo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(txId: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val vapi = (ctx.applicationContext as App).container.vapi
    val scope = rememberCoroutineScope()

    var tx by remember(txId) { mutableStateOf<MoneyTransactionDto?>(null) }
    var loading by remember(txId) { mutableStateOf(true) }
    var error by remember(txId) { mutableStateOf<String?>(null) }

    LaunchedEffect(txId) {
        loading = true; error = null
        scope.launch {
            try {
                tx = vapi.getMoneyTransaction(txId).data
                if (tx == null) error = "Không tìm thấy giao dịch"
            } catch (e: Exception) {
                error = e.message ?: "Lỗi tải giao dịch"
            } finally { loading = false }
        }
    }

    Scaffold(
        containerColor = Color(0xFFF4F5F7),
        topBar = {
            TopAppBar(
                title = { Text("Giao dịch", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Đóng") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White, titleContentColor = Color(0xFF2C3E50)),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cyan)
                error != null -> Text(error!!, Modifier.align(Alignment.Center), color = Red)
                tx != null -> Body(tx!!)
            }
        }
    }
}

@Composable
private fun Body(t: MoneyTransactionDto) {
    val isIn = t.direction == "in"
    val amountColor = if (isIn) Green else Red
    val sign = if (isIn) "+" else "-"
    val matched = t.partyId != null || t.autoMatched

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Số tiền nổi bật + hướng.
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (isIn) "TIỀN VÀO" else "TIỀN RA", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = amountColor, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(amountColor.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp))
                Text("$sign${moneyFmt.format(t.amount.toLong())} đ", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = amountColor)
                Text(t.code, fontSize = 12.sp, color = Color(0xFF8593A1))
            }
        }

        // Nội dung chuyển khoản.
        t.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Nội dung chuyển khoản", fontSize = 12.sp, color = Color(0xFF8593A1), fontWeight = FontWeight.Medium)
                    Text(desc, fontSize = 14.sp, color = Color(0xFF2C3E50))
                }
            }
        }

        // Thông tin GD.
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Trạng thái", fontSize = 13.sp, color = Color(0xFF8593A1))
                    Text(
                        if (matched) "Đã đối soát" else "Chờ đối soát",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (matched) Green else Color(0xFFA16207),
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background((if (matched) Green else Color(0xFFA16207)).copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                t.bankName?.takeIf { it.isNotBlank() }?.let { Row2("Ngân hàng", it) }
                t.bankAccount?.takeIf { it.isNotBlank() }?.let { Row2("Số tài khoản", it) }
                t.date.takeIf { it.isNotBlank() }?.let { Row2("Ngày", it) }
                t.purposeLabel?.takeIf { it.isNotBlank() }?.let { Row2("Mục đích", it) }
                t.purposeDetail?.takeIf { it.isNotBlank() }?.let { Row2("Chi tiết", it) }
            }
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = Color(0xFF8593A1))
        Text(value, fontSize = 13.sp, color = Color(0xFF2C3E50), fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp))
    }
}
