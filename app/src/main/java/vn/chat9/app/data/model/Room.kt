package vn.chat9.app.data.model

data class Room(
    val id: Int,
    val type: String,
    val name: String? = null,
    val avatar: String? = null,
    val last_message: Message? = null,
    val last_message_at: String? = null,
    val unread_count: Int? = null,
    val other_user: OtherUser? = null,
    /** Per-room role của current user trong room này: "admin" hoặc "member". */
    val role: String? = null,
    /** MySQL TINYINT(1) → JSON int 0/1, KHÔNG phải bool. So sánh == 1. */
    val muted: Int? = null,
    val member_count: Int? = null,
    /** Top 4 thành viên cho client tự ghép mosaic avatar. Server enrich
     *  cho group rooms (private rooms = null). */
    val member_previews: List<MemberPreview>? = null,
    /** MySQL TINYINT(1): 1 = name auto-sinh, 0 = admin đã đặt. */
    val name_is_default: Int? = null,
) {
    /** Helper: là admin của room này (chỉ áp dụng cho group). */
    val isAdmin: Boolean get() = role == "admin"
    val isGroup: Boolean get() = type == "group"
    val nameIsDefault: Boolean get() = name_is_default == 1
}

/** Lite preview cho mosaic — nhỏ hơn RoomMember, chỉ giữ avatar + username. */
data class MemberPreview(
    val id: Int,
    val username: String,
    val avatar: String? = null,
)

data class RoomMember(
    val id: Int,
    val username: String,
    val avatar: String? = null,
    val bio: String? = null,
    val role: String? = null,
    val joined_at: String? = null,
    val is_online: Boolean? = null,
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
