package vn.chat9.app.util

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object DateUtils {

    private val serverTz = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val utcTz = TimeZone.getTimeZone("UTC")

    private fun parseDate(dateStr: String): Date? {
        // ISO format with Z = UTC (from Node.js socket events)
        if (dateStr.endsWith("Z") || dateStr.contains("T")) {
            val utcPatterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss"
            )
            for (pattern in utcPatterns) {
                try {
                    val fmt = SimpleDateFormat(pattern, Locale.getDefault())
                    fmt.timeZone = if (dateStr.endsWith("Z")) utcTz else serverTz
                    return fmt.parse(dateStr)
                } catch (_: Exception) {}
            }
        }
        // Plain format from PHP API = server timezone
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            fmt.timeZone = serverTz
            return fmt.parse(dateStr)
        } catch (_: Exception) {}
        return null
    }

    // Room list time: Vừa xong, 5 phút, 14:02, Hôm qua, T2, 08/04
    fun formatTime(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        return try {
            val date = parseDate(dateStr) ?: return dateStr
            val now = Calendar.getInstance()
            val cal = Calendar.getInstance().apply { time = date }

            val diffSeconds = TimeUnit.MILLISECONDS.toSeconds(now.timeInMillis - cal.timeInMillis)
            val isToday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
            val isYesterday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) == 1
            val isDayBeforeYesterday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) == 2
            val diffDays = TimeUnit.MILLISECONDS.toDays(now.timeInMillis - cal.timeInMillis)

            when {
                diffSeconds < 60 -> "Vừa xong"
                isToday -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
                isYesterday -> "Hôm qua"
                isDayBeforeYesterday -> "Hôm kia"
                diffDays < 7L -> SimpleDateFormat("EEE", Locale("vi")).format(date)
                else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(date)
            }
        } catch (e: Exception) {
            dateStr
        }
    }

    // Bubble time: Vừa xong, 14:02, Hôm qua 14:02, 08/04 15:03
    fun formatMessageTime(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        return try {
            val date = parseDate(dateStr) ?: return dateStr
            val now = Calendar.getInstance()
            val cal = Calendar.getInstance().apply { time = date }
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)

            val diffSeconds = TimeUnit.MILLISECONDS.toSeconds(now.timeInMillis - cal.timeInMillis)

            val isToday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
            val isYesterday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) == 1
            val isDayBeforeYesterday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) == 2

            when {
                diffSeconds < 60 -> "Vừa xong"
                isToday -> timeStr
                isYesterday -> "Hôm qua $timeStr"
                isDayBeforeYesterday -> "Hôm kia $timeStr"
                else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(date) + " $timeStr"
            }
        } catch (e: Exception) {
            dateStr
        }
    }

    fun timeAgo(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        return try {
            val date = parseDate(dateStr) ?: return dateStr
            val diffMs = System.currentTimeMillis() - date.time
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
            val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
            when {
                minutes < 1 -> "Vừa xong"
                minutes < 60 -> "${minutes} phút trước"
                hours < 24 -> "${hours} giờ trước"
                else -> formatTime(dateStr)
            }
        } catch (_: Exception) { dateStr }
    }

    // Parse date string to millis (public for grouping logic)
    fun toMillis(dateStr: String): Long = parseDate(dateStr)?.time ?: 0L

    // Check if two date strings are on different calendar days
    fun isDifferentDay(dateStr1: String, dateStr2: String): Boolean {
        val d1 = parseDate(dateStr1) ?: return false
        val d2 = parseDate(dateStr2) ?: return false
        val c1 = Calendar.getInstance().apply { time = d1 }
        val c2 = Calendar.getInstance().apply { time = d2 }
        return c1.get(Calendar.YEAR) != c2.get(Calendar.YEAR) || c1.get(Calendar.DAY_OF_YEAR) != c2.get(Calendar.DAY_OF_YEAR)
    }

    // Format date separator: "16 tháng 04, 2026" or "Hôm nay", "Hôm qua"
    fun formatDateSeparator(dateStr: String): String {
        val date = parseDate(dateStr) ?: return ""
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply { time = date }
        val isToday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
        val isYesterday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) == 1
        return when {
            isToday -> "Hôm nay"
            isYesterday -> "Hôm qua"
            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) -> SimpleDateFormat("dd 'tháng' MM", Locale("vi")).format(date)
            else -> SimpleDateFormat("dd 'tháng' MM, yyyy", Locale("vi")).format(date)
        }
    }

    // Short time only: "HH:mm" — for timestamp in bubbles
    fun formatTimeShort(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        return try {
            val date = parseDate(dateStr) ?: return ""
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        } catch (_: Exception) { "" }
    }

    fun formatFullDate(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        return try {
            val date = parseDate(dateStr) ?: return dateStr
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            dateStr
        }
    }
}
