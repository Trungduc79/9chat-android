package vn.chat9.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import vn.chat9.app.data.model.User

class TokenManager(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    private val gson = Gson()

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    var user: User?
        get() {
            val json = prefs.getString("user", null) ?: return null
            return gson.fromJson(json, User::class.java)
        }
        set(value) = prefs.edit().putString("user", value?.let { gson.toJson(it) }).apply()

    val isLoggedIn: Boolean get() = accessToken != null

    fun saveAuth(accessToken: String, refreshToken: String, user: User) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.user = user
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "9chat_secure_prefs"

        /**
         * Tink's master key in AndroidKeystore can get out of sync with the
         * encrypted prefs file after uninstall/reinstall or backup restore,
         * throwing AEADBadTagException at EncryptedSharedPreferences.create().
         * Recover by wiping both and recreating — user just has to log in again.
         */
        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                build(context)
            } catch (e: Exception) {
                Log.w(TAG, "Encrypted prefs decrypt failed — wiping and recreating", e)
                // Wipe the stale prefs file
                context.deleteSharedPreferences(PREFS_NAME)
                // Also remove the Tink master key so a fresh one is created
                try {
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    if (ks.containsAlias("_androidx_security_master_key_")) {
                        ks.deleteEntry("_androidx_security_master_key_")
                    }
                } catch (_: Exception) {}
                build(context)
            }
        }

        private fun build(context: Context): SharedPreferences {
            return EncryptedSharedPreferences.create(
                PREFS_NAME,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
