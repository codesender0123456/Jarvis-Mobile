package com.example.core.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveModelLadderTest {

    private var clock = 1_000_000L
    private val models = listOf("model-primary", "model-fallback")
    private fun ladder() = LiveModelLadder(models) { clock }

    @Test
    fun `starts on the preferred model`() {
        assertEquals("model-primary", ladder().currentModel())
    }

    @Test
    fun `quota steps down and puts the primary in a five minute cooldown`() {
        val ladder = ladder()

        assertEquals(LadderAction.STEP_DOWN, ladder.noteFailure("model-primary", LiveFailure.QUOTA))
        assertEquals("model-fallback", ladder.currentModel())
        assertEquals(
            LiveModelLadder.QUOTA_COOLDOWN_MS,
            ladder.cooldownRemainingMs("model-primary")
        )
    }

    @Test
    fun `quota cooldown expires and the ladder recovers on its own`() {
        val ladder = ladder()
        ladder.noteFailure("model-primary", LiveFailure.QUOTA)
        assertEquals("model-fallback", ladder.currentModel())

        clock += LiveModelLadder.QUOTA_COOLDOWN_MS + 1

        assertEquals("model-primary", ladder.currentModel())
        assertFalse(ladder.isInCooldown("model-primary"))
    }

    @Test
    fun `lost model access uses a six hour cooldown`() {
        val ladder = ladder()

        assertEquals(
            LadderAction.STEP_DOWN,
            ladder.noteFailure("model-primary", LiveFailure.MODEL_UNAVAILABLE)
        )
        assertEquals(
            LiveModelLadder.MODEL_ACCESS_COOLDOWN_MS,
            ladder.cooldownRemainingMs("model-primary")
        )

        clock += LiveModelLadder.QUOTA_COOLDOWN_MS + 1

        // Still not the primary: five minutes must not unblock a six hour block.
        assertEquals("model-fallback", ladder.currentModel())
    }

    @Test
    fun `transient failure never steps the ladder`() {
        val ladder = ladder()

        repeat(5) {
            assertEquals(
                LadderAction.RETRY_SAME_MODEL,
                ladder.noteFailure("model-primary", LiveFailure.TRANSIENT)
            )
        }

        assertEquals("model-primary", ladder.currentModel())
        assertEquals(emptyList<String>(), models.filter { ladder.isInCooldown(it) })
    }

    @Test
    fun `invalid api key is fatal and stops the ladder`() {
        val ladder = ladder()

        assertEquals(
            LadderAction.STOP,
            ladder.noteFailure("model-primary", LiveFailure.INVALID_API_KEY)
        )
        assertTrue(ladder.isFatal)

        // Fatal is sticky: further failures cannot revive the session.
        assertEquals(LadderAction.STOP, ladder.noteFailure("model-fallback", LiveFailure.QUOTA))
        assertTrue(ladder.isFatal)
    }

    @Test
    fun `ladder waits instead of failing when every model is in cooldown`() {
        val ladder = ladder()
        ladder.noteFailure("model-primary", LiveFailure.QUOTA)
        ladder.noteFailure("model-fallback", LiveFailure.QUOTA)

        assertEquals(LadderAction.WAIT, ladder.noteFailure("model-fallback", LiveFailure.QUOTA))
        assertTrue(ladder.availableModels().isEmpty())
        assertTrue(ladder.nextRetryDelayMs() > 0L)
    }

    @Test
    fun `reset clears fatal but keeps cooldowns`() {
        val ladder = ladder()
        ladder.noteFailure("model-primary", LiveFailure.INVALID_API_KEY)
        ladder.noteFailure("model-primary", LiveFailure.QUOTA)

        ladder.reset()

        assertFalse(ladder.isFatal)
        assertTrue(ladder.isInCooldown("model-primary"))
    }

    @Test
    fun `clearAll drops cooldowns for an explicit new session`() {
        val ladder = ladder()
        ladder.noteFailure("model-primary", LiveFailure.MODEL_UNAVAILABLE)

        ladder.clearAll()

        assertEquals("model-primary", ladder.currentModel())
    }

    @Test
    fun `cooldown never reports negative time`() {
        val ladder = ladder()
        ladder.noteFailure("model-primary", LiveFailure.QUOTA)
        clock += LiveModelLadder.QUOTA_COOLDOWN_MS * 10

        assertEquals(0L, ladder.cooldownRemainingMs("model-primary"))
        assertEquals(0L, ladder.nextRetryDelayMs())
    }

    @Test
    fun `default models are the primary plus exactly one fallback`() {
        val defaults = LiveModelLadder.DEFAULT_MODELS

        assertEquals(2, defaults.size)
        assertEquals("gemini-3.1-flash-live-preview", defaults.first())
        assertEquals("gemini-2.5-flash-native-audio-preview-12-2025", defaults.last())
        assertNotEquals("gemini-3.8-live", defaults.first())
        assertNull(defaults.firstOrNull { it.contains("3.8") })
    }
}
