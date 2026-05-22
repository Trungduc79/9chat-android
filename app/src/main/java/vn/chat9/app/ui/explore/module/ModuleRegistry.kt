package vn.chat9.app.ui.explore.module

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Inventory2
import vn.chat9.app.data.repository.PermissionStore
import vn.chat9.app.ui.explore.ModulePlaceholder
import vn.chat9.app.ui.modules.warehouse.WarehouseScreen

/**
 * Đăng ký tĩnh các module quản trị (metadata nhẹ, gần như 0 chi phí cho tới
 * khi user mở module). Thêm module mới = thêm 1 [AdminModule] vào [all] — KHÔNG
 * đụng tab Khám phá hay module khác.
 *
 * Gate theo permission code của RBAC 9chat (xem migration 028). Map quyền:
 *  - warehouse.*  → Vận hành kho  (PENDING: thêm nhóm warehouse vào RBAC backend)
 *  - debt/invoice/report.financial → Kế toán
 *  - system.*     → Quản trị tổng
 */
object ModuleRegistry {

    val all: List<AdminModule> = listOf(
        AdminModule(
            id = "warehouse",
            title = "Vận hành kho",
            subtitle = "Giao / nhận hàng, tồn kho",
            icon = Icons.Default.Inventory2,
            requiredPermissions = listOf("warehouse.view", "warehouse.fulfill"),
            entry = { onBack -> WarehouseScreen(onBack) },
        ),
        AdminModule(
            id = "accounting",
            title = "Kế toán",
            subtitle = "Công nợ, hoá đơn, báo cáo",
            icon = Icons.Default.Calculate,
            requiredPermissions = listOf("debt.view_all", "invoice.view_all", "report.financial"),
            entry = { onBack -> ModulePlaceholder("Kế toán", onBack) },
        ),
        AdminModule(
            id = "admin",
            title = "Quản trị tổng",
            subtitle = "Phân quyền, cài đặt hệ thống",
            icon = Icons.Default.AdminPanelSettings,
            requiredPermissions = listOf("system.permission_manage", "system.settings"),
            entry = { onBack -> ModulePlaceholder("Quản trị tổng", onBack) },
        ),
    )

    fun byId(id: String): AdminModule? = all.firstOrNull { it.id == id }

    /** Module user được phép thấy. bypass_all → thấy hết; ngược lại cần hasAny. */
    fun visibleFor(perm: PermissionStore): List<AdminModule> =
        all.filter { perm.isBypass || perm.hasAny(*it.requiredPermissions.toTypedArray()) }
}
