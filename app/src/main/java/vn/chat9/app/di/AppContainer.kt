package vn.chat9.app.di

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import vn.chat9.app.BuildConfig
import vn.chat9.app.data.api.ApiService
import vn.chat9.app.data.api.AuthInterceptor
import vn.chat9.app.data.local.TokenManager
import vn.chat9.app.data.repository.PermissionStore
import vn.chat9.app.data.socket.ChatSocket
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {

    val tokenManager = TokenManager(context)

    private val _sessionExpired = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sessionExpired: SharedFlow<Unit> = _sessionExpired

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenManager) {
            _sessionExpired.tryEmit(Unit)
        })
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: ApiService = retrofit.create(ApiService::class.java)

    val socket: ChatSocket = ChatSocket { tokenManager.accessToken ?: "" }

    /** Phase 2 RBAC store. Refresh sau login + force-refresh sau accept invite.
     *  Merge thêm vai trò nhân viên vapi khớp SĐT → mở module Bán hàng/Kho. */
    val permissions: PermissionStore = PermissionStore(
        api,
        // Phase 4: lấy QUYỀN thật (permissions) từ roles-by-phone → dùng trực tiếp,
        // không map cứng role→quyền. Đổi quyền trên admin là app áp dụng (qua realtime + reload).
        staffPermissionsByPhone = { phone ->
            runCatching { vapi.staffRolesByPhone(phone).data?.permissions ?: emptyList() }.getOrDefault(emptyList())
        },
        phoneProvider = { tokenManager.user?.phone },
    )

    /** Global cache map(user_id → alias). Single source of truth cho alias
     *  resolution trên toàn app. Refresh sau login + force-refresh sau khi
     *  user save alias / accept friend request. */
    val friendAliases: vn.chat9.app.data.repository.FriendAliasStore =
        vn.chat9.app.data.repository.FriendAliasStore(api)

    /** In-process cache cho URL preview metadata. Đọc/ghi từ main thread
     *  (Composable rendering) — không cần Mutex. Server cache Redis 24h
     *  rồi nên client cache trong session đủ tránh fetch lại. */
    val urlPreviewCache: MutableMap<String, vn.chat9.app.data.model.UrlPreview> =
        mutableMapOf()

    // ===== vapi (backend nghiệp vụ cho module quản trị) =====
    // LAZY: chỉ khởi tạo khi 1 module quản trị thực sự gọi → tài khoản thường
    // (không vào module nào) KHÔNG tốn tài nguyên dựng Retrofit/OkHttp cho vapi.
    val vapi: vn.chat9.app.data.vapi.VapiApiService by lazy {
        // Gửi SĐT staff đang đăng nhập → vapi enforce quyền theo staff (server-side thật).
        vn.chat9.app.data.vapi.VapiClient.create(phoneProvider = { tokenManager.user?.phone })
    }
    val warehouseRepo: vn.chat9.app.data.repository.WarehouseRepository by lazy {
        vn.chat9.app.data.repository.WarehouseRepository(vapi)
    }
}
