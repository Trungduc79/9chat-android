package vn.chat9.app.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.chat9.app.App
import vn.chat9.app.ui.explore.module.AdminModule
import vn.chat9.app.ui.explore.module.ModuleRegistry

/**
 * Tab "Khám phá" = hub module quản trị (DARK MODE, khớp admin.ai.vn/kho).
 * Chỉ render module user có quyền; không quyền → màn trống. Module lazy.
 */
@Composable
fun ExploreScreen(onOpenModule: (String) -> Unit) {
    val container = (LocalContext.current.applicationContext as App).container
    val perms by container.permissions.state.collectAsState()
    LaunchedEffect(Unit) { container.permissions.refresh() }

    val modules = remember(perms) { ModuleRegistry.visibleFor(container.permissions) }

    Column(
        modifier = Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding().padding(16.dp),
    ) {
        Text("Khám phá", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AdminColors.Text)
        Spacer(Modifier.height(4.dp))
        Text("Chức năng quản trị theo quyền của bạn", fontSize = 13.sp, color = AdminColors.TextMuted)
        Spacer(Modifier.height(16.dp))

        if (modules.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null, tint = AdminColors.TextMuted, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Chưa có chức năng quản trị", color = AdminColors.TextMuted)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(modules, key = { it.id }) { m -> ModuleCard(m) { onOpenModule(m.id) } }
            }
        }
    }
}

@Composable
private fun ModuleCard(m: AdminModule, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AdminColors.Card,
        modifier = Modifier.fillMaxWidth().aspectRatio(1.2f).clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(AdminColors.Primary),
                contentAlignment = Alignment.Center,
            ) { Icon(m.icon, null, tint = AdminColors.White, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.weight(1f))
            Text(m.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = AdminColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.subtitle, fontSize = 12.sp, color = AdminColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Host module: tìm descriptor theo id rồi compose entry (lazy). */
@Composable
fun ModuleHostScreen(moduleId: String?, onBack: () -> Unit) {
    val m = remember(moduleId) { moduleId?.let { ModuleRegistry.byId(it) } }
    if (m == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    m.entry(onBack)
}

/** Placeholder Bước 1 — màn rỗng cho module chưa làm (dark). */
@Composable
fun ModulePlaceholder(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AdminColors.Bg).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Quay lại", tint = AdminColors.Primary) }
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AdminColors.Text)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Module \"$title\" — đang phát triển", color = AdminColors.TextMuted)
        }
    }
}
