package com.example.core.gemini

/**
 * Why a Live connection attempt failed.
 *
 * Only two of these change which model is used. Everything else is a connectivity problem and
 * must be retried on the same model: stepping down on any error permanently pins the app to
 * the oldest model after a single dropped Wi-Fi packet.
 */
enum class LiveFailure {
    /** Quota exhausted / rate limited (HTTP 429, RESOURCE_EXHAUSTED). */
    QUOTA,

    /** The model is not available to this key (HTTP 404 / 403, PERMISSION_DENIED on the model). */
    MODEL_UNAVAILABLE,

    /** The API key itself is missing, invalid or not enabled. Retrying cannot help. */
    INVALID_API_KEY,

    /** Socket dropped, timeout, 5xx, DNS, airplane mode, ... */
    TRANSIENT
}

/** What the caller should do after [LiveModelLadder.noteFailure]. */
enum class LadderAction {
    /** Nothing to do; the session is already fatal and must not reconnect. */
    STOP,

    /** Reconnect using the same model. */
    RETRY_SAME_MODEL,

    /** Reconnect using the next model that is not in cooldown. */
    STEP_DOWN,

    /** Cooldown is still active on every model; wait before trying again. */
    WAIT
}

/**
 * Ordered list of Live models with per-model cooldowns.
 *
 * The ladder only steps down for quota (5 min) and lost model access (6 h). Cooldowns expire on
 * their own, so the preferred model is automatically retried once its cooldown lapses and the
 * session recovers without the user or the app restarting anything.
 *
 * Deliberately free of Android dependencies so it can be unit tested on the JVM.
 */
class LiveModelLadder(
    models: List<String> = DEFAULT_MODELS,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val models: List<String> = models.ifEmpty { DEFAULT_MODELS }
    private val blockedUntilMs = mutableMapOf<String, Long>()

    /** True once an invalid API key was seen. Sticky until [reset]. */
    var isFatal: Boolean = false
        private set

    /** Best model that is not currently in cooldown. */
    fun currentModel(): String = availableModels().firstOrNull() ?: models.first()

    fun availableModels(): List<String> {
        val timestamp = now()
        return models.filter { (blockedUntilMs[it] ?: 0L) <= timestamp }
    }

    fun isInCooldown(model: String): Boolean = (blockedUntilMs[model] ?: 0L) > now()

    /** Milliseconds until [model] leaves cooldown; 0 when it is usable. */
    fun cooldownRemainingMs(model: String): Long =
        ((blockedUntilMs[model] ?: 0L) - now()).coerceAtLeast(0L)

    /** Longest remaining cooldown across all models, used to schedule the next attempt. */
    fun nextRetryDelayMs(): Long =
        models.maxOfOrNull { cooldownRemainingMs(it) } ?: 0L

    fun noteFailure(model: String, failure: LiveFailure): LadderAction = when (failure) {
        LiveFailure.INVALID_API_KEY -> {
            isFatal = true
            LadderAction.STOP
        }

        LiveFailure.QUOTA -> block(model, QUOTA_COOLDOWN_MS)

        LiveFailure.MODEL_UNAVAILABLE -> block(model, MODEL_ACCESS_COOLDOWN_MS)

        // A dropped socket says nothing about the model, so the ladder must stay put.
        LiveFailure.TRANSIENT -> LadderAction.RETRY_SAME_MODEL
    }

    private fun block(model: String, durationMs: Long): LadderAction {
        blockedUntilMs[model] = now() + durationMs
        return if (availableModels().isNotEmpty()) {
            LadderAction.STEP_DOWN
        } else {
            LadderAction.WAIT
        }
    }

    /** Clears the fatal flag. Cooldowns are time based and are intentionally kept. */
    fun reset() {
        isFatal = false
    }

    /** Full reset, used when the user explicitly starts a new session or changes the API key. */
    fun clearAll() {
        isFatal = false
        blockedUntilMs.clear()
    }

    companion object {
        const val QUOTA_COOLDOWN_MS = 5L * 60L * 1000L
        const val MODEL_ACCESS_COOLDOWN_MS = 6L * 60L * 60L * 1000L

        /**
         * Newest model first. The older native-audio preview is the only fallback: it is kept
         * because it is the model the Live endpoint is most broadly available on, and a single
         * fallback is enough to survive a primary-model rollback without silently degrading
         * the voice experience on every transient error.
         */
        val DEFAULT_MODELS = listOf(
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025"
        )
    }
}
