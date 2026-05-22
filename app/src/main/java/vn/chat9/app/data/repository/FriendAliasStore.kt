package vn.chat9.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.chat9.app.data.api.ApiService

/**
 * Global cache map(user_id → alias) cho mọi friend của current user.
 * Source of truth duy nhất cho alias resolution trên toàn app.
 *
 * Lifecycle:
 *   - `refresh()` sau login + khi user accept/reject friend request +
 *     khi user save alias trong UserWallScreen → API /friends/list.php
 *     trả về list bạn bè kèm alias, store cache lại.
 *   - `clear()` on logout.
 *
 * UI consumers:
 *   - ChatScreen group chat: getDisplayName(userId, username) check
 *     `aliasFor(userId)` trước khi fallback username → alias hiển thị
 *     cho mọi message của bạn bè trong nhóm.
 *   - Bất kỳ Composable nào cần map user_id → alias mà KHÔNG có Friend
 *     object trong tay (ví dụ cho member trong group).
 *
 * Cache TTL ngắn (3 phút) — alias đổi không thường xuyên, đổi qua flow
 * UserWall đã tự refresh nên TTL chỉ là safety net.
 */
class FriendAliasStore(private val api: ApiService) {

    private val _aliasMap = MutableStateFlow<Map<Int, String>>(emptyMap())
    val state: StateFlow<Map<Int, String>> = _aliasMap.asStateFlow()

    private val mutex = Mutex()
    private var lastFetchMs: Long = 0

    fun aliasFor(userId: Int): String? = _aliasMap.value[userId]

    /** Tên hiển thị cho user_id: alias nếu có, fallback username truyền vào. */
    fun displayName(userId: Int, fallback: String?): String {
        return _aliasMap.value[userId] ?: (fallback ?: "")
    }

    suspend fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFetchMs < CACHE_TTL_MS) return
        mutex.withLock {
            if (!force && System.currentTimeMillis() - lastFetchMs < CACHE_TTL_MS) return
            try {
                val res = api.getFriends("friends")
                if (res.success && res.data != null) {
                    val newMap = res.data
                        .mapNotNull { f -> f.alias?.takeIf { it.isNotBlank() }?.let { f.id to it } }
                        .toMap()
                    _aliasMap.value = newMap
                    lastFetchMs = System.currentTimeMillis()
                }
            } catch (_: Exception) { /* giữ map cũ */ }
        }
    }

    fun clear() {
        _aliasMap.value = emptyMap()
        lastFetchMs = 0
    }

    /**
     * Update local cache mà không fetch API — dùng sau khi user save alias
     * trong UserWallScreen, để UI cập nhật ngay không phải đợi API round-trip.
     */
    fun setLocal(userId: Int, alias: String?) {
        _aliasMap.value = if (alias.isNullOrBlank()) {
            _aliasMap.value - userId
        } else {
            _aliasMap.value + (userId to alias)
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 3 * 60 * 1000L  // 3 phút
    }
}
