package vn.chat9.app.ui.modules.sale

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.chat9.app.ui.explore.AdminColors

/**
 * Module "Bán hàng" — entry của tab Khám phá → Sale.
 *
 * State nav: list (đơn của tôi) ↔ form (tạo đơn). Detail xem đơn = list_item
 * → mở SaleOrderForm với readonly = true (TODO sau).
 *
 * Permission gate ở [ModuleRegistry]: cần `sale.create_order` OR `sale.view_orders`.
 * BE filter `created_by_user_id` để tách đơn theo NV (xem migration vapi d8e029d).
 */
private sealed interface SaleNavState {
    object List : SaleNavState
    object Create : SaleNavState
}

@Composable
fun SaleScreen(onBack: () -> Unit) {
    var nav by remember { mutableStateOf<SaleNavState>(SaleNavState.List) }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (nav is SaleNavState.List) onBack() else nav = SaleNavState.List
    }

    Column(Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()) {
        // App bar đơn giản — back ←  title  + (FAB nổi ở list)
        Row(
            modifier = Modifier.fillMaxWidth().background(AdminColors.Card).height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (nav is SaleNavState.List) onBack() else nav = SaleNavState.List
            }) {
                Icon(Icons.Default.ArrowBack, "Quay lại", tint = AdminColors.Text)
            }
            val title = when (nav) {
                is SaleNavState.List -> "Đơn của tôi"
                is SaleNavState.Create -> "Tạo đơn bán"
            }
            Text(title, color = AdminColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = nav,
                transitionSpec = {
                    val dir = if (targetState is SaleNavState.Create) 1 else -1
                    (slideInHorizontally { dir * it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -dir * it } + fadeOut())
                },
                label = "saleContent",
            ) { state ->
                when (state) {
                    is SaleNavState.List -> SaleOrdersList()
                    is SaleNavState.Create -> SaleOrderForm(onDone = { nav = SaleNavState.List })
                }
            }

            // FAB Tạo đơn — chỉ ở màn list.
            if (nav is SaleNavState.List) {
                FloatingActionButton(
                    onClick = { nav = SaleNavState.Create },
                    containerColor = AdminColors.Primary,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(Icons.Default.Add, "Tạo đơn bán")
                }
            }
        }
    }
}
