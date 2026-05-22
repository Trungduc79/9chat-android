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
) {
    /** Tên hiển thị: alias nếu đã đặt, fallback username. Dùng cho mọi
     *  vị trí hiển thị bạn bè (room list, danh bạ, contact picker, wall...).
     *  Quy tắc: bạn bè ưu tiên alias, chỉ rơi về username khi alias trống. */
    val displayName: String get() = alias?.takeIf { it.isNotBlank() } ?: username
}
