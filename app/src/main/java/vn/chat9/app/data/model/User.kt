package vn.chat9.app.data.model

data class User(
    val id: Int,
    val username: String,
    val nine_id: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val cover_photo: String? = null,
    val bio: String? = null,
    val gender: String? = null,
    val type: String? = null,
    val type_rule: String? = null,
    val status: String? = null,
    val friend_count: Int? = null,
    val allow_stranger_msg: Int? = null,
    val allow_phone_search: Int? = null,
    val allow_friend_request: Int? = null,
    val qr_code: String? = null,
    val last_seen: String? = null,
    val created_at: String? = null
)
