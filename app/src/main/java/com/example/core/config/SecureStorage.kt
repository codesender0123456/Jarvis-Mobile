package com.example.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Handles encrypted storage for sensitive keys (e.g. Gemini API Key)
 * using AndroidX Security MasterKey and EncryptedSharedPreferences.
 *
 * This fails closed. If AndroidKeyStore is unavailable or the encrypted file cannot be read
 * (headless JVM tests, corrupted keystore, restored backup onto a new device) the unreadable
 * encrypted preferences are wiped and secrets are kept in process memory only. Nothing is ever
 * written back to disk unencrypted: silently downgrading to a plaintext preference file would
 * expose the API key to anything that can read app storage.
 */
class SecureStorage(private val context: Context) {

    private val prefs: SharedPreferences? by lazy { openEncryptedPreferences() }

    /** True when secrets are protected by AndroidKeyStore-backed encryption. */
    @Volatile
    var isEncryptedStorageAvailable: Boolean = true
        private set

    // In-memory-only fallbacks used when encryption is unavailable. Values live only as long as
    // the process, so a key entered during this session still works and is never persisted.
    @Volatile
    private var inMemoryApiKey: String? = null

    @Volatile
    private var inMemorySessionHandle: String? = null

    private fun openEncryptedPreferences(): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { isEncryptedStorageAvailable = true }
        } catch (e: Throwable) {
            isEncryptedStorageAvailable = false
            Log.e(
                "SecureStorage",
                "AndroidKeyStore unavailable or corrupted; failing closed and resetting unreadable secure prefs",
                e
            )
            // Delete the unreadable encrypted prefs outright. Opening the same path with
            // getSharedPreferences() to clear it instead would leave an empty plaintext-framed
            // XML file sitting at the secure path, which EncryptedSharedPreferences can never parse
            // again: the key would be permanently unpersistable, silently, on every future launch.
            try {
                context.deleteSharedPreferences(PREFS_FILE)
            } catch (ignored: Throwable) {
                Log.w("SecureStorage", "Could not reset unreadable secure prefs", ignored)
            }
            null
        }
    }

    /** True when secrets are protected by AndroidKeyStore-backed encryption. */
    fun isEncrypted(): Boolean = prefs != null

    fun setApiKey(apiKey: String) {
        val value = apiKey.trim()
        val encryptedPrefs = prefs
        if (encryptedPrefs == null) {
            // Memory only: still usable this session, never written to disk.
            inMemoryApiKey = value
            Log.w("SecureStorage", "Encrypted storage unavailable; API key kept in memory only")
            return
        }
        // commit() rather than apply(): a process death before an async write would lose the key.
        encryptedPrefs.edit().putString(KEY_API_KEY, value).commit()
    }

    fun getApiKey(): String {
        val encryptedPrefs = prefs ?: return inMemoryApiKey ?: ""
        return encryptedPrefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun hasApiKey(): Boolean = getApiKey().isNotEmpty()

    fun getSessionHandle(): String? {
        val encryptedPrefs = prefs ?: return inMemorySessionHandle
        return encryptedPrefs.getString(KEY_SESSION_HANDLE, null)
    }

    fun setSessionHandle(handle: String?) {
        val encryptedPrefs = prefs
        if (encryptedPrefs == null) {
            inMemorySessionHandle = handle
            return
        }
        // commit(): the handle is worthless after a process death if it was never written, and
        // reconnect logic reads it back expecting the last persisted value.
        encryptedPrefs.edit().apply {
            if (handle == null) remove(KEY_SESSION_HANDLE) else putString(KEY_SESSION_HANDLE, handle)
        }.commit()
    }

    fun clearSessionHandle() {
        val encryptedPrefs = prefs
        if (encryptedPrefs == null) {
            inMemorySessionHandle = null
            return
        }
        encryptedPrefs.edit().remove(KEY_SESSION_HANDLE).commit()
    }

    companion object {
        private const val PREFS_FILE = "jarvis_secure_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_SESSION_HANDLE = "gemini_live_session_handle"
    }
}