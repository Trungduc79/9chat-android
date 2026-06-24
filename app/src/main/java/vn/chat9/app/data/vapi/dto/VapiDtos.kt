package vn.chat9.app.data.vapi.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO map JSON vapi. KHÔNG dùng `by lazy` — Gson dùng Unsafe bỏ qua constructor
 * nên lazy-field sẽ null → NPE. Dùng `val` mặc định, hoặc `get()` computed.
 */
data class OrderDto(
    val id: Long = 0,
    val type: String = "sale",                 // sale|customer_return|purchase|supplier_return
    val status: String = "",
    val code: String = "",
    val party: PartyDto? = null,               // KH (sale) hoặc NCC (purchase)
    @SerializedName("ordered_at") val orderedAt: String? = null,
    @SerializedName("confirmed_at") val confirmedAt: String? = null,
    @SerializedName("completed_at") val completedAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("warehouse_id") val warehouseId: Long? = null,   // kho bán (sale edit)
    @SerializedName("total_amount") val totalAmount: Double? = null,
    val notes: String? = null,
    // Phí ship + COD (init form ở màn fulfill; NV kho có thể sửa rồi gửi qua FulfillRequest)
    @SerializedName("shipping_fee") val shippingFee: Double? = null,           // Phí ship KH → công nợ
    @SerializedName("actual_shipping_fee") val actualShippingFee: Double? = null, // Phí ship KHO → chi phí
    @SerializedName("cod_collected") val codCollected: Double? = null,         // Thu hộ COD
    // Drop-ship: đơn liên kết (đơn nhập trỏ tới đơn bán đã tự giao sau cascade fulfill,
    // hoặc đơn bán bị chặn fulfill trực tiếp — phải đi qua đơn nhập).
    @SerializedName("linked_order_id") val linkedOrderId: Long? = null,
    @SerializedName("linked_order") val linkedOrder: LinkedOrderDto? = null,
    val meta: MetaDto? = null,
    val items: List<OrderItemDto> = emptyList(),
) {
    val isPurchase: Boolean get() = type == "purchase" || type == "supplier_return"

    /** Đơn nhập (NCC): ưu tiên tên rút gọn (short_name). Đơn bán (KH): tên đầy đủ. */
    val partyName: String get() {
        val p = party
        val n = if (isPurchase) (p?.shortName?.takeIf { it.isNotBlank() } ?: p?.name) else p?.name
        return n ?: "#${p?.id ?: ""}"
    }

    // ===== Drop-ship (đơn nhập tạo đơn bán giao thẳng cho KH) — dùng get() để Gson không NPE.
    val isDropship: Boolean get() = isPurchase && linkedOrderId != null
    /** Tên ngắn NCC (party đơn nhập). */
    val dropshipSupplier: String get() =
        party?.shortName?.takeIf { it.isNotBlank() } ?: party?.name?.takeIf { it.isNotBlank() } ?: "#${party?.id ?: ""}"
    /** Tên KH của đơn bán giao thẳng liên kết. */
    val dropshipCustomer: String get() =
        linkedOrder?.party?.name?.takeIf { it.isNotBlank() } ?: "khách hàng"

    /** Thời điểm BẤM xác nhận thật (giờ VN). Đơn cũ chưa có → fallback updated_at.
     *  KHÔNG dùng completed_at (= ngày giao NV tự chọn, có thể backdate). */
    val fulfilledRealTime: String? get() = meta?.fulfillment?.atServer ?: updatedAt
}

data class PartyDto(
    val id: Long = 0,
    val name: String? = null,
    val phone: String? = null,
    @SerializedName("short_name") val shortName: String? = null,
)

data class OrderItemDto(
    val id: Long = 0,
    @SerializedName("variant_id") val variantId: Long = 0,
    @SerializedName("unit_id") val unitId: Long = 0,
    @SerializedName("qty_unit") val qtyUnit: Double = 0.0,
    @SerializedName("unit_price") val unitPrice: Double = 0.0,
    @SerializedName("stock_unit") val stockUnit: Double? = null,   // tồn quy đổi (BE @show)
    @SerializedName("image_url") val imageUrl: String? = null,     // thumb variant (BE resolve)
    val snapshot: SnapshotDto = SnapshotDto(),
) {
    val productName: String get() = snapshot.productName ?: "SP #$variantId"
    val unitName: String get() = snapshot.unitName ?: ""
    /** "Màu: Đỏ, Size: L" (tên: giá trị). */
    val variantLabel: String
        get() = snapshot.variantAttributes
            ?.entries?.filter { it.value.isNotBlank() }
            ?.joinToString(", ") { "${it.key}: ${it.value}" } ?: ""

    /** Cặp (tên nhóm phân loại, giá trị) — render tên mờ + giá trị trắng như web. */
    val variantPairs: List<Pair<String, String>>
        get() = snapshot.variantAttributes
            ?.entries?.filter { it.value.isNotBlank() }
            ?.map { it.key to it.value } ?: emptyList()
}

data class SnapshotDto(
    @SerializedName("product_name") val productName: String? = null,
    @SerializedName("variant_name") val variantName: String? = null,
    @SerializedName("variant_attributes") val variantAttributes: Map<String, String>? = null,
    @SerializedName("unit_name") val unitName: String? = null,
)

data class MetaDto(val fulfillment: FulfillmentDto? = null)

data class FulfillmentDto(
    @SerializedName("by_user_id") val byUserId: Long? = null,
    @SerializedName("by_name") val byName: String? = null,
    val at: String? = null,                    // ngày giao NV chọn (hiển thị)
    @SerializedName("at_server") val atServer: String? = null, // lúc bấm xác nhận thật (giờ VN) — lọc tab Hoàn thành
)

// ----- fulfill -----
data class FulfillRequest(
    @SerializedName("confirmed_by_user_id") val confirmedByUserId: Long?,
    @SerializedName("confirmed_by_name") val confirmedByName: String?,
    val items: List<DeliveredItem>,
    // Phí ship + COD (NV kho chốt lúc giao). BE: ship_KH→công nợ, ship_KHO→chi phí
    // draft, COD→cust_cash_in thật (cộng quỹ + giảm phải thu).
    @SerializedName("shipping_fee") val shippingFee: Double? = null,
    @SerializedName("actual_shipping_fee") val actualShippingFee: Double? = null,
    @SerializedName("cod_amount") val codAmount: Double? = null,
    @SerializedName("cod_casher_id") val codCasherId: Long? = null,
    @SerializedName("completed_at") val completedAt: String? = null,   // ISO date NV chọn
)

data class DeliveredItem(
    val id: Long,
    @SerializedName("qty_delivered") val qtyDelivered: Double,
)

data class FulfillResult(
    val order: OrderDto? = null,
    @SerializedName("remainder_order") val remainderOrder: OrderDto? = null,
)

/** Đơn liên kết drop-ship eager-load nhẹ (BE trả 4 cột id/code/type/status). */
data class LinkedOrderDto(
    val id: Long = 0,
    val code: String = "",
    val type: String = "",                     // sale|purchase
    val status: String = "",                   // draft|confirmed|delivered|received|...
    val party: PartyDto? = null,               // KH đơn bán giao thẳng (BE eager-load ở show + list)
)

// ----- attachments -----
data class AttachmentDto(
    val id: Long = 0,
    val url: String? = null,
    @SerializedName("attachable_id") val attachableId: Long = 0,
)

// ----- cashers (quỹ thu tiền) -----
data class CasherDto(
    val id: Long = 0,
    val name: String = "",
    val type: String = "",                     // petty_cash|bank_account|main_cash|...
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("is_default") val isDefault: Boolean = false,
    @SerializedName("warehouse_id") val warehouseId: Long? = null,
)

// ----- warehouses -----
data class WarehouseDto(
    val id: Long = 0,
    val name: String = "",
    val code: String? = null,
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("is_default") val isDefault: Boolean = false,
)

/** Danh mục (dòng sản phẩm) cho dropdown lọc Kiểm kho. */
data class CategoryDto(
    val id: Long = 0,
    val name: String = "",
    val code: String? = null,
)

// ===== Kiểm kho =====
data class StocktakeItemReq(
    @SerializedName("variant_id") val variantId: Long,
    val qty: Double,
    @SerializedName("unit_id") val unitId: Long? = null,
)
data class StocktakeRequest(
    @SerializedName("warehouse_id") val warehouseId: Long? = null,
    @SerializedName("user_id") val userId: Long? = null,
    val note: String? = null,
    val items: List<StocktakeItemReq>,
)
data class StocktakeResultDto(val count: Int = 0)

/** Vai trò + QUYỀN nhân viên vapi khớp theo SĐT (mở module 9chat). Phase 4: dùng
 *  `permissions` (code thật từ roles.permissions) trực tiếp, không map cứng. */
data class StaffRolesDto(
    val matched: Boolean = false,
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
)

// ===== Module Sale =====

/** KH cho picker + search. BE trả full Customer; chỉ dùng cột nhẹ ở UI. */
data class CustomerDto(
    val id: Long = 0,
    val name: String = "",
    val phone: String? = null,
    val code: String? = null,
    @SerializedName("debt_balance") val debtBalance: Double? = null,
)

/** Product + variant flat cho list search (BE eager-load variants nhẹ). */
data class ProductSearchDto(
    val id: Long = 0,
    val name: String = "",
    val sku: String? = null,
    val variants: List<VariantSearchDto> = emptyList(),
    @SerializedName("primary_image") val primaryImage: PrimaryImageDto? = null,
)

/**
 * Variant từ /v1/variants (indexGlobal) — eager-load product + units + image,
 * append stock_base (tồn theo kho khi truyền warehouse_id).
 */
data class VariantSearchDto(
    val id: Long = 0,
    val sku: String? = null,
    val name: String? = null,
    val price: Double? = null,
    val stock: Double? = null,
    @SerializedName("stock_base") val stockBase: Double? = null,   // tồn base unit theo kho
    val image: String? = null,
    val attributes: Map<String, String>? = null,
    val units: List<VariantUnitDto> = emptyList(),
    val product: ProductMiniDto? = null,
    @SerializedName("default_unit_id") val defaultUnitId: Long? = null,
)

/** Đơn vị tính của variant (cho dropdown + quy đổi tồn). */
data class VariantUnitDto(
    val id: Long = 0,
    val name: String = "",
    @SerializedName("conversion_factor") val conversionFactor: Double = 1.0,
    val price: Double? = null,
    @SerializedName("is_base") val isBase: Boolean = false,
    @SerializedName("is_default_sale") val isDefaultSale: Boolean = false,
)

/** Product rút gọn eager-load trong variant. */
data class ProductMiniDto(
    val id: Long = 0,
    val name: String = "",
    @SerializedName("primary_image") val primaryImage: PrimaryImageDto? = null,
)

data class PrimaryImageDto(val url: String? = null)

/** SP hay mua của KH (chip gợi ý). */
data class RecentProductDto(
    @SerializedName("product_id") val productId: Long = 0,
    @SerializedName("product_name") val productName: String = "",
    @SerializedName("product_sku") val productSku: String? = null,
)

/** Last price KH-variant từ đơn gần nhất. */
data class LastPriceDto(
    @SerializedName("unit_price") val unitPrice: Double? = null,
    @SerializedName("unit_id") val unitId: Long? = null,
    @SerializedName("ordered_at") val orderedAt: String? = null,
)

/** Payload tạo đơn từ app sale. Status quyết draft hay confirmed. */
data class CreateOrderRequest(
    val type: String = "sale",
    @SerializedName("party_type") val partyType: String = "customer",
    @SerializedName("party_id") val partyId: Long,
    val status: String = "draft",                                          // draft | confirmed
    @SerializedName("ordered_at") val orderedAt: String? = null,           // ngày đơn (ISO)
    @SerializedName("warehouse_id") val warehouseId: Long? = null,         // kho bán → NV kho đó thấy
    @SerializedName("shipping_fee") val shippingFee: Double? = null,       // phí ship KH
    @SerializedName("actual_shipping_fee") val actualShippingFee: Double? = null, // phí ship kho
    @SerializedName("cod_collected") val codCollected: Double? = null,     // thu hộ
    val items: List<CreateOrderItem>,
    val notes: String? = null,
    @SerializedName("created_by_user_id") val createdByUserId: Long? = null, // 9chat user id
)

data class CreateOrderItem(
    @SerializedName("variant_id") val variantId: Long,
    @SerializedName("unit_id") val unitId: Long,
    @SerializedName("qty_unit") val qtyUnit: Double,
    @SerializedName("unit_price") val unitPrice: Double,
)
