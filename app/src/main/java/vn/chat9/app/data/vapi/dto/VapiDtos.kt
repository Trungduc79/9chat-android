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
}

data class PartyDto(
    val id: Long = 0,
    val name: String? = null,
    @SerializedName("short_name") val shortName: String? = null,
)

data class OrderItemDto(
    val id: Long = 0,
    @SerializedName("variant_id") val variantId: Long = 0,
    @SerializedName("qty_unit") val qtyUnit: Double = 0.0,
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
    @SerializedName("variant_attributes") val variantAttributes: Map<String, String>? = null,
    @SerializedName("unit_name") val unitName: String? = null,
)

data class MetaDto(val fulfillment: FulfillmentDto? = null)

data class FulfillmentDto(
    @SerializedName("by_user_id") val byUserId: Long? = null,
    @SerializedName("by_name") val byName: String? = null,
    val at: String? = null,                    // thời điểm xác nhận (ISO)
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

data class VariantSearchDto(
    val id: Long = 0,
    val sku: String? = null,
    val name: String? = null,
    val price: Double? = null,
    val stock: Double? = null,
    val image: String? = null,
    val attributes: Map<String, String>? = null,
    @SerializedName("default_unit_id") val defaultUnitId: Long? = null,    // BE: is_default_sale unit
)

data class PrimaryImageDto(val url: String? = null)

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
