package vn.chat9.app.data.model

data class Message(
    val id: Int,
    val room_id: Int,
    val user_id: Int,
    val type: String,
    val content: String? = null,
    val transcript: String? = null,
    val file_url: String? = null,
    val file_name: String? = null,
    val file_size: Long? = null,
    val reply_to: Int? = null,
    val created_at: String,
    val username: String? = null,
    val avatar: String? = null,
    val reply_message: Message? = null,
    val contact_user: User? = null,
    val reactions: ReactionData? = null
)

data class ReactionRequest(val message_id: Int, val type: String)
data class ReactionRemoveRequest(val message_id: Int)

data class ReactionData(
    val summary: Map<String, Int>? = null,
    val my_reactions: Map<String, Int>? = null,
    val my_last_reaction: String? = null
)

data class ReactionDetailUser(
    val user_id: Int,
    val username: String,
    val avatar: String? = null,
    val reactions: Map<String, Int> = emptyMap(),
    val total_count: Int = 0,
    val is_me: Boolean = false
)

data class ReactionDetailResponse(
    val summary: Map<String, Int>? = null,
    val users: List<ReactionDetailUser> = emptyList()
)

data class MessageHistory(
    val messages: List<Message>,
    val has_more: Boolean
)

data class MessageSearchResult(
    val id: Int,
    val room_id: Int,
    val user_id: Int,
    val type: String,
    val content: String? = null,
    val created_at: String,
    val username: String? = null,
    val avatar: String? = null,
    val room_type: String? = null,
    val other_user_id: Int? = null,
    val other_username: String? = null,
    val other_avatar: String? = null,
    val other_alias: String? = null
) {
    val roomDisplayName: String get() = other_alias ?: other_username ?: "Chat"
}

data class FileData(
    val file_id: Int,
    val file_url: String,
    val file_name: String,
    val file_type: String,
    val file_size: Long
)

data class TranscribeRequest(val message_id: Int, val language: String = "vi-VN")
data class TranscribeResponse(val message_id: Int, val transcript: String)
