package vn.chat9.app.data.model

data class Room(
    val id: Int,
    val type: String,
    val name: String? = null,
    val last_message: Message? = null,
    val last_message_at: String? = null,
    val unread_count: Int? = null,
    val other_user: OtherUser? = null
)

data class OtherUser(
    val id: Int,
    val username: String,
    val avatar: String? = null,
    val is_online: Boolean? = null,
    val alias: String? = null
) {
    val displayName: String get() = alias ?: username
}
