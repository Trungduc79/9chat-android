package vn.chat9.app.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the last N messages per room in SharedPreferences so we can render
 * a MessagingStyle notification with multiple unread lines, like Zalo.
 */
object NotificationMessageCache {
    private const val PREFS = "notif_msg_cache"
    private const val MAX = 8

    data class Entry(val text: String, val time: Long, val senderName: String)

    @Synchronized
    fun add(context: Context, roomId: Int, text: String, senderName: String): List<Entry> {
        if (roomId <= 0) return listOf(Entry(text, System.currentTimeMillis(), senderName))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = load(prefs, roomId).toMutableList()
        list.add(Entry(text, System.currentTimeMillis(), senderName))
        val trimmed = if (list.size > MAX) list.takeLast(MAX) else list
        save(prefs, roomId, trimmed)
        return trimmed
    }

    @Synchronized
    fun clear(context: Context, roomId: Int) {
        if (roomId <= 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(roomId)).apply()
    }

    private fun key(roomId: Int) = "room_$roomId"

    private fun load(prefs: android.content.SharedPreferences, roomId: Int): List<Entry> {
        val json = prefs.getString(key(roomId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.getString("t"), o.getLong("ts"), o.optString("s", ""))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(prefs: android.content.SharedPreferences, roomId: Int, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("t", e.text)
            o.put("ts", e.time)
            o.put("s", e.senderName)
            arr.put(o)
        }
        prefs.edit().putString(key(roomId), arr.toString()).apply()
    }
}
