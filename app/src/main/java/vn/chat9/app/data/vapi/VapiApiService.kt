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
import vn.chat9.app.data.vapi.dto.FulfillRequest
import vn.chat9.app.data.vapi.dto.FulfillResult
import vn.chat9.app.data.vapi.dto.OrderDto

/**
 * Retrofit cho vapi (gateway nghiệp vụ — KHÁC backend 9chat). Auth = X-API-Key
 * (định danh app "9chat Android"); danh tính user 9chat truyền trong payload.
 * Mọi module quản trị dùng chung interface này (thêm endpoint khi có module mới).
 */
interface VapiApiService {

    @GET("v1/orders")
    suspend fun listOrders(
        @Query("status") status: String,
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
}
