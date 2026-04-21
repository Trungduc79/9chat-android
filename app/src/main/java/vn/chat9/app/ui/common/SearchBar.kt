package vn.chat9.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppSearchBar(
    onSearchClick: () -> Unit = {},
    rightIconRes: Int,
    onRightIconClick: () -> Unit = {}
) {
    val gradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF3E1F91), // Primary
            Color(0xFF8E44AD), // Secondary
            Color(0xFFFF6F61)  // Accent
        )
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .clickable { onSearchClick() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, "search", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Tìm kiếm", fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            IconButton(onClick = onRightIconClick, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(rightIconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        HorizontalDivider(color = Color(0xFFE0E6ED), thickness = 1.dp)
    }
}
