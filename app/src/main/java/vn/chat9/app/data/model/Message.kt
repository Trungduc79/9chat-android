package vn.chat9.app.data.model

data class Message(
    val id: Int,
    val room_id: Int,
    val user_id: Int,
    val type: String,
    val content: String? = null,
    val transcript: String? = null,
    val file_url: String? = null,
    /** Multi-image album: server gửi JSON string. Parse bằng helper
     *  `parsedImages` ở dưới để tránh parse trong Composable hot path. */
    val images: String? = null,
    val file_name: String? = null,
    val file_size: Long? = null,
    val reply_to: Int? = null,
    val created_at: String,
    val username: String? = null,
    val avatar: String? = null,
    val reply_message: Message? = null,
    val contact_user: User? = null,
    val reactions: ReactionData? = null
) {
    /** Parse images JSON string → list of {url, name?, size?, w?, h?}.
     *  Computed property (KHÔNG dùng `by lazy` — Gson tạo instance qua
     *  Unsafe, bỏ qua constructor → delegate field null → NPE).
     *  Caller cache local nếu hot path (vd `remember(message) { ... }`). */
    val parsedImages: List<MessageImage>
        get() {
            if (images.isNullOrBlank()) return emptyList()
            return try {
                val arr = org.json.JSONArray(images)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("url").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    MessageImage(
                        url = url,
                        name = o.optString("name").takeIf { it.isNotBlank() },
                        size = if (o.has("size")) o.optLong("size") else null,
                        w = if (o.has("w") && !o.isNull("w")) o.optInt("w") else null,
                        h = if (o.has("h") && !o.isNull("h")) o.optInt("h") else null,
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
}

data class MessageImage(
    val url: String,
    val name: String? = null,
    val size: Long? = null,
    val w: Int? = null,
    val h: Int? = null,
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
