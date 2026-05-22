package vn.chat9.app.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Thanh tìm kiếm khu quản trị — cùng kích thước thanh search 9chat (AppSearchBar:
 * Row pad 12/10 + IconButton(28) → cùng chiều cao). DARK mode, icon trái = mũi tên
 * ← (giữ action back) thay icon tìm kiếm.
 */
@Composable
fun AdminSearchBar(placeholder: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().background(AdminColors.Card)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = AdminColors.Text, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(placeholder, fontSize = 16.sp, color = AdminColors.TextMuted, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = AdminColors.Border, thickness = 1.dp)
    }
}
