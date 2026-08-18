package tools.isekai.cameraclient

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for the two secrets the viewer holds: the Endpoint
 * private key and the Auth0 session/manual token. Android's equivalent of
 * iOS's Keychain (`KeychainStore.swift`) -- backed by a Keystore-held AES256
 * master key wrapping an AES256-GCM (values) / AES256-SIV (keys)
 * `EncryptedSharedPreferences` file, in place of the OS-level Keychain iOS
 * has.
 */
object SecureStore {
    private const val FILE_NAME = "secure_store"
    const val ENDPOINT_KEY_PEM = "endpoint-key-pem"

    /** The whole [Auth0Tokens] blob from a login, as JSON. */
    const val AUTH0_SESSION = "auth0-session"

    /** A token pasted by hand, the fallback for when Auth0 is unreachable. */
    const val AUTH0_MANUAL_TOKEN = "auth0-access-token"

    @Volatile
    private var prefsInstance: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefsInstance?.let { return it }
        synchronized(this) {
            prefsInstance?.let { return it }
            val masterKey =
                MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val created =
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            prefsInstance = created
            return created
        }
    }

    fun get(
        context: Context,
        key: String,
    ): String? = prefs(context).getString(key, null)

    fun set(
        context: Context,
        key: String,
        value: String,
    ) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun delete(
        context: Context,
        key: String,
    ) {
        prefs(context).edit().remove(key).apply()
    }
}
