package vn.chat9.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.chat9.app.App
import vn.chat9.app.data.model.AdminUser
import vn.chat9.app.data.model.Invite
import vn.chat9.app.data.model.InviteCreated
import vn.chat9.app.data.model.RoleSummary

/**
 * Phase 2.4b — Admin screen với 2 tab: Nhân viên + Mời.
 *
 * Entry: từ AccountScreen, gated bằng `canManageStaff()` (chỉ user có
 * staff.assign_role / staff.invite / system.permission_manage thấy).
 *
 * Server endpoints sử dụng:
 *   GET  /admin/roles/list        — load danh sách role
 *   GET  /admin/users/list        — list users (có phân trang)
 *   POST /admin/users/assign-role — gán role
 *   POST /admin/users/revoke-role — thu hồi
 *   POST /admin/invites/create    — tạo invite mới
 *   GET  /admin/invites/list      — list invite active
 *   POST /admin/invites/revoke    — thu hồi invite
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var roles by remember { mutableStateOf<List<RoleSummary>>(emptyList()) }
    var snack by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val res = container.api.adminListRoles()
            if (res.success && res.data != null) roles = res.data
        } catch (_: Exception) {}
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Quản trị") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Nhân viên") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Mời") })
            }
            when (tab) {
                0 -> StaffTab(
                    roles = roles,
                    onSnack = { snack = it },
                )
                1 -> InvitesTab(
                    roles = roles,
                    onSnack = { snack = it },
                )
            }
        }

        snack?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2500)
                snack = null
            }
            Snackbar(
                modifier = Modifier.padding(16.dp),
            ) { Text(msg) }
        }
    }
}

// ============================================================================
// Tab 1 — Nhân viên (list users + assign/revoke role)
// ============================================================================

@Composable
private fun StaffTab(
    roles: List<RoleSummary>,
    onSnack: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<AdminUser?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            try {
                val res = container.api.adminListUsers(query = query.takeIf { it.isNotBlank() })
                if (res.success && res.data != null) users = res.data
            } catch (_: Exception) {}
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Tìm theo tên / SĐT / email") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { reload() }) { Icon(Icons.Default.Search, "Tìm") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(users, key = { it.id }) { u ->
                UserRow(user = u, onClick = { managing = u })
                HorizontalDivider()
            }
            if (users.isEmpty() && !loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Chưa có user nào", color = Color.Gray)
                    }
                }
            }
        }
    }

    managing?.let { user ->
        ManageUserRolesDialog(
            user = user,
            roles = roles,
            onDismiss = { managing = null },
            onAssign = { roleId ->
                scope.launch {
                    try {
                        val res = container.api.adminAssignRole(mapOf("user_id" to user.id, "role_id" to roleId))
                        if (res.success) {
                            onSnack("Đã gán role")
                            reload()
                            managing = null
                        } else {
                            onSnack(res.message ?: "Lỗi gán role")
                        }
                    } catch (e: Exception) {
                        onSnack("Lỗi: ${e.message}")
                    }
                }
            },
            onRevoke = { roleId ->
                scope.launch {
                    try {
                        val res = container.api.adminRevokeRole(mapOf("user_id" to user.id, "role_id" to roleId))
                        if (res.success) {
                            onSnack("Đã thu hồi role")
                            reload()
                            managing = null
                        } else {
                            onSnack(res.message ?: "Lỗi thu hồi role")
                        }
                    } catch (e: Exception) {
                        onSnack("Lỗi: ${e.message}")
                    }
                }
            }
        )
    }
}

@Composable
private fun UserRow(user: AdminUser, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(user.username, fontWeight = FontWeight.Medium)
            user.phone?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
            if (user.roles.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row {
                    user.roles.forEach { r ->
                        AssistChip(
                            onClick = onClick,
                            label = { Text(r.display_name, fontSize = 11.sp) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
            }
        }
        Icon(Icons.Default.ChevronRight, "manage", tint = Color.Gray)
    }
}

@Composable
private fun ManageUserRolesDialog(
    user: AdminUser,
    roles: List<RoleSummary>,
    onDismiss: () -> Unit,
    onAssign: (Int) -> Unit,
    onRevoke: (Int) -> Unit,
) {
    val currentRoleIds = user.roles.map { it.id }.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Role của ${user.username}") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(roles, key = { it.id }) { r ->
                    val granted = r.id in currentRoleIds
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.display_name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("${r.code} · ${r.member_count} thành viên", fontSize = 11.sp, color = Color.Gray)
                        }
                        if (granted) {
                            TextButton(onClick = { onRevoke(r.id) }) { Text("Thu hồi", color = Color.Red) }
                        } else {
                            FilledTonalButton(onClick = { onAssign(r.id) }) { Text("Gán") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}

// ============================================================================
// Tab 2 — Mời nhân viên (list invite + create + revoke)
// ============================================================================

@Composable
private fun InvitesTab(
    roles: List<RoleSummary>,
    onSnack: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var invites by remember { mutableStateOf<List<Invite>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var justCreated by remember { mutableStateOf<InviteCreated?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            try {
                val res = container.api.adminListInvites()
                if (res.success && res.data != null) invites = res.data
            } catch (_: Exception) {}
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Button(
            onClick = { creating = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Tạo link mời mới")
        }
        Spacer(Modifier.height(12.dp))
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(invites, key = { it.id }) { inv ->
                InviteRow(
                    invite = inv,
                    onRevoke = {
                        scope.launch {
                            try {
                                val res = container.api.adminRevokeInvite(mapOf("invite_id" to inv.id))
                                if (res.success) {
                                    onSnack("Đã thu hồi invite")
                                    reload()
                                } else onSnack(res.message ?: "Lỗi thu hồi")
                            } catch (e: Exception) {
                                onSnack("Lỗi: ${e.message}")
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
            if (invites.isEmpty() && !loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Chưa có link mời nào", color = Color.Gray)
                    }
                }
            }
        }
    }

    if (creating) {
        CreateInviteDialog(
            roles = roles.filter { it.code !in listOf("owner", "system") },
            onDismiss = { creating = false },
            onCreate = { roleId, hours, maxUses, note ->
                scope.launch {
                    try {
                        val body = mutableMapOf<String, Any?>(
                            "role_id" to roleId,
                            "expires_in_hours" to hours,
                            "max_uses" to maxUses,
                        )
                        if (!note.isNullOrBlank()) body["note"] = note
                        val res = container.api.adminCreateInvite(body)
                        if (res.success && res.data != null) {
                            justCreated = res.data
                            creating = false
                            reload()
                        } else onSnack(res.message ?: "Lỗi tạo invite")
                    } catch (e: Exception) {
                        onSnack("Lỗi: ${e.message}")
                    }
                }
            }
        )
    }

    justCreated?.let { jc ->
        InviteCreatedDialog(invite = jc, onDismiss = { justCreated = null })
    }
}

@Composable
private fun InviteRow(invite: Invite, onRevoke: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(invite.role?.display_name ?: "?", fontWeight = FontWeight.Medium)
                invite.note?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
                Text(
                    "Hết hạn ${invite.expires_at} · " +
                        "${invite.uses_count}/${if (invite.max_uses == 0) "∞" else invite.max_uses.toString()}",
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
            }
            TextButton(onClick = onRevoke) { Text("Thu hồi", color = Color.Red) }
        }
    }
}

@Composable
private fun CreateInviteDialog(
    roles: List<RoleSummary>,
    onDismiss: () -> Unit,
    onCreate: (roleId: Int, hours: Int, maxUses: Int, note: String?) -> Unit,
) {
    var selectedRole by remember { mutableStateOf(roles.firstOrNull()) }
    var hours by remember { mutableStateOf("72") }
    var maxUses by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    var rolePickerOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tạo link mời") },
        text = {
            Column {
                OutlinedTextField(
                    value = selectedRole?.display_name ?: "Chọn role",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Role") },
                    trailingIcon = {
                        IconButton(onClick = { rolePickerOpen = true }) {
                            Icon(Icons.Default.ArrowDropDown, "pick")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clickable { rolePickerOpen = true }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hours,
                    onValueChange = { hours = it.filter { c -> c.isDigit() } },
                    label = { Text("Hết hạn sau (giờ)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = maxUses,
                    onValueChange = { maxUses = it.filter { c -> c.isDigit() } },
                    label = { Text("Số lượt dùng (0 = không giới hạn)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Ghi chú (tuỳ chọn)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val r = selectedRole ?: return@TextButton
                    val h = hours.toIntOrNull() ?: 72
                    val m = maxUses.toIntOrNull() ?: 1
                    onCreate(r.id, h, m, note.takeIf { it.isNotBlank() })
                },
                enabled = selectedRole != null && hours.isNotBlank()
            ) { Text("Tạo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )

    if (rolePickerOpen) {
        AlertDialog(
            onDismissRequest = { rolePickerOpen = false },
            title = { Text("Chọn role") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(roles, key = { it.id }) { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedRole = r
                                rolePickerOpen = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(r.display_name, fontWeight = FontWeight.Medium)
                                Text(r.code, fontSize = 11.sp, color = Color.Gray)
                            }
                            if (selectedRole?.id == r.id) Icon(Icons.Default.Check, null, tint = Color(0xFF3E1F91))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { rolePickerOpen = false }) { Text("Đóng") } }
        )
    }
}

@Composable
private fun InviteCreatedDialog(invite: InviteCreated, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đã tạo link mời") },
        text = {
            Column {
                Text("Role: ${invite.role.display_name}", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text("Token chỉ hiển thị 1 lần — copy ngay rồi gửi cho nhân viên qua Zalo / SMS.",
                    fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(invite.url, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("invite", invite.url))
                android.widget.Toast.makeText(context, "Đã copy link", android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("Copy & đóng") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}
