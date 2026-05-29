package vn.chat9.app.ui.explore

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * PullToRefreshBox với indicator dark mode (nền [AdminColors.Card] + spinner [AdminColors.Primary])
 * — dùng chung cho mọi module tab Khám phá để icon reload không bị sáng lạc tông trên nền tối.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = AdminColors.Card,
                color = AdminColors.Primary,
            )
        },
        content = content,
    )
}
