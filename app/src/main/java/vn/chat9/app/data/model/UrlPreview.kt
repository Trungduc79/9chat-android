package vn.chat9.app.data.model

/** Response của GET /api/v1/url/preview.php */
data class UrlPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val site_name: String? = null,
    val favicon: String? = null,
)
