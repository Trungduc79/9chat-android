package vn.chat9.app.util

import vn.chat9.app.BuildConfig

object UrlUtils {
    private val BASE_URL = BuildConfig.SOCKET_URL

    fun toFullUrl(path: String?): String? {
        if (path == null) return null
        if (path.startsWith("http")) return path
        return "$BASE_URL$path"
    }
}
