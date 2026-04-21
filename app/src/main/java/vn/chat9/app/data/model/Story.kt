package vn.chat9.app.data.model

data class Story(
    val id: Int,
    val user_id: Int,
    val content: String? = null,
    val image_url: String? = null,
    val views_count: Int? = null,
    val viewed: Int? = null,
    val username: String? = null,
    val avatar: String? = null,
    val created_at: String? = null,
    val expires_at: String? = null
)
