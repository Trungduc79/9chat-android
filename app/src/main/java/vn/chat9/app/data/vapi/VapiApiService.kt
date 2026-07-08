package vn.chat9.app.data.vapi

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import vn.chat9.app.data.vapi.dto.AttachmentDto
import vn.chat9.app.data.vapi.dto.CasherDto
import vn.chat9.app.data.vapi.dto.CategoryDto
import vn.chat9.app.data.vapi.dto.CreateMoneyTxRequest
import vn.chat9.app.data.vapi.dto.DebtBackdatedPreviewDto
import vn.chat9.app.data.vapi.dto.DebtOverviewDto
import vn.chat9.app.data.vapi.dto.DebtPendingDto
import vn.chat9.app.data.vapi.dto.DebtPostRequest
import vn.chat9.app.data.vapi.dto.DebtPostResultDto
import vn.chat9.app.data.vapi.dto.DebtStatementDto
import vn.chat9.app.data.vapi.dto.ExpenseCategoryDto
import vn.chat9.app.data.vapi.dto.MoneyTransactionDto
import vn.chat9.app.data.vapi.dto.PayExpenseRequest
import vn.chat9.app.data.vapi.dto.ProductHistoryDto
import vn.chat9.app.data.vapi.dto.StaffRolesDto
import vn.chat9.app.data.vapi.dto.StocktakeRequest
import vn.chat9.app.data.vapi.dto.StocktakeResultDto
import vn.chat9.app.data.vapi.dto.CreateOrderItem
import vn.chat9.app.data.vapi.dto.CreateOrderRequest
import vn.chat9.app.data.vapi.dto.AttachVatInfoReq
import vn.chat9.app.data.vapi.dto.PoDraftResultDto
import vn.chat9.app.data.vapi.dto.SyncEiReq
import vn.chat9.app.data.vapi.dto.TaxLookupDto
import vn.chat9.app.data.vapi.dto.VatBuyerNameReq
import vn.chat9.app.data.vapi.dto.VatDraftImagesDto
import vn.chat9.app.data.vapi.dto.VatDraftReq
import vn.chat9.app.data.vapi.dto.VatIncludePhoneReq
import vn.chat9.app.data.vapi.dto.VatInfoDto
import vn.chat9.app.data.vapi.dto.VatOutputInvoiceDto
import vn.chat9.app.data.vapi.dto.VatStockCheckDto
import vn.chat9.app.data.vapi.dto.CustomerDto
import vn.chat9.app.data.vapi.dto.FulfillRequest
import vn.chat9.app.data.vapi.dto.FulfillResult
import vn.chat9.app.data.vapi.dto.LastPriceDto
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.data.vapi.dto.ProductSearchDto
import vn.chat9.app.data.vapi.dto.RecentProductDto
import vn.chat9.app.data.vapi.dto.VariantSearchDto
import vn.chat9.app.data.vapi.dto.WarehouseDto

/**
 * Retrofit cho vapi (gateway nghiệp vụ — KHÁC backend 9chat). Auth = X-API-Key
 * (định danh app "9chat Android"); danh tính user 9chat truyền trong payload.
 * Mọi module quản trị dùng chung interface này (thêm endpoint khi có module mới).
 */
interface VapiApiService {

    @GET("v1/orders")
    suspend fun listOrders(
        @Query("status") status: String? = null,
        @Query("warehouse_id") warehouseId: Long? = null,
        @Query("created_by_user_id") createdByUserId: Long? = null,  // app sale: chỉ đơn của NV
        @Query("type") type: String? = null,                          // sale|purchase|...
        @Query("invoice_only") invoiceOnly: String? = null,           // '1' = chỉ HĐ VAT (is_invoice_only)
        @Query("per_page") perPage: Int = 100,
    ): VapiResponse<List<OrderDto>>

    // ===== HĐ VAT =====
    @GET("v1/customers/{id}/vat-info")
    suspend fun listCustomerVatInfo(@Path("id") customerId: Long): VapiResponse<List<VatInfoDto>>

    @retrofit2.http.PATCH("v1/orders/{id}/vat-buyer-name")
    suspend fun setVatBuyerName(@Path("id") id: Long, @Body body: VatBuyerNameReq): VapiResponse<Unit>

    @GET("v1/orders/{id}")
    suspend fun getOrder(@Path("id") id: Long): VapiResponse<OrderDto>

    @POST("v1/orders/{id}/fulfill")
    suspend fun fulfill(@Path("id") id: Long, @Body body: FulfillRequest): VapiResponse<FulfillResult>

    @GET("v1/attachments")
    suspend fun listAttachments(
        @Query("attachable_type") type: String = "order",
        @Query("attachable_id") id: Long? = null,
        @Query("kind") kind: String = "photo",
        @Query("per_page") perPage: Int = 200,
    ): VapiResponse<List<AttachmentDto>>

    @Multipart
    @POST("v1/attachments")
    suspend fun uploadAttachment(
        @Part("attachable_type") type: RequestBody,
        @Part("attachable_id") id: RequestBody,
        @Part("kind") kind: RequestBody,
        @Part file: MultipartBody.Part,
    ): VapiResponse<AttachmentDto>

    @DELETE("v1/attachments/{id}")
    suspend fun deleteAttachment(@Path("id") id: Long): VapiResponse<Unit>

    // Quỹ thu/chi (cashers) — dropdown chọn quỹ cho COD
    @GET("v1/cashers")
    suspend fun listCashers(
        @Query("active") active: Int = 1,
        @Query("per_page") perPage: Int = 100,
    ): VapiResponse<List<CasherDto>>

    // Kho — màn chọn kho làm việc + lọc đơn theo kho
    @GET("v1/warehouses")
    suspend fun listWarehouses(
        @Query("active") active: Int = 1,
        @Query("per_page") perPage: Int = 100,
    ): VapiResponse<List<WarehouseDto>>

    // ===== Module Sale (Tab Khám phá → Bán hàng) =====

    /** KH gần nhất theo user-tạo-đơn (20 hàng đầu) — recent customers cho picker. */
    @GET("v1/customers/recent-by-orders")
    suspend fun recentCustomers(
        @Query("created_by_user_id") createdByUserId: Long,
        @Query("limit") limit: Int = 20,
    ): VapiResponse<List<CustomerDto>>

    /** Search KH theo tên / SĐT / mã (BE param 'search' — name + name_ascii bỏ dấu). */
    @GET("v1/customers")
    suspend fun searchCustomers(
        @Query("search") q: String,
        @Query("per_page") perPage: Int = 20,
    ): VapiResponse<List<CustomerDto>>

    /** KH có đơn bán ĐÃ GIAO hôm nay (giao muộn nhất trước) — gợi ý thu tiền mặt. */
    @GET("v1/customers/delivered-today")
    suspend fun customersDeliveredToday(
        @Query("limit") limit: Int = 5,
    ): VapiResponse<List<CustomerDto>>

    /** Search/list SP (`vapi /products`). q rỗng + category_id → list SP theo danh mục
     *  (dùng cho dropdown sản phẩm cascade ở Kiểm kho). */
    @GET("v1/products")
    suspend fun searchProducts(
        @Query("q") q: String? = null,
        @Query("category_id") categoryId: Long? = null,
        @Query("warehouse_id") warehouseId: Long? = null,   // → BE sort theo tồn kho cao→thấp
        @Query("per_page") perPage: Int = 50,
    ): VapiResponse<List<ProductSearchDto>>

    /** Danh mục (dòng sản phẩm) cho dropdown lọc. */
    @GET("v1/categories")
    suspend fun listCategories(
        @Query("per_page") perPage: Int = 200,
    ): VapiResponse<List<CategoryDto>>

    /** Lưu kiểm kho: áp số đếm thực tế cho 1 kho (điều chỉnh tồn + ghi lịch sử). */
    @POST("v1/stocktake")
    suspend fun submitStocktake(@Body req: StocktakeRequest): VapiResponse<StocktakeResultDto>

    /** Vai trò nhân viên khớp theo SĐT — mở module 9chat theo vai trò. */
    @GET("v1/staff/roles-by-phone")
    suspend fun staffRolesByPhone(@Query("phone") phone: String): VapiResponse<StaffRolesDto>

    /** Search BIẾN THỂ trực tiếp (/v1/variants) — eager-load product+units+image,
     *  append stock_base theo warehouse_id (tồn theo kho). category_id lọc theo danh mục. */
    @GET("v1/variants")
    suspend fun listAllVariants(
        @Query("search") search: String? = null,
        @Query("product_id") productId: Long? = null,
        @Query("category_id") categoryId: Long? = null,
        @Query("warehouse_id") warehouseId: Long? = null,
        @Query("per_page") perPage: Int = 30,
    ): VapiResponse<List<VariantSearchDto>>

    // Lịch sử kho 1 variant (mới nhất lên đầu). product_id để BE tính sổ tồn luỹ kế.
    @GET("v1/product-history")
    suspend fun listProductHistory(
        @Query("product_id") productId: Long? = null,
        @Query("variant_id") variantId: Long? = null,
        @Query("from") from: String? = null,   // yyyy-MM-dd
        @Query("to") to: String? = null,        // yyyy-MM-dd
        @Query("per_page") perPage: Int = 100,
    ): VapiResponse<List<ProductHistoryDto>>

    /** 5 SP hay mua của KH (chip gợi ý). */
    @GET("v1/customers/{customerId}/recent-products")
    suspend fun recentProducts(
        @Path("customerId") customerId: Long,
        @Query("limit") limit: Int = 5,
    ): VapiResponse<List<RecentProductDto>>

    /** Last price KH đã mua variant này (cho auto-fill giá khi add item). */
    @GET("v1/customers/{customerId}/last-price")
    suspend fun lastPrice(
        @Path("customerId") customerId: Long,
        @Query("variant_id") variantId: Long,
        @Query("unit_id") unitId: Long? = null,
    ): VapiResponse<LastPriceDto>

    /** Tạo đơn nháp / xác nhận (status quyết payload). */
    @POST("v1/orders")
    suspend fun createOrder(@Body body: CreateOrderRequest): VapiResponse<OrderDto>

    /** Cập nhật đơn (edit đơn draft từ màn chi tiết sale). */
    @retrofit2.http.PUT("v1/orders/{id}")
    suspend fun updateOrder(@Path("id") id: Long, @Body body: CreateOrderRequest): VapiResponse<OrderDto>

    // Sửa item đơn NON-DRAFT (bulk update bỏ qua items với đơn non-draft) → per-item endpoint.
    @POST("v1/orders/{id}/items")
    suspend fun addOrderItem(@Path("id") id: Long, @Body body: CreateOrderItem): VapiResponse<Unit>

    @retrofit2.http.PUT("v1/orders/{id}/items/{itemId}")
    suspend fun updateOrderItem(@Path("id") id: Long, @Path("itemId") itemId: Long, @Body body: CreateOrderItem): VapiResponse<Unit>

    @DELETE("v1/orders/{id}/items/{itemId}")
    suspend fun deleteOrderItem(@Path("id") id: Long, @Path("itemId") itemId: Long): VapiResponse<Unit>

    /** Xoá đơn (dọn đơn nháp VAT chưa xuất HĐ ở tab VAT). */
    @DELETE("v1/orders/{id}")
    suspend fun deleteOrder(@Path("id") id: Long): VapiResponse<Unit>

    /** Sao chép sang HĐ VAT nháp mới (giữ is_invoice_only). */
    @POST("v1/orders/{id}/copy")
    suspend fun copyOrder(@Path("id") id: Long): VapiResponse<OrderDto>

    /** Sao chép HĐ VAT → đơn bán thường nháp (is_invoice_only=false). */
    @POST("v1/orders/{id}/copy-to-normal")
    suspend fun copyOrderToNormal(@Path("id") id: Long): VapiResponse<OrderDto>

    /** Admin duyệt đơn: draft → confirmed. */
    @POST("v1/orders/{id}/confirm")
    suspend fun confirmOrder(@Path("id") id: Long): VapiResponse<OrderDto>

    /** Xoá HĐ nháp EI liên kết (strict_ei=1 → EI từ chối thì KHÔNG xoá gì). */
    @DELETE("v1/vat-output-invoices/{id}")
    suspend fun deleteVatOutputInvoice(
        @Path("id") id: Long,
        @Query("strict_ei") strictEi: Int = 1,
    ): VapiResponse<Unit>

    // ===== HĐ VAT đầu ra (tab VAT) =====
    @GET("v1/vat-output-invoices")
    suspend fun listVatOutputInvoices(
        @Query("per_page") perPage: Int = 200,
    ): VapiResponse<List<VatOutputInvoiceDto>>

    @GET("v1/vat-output-invoices/{id}")
    suspend fun getVatOutputInvoice(@Path("id") id: Long): VapiResponse<VatOutputInvoiceDto>

    /** Ảnh PNG HĐ đã render trên EI (binary) — xem trực tiếp HĐ trên EI. */
    @GET("v1/vat-output-invoices/{id}/preview-image")
    suspend fun vatOutputPreviewImage(@Path("id") id: Long): okhttp3.ResponseBody

    @POST("v1/vat-output-invoices/sync-easyinvoice")
    suspend fun syncEasyInvoice(@Body body: SyncEiReq = SyncEiReq()): VapiResponse<Unit>

    // ===== Form HĐ VAT (Phase 3B) — tạo/xem trước/ký =====
    /** Upload PO khách (multipart) → AI tạo đơn HĐ VAT nháp. Cần scope ai:write. */
    @Multipart
    @POST("v1/ai/po-draft")
    suspend fun poDraft(@Part file: MultipartBody.Part): VapiResponse<PoDraftResultDto>

    @POST("v1/orders/{id}/touch")
    suspend fun touchOrder(@Path("id") id: Long): VapiResponse<Unit>

    @retrofit2.http.PATCH("v1/orders/{id}/vat-include-phone")
    suspend fun setVatIncludePhone(@Path("id") id: Long, @Body body: VatIncludePhoneReq): VapiResponse<Unit>

    /** Render HĐ nháp → ảnh từng trang (data URL). Không cần EI draft. */
    @GET("v1/orders/{id}/vat-draft-images")
    suspend fun vatDraftImages(
        @Path("id") id: Long,
        @Query("price_type") priceType: String = "inclusive",
        @Query("vat_info_id") vatInfoId: Long? = null,
    ): VapiResponse<VatDraftImagesDto>

    @GET("v1/orders/{id}/vat-stock-check")
    suspend fun vatStockCheck(@Path("id") id: Long): VapiResponse<VatStockCheckDto>

    /** Tạo HĐ nháp trên EasyInvoice (chưa ký). */
    @POST("v1/orders/{id}/create-vat-draft")
    suspend fun createVatDraft(@Path("id") id: Long, @Body body: VatDraftReq): VapiResponse<VatOutputInvoiceDto>

    /** Ký phát hành HĐ (smart route: build+ký nếu chưa có nháp EI). */
    @POST("v1/orders/{id}/issue-vat")
    suspend fun issueVat(@Path("id") id: Long, @Body body: VatDraftReq): VapiResponse<VatOutputInvoiceDto>

    @POST("v1/orders/{id}/update-vat-draft")
    suspend fun updateVatDraft(@Path("id") id: Long, @Body body: VatDraftReq): VapiResponse<VatOutputInvoiceDto>

    /** Thêm đơn vị mua (MST) mới cho khách. */
    @POST("v1/customers/{id}/vat-info")
    suspend fun attachCustomerVatInfo(@Path("id") customerId: Long, @Body body: AttachVatInfoReq): VapiResponse<VatInfoDto>

    /** Tra cứu thông tin doanh nghiệp theo MST (VietQR proxy). */
    @GET("v1/lookup/tax-code/{mst}")
    suspend fun lookupTaxCode(@Path("mst") mst: String): VapiResponse<TaxLookupDto>

    // ===== Module Kế toán (Tab Khám phá → Kế toán) — Phase 1: Dòng tiền =====

    /** Danh sách GD tiền (mới nhất trước). Lọc Thu/Chi làm client-side (mirror web). */
    @GET("v1/money-transactions")
    suspend fun listMoneyTransactions(
        @Query("per_page") perPage: Int = 80,
    ): VapiResponse<List<MoneyTransactionDto>>

    /** Tạo GD tiền mặt (thu KH / GD pending tiền ra để trả phí). */
    @POST("v1/money-transactions")
    suspend fun createMoneyTransaction(@Body body: CreateMoneyTxRequest): VapiResponse<MoneyTransactionDto>

    /** Biến GD pending tiền ra thành chi phí (tạo Expense + duyệt + mark-paid). */
    @POST("v1/money-transactions/{id}/pay-expense")
    suspend fun payExpense(@Path("id") id: Long, @Body body: PayExpenseRequest): VapiResponse<MoneyTransactionDto>

    /** Danh mục chi phí (dropdown loại phí khi chi tiền mặt). */
    @GET("v1/expense-categories")
    suspend fun listExpenseCategories(
        @Query("per_page") perPage: Int = 200,
        @Query("is_active") isActive: Int = 1,
    ): VapiResponse<List<ExpenseCategoryDto>>

    // ===== Module Kế toán — Phase 2: Công nợ =====

    /** Tổng công nợ KH/NCC (đối tác có số dư ≠ 0). */
    @GET("v1/debts/overview")
    suspend fun debtOverview(@Query("party_type") partyType: String): VapiResponse<DebtOverviewDto>

    /** Sổ chi tiết công nợ 1 đối tác (không from/to → BE tự áp 15 ngày gần nhất). */
    @GET("v1/debts/statement")
    suspend fun debtStatement(
        @Query("party_type") partyType: String,
        @Query("party_id") partyId: Long,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): VapiResponse<DebtStatementDto>

    /** Nguồn công nợ CHƯA CHỐT + ứng ship chờ hoàn. */
    @GET("v1/debts/pending")
    suspend fun debtPending(
        @Query("party_type") partyType: String,
        @Query("party_id") partyId: Long,
    ): VapiResponse<DebtPendingDto>

    /** Kiểm tra nguồn lùi ngày trước khi chốt (cần lý do). */
    @GET("v1/debts/backdated-preview")
    suspend fun debtBackdatedPreview(
        @Query("party_type") partyType: String,
        @Query("party_id") partyId: Long,
    ): VapiResponse<DebtBackdatedPreviewDto>

    /** Chốt công nợ: post mọi nguồn pending → sổ cái. */
    @POST("v1/debts/post")
    suspend fun debtPost(@Body body: DebtPostRequest): VapiResponse<DebtPostResultDto>
}
