package vn.chat9.app.data.vapi.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO map JSON vapi. KHÔNG dùng `by lazy` — Gson dùng Unsafe bỏ qua constructor
 * nên lazy-field sẽ null → NPE. Dùng `val` mặc định, hoặc `get()` computed.
 */
/** Mặt hàng để render ảnh gửi NCC (ảnh data-URI + SL, không giá). */
data class SupplierImageDto(
    val title: String = "",
    @SerializedName("order_code") val orderCode: String = "",
    @SerializedName("supplier_short_name") val supplierShortName: String = "",
    val items: List<SupplierImageItemDto> = emptyList(),
)

data class SupplierImageItemDto(
    val name: String = "",
    val qty: Double = 0.0,
    val unit: String = "",
    val image: String? = null,   // data:image/...;base64,...
)

data class OrderDto(
    val id: Long = 0,
    val type: String = "sale",                 // sale|customer_return|purchase|supplier_return
    val status: String = "",
    val code: String = "",
    val party: PartyDto? = null,               // KH (sale) hoặc NCC (purchase)
    @SerializedName("ordered_at") val orderedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
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
    @SerializedName("discount_amount") val discountAmount: Double? = null,      // Giảm cả đơn → giảm công nợ
    @SerializedName("discount_reason") val discountReason: String? = null,      // Lý do giảm cả đơn
    // Drop-ship: đơn liên kết (đơn nhập trỏ tới đơn bán đã tự giao sau cascade fulfill,
    // hoặc đơn bán bị chặn fulfill trực tiếp — phải đi qua đơn nhập).
    @SerializedName("linked_order_id") val linkedOrderId: Long? = null,
    @SerializedName("linked_order") val linkedOrder: LinkedOrderDto? = null,
    @SerializedName("dropship_customer_id") val dropshipCustomerId: Long? = null,   // đơn nhập giao thẳng → KH nhận
    @SerializedName("dropship_customer") val dropshipCustomerObj: DropshipCustomerDto? = null, // KH nhận (tên+màu+tỉnh)
    @SerializedName("vat_output_invoice_id") val vatOutputInvoiceId: Long? = null, // HĐ VAT liên kết (null = đơn nháp chưa xuất)
    @SerializedName("vat_include_phone") val vatIncludePhone: Boolean = false,      // SĐT khách hiển thị trên HĐ
    val meta: MetaDto? = null,
    @SerializedName("thumb_url") val thumbUrl: String? = null,       // ảnh đính kèm đầu tiên (BE resolve ở list)
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
    val isDropship: Boolean get() = isPurchase && (linkedOrderId != null || dropshipCustomerId != null)
    /** Tên ngắn NCC (party đơn nhập). */
    val dropshipSupplier: String get() =
        party?.shortName?.takeIf { it.isNotBlank() } ?: party?.name?.takeIf { it.isNotBlank() } ?: "#${party?.id ?: ""}"
    /** Tên KH nhận — tên thường đã kèm tỉnh; nếu có province riêng mà tên chưa chứa thì thêm "(Tỉnh)". */
    val dropshipCustomer: String get() {
        val name = dropshipCustomerObj?.name?.takeIf { it.isNotBlank() }
            ?: linkedOrder?.party?.name?.takeIf { it.isNotBlank() } ?: "khách hàng"
        val prov = dropshipCustomerObj?.primaryAddress?.province?.takeIf { it.isNotBlank() }
        return if (prov != null && !name.contains(prov)) "$name ($prov)" else name
    }
    /** id + màu định danh KH nhận (fallback linkedOrder.party). */
    val dropshipCustomerColorId: Long get() = dropshipCustomerObj?.id ?: linkedOrder?.party?.id ?: 0
    val dropshipCustomerColor: String? get() = dropshipCustomerObj?.displayColor ?: linkedOrder?.party?.displayColor

    // ===== Chiều ngược: đơn BÁN giao thẳng TỪ NCC (linkedOrder = đơn nhập) → hiện "KH ← NCC".
    val isDropshipSale: Boolean get() = type == "sale" && linkedOrderId != null
    val dropshipFromSupplier: String get() =
        linkedOrder?.party?.shortName?.takeIf { it.isNotBlank() }
            ?: linkedOrder?.party?.name?.takeIf { it.isNotBlank() } ?: "NCC"
    val dropshipSupplierColorId: Long get() = linkedOrder?.party?.id ?: 0
    val dropshipSupplierColor: String? get() = linkedOrder?.party?.displayColor

    /** Thời điểm BẤM xác nhận thật (giờ VN). Đơn cũ chưa có → fallback updated_at.
     *  KHÔNG dùng completed_at (= ngày giao NV tự chọn, có thể backdate). */
    val fulfilledRealTime: String? get() = meta?.fulfillment?.atServer ?: updatedAt
}

data class PartyDto(
    val id: Long = 0,
    val name: String? = null,
    val phone: String? = null,
    @SerializedName("short_name") val shortName: String? = null,
    @SerializedName("display_color") val displayColor: String? = null,  // màu định danh (BE trả)
)

/** KH nhận hàng giao thẳng (đơn nhập drop-ship) — tên + màu định danh + tỉnh. */
data class DropshipCustomerDto(
    val id: Long = 0,
    val name: String? = null,
    @SerializedName("display_color") val displayColor: String? = null,
    @SerializedName("primary_address") val primaryAddress: DropshipAddressDto? = null,
)
data class DropshipAddressDto(val province: String? = null)

data class OrderItemDto(
    val id: Long = 0,
    @SerializedName("variant_id") val variantId: Long = 0,
    @SerializedName("unit_id") val unitId: Long = 0,
    @SerializedName("qty_unit") val qtyUnit: Double = 0.0,
    @SerializedName("unit_price") val unitPrice: Double = 0.0,
    @SerializedName("stock_unit") val stockUnit: Double? = null,   // tồn quy đổi (BE @show)
    @SerializedName("image_url") val imageUrl: String? = null,     // thumb variant (BE resolve)
    val variant: OrderItemVariantDto? = null,                      // eager-load: units cho đổi đơn vị
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

/** Response của add/update 1 item ({ item, order }) — cần item.id sau autosave thêm dòng. */
data class OrderItemMutationDto(
    val item: OrderItemDto? = null,
    val order: OrderDto? = null,
)

/** Variant rút gọn trong order item — chỉ cần units cho dropdown đổi đơn vị. */
data class OrderItemVariantDto(
    val id: Long = 0,
    val units: List<VariantUnitDto> = emptyList(),
)

data class SnapshotDto(
    @SerializedName("product_name") val productName: String? = null,
    @SerializedName("variant_name") val variantName: String? = null,
    @SerializedName("variant_attributes") val variantAttributes: Map<String, String>? = null,
    @SerializedName("unit_name") val unitName: String? = null,
)

data class MetaDto(
    val fulfillment: FulfillmentDto? = null,
    @SerializedName("vat_price_type") val vatPriceType: String? = null, // inclusive|exclusive (hình thức giá HĐ đã lưu)
)

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
    val code: String = "",
    val name: String = "",
    val type: String = "",                     // petty_cash|bank_account|main|safe|mobile
    @SerializedName("current_balance") val currentBalance: Double = 0.0,   // số dư hiện tại (auto-sync)
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("is_default") val isDefault: Boolean = false,
    @SerializedName("warehouse_id") val warehouseId: Long? = null,
)

// ----- Kế toán: dòng tiền (money-transactions) + chi phí (expense-categories) -----
data class MoneyTransactionDto(
    val id: Long = 0,
    val type: String = "",                     // pending|cust_cash_in|cust_bank_in|...
    val direction: String = "",                // in|out
    val channel: String = "",                  // bank|cash|internal
    @SerializedName("party_type") val partyType: String? = null,
    @SerializedName("party_id") val partyId: Long? = null,
    @SerializedName("casher_id") val casherId: Long? = null,
    @SerializedName("bank_name") val bankName: String? = null,
    @SerializedName("bank_account") val bankAccount: String? = null,
    @SerializedName("purpose_detail") val purposeDetail: String? = null,
    val amount: Double = 0.0,
    val code: String = "",
    val date: String = "",
    val reference: String? = null,
    val description: String? = null,
    @SerializedName("debt_calc") val debtCalc: String? = null,
    @SerializedName("money_calc") val moneyCalc: String? = null,
    @SerializedName("auto_matched") val autoMatched: Boolean = false,
)

data class ExpenseCategoryDto(
    val id: Long = 0,
    val name: String = "",
)

/** Tạo GD tiền mặt (thu KH type=cust_cash_in; chi phí type=pending+direction=out+channel=cash). */
data class CreateMoneyTxRequest(
    val type: String,
    val direction: String? = null,
    val channel: String? = null,
    @SerializedName("party_type") val partyType: String? = null,
    @SerializedName("party_id") val partyId: Long? = null,
    @SerializedName("casher_id") val casherId: Long? = null,
    val amount: Double,
    val date: String,
    val description: String? = null,
)

/** Biến GD pending tiền ra → chi phí (tạo Expense + duyệt + mark-paid). */
data class PayExpenseRequest(
    @SerializedName("category_id") val categoryId: Long,
    val description: String? = null,
)

// ----- Kế toán: Công nợ (debts) -----
data class DebtOverviewRowDto(
    @SerializedName("party_type") val partyType: String = "",
    @SerializedName("party_id") val partyId: Long = 0,
    val name: String = "",
    val code: String? = null,
    @SerializedName("display_color") val displayColor: String? = null,
    val posted: Double = 0.0,
    val pending: Double = 0.0,
    @SerializedName("pending_count") val pendingCount: Int = 0,
    val balance: Double = 0.0,
)

data class DebtOverviewDto(
    @SerializedName("party_type") val partyType: String = "",
    val count: Int = 0,
    @SerializedName("total_positive") val totalPositive: Double = 0.0,
    @SerializedName("total_negative") val totalNegative: Double = 0.0,
    val rows: List<DebtOverviewRowDto> = emptyList(),
)

data class DebtPeriodDto(val from: String = "", val to: String = "")

data class DebtStatementRowDto(
    val id: Long = 0,
    @SerializedName("doc_no") val docNo: String? = null,
    val date: String = "",
    val origin: String? = null,
    @SerializedName("origin_table") val originTable: String? = null,
    @SerializedName("origin_id") val originId: Long? = null,
    val description: String? = null,
    val qty: Double? = null,
    @SerializedName("unit_name") val unitName: String? = null,
    @SerializedName("unit_price") val unitPrice: Double? = null,
    val debit: Double = 0.0,
    val credit: Double = 0.0,
)

data class DebtStatementDto(
    @SerializedName("party_type") val partyType: String = "",
    @SerializedName("party_id") val partyId: Long = 0,
    val period: DebtPeriodDto = DebtPeriodDto(),
    @SerializedName("opening_balance") val openingBalance: Double = 0.0,
    @SerializedName("total_debit") val totalDebit: Double = 0.0,
    @SerializedName("total_credit") val totalCredit: Double = 0.0,
    @SerializedName("closing_balance") val closingBalance: Double = 0.0,
    val rows: List<DebtStatementRowDto> = emptyList(),
)

data class DebtPendingRowDto(
    val description: String? = null,
    @SerializedName("born_debt") val bornDebt: Double = 0.0,
    @SerializedName("born_credit") val bornCredit: Double = 0.0,
    @SerializedName("unit_name") val unitName: String? = null,
    val qty: Double? = null,
    @SerializedName("unit_price") val unitPrice: Double? = null,
    // Cho sửa inline SL/đơn giá ngay ở tab Chưa chốt (chỉ dòng item đơn hàng có đủ 3 field này).
    @SerializedName("variant_id") val variantId: Long? = null,
    @SerializedName("unit_id") val unitId: Long? = null,
    val meta: DebtPendingRowMetaDto? = null,
)

data class DebtPendingRowMetaDto(
    @SerializedName("order_item_id") val orderItemId: Long? = null,
)

data class DebtPendingSourceDto(
    val type: String = "",
    @SerializedName("origin_table") val originTable: String = "",
    @SerializedName("origin_id") val originId: Long = 0,
    @SerializedName("origin_key") val originKey: String = "",
    @SerializedName("doc_no") val docNo: String? = null,
    @SerializedName("accounting_date") val accountingDate: String = "",
    @SerializedName("is_backdated") val isBackdated: Boolean = false,
    val rows: List<DebtPendingRowDto> = emptyList(),
)

data class DebtPendingAdvanceDto(
    val id: Long = 0,
    val code: String = "",
    val amount: Double = 0.0,
    val reimbursed: Double = 0.0,
    val remaining: Double = 0.0,
    val description: String? = null,
    @SerializedName("order_id") val orderId: Long? = null,
    @SerializedName("order_code") val orderCode: String? = null,
    @SerializedName("expense_date") val expenseDate: String? = null,
)

// ----- Hoàn ứng ship: ứng viên GD tiền ra + phân bổ (allocate customer_debt) -----
data class ExpensePaymentCandidateDto(
    val id: Long = 0,
    val code: String = "",
    val type: String = "",
    val amount: Double = 0.0,
    val date: String = "",
    @SerializedName("bank_name") val bankName: String? = null,
    val description: String? = null,
    @SerializedName("casher_id") val casherId: Long? = null,
)
data class PaymentCandidatesDto(val candidates: List<ExpensePaymentCandidateDto> = emptyList())
data class AllocateLineDto(
    val purpose: String,
    @SerializedName("party_id") val partyId: Long?,
    @SerializedName("order_id") val orderId: Long?,
    val amount: Double,
)
data class AllocateRequest(val lines: List<AllocateLineDto>)
data class AllocateResultDto(val transaction: MoneyTransactionDto? = null)

data class DebtPendingDto(
    @SerializedName("pending_count") val pendingCount: Int = 0,
    @SerializedName("total_debit") val totalDebit: Double = 0.0,
    @SerializedName("total_credit") val totalCredit: Double = 0.0,
    val sources: List<DebtPendingSourceDto> = emptyList(),
    @SerializedName("pending_advances") val pendingAdvances: List<DebtPendingAdvanceDto> = emptyList(),
)

data class DebtBackdatedSourceDto(
    @SerializedName("origin_key") val originKey: String = "",
    @SerializedName("accounting_date") val accountingDate: String = "",
    @SerializedName("source_type") val sourceType: String = "",
)

data class DebtBackdatedPreviewDto(
    @SerializedName("last_accounting_date_in_ledger") val lastAccountingDate: String? = null,
    @SerializedName("backdated_count") val backdatedCount: Int = 0,
    val sources: List<DebtBackdatedSourceDto> = emptyList(),
)

data class DebtPostRequest(
    @SerializedName("party_type") val partyType: String,
    @SerializedName("party_id") val partyId: Long,
    @SerializedName("backdate_reasons") val backdateReasons: Map<String, String> = emptyMap(),
)

data class DebtPostResultDto(
    @SerializedName("rows_created") val rowsCreated: Int = 0,
    @SerializedName("current_balance") val currentBalance: Double = 0.0,
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
    @SerializedName("display_color") val displayColor: String? = null,
)

/** NCC cho picker đơn nhập + search. BE trả full Supplier; chỉ dùng cột nhẹ ở UI. */
data class SupplierDto(
    val id: Long = 0,
    val code: String = "",
    val name: String = "",
    @SerializedName("short_name") val shortName: String? = null,
    val phone: String? = null,
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("display_color") val displayColor: String? = null,
) {
    /** Tên hiển thị: ưu tiên short_name (đơn nhập sắp xếp + hiển thị theo tên rút gọn). */
    val display: String get() = shortName?.takeIf { it.isNotBlank() } ?: name
}

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
    @SerializedName("is_default_invoice") val isDefaultInvoice: Boolean = false,
)

/** Product rút gọn eager-load trong variant. */
data class ProductMiniDto(
    val id: Long = 0,
    val name: String = "",
    @SerializedName("primary_image") val primaryImage: PrimaryImageDto? = null,
)

data class PrimaryImageDto(val url: String? = null)

/**
 * Dòng lịch sử kho (product_history). actor_name/actor_type do BE enrich:
 * đơn nhập/bán → đối tác (NCC/khách), kiểm kho → nhân viên.
 */
data class ProductHistoryDto(
    val id: Long = 0,
    val type: Int = 0,
    @SerializedName("type_label") val typeLabel: String = "",
    @SerializedName("qty_change") val qtyChange: Double? = null,
    @SerializedName("stock_after") val stockAfter: Double? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("actor_name") val actorName: String? = null,
    @SerializedName("actor_type") val actorType: String? = null,
    @SerializedName("ref_type") val refType: String? = null,   // order|order_edit|orders → có thể có ảnh đơn
    @SerializedName("ref_id") val refId: Long? = null,
)

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
/** Đơn vị mua (vat_info) của khách — cho HĐ VAT. */
data class VatInfoDto(
    val id: Long = 0,
    @SerializedName("tax_code") val taxCode: String? = null,
    @SerializedName("legal_name") val legalName: String? = null,
    @SerializedName("short_name") val shortName: String? = null,
)

data class VatBuyerNameReq(@SerializedName("buyer_name") val buyerName: String?)

/** HĐ VAT đầu ra (vat_output_invoices) — nguồn list tab VAT (mirror SaleVatListView). */
data class VatOutputInvoiceDto(
    val id: Long = 0,
    val number: String? = null,                                       // số HĐ (đã phát hành)
    val series: String? = null,
    @SerializedName("template_code") val templateCode: String? = null, // ký hiệu mẫu
    val subtotal: Double = 0.0,                                        // tiền hàng chưa thuế
    @SerializedName("vat_amount") val vatAmount: Double = 0.0,         // tổng VAT
    val total: Double = 0.0,
    val items: List<VatOutputItemDto> = emptyList(),                  // dòng HĐ → gộp VAT theo suất
    @SerializedName("issue_date") val issueDate: String? = null,
    @SerializedName("buyer_name") val buyerName: String? = null,      // tên đơn vị mua ghi trên HĐ
    @SerializedName("seller_tax_code") val sellerTaxCode: String? = null, // MST bên bán → tra cứu EI
    @SerializedName("cqt_code") val cqtCode: String? = null,          // mã CQT (đã ký)
    @SerializedName("cqt_status") val cqtStatus: Int? = null,
    @SerializedName("cqt_status_label") val cqtStatusLabel: String? = null,
    @SerializedName("easyinvoice_lookup_code") val eiLookupCode: String? = null,
    @SerializedName("order_id") val orderId: Long? = null,
    @SerializedName("vat_info_id") val vatInfoId: Long? = null,       // đơn vị mua đã dùng xuất HĐ
    @SerializedName("created_at") val createdAt: String? = null,      // thời gian tạo HĐ nháp EI
    val order: OrderDto? = null,                                      // đơn gắn HĐ (lấy tên KH)
) {
    /** Đã phát hành = có mã CQT hoặc cqt_status ∈ {5,6,7}. */
    val signed: Boolean get() = !cqtCode.isNullOrBlank() || cqtStatus in listOf(5, 6, 7)
    /** Tên KH của đơn (khác tên đơn vị mua). */
    val customerName: String get() = order?.party?.name?.takeIf { it.isNotBlank() } ?: "—"
}

/** Dòng HĐ VAT đầu ra — dùng để gộp VAT theo thuế suất (vat_breakdown BE để null). */
data class VatOutputItemDto(
    @SerializedName("vat_rate") val vatRate: Double = 0.0,
    @SerializedName("vat_amount") val vatAmount: Double = 0.0,
    val subtotal: Double = 0.0,           // tiền hàng chưa thuế (net) dòng
    val total: Double = 0.0,              // thành tiền đã gồm VAT (gross) dòng
)

/** Kết quả Upload PO → AI tạo HĐ VAT nháp. */
data class PoDraftResultDto(
    val order: OrderDto? = null,
    @SerializedName("matched_customer") val matchedCustomer: PartyDto? = null,
    val unresolved: List<String> = emptyList(),
)

/** Ảnh HĐ nháp render từng trang (data URL base64). */
data class VatDraftPageDto(
    val page: Int = 0,
    val image: String = "",                                          // "data:image/png;base64,..."
    @SerializedName("size_bytes") val sizeBytes: Long = 0,
)
data class VatDraftImagesDto(
    val pages: List<VatDraftPageDto> = emptyList(),
    @SerializedName("total_pages") val totalPages: Int = 0,
    val cached: Boolean = false,
)

/** Kiểm tra thiếu tồn XNT khi ký HĐ. */
data class VatShortageDto(
    @SerializedName("item_name") val itemName: String = "",
    val unit: String? = null,
    @SerializedName("requested_qty") val requestedQty: Double = 0.0,
    @SerializedName("available_qty") val availableQty: Double = 0.0,
    @SerializedName("shortage_qty") val shortageQty: Double = 0.0,
)
data class VatStockCheckDto(val shortages: List<VatShortageDto> = emptyList(), val ok: Boolean = true)

data class VatPriceIssueDto(
    @SerializedName("item_name") val itemName: String = "",
    val unit: String? = null,
    @SerializedName("sale_price") val salePrice: Double = 0.0,
    @SerializedName("cost_price") val costPrice: Double = 0.0,
    val diff: Double = 0.0,
    @SerializedName("price_type") val priceType: String = "exclusive",
)
data class VatPriceCheckDto(val issues: List<VatPriceIssueDto> = emptyList(), val ok: Boolean = true)

/** Body cho create/update/issue VAT draft (price_type + vat_info_id + ignore_unit_warnings). */
data class VatDraftReq(
    @SerializedName("price_type") val priceType: String = "inclusive",
    @SerializedName("ignore_unit_warnings") val ignoreUnitWarnings: Boolean = true,
    @SerializedName("vat_info_id") val vatInfoId: Long? = null,
)
data class VatIncludePhoneReq(val include: Boolean)
data class SyncEiReq(val from: String? = null, val to: String? = null)
/** Thêm đơn vị mua (vat_info) cho khách. */
data class AttachVatInfoReq(
    val type: String = "business",                                   // business | personal
    @SerializedName("tax_code") val taxCode: String,                 // MST hoặc CCCD
    @SerializedName("legal_name") val legalName: String,
    @SerializedName("short_name") val shortName: String? = null,
    val address: String? = null,
)

/** Kết quả tra cứu MST (VietQR proxy) — chỉ áp dụng doanh nghiệp. */
data class TaxLookupDto(
    @SerializedName("tax_code") val taxCode: String? = null,
    @SerializedName("legal_name") val legalName: String? = null,
    @SerializedName("short_name") val shortName: String? = null,
    val address: String? = null,
    val status: String? = null,
)

data class CreateOrderRequest(
    val type: String = "sale",
    @SerializedName("is_invoice_only") val isInvoiceOnly: Boolean? = null, // đơn ảo HĐ VAT (không trừ kho)
    @SerializedName("party_type") val partyType: String = "customer",
    @SerializedName("party_id") val partyId: Long,
    val status: String = "draft",                                          // draft | confirmed
    @SerializedName("ordered_at") val orderedAt: String? = null,           // ngày đơn (ISO)
    @SerializedName("warehouse_id") val warehouseId: Long? = null,         // kho bán → NV kho đó thấy
    @SerializedName("shipping_fee") val shippingFee: Double? = null,       // phí ship KH
    @SerializedName("actual_shipping_fee") val actualShippingFee: Double? = null, // phí ship kho
    @SerializedName("cod_collected") val codCollected: Double? = null,     // thu hộ
    @SerializedName("discount_amount") val discountAmount: Double? = null,  // giảm cả đơn → giảm công nợ
    @SerializedName("discount_reason") val discountReason: String? = null,  // lý do giảm cả đơn
    @SerializedName("dropship_customer_id") val dropshipCustomerId: Long? = null, // đơn nhập giao thẳng → KH nhận
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

// ===== Báo cáo Lãi/Lỗ (P&L) — GET /v1/reports/pnl* (mirror @/types/models/pnl.ts) =====
data class PnLRevenueDto(
    @SerializedName("gross_revenue") val grossRevenue: Double = 0.0,
    @SerializedName("customer_returns") val customerReturns: Double = 0.0,
    @SerializedName("revenue_deduction") val revenueDeduction: Double = 0.0,
    @SerializedName("net_revenue") val netRevenue: Double = 0.0,
)
data class PnLCogsDto(
    @SerializedName("net_cogs") val netCogs: Double = 0.0,
)
data class PnLOpexBreakdownDto(
    @SerializedName("category_id") val categoryId: Long = 0,
    val name: String = "",
    val entries: Int = 0,
    val amount: Double = 0.0,
)
data class PnLOpexDto(
    @SerializedName("operating_base") val operatingBase: Double = 0.0,
    @SerializedName("marketing_discount") val marketingDiscount: Double = 0.0,
    @SerializedName("loyalty_discount") val loyaltyDiscount: Double = 0.0,
    @SerializedName("returns_writeoff") val returnsWriteoff: Double = 0.0,
    @SerializedName("total_opex") val totalOpex: Double = 0.0,
    val breakdown: List<PnLOpexBreakdownDto> = emptyList(),
)
data class PnLFinancialDto(
    @SerializedName("loan_interest") val loanInterest: Double = 0.0,
)
data class PnLTaxInvoiceCostsDto(
    @SerializedName("invoice_fee") val invoiceFee: Double = 0.0,
    @SerializedName("vat_payable") val vatPayable: Double = 0.0,
    val cit: Double = 0.0,
    val total: Double = 0.0,
)
data class PnLStatementDto(
    val revenue: PnLRevenueDto = PnLRevenueDto(),
    val cogs: PnLCogsDto = PnLCogsDto(),
    @SerializedName("gross_profit") val grossProfit: Double = 0.0,
    @SerializedName("gross_margin_percent") val grossMarginPercent: Double = 0.0,
    @SerializedName("operating_expenses") val operatingExpenses: PnLOpexDto = PnLOpexDto(),
    @SerializedName("operating_profit") val operatingProfit: Double = 0.0,
    @SerializedName("operating_margin_percent") val operatingMarginPercent: Double = 0.0,
    @SerializedName("financial_expenses") val financialExpenses: PnLFinancialDto = PnLFinancialDto(),
    @SerializedName("tax_invoice_costs") val taxInvoiceCosts: PnLTaxInvoiceCostsDto = PnLTaxInvoiceCostsDto(),
    @SerializedName("net_profit") val netProfit: Double = 0.0,
    @SerializedName("net_margin_percent") val netMarginPercent: Double = 0.0,
)
data class PnLTimelineRowDto(
    val label: String = "",
    @SerializedName("net_profit") val netProfit: Double = 0.0,
)
data class PnLCategoryRowDto(
    @SerializedName("category_id") val categoryId: Long = 0,
    val name: String = "",
    @SerializedName("total_amount") val totalAmount: Double = 0.0,
)
data class PnLTopProductDto(
    @SerializedName("product_id") val productId: Long = 0,
    @SerializedName("product_name") val productName: String = "",
    val sku: String? = null,
    @SerializedName("qty_sold") val qtySold: Double = 0.0,
    val revenue: Double = 0.0,
    val cogs: Double = 0.0,
    @SerializedName("gross_profit") val grossProfit: Double = 0.0,
    @SerializedName("gross_margin_percent") val grossMarginPercent: Double = 0.0,
)
data class PnLPartyRefDto(
    val id: Long = 0,
    val name: String? = null,
    @SerializedName("display_color") val displayColor: String? = null,
)
data class PnLTotalsDto(
    @SerializedName("net_profit") val netProfit: Double = 0.0,
)
data class PnLOrderRowDto(
    @SerializedName("order_id") val orderId: Long = 0,
    val code: String = "",
    @SerializedName("ordered_at") val orderedAt: String? = null,
    val estimated: Boolean = false,
    val customer: PnLPartyRefDto? = null,
    @SerializedName("net_profit") val netProfit: Double = 0.0,
)
data class PnLOrdersReportDto(
    val count: Int = 0,
    @SerializedName("estimated_count") val estimatedCount: Int = 0,
    val totals: PnLTotalsDto = PnLTotalsDto(),
    val rows: List<PnLOrderRowDto> = emptyList(),
)
data class PnLCustomerRowDto(
    @SerializedName("customer_id") val customerId: Long? = null,
    @SerializedName("customer_name") val customerName: String? = null,
    @SerializedName("customer_display_color") val customerDisplayColor: String? = null,
    @SerializedName("order_count") val orderCount: Int = 0,
    @SerializedName("estimated_count") val estimatedCount: Int = 0,
    @SerializedName("net_profit") val netProfit: Double = 0.0,
)
data class PnLCustomersReportDto(
    val count: Int = 0,
    @SerializedName("estimated_count") val estimatedCount: Int = 0,
    val totals: PnLTotalsDto = PnLTotalsDto(),
    val rows: List<PnLCustomerRowDto> = emptyList(),
)
data class PnLOrderItemDto(
    @SerializedName("order_item_id") val orderItemId: Long = 0,
    val name: String? = null,
    @SerializedName("unit_name") val unitName: String? = null,
    @SerializedName("qty_unit") val qtyUnit: Double = 0.0,
    @SerializedName("unit_price") val unitPrice: Double = 0.0,
    @SerializedName("line_revenue") val lineRevenue: Double = 0.0,
    @SerializedName("cost_each") val costEach: Double = 0.0,
    @SerializedName("line_cogs") val lineCogs: Double = 0.0,
    @SerializedName("line_profit") val lineProfit: Double = 0.0,
    @SerializedName("margin_percent") val marginPercent: Double = 0.0,
    val estimated: Boolean = false,
)
data class PnLOrderShipDto(
    val actual: Double = 0.0,
    @SerializedName("reimbursed_advance") val reimbursedAdvance: Double = 0.0,
    @SerializedName("open_advance") val openAdvance: Double = 0.0,
    val margin: Double = 0.0,
)
data class PnLOrderRevenueDto(
    val net: Double = 0.0,
)
data class PnLOrderDetailDto(
    val applicable: Boolean = false,
    val reason: String? = null,
    @SerializedName("order_id") val orderId: Long = 0,
    val code: String = "",
    @SerializedName("ordered_at") val orderedAt: String? = null,
    val customer: PnLPartyRefDto? = null,
    val revenue: PnLOrderRevenueDto = PnLOrderRevenueDto(),
    val cogs: Double = 0.0,
    @SerializedName("gross_profit") val grossProfit: Double = 0.0,
    @SerializedName("gross_margin_percent") val grossMarginPercent: Double = 0.0,
    val ship: PnLOrderShipDto = PnLOrderShipDto(),
    @SerializedName("other_discount") val otherDiscount: Double = 0.0,
    @SerializedName("net_profit") val netProfit: Double = 0.0,
    @SerializedName("net_margin_percent") val netMarginPercent: Double = 0.0,
    val items: List<PnLOrderItemDto> = emptyList(),
)
