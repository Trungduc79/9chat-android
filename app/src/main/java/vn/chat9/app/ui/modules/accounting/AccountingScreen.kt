package vn.chat9.app.ui.modules.accounting

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.chat9.app.ui.explore.AdminColors

/**
 * Module "Kế toán" — entry tab Khám phá → Kế toán. 3 tab (mirror web /accounting):
 * VAT · Công nợ · Dòng tiền. Mặc định mở Dòng tiền.
 *
 * Lộ trình port: Phase 1 = Dòng tiền (số dư quỹ + GD + tạo GD tiền mặt); Phase 2 =
 * Công nợ; Phase 3 = VAT. Permission gate ở [ModuleRegistry].
 */
/**
 * Cờ chặn vuốt đổi tab — overlay con (vd dialog sửa đơn ở Công nợ) set true để
 * tắt gesture vuốt trái/phải của [AccountingScreen] khi đang hiển thị.
 */
val LocalTabSwipeBlocked = staticCompositionLocalOf<MutableState<Boolean>?> { null }

private enum class AccTab(val label: String, val icon: ImageVector) {
    PNL("Lãi Lỗ", Icons.Default.ShowChart),
    VAT("VAT", Icons.Default.ReceiptLong),
    DEBTS("Công nợ", Icons.Default.AccountBalanceWallet),
    CASHFLOW("Dòng tiền", Icons.Default.Payments),
}

@Composable
fun AccountingScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(AccTab.PNL) }   // mặc định Lãi Lỗ (mirror web)
    val swipeBlocked = remember { mutableStateOf(false) } // overlay con tắt vuốt đổi tab

    androidx.activity.compose.BackHandler(enabled = true) { onBack() }

    CompositionLocalProvider(LocalTabSwipeBlocked provides swipeBlocked) {
    Column(Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()) {
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = AdminColors.Card,
            contentColor = AdminColors.Primary,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            AccTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        val c = if (tab == t) AdminColors.Primary else AdminColors.TextMuted
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(t.icon, null, tint = c, modifier = Modifier.size(16.dp))
                            Text(t.label, fontSize = 13.sp, color = c)
                        }
                    },
                )
            }
        }

        // Vuốt trái/phải đổi tab (threshold 80px). Tab đầu vuốt phải → thoát module.
        var dragAccum by remember { mutableStateOf(0f) }
        Box(
            // swipeBlocked (overlay con) → bỏ qua gesture vuốt đổi tab.
            Modifier.weight(1f).fillMaxWidth().pointerInput(tab, swipeBlocked.value) {
                if (swipeBlocked.value) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAccum < -80f && tab.ordinal < AccTab.entries.lastIndex) tab = AccTab.entries[tab.ordinal + 1]
                        else if (dragAccum > 80f) {
                            if (tab.ordinal > 0) tab = AccTab.entries[tab.ordinal - 1] else onBack()
                        }
                        dragAccum = 0f
                    },
                ) { _, dx -> dragAccum += dx }
            },
        ) {
            when (tab) {
                AccTab.PNL -> AccountingPnLTab()
                AccTab.CASHFLOW -> AccountingCashflowTab()
                AccTab.DEBTS -> AccountingDebtsTab()
                AccTab.VAT -> AccountingVatTab()
            }
        }
    }
    }
}
