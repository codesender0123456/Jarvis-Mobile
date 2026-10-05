package com.example.core.gemini

import com.example.actions.ActionRegistry
import com.example.actions.SafetyGuard
import com.example.actions.UndoStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.util.Base64

/**
 * Asserts the Live API wire format directly.
 *
 * The bugs these cover were all invisible at runtime: the server accepts a malformed setup and
 * simply never responds, so nothing short of inspecting the JSON catches them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeminiLiveProtocolFormatTest {

    private val registry = ActionRegistry(UndoStack(), SafetyGuard())

    private fun setup(handle: String? = null) =
        buildSetupMessage("You are JARVIS", "Puck", "gemini-3.1-flash-live-preview", handle, registry)
            .getJSONObject("setup")

    @Test
    fun `setup sends sessionResumption as an object`() {
        val resumption = setup().getJSONObject("sessionResumption")
        assertNotNull(resumption)
        // The old code sent a bare "sessionResumptionHandle" string, which the API ignores.
        assertFalse(setup().has("sessionResumptionHandle"))
    }

    @Test
    fun `setup includes the stored handle when resuming`() {
        assertEquals("handle-abc", setup("handle-abc").getJSONObject("sessionResumption").getString("handle"))
    }

    @Test
    fun `setup sends an empty resumption object when there is no handle`() {
        val resumption = setup(null).getJSONObject("sessionResumption")
        assertEquals(0, resumption.length())
    }

    @Test
    fun `setup enables sliding window compression`() {
        val compression = setup().getJSONObject("contextWindowCompression")
        assertNotNull(compression.getJSONObject("slidingWindow"))
    }

    @Test
    fun `setup requests sliding window so long sessions survive`() {
        // Asserted explicitly because a long conversation silently stops accepting turns without it.
        assertTrue(setup().has("contextWindowCompression"))
    }

    @Test
    fun `setup prefixes the model with models slash`() {
        assertEquals("models/gemini-3.1-flash-live-preview", setup().getString("model"))
    }

    @Test
    fun `setup requests audio output with the selected voice`() {
        val genConfig = setup().getJSONObject("generationConfig")
        assertEquals("AUDIO", genConfig.getJSONArray("responseModalities").getString(0))
        assertEquals(
            "Puck",
            genConfig.getJSONObject("speechConfig")
                .getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig")
                .getString("voiceName")
        )
    }

    @Test
    fun `setup enables both transcription streams`() {
        assertNotNull(setup().getJSONObject("inputAudioTranscription"))
        assertNotNull(setup().getJSONObject("outputAudioTranscription"))
    }

    @Test
    fun `audio input uses realtimeInput audio not mediaChunks`() {
        val pcm = ByteArray(320) { it.toByte() }
        val realtimeInput = buildRealtimeAudioMessage(pcm).getJSONObject("realtimeInput")

        assertNotNull(realtimeInput.getJSONObject("audio"))
        // mediaChunks was removed from the Live API; sending it produced a dead session.
        assertFalse(realtimeInput.has("mediaChunks"))

        val audio = realtimeInput.getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertEquals(
            pcm.toList(),
            Base64.decode(audio.getString("data"), Base64.NO_WRAP).toList()
        )
        assertFalse(realtimeInput.getBoolean("turnComplete"))
    }

    @Test
    fun `text input uses realtimeInput text`() {
        val realtimeInput = buildRealtimeTextMessage("what time is it").getJSONObject("realtimeInput")
        assertEquals("what time is it", realtimeInput.getString("text"))
        assertTrue(realtimeInput.getBoolean("turnComplete"))
        assertFalse(realtimeInput.has("turns"))
    }

    @Test
    fun `api key errors are fatal`() {
        assertEquals(
            LiveFailure.INVALID_API_KEY,
            classifyFailure("API key not valid. Please pass a valid API key.", null)
        )
    }

    @Test
    fun `quota errors are classified as quota`() {
        assertEquals(LiveFailure.QUOTA, classifyFailure("Resource has been exhausted", 429))
    }

    @Test
    fun `model access errors are classified as model unavailable`() {
        assertEquals(
            LiveFailure.MODEL_UNAVAILABLE,
            classifyFailure("models/x is not found for api version v1beta", 404)
        )
    }

    @Test
    fun `socket errors stay transient so the ladder does not step`() {
        assertEquals(LiveFailure.TRANSIENT, classifyFailure("connection reset by peer", null))
    }

    @Test
    fun `a quota error mentioning the api key is still quota`() {
        // "Quota exceeded for API key ..." must not be read as a fatal key error, or a
        // recoverable rate limit permanently stops the session.
        assertEquals(
            LiveFailure.QUOTA,
            classifyFailure("Quota exceeded for API key abcd1234", 429)
        )
    }

    @Test
    fun `a bare 403 is treated as lost model access`() {
        // 403 is ambiguous: only an explicit key/permission message makes it fatal.
        assertEquals(LiveFailure.MODEL_UNAVAILABLE, classifyFailure("forbidden", 403))
    }

    @Test
    fun `permission denied text is treated as an invalid key`() {
        assertEquals(
            LiveFailure.INVALID_API_KEY,
            classifyFailure("PERMISSION_DENIED: API key not valid", null)
        )
    }

    @Test
    fun `401 is an invalid key`() {
        assertEquals(LiveFailure.INVALID_API_KEY, classifyFailure("unauthorized", 401))
    }

    @Test
    fun `a missing model is attributed to the model rung`() {
        assertEquals(
            LiveFailure.MODEL_UNAVAILABLE,
            classifyFailure("models/gemini-x is not found for API version v1beta", 404)
        )
    }
}
