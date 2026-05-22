package vn.chat9.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.chat9.app.data.api.ApiService
import vn.chat9.app.data.model.PermissionsData

/**
 * In-memory cache of the current user's permissions, kept in sync with
 * the backend's RBAC framework (Phase 2).
 *
 * Lifecycle:
 *   - `refresh()` after login / app launch (when token exists) → pulls
 *     `/users/permissions.php` and broadcasts via [state].
 *   - `clear()` on logout → resets to empty (default-DENY).
 *   - 5-minute TTL via [REFRESH_INTERVAL_MS]; callers can force a fresh
 *     fetch by passing `force=true` (e.g. right after accepting an invite
 *     so the new role takes effect immediately).
 *
 * UI consumers should `collectAsState()` the [state] flow and render based
 * on `has(code)` / `isAdmin` / `bypass_all`. Don't cache the snapshot in
 * a long-lived variable — it can change after invite-accept.
 */
class PermissionStore(private val api: ApiService) {

    private val _state = MutableStateFlow(PermissionsData())
    val state: StateFlow<PermissionsData> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastFetchMs: Long = 0

    /**
     * True nếu user có permission code đó (hoặc bypass_all).
     * Default-DENY: chưa fetch hoặc lỗi → false (chặn an toàn).
     */
    fun has(code: String): Boolean {
        val s = _state.value
        return s.bypass_all || code in s.permissions
    }

    /** True nếu user có ANY trong list. */
    fun hasAny(vararg codes: String): Boolean {
        val s = _state.value
        if (s.bypass_all) return true
        return codes.any { it in s.permissions }
    }

    /** True nếu role code (hoặc descendant) được gán. */
    fun hasRole(roleCode: String): Boolean = roleCode in _state.value.roles

    /** Là owner / system → bypass mọi check. */
    val isBypass: Boolean get() = _state.value.bypass_all

    /** Có quyền vào màn admin (gán role / mời nhân viên). */
    val canManageStaff: Boolean
        get() = hasAny("staff.assign_role", "staff.invite", "system.permission_manage")

    suspend fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFetchMs < REFRESH_INTERVAL_MS) return

        mutex.withLock {
            // Re-check inside lock — another coroutine có thể đã refresh.
            if (!force && System.currentTimeMillis() - lastFetchMs < REFRESH_INTERVAL_MS) return

            try {
                val resp = api.getMyPermissions()
                if (resp.success && resp.data != null) {
                    _state.value = resp.data
                    lastFetchMs = System.currentTimeMillis()
                }
                // Nếu thất bại — giữ state cũ, không clear (tránh cướp quyền tạm thời
                // do mạng yếu / 500). Default-DENY chỉ áp dụng khi state khởi tạo trống.
            } catch (_: Exception) {
                // ignore — state cũ giữ nguyên
            }
        }
    }

    fun clear() {
        _state.value = PermissionsData()
        lastFetchMs = 0
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 phút
    }
}
