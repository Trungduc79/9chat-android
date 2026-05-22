package vn.chat9.app.util

import android.content.Context
import android.net.Uri

/**
 * URL Safety analyzer + trust list.
 *
 * Trả các "cờ đỏ" của 1 URL trước khi mở để user xác nhận. Trust list
 * lưu trong SharedPreferences theo hostname — lần sau click cùng host
 * skip dialog.
 */
object UrlSafety {

    private const val PREFS = "9chat_url_trust"
    private const val KEY = "trusted_domains"

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "goo.gl", "t.co", "t.me",
        "ow.ly", "is.gd", "buff.ly", "rb.gy", "rebrand.ly",
        "shorturl.at", "cutt.ly", "short.io",
    )
    private val IPV4_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    data class Flag(val icon: String, val label: String, val desc: String)

    data class Analysis(
        val host: String,
        val flags: List<Flag>,
        val isDanger: Boolean,
    )

    fun analyze(url: String): Analysis {
        val flags = mutableListOf<Flag>()
        var host = ""
        try {
            val uri = Uri.parse(url)
            host = uri.host?.lowercase() ?: ""
            val scheme = uri.scheme?.lowercase()

            if (scheme == "http") {
                flags += Flag("🔓", "Không mã hoá (HTTP)",
                    "Dữ liệu có thể bị nghe lén trên đường truyền.")
            }
            if (host in SHORTENERS) {
                flags += Flag("🔗", "URL rút gọn",
                    "Tên miền đích bị che. Click để mở mới biết được.")
            }
            if (IPV4_RE.matches(host) || host.startsWith("[")) {
                flags += Flag("🌐", "Địa chỉ IP",
                    "URL trỏ tới IP thay vì tên miền — thường gặp ở phishing.")
            }
            if (host.contains("xn--")) {
                flags += Flag("⚠️", "Tên miền mạo danh",
                    "Có ký tự đặc biệt giả tên thương hiệu (vd paypa1.com).")
            }
        } catch (_: Exception) {
            flags += Flag("❓", "URL không hợp lệ", "Không phân tích được.")
        }
        // Cờ đỏ "nguy hiểm" — IP-address hoặc punycode → nút mở thành đỏ
        val isDanger = flags.any { it.icon == "🌐" || it.icon == "⚠️" }
        return Analysis(host, flags, isDanger)
    }

    fun isTrusted(context: Context, host: String): Boolean {
        if (host.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        return host in set
    }

    fun addTrust(context: Context, host: String) {
        if (host.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        current += host
        prefs.edit().putStringSet(KEY, current).apply()
    }
}
