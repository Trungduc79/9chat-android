package vn.chat9.app.ui.explore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Nút mờ "cuộn lên đầu" — hiện khi list đã cuộn xuống (>=3 item đầu ẩn), đặt bottom-end.
 * Mirror web: size = nút + (56.dp), opacity 35%. [bottomPadding] chừa chỗ khi có nút +
 * bên dưới (đơn bán/đơn nhập → 84.dp: nút + 56 + padding 16 + hở 12). Dùng trong BoxScope.
 */
@Composable
fun BoxScope.AdminScrollTopButton(
    listState: LazyListState,
    bottomPadding: Dp = 16.dp,
) {
    val scope = rememberCoroutineScope()
    val visible by remember { derivedStateOf { listState.firstVisibleItemIndex >= 3 } }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomPadding),
    ) {
        Surface(
            shape = CircleShape,
            color = AdminColors.Card,
            border = BorderStroke(1.dp, AdminColors.Border),
            shadowElevation = 4.dp,
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier.size(56.dp).alpha(0.35f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Lên đầu trang",
                    tint = AdminColors.Text,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
