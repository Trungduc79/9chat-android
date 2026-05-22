package vn.chat9.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import vn.chat9.app.App
import vn.chat9.app.data.model.PermissionsData

/**
 * Composable helpers cho Phase 2 RBAC. Tất cả đều state-aware — khi
 * `PermissionStore.state` đổi (vd sau accept invite), UI dùng helper này
 * sẽ recompose tự động.
 *
 * Default-DENY: nếu store chưa fetch (state mặc định empty) thì mọi
 * `hasPermission()` trả false — an toàn cho UI ẩn nút trong khi đợi.
 */

@Composable
fun rememberPermissions(): PermissionsData {
    val container = (LocalContext.current.applicationContext as App).container
    val state by container.permissions.state.collectAsState()
    return state
}

@Composable
fun hasPermission(code: String): Boolean {
    val s = rememberPermissions()
    return s.bypass_all || code in s.permissions
}

@Composable
fun hasAnyPermission(vararg codes: String): Boolean {
    val s = rememberPermissions()
    if (s.bypass_all) return true
    return codes.any { it in s.permissions }
}

@Composable
fun hasRole(roleCode: String): Boolean {
    val s = rememberPermissions()
    return roleCode in s.roles
}

@Composable
fun isBypassAll(): Boolean = rememberPermissions().bypass_all

/** Đủ điều kiện thấy entry vào Admin (gán role / mời nhân viên). */
@Composable
fun canManageStaff(): Boolean = hasAnyPermission(
    "staff.assign_role",
    "staff.invite",
    "system.permission_manage",
)
