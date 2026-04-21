package vn.chat9.app.data.model

data class WallData(
    val user: WallUser,
    val is_self: Boolean = false,
    val is_friend: Boolean = false,
    // null | "accepted" | "pending_sent" | "pending_received"
    val friend_status: String? = null,
    // Populated when status is pending_sent or pending_received so the client
    // can call friends/respond.php without another round-trip.
    val friend_request_id: Int? = null,
    val friend_alias: String? = null,
    val stories: List<Story> = emptyList()
)

data class WallUser(
    val id: Int,
    val username: String,
    val avatar: String? = null,
    val cover_photo: String? = null,
    val bio: String? = null,
    val nine_id: String? = null,
    val friend_count: Int? = null
)
