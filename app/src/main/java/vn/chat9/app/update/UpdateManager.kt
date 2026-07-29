package vn.chat9.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import vn.chat9.app.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bản mô tả phiên bản mới lấy từ server (version.json).
 *
 * @property versionCode mã build tăng dần — so với [BuildConfig.VERSION_CODE].
 * @property versionName tên hiển thị (vd "1.0.42").
 * @property apkUrl URL tải file APK đã ký.
 * @property notes ghi chú thay đổi hiển thị trong hộp thoại.
 * @property mandatory true → không cho bỏ qua (bắt buộc cập nhật).
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val mandatory: Boolean,
)

/**
 * Cập nhật in-app tự-host (không qua Store). Luồng:
 * 1. Mở app → [fetchLatest] đọc `version.json` trên VPS 9chat.vn.
 * 2. Nếu [UpdateInfo.versionCode] > bản đang chạy → hiện hộp thoại (UpdateGate).
 * 3. User bấm cập nhật → [startInstall] tải APK bằng DownloadManager rồi bật
 *    màn hình cài đặt của hệ thống.
 *
 * Android chặn cài ngầm: người dùng vẫn phải chạm "Cài đặt" và cấp quyền
 * "Cài ứng dụng không rõ nguồn gốc" một lần cho app.
 */
object UpdateManager {

    private const val APK_NAME = "9chat-update.apk"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Đọc manifest cập nhật. Trả null nếu lỗi mạng / parse — im lặng, không chặn app. */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(BuildConfig.UPDATE_MANIFEST_URL).build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val body = res.body?.string() ?: return@use null
                val j = JSONObject(body)
                UpdateInfo(
                    versionCode = j.getInt("versionCode"),
                    versionName = j.optString("versionName", ""),
                    apkUrl = j.getString("apkUrl"),
                    notes = j.optString("notes", ""),
                    mandatory = j.optBoolean("mandatory", false),
                )
            }
        }.getOrNull()
    }

    /** Có bản mới hơn bản đang chạy không. */
    fun hasUpdate(info: UpdateInfo): Boolean = info.versionCode > BuildConfig.VERSION_CODE

    /**
     * Tải APK và bật màn hình cài đặt. Nếu app chưa được cấp quyền cài từ
     * nguồn không rõ (Android O+), mở thẳng màn hình cấp quyền rồi dừng —
     * user bật xong mở lại app, hộp thoại cập nhật hiện lại để thử tiếp.
     */
    fun startInstall(context: Context, info: UpdateInfo) {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appCtx.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                appCtx,
                "Hãy bật \"Cài ứng dụng không rõ nguồn gốc\" cho 9chat rồi mở lại app.",
                Toast.LENGTH_LONG,
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appCtx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appCtx.startActivity(intent) }
            return
        }

        // Xoá bản tải cũ (nếu có) để không cài nhầm APK cũ.
        val dir = appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        File(dir, APK_NAME).takeIf { it.exists() }?.delete()

        val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Cập nhật 9chat ${info.versionName}")
            .setDescription("Đang tải bản cập nhật…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(appCtx, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            .setMimeType("application/vnd.android.package-archive")
        val downloadId = dm.enqueue(request)
        Toast.makeText(appCtx, "Đang tải bản cập nhật…", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                appCtx.unregisterReceiver(this)
                val apk = File(appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
                if (!apk.exists()) {
                    Toast.makeText(appCtx, "Tải bản cập nhật thất bại.", Toast.LENGTH_SHORT).show()
                    return
                }
                launchInstall(appCtx, apk)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appCtx.registerReceiver(receiver, filter)
        }
    }

    private fun launchInstall(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
