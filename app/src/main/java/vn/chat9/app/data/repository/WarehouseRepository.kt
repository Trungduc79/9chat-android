package vn.chat9.app.data.repository

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import vn.chat9.app.data.vapi.VapiApiService
import vn.chat9.app.data.vapi.dto.AttachmentDto
import vn.chat9.app.data.vapi.dto.CasherDto
import vn.chat9.app.data.vapi.dto.FulfillRequest
import vn.chat9.app.data.vapi.dto.FulfillResult
import vn.chat9.app.data.vapi.dto.OrderDto
import vn.chat9.app.data.vapi.dto.WarehouseDto

/**
 * Repository module Vận hành kho — gọi vapi. Không giữ state (stateless) →
 * ViewModel của module tự quản state + scope, rời module là sạch.
 */
class WarehouseRepository(private val api: VapiApiService) {

    suspend fun listOrders(status: String, warehouseId: Long? = null): List<OrderDto> =
        api.listOrders(status, warehouseId).data ?: emptyList()

    suspend fun getOrder(id: Long): OrderDto? = api.getOrder(id).data

    suspend fun fulfill(id: Long, req: FulfillRequest): FulfillResult? = api.fulfill(id, req).data

    suspend fun photos(orderId: Long): List<AttachmentDto> =
        api.listAttachments(id = orderId).data ?: emptyList()

    suspend fun allPhotos(): List<AttachmentDto> = api.listAttachments().data ?: emptyList()

    suspend fun uploadPhoto(orderId: Long, bytes: ByteArray): AttachmentDto? {
        fun text(s: String) = s.toRequestBody("text/plain".toMediaType())
        val part = MultipartBody.Part.createFormData(
            "file", "photo.jpg", bytes.toRequestBody("image/*".toMediaType()),
        )
        return api.uploadAttachment(text("order"), text(orderId.toString()), text("photo"), part).data
    }

    suspend fun deletePhoto(id: Long) {
        api.deleteAttachment(id)
    }

    /** Quỹ thu/chi active — dropdown chọn quỹ COD ở màn fulfill. */
    suspend fun listCashers(): List<CasherDto> = api.listCashers().data ?: emptyList()

    /** Kho active — màn chọn kho làm việc lúc bắt đầu module. */
    suspend fun listWarehouses(): List<WarehouseDto> = api.listWarehouses().data ?: emptyList()
}
