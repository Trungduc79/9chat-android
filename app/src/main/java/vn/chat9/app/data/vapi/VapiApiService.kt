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
import vn.chat9.app.data.vapi.dto.StaffRolesDto
import vn.chat9.app.data.vapi.dto.StocktakeRequest
import vn.chat9.app.data.vapi.dto.StocktakeResultDto
import vn.chat9.app.data.vapi.dto.CreateOrderRequest
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
        @Query("status") status: String,
        @Query("warehouse_id") warehouseId: Long? = null,
        @Query("created_by_user_id") createdByUserId: Long? = null,  // app sale: chỉ đơn của NV
        @Query("type") type: String? = null,                          // sale|purchase|...
        @Query("per_page") perPage: Int = 100,
    ): VapiResponse<List<OrderDto>>

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
}
