package com.example

import com.example.core.gemini.ONE_SHOT_MODEL_LADDER
import com.example.core.gemini.cooldownDurationMsFor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelLadderTest {

    @Test
    fun testModelLadderOrder() {
        // Asserted against the production constant rather than a re-typed copy, so the test
        // cannot drift away from the code or the README.
        assertEquals(
            listOf(
                "gemini-3.1-flash-lite-preview",
                "gemini-2.5-flash-lite",
                "gemini-2.5-flash",
                "gemini-3.5-flash"
            ),
            ONE_SHOT_MODEL_LADDER
        )
    }

    @Test
    fun quotaUsesFiveMinuteCooldown() {
        assertEquals(TimeUnit.MINUTES.toMillis(5), cooldownDurationMsFor(429))
    }

    @Test
    fun gatewayErrorsUseThirtyMinuteCooldown() {
        assertEquals(TimeUnit.MINUTES.toMillis(30), cooldownDurationMsFor(503))
        assertEquals(TimeUnit.MINUTES.toMillis(30), cooldownDurationMsFor(504))
    }

    @Test
    fun missingModelUsesSixHourCooldown() {
        assertEquals(TimeUnit.HOURS.toMillis(6), cooldownDurationMsFor(404))
    }

    @Test
    fun configurationErrorsFailFastInsteadOfCoolingDown() {
        // A bad key or a malformed request is not fixed by waiting, so cooling every model down
        // here would only delay the identical failure.
        listOf(400, 401, 403).forEach { code ->
            assertEquals("HTTP $code must not trigger a cooldown", 0L, cooldownDurationMsFor(code))
        }
    }

    @Test
    fun unexpectedCodesDoNotCooldown() {
        assertEquals(0L, cooldownDurationMsFor(418))
        assertEquals(0L, cooldownDurationMsFor(200))
    }
}
