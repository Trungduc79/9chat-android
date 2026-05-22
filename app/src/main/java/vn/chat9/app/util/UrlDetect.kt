package vn.chat9.app.util

/**
 * URL classification helpers cho message type detection.
 *
 * `isExternalUrlOnly` — trả true nếu chuỗi là 1 URL http/https duy nhất
 * và KHÔNG phải 9chat deep link (9chat:// scheme hoặc *.9chat.vn host).
 * Dùng khi user gõ tin để quyết định gửi với type='url' (render preview
 * card) hay type='text' (DeepLinkRouter tự intercept khi tap).
 */
object UrlDetect {

    private val URL_ONLY_REGEX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    fun isExternalUrlOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (!URL_ONLY_REGEX.matches(trimmed)) return false
        val host = try {
            java.net.URI(trimmed).host?.lowercase() ?: return false
        } catch (_: Exception) { return false }
        if (host == "9chat.vn" || host == "www.9chat.vn") return false
        return true
    }
}
