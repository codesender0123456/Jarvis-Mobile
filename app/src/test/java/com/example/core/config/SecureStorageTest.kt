package com.example.core.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SecureStorage must fail closed: if AndroidKeyStore cannot be used, secrets stay in memory and
 * nothing is written to disk in plaintext.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecureStorageTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `api key round trips`() {
        val storage = SecureStorage(context)
        storage.setApiKey("  test-key-123  ")

        assertEquals("test-key-123", storage.getApiKey())
        assertTrue(storage.hasApiKey())
    }

    @Test
    fun `empty api key reports not configured`() {
        val storage = SecureStorage(context)
        storage.setApiKey("")

        assertFalse(storage.hasApiKey())
        assertEquals("", storage.getApiKey())
    }

    @Test
    fun `session handle round trips and clears`() {
        val storage = SecureStorage(context)
        storage.setSessionHandle("handle-1")
        assertEquals("handle-1", storage.getSessionHandle())

        storage.clearSessionHandle()
        assertNull(storage.getSessionHandle())
    }

    @Test
    fun `null session handle removes the stored value`() {
        val storage = SecureStorage(context)
        storage.setSessionHandle("handle-1")
        storage.setSessionHandle(null)

        assertNull(storage.getSessionHandle())
    }

    @Test
    fun `secrets never touch a plaintext fallback file`() {
        // The old implementation returned a real, unencrypted SharedPreferences under
        // "jarvis_secure_prefs.temp" whenever the keystore failed, silently downgrading storage.
        // This asserts the file is not merely empty but absent: opening it to inspect it, as an
        // earlier version of this test did, would create it and make the assertion vacuous.
        val storage = SecureStorage(context)
        storage.setApiKey("secret-value")

        val fallbackFile = File(
            context.applicationInfo.dataDir,
            "shared_prefs/jarvis_secure_prefs.temp.xml"
        )
        assertFalse(
            "API key must never land in a plaintext preferences file",
            fallbackFile.exists()
        )
    }

    @Test
    fun `no on-disk file contains the secret in plaintext`() {
        val secret = "secret-value-2"
        val storage = SecureStorage(context)
        storage.setApiKey(secret)

        // Robolectric does not implement AndroidKeyStore, so this runs in the fail-closed,
        // memory-only mode. That is the interesting case: it must not degrade to disk.
        if (!storage.isEncrypted()) {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            val offenders = prefsDir.listFiles()
                ?.filter { it.readText().contains(secret) }
                .orEmpty()
            assertTrue(
                "Found the API key written in plaintext: ${offenders.map { it.name }}",
                offenders.isEmpty()
            )
        }
    }

    @Test
    fun `round trip works in whichever storage mode is available`() {
        // Deliberately does not assert isEncrypted() == true: Robolectric has no AndroidKeyStore,
        // so the fail-closed memory-only path is the one actually exercised here. On a real device
        // the encrypted path must behave identically from the caller's point of view.
        val storage = SecureStorage(context)
        storage.setApiKey("round-trip-key")

        assertEquals("round-trip-key", storage.getApiKey())
        assertTrue(storage.hasApiKey())
    }
}
