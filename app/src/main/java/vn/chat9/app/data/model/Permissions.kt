package vn.chat9.app.data.model

/** Response của GET /users/permissions.php */
data class PermissionsData(
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val bypass_all: Boolean = false,
    val bypass_friend: Boolean = false,
)

/** Role admin entry — GET /admin/roles/list.php trả mảng. */
data class RoleSummary(
    val id: Int,
    val code: String,
    val display_name: String,
    val description: String? = null,
    val parent_id: Int? = null,
    val is_assignable: Boolean = true,
    val bypass_all: Boolean = false,
    val bypass_friend: Boolean = false,
    val member_count: Int = 0,
)

/** User entry trong admin/users/list.php — kèm roles hiện có. */
data class AdminUser(
    val id: Int,
    val username: String,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val roles: List<RoleRef> = emptyList(),
)

data class RoleRef(
    val id: Int,
    val code: String,
    val display_name: String,
)

/** Invite (admin/invites/list.php — không có raw token, chỉ token_preview). */
data class Invite(
    val id: Int,
    val role_id: Int,
    val role: RoleRef? = null,
    val inviter: InviterRef? = null,
    val expires_at: String,
    val max_uses: Int,
    val uses_count: Int,
    val note: String? = null,
    val token_preview: String? = null,
    val created_at: String? = null,
)

data class InviterRef(
    val id: Int,
    val username: String,
)

/** Response khi tạo invite — raw `token` trả 1 lần duy nhất. */
data class InviteCreated(
    val id: Int,
    val token: String,
    val url: String,
    val expires_at: String,
    val max_uses: Int,
    val note: String? = null,
    val role: RoleRef,
)

/** Response GET /invites/check.php (public). */
data class InviteCheck(
    val valid: Boolean,
    val reason: String? = null,
    val role: RolePreview? = null,
    val inviter: InviterRef? = null,
    val expires_at: String? = null,
    val uses_remaining: Int? = null,
)

data class RolePreview(
    val code: String,
    val display_name: String,
    val description: String? = null,
)

/** Response POST /invites/accept.php. */
data class InviteAccepted(
    val role_granted: RoleRef,
    val already_had: Boolean,
    val permissions: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
)
