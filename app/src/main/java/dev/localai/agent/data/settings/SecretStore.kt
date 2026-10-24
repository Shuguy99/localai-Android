package dev.localai.agent.data.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hugging Face token at rest is encrypted with an Android Keystore key.
 * The token is only ever attached to requests when the user configured one.
 */
class SecretStore(context: Context) {

    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        // Keystore can be unavailable on some emulators; fail closed (no token).
        null
    }

    fun token(): String? =
        prefs?.getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setToken(value: String?) {
        val editor = prefs?.edit() ?: return
        if (value.isNullOrBlank()) editor.remove(KEY_HF_TOKEN) else editor.putString(KEY_HF_TOKEN, value.trim())
        editor.apply()
    }

    fun hasToken(): Boolean = token() != null

    private companion object {
        const val KEY_HF_TOKEN = "hf_token"
    }
}
