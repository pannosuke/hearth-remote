package net.pcg.hearth.remote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import net.pcg.hearth.remote.protocol.JellyfinCredentialStore
import net.pcg.hearth.remote.protocol.JellyfinLogin
import java.util.UUID

/**
 * Jellyfin access token, in Keystore-backed prefs. The password is never written.
 */
internal class AndroidJellyfinStore(context: Context) : JellyfinCredentialStore {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { open() }

    override fun read(): JellyfinLogin? {
        val url = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val userId = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs.getString(KEY_USER_NAME, null).orEmpty()
        return JellyfinLogin(url, userId, name, token)
    }

    override fun write(login: JellyfinLogin) {
        prefs.edit()
            .putString(KEY_URL, login.serverUrl)
            .putString(KEY_USER_ID, login.userId)
            .putString(KEY_USER_NAME, login.userName)
            .putString(KEY_TOKEN, login.accessToken)
            .apply()
    }

    override fun clearLogin() {
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_TOKEN)
            .remove(KEY_SELECTED)
            .apply()
    }

    override fun serverUrl(): String = prefs.getString(KEY_URL, null).orEmpty()

    override fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE, created).apply()
        return created
    }

    override fun selectedDeviceId(): String? = prefs.getString(KEY_SELECTED, null)

    override fun setSelectedDeviceId(id: String?) {
        val editor = prefs.edit()
        if (id.isNullOrBlank()) editor.remove(KEY_SELECTED) else editor.putString(KEY_SELECTED, id)
        editor.apply()
    }

    private fun open(): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            FILE,
            masterKey,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private companion object {
        const val FILE = "hearth_jellyfin_secure"
        const val KEY_URL = "server_url"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_TOKEN = "access_token"
        const val KEY_DEVICE = "device_id"
        const val KEY_SELECTED = "selected_device_id"
    }
}
