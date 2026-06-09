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
class PermissionStore(
    private val api: ApiService,
    // Phase 4: tra QUYỀN nhân viên vapi theo SĐT (code thật từ roles.permissions; rỗng nếu
    // không khớp/nghỉ việc). Dùng TRỰC TIẾP để mở module 9chat — đổi quyền trên admin là
    // app áp dụng (không map cứng, không build APK).
    private val staffPermissionsByPhone: suspend (String) -> List<String> = { emptyList() },
    private val phoneProvider: () -> String? = { null },
) {

    private val _state = MutableStateFlow(PermissionsData())
    val state: StateFlow<PermissionsData> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastFetchMs: Long = 0

    // Quyền vapi gán lần refresh TRƯỚC — để gỡ chính xác khi NV nghỉ/đổi vai trò (revoke),
    // không cộng dồn và không phụ thuộc danh sách cứng.
    private var lastVapiPerms: List<String> = emptyList()

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

            // 1. Base quyền 9chat. Fetch lỗi → giữ base hiện tại (tránh cướp quyền tạm
            //    thời do mạng yếu / 500). KHÔNG set _state ở đây để không phát trạng thái
            //    trung gian (thiếu quyền vapi) → tránh màn module bị đá nhầm lúc refresh.
            var base = _state.value
            try {
                val resp = api.getMyPermissions()
                if (resp.success && resp.data != null) {
                    base = resp.data
                    lastFetchMs = System.currentTimeMillis()
                }
            } catch (_: Exception) {
                // ignore — base cũ giữ nguyên
            }

            // 2. Phase 4: lấy QUYỀN vapi trực tiếp (code thật từ roles.permissions).
            //    Lỗi mạng → giữ quyền lần trước (tránh cướp quyền tạm thời); SĐT trống → rỗng.
            val phone = phoneProvider()
            val vapiPerms = if (!phone.isNullOrBlank()) {
                try { staffPermissionsByPhone(phone) } catch (_: Exception) { lastVapiPerms }
            } else {
                emptyList()
            }

            // 3. Gộp DỨT KHOÁT + EMIT MỘT LẦN: gỡ quyền vapi LẦN TRƯỚC rồi thêm quyền HIỆN TẠI
            //    → NV nghỉ/đổi vai trò (rỗng/đổi) thu hồi ngay, không cộng dồn.
            val baseWithoutVapi = base.permissions.filterNot { it in lastVapiPerms }
            lastVapiPerms = vapiPerms
            _state.value = base.copy(permissions = (baseWithoutVapi + vapiPerms).distinct())
        }
    }

    fun clear() {
        _state.value = PermissionsData()
        lastFetchMs = 0
        lastVapiPerms = emptyList()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 phút
        // (Phase 4: bỏ map cứng VAPI_ROLE_PERMS — quyền lấy thẳng từ roles-by-phone.)
    }
}
