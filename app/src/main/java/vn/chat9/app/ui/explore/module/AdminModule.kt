package vn.chat9.app.ui.explore.module

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Mô tả 1 module quản trị hiển thị trong tab "Khám phá".
 *
 * DỮ LIỆU THUẦN — không giữ state / đối tượng nặng. `entry` (màn gốc của
 * module) chỉ được compose KHI user thực sự mở module (lazy) → module không
 * dùng thì không khởi tạo gì, không ngốn tài nguyên.
 */
data class AdminModule(
    /** id ổn định, dùng làm route + key. vd "warehouse". */
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    /**
     * OR-gate: user chỉ cần CÓ ÍT NHẤT 1 trong các permission code này là vào
     * được (khớp [vn.chat9.app.data.repository.PermissionStore.hasAny]).
     */
    val requiredPermissions: List<String>,
    /** Màn gốc của module — chỉ compose khi đang mở. */
    val entry: @Composable (onBack: () -> Unit) -> Unit,
)
