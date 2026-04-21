package vn.chat9.app.data.model

data class Friend(
    val id: Int,
    val username: String,
    val avatar: String? = null,
    val alias: String? = null,
    val bio: String? = null,
    val last_seen: String? = null,
    val friends_since: String? = null,
    // For pending requests
    val request_id: Int? = null,
    val message: String? = null,
    val requested_at: String? = null
)
