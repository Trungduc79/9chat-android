package vn.chat9.app.data.vapi

/**
 * Shape response chuẩn của vapi: { success, data, message, code }.
 * Lỗi 4xx → Retrofit ném HttpException; parse errorBody thành VapiResponse để
 * lấy `code`/`message` thật (vd PHOTO_REQUIRED, TOTAL_DELIVERED_ZERO).
 */
data class VapiResponse<T>(
    val success: Boolean = false,
    val data: T? = null,
    val message: String? = null,
    val code: String? = null,
)
