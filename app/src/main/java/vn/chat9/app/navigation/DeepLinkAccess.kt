package vn.chat9.app.navigation

import vn.chat9.app.data.repository.PermissionStore

/**
 * Gate quyền cho deep-link thẻ nghiệp vụ: chỉ chuyển nội app khi người xem có
 * quyền module tương ứng (hoặc bypass_all). Default-DENY (khớp triết lý RBAC).
 *
 * Áp ở [vn.chat9.app.MainActivity] `navigateTo` — chokepoint chung nên phủ MỌI
 * lối vào: tap thẻ trong chat, intent ngoài (VIEW), tap notification.
 *
 * Destination thường (Chat/Wall/Search/…) luôn cho qua — chỉ chặn business.
 * Thêm entity mới: bổ sung nhánh + set quyền tại đây.
 */
object DeepLinkAccess {

    fun canOpen(dest: AppDestination, perms: PermissionStore): Boolean = when (dest) {
        is AppDestination.OrderDetail -> perms.hasAny(
            "warehouse.read", "warehouse.manage",
            "order.read", "order.create", "order.approve",
            "sale.view_orders", "sale.create_order",
        )
        is AppDestination.InvoiceDetail -> perms.hasAny("vat_invoice.read")
        is AppDestination.DebtDetail -> perms.hasAny("debt.read", "debt.update")
        is AppDestination.ProductDetail -> perms.hasAny(
            "warehouse.read", "warehouse.manage",
            "order.read", "order.create",
            "sale.view_orders", "sale.create_order",
        )
        is AppDestination.TransactionDetail -> perms.hasAny("transaction.read")
        else -> true
    }
}
