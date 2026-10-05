package com.example.core.gemini

import android.util.Base64
import android.util.Log
import com.example.actions.ActionRegistry
import com.example.actions.ActionResult
import com.example.core.config.SecureStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

enum class LiveSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

data class LiveActivityEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String, // "user", "assistant", "tool", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val cardData: Map<String, Any?>? = null
)

class GeminiLiveSession(
    private val secureStorage: SecureStorage,
    private val actionRegistry: ActionRegistry,
    private val audioEngine: AudioEngine,
    private val okHttpClient: OkHttpClient
) {
    private val ladder = LiveModelLadder()

    private val _sessionState = MutableStateFlow(LiveSessionState.DISCONNECTED)
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    private val _activityLog = MutableStateFlow<List<LiveActivityEvent>>(emptyList())
    val activityLog: StateFlow<List<LiveActivityEvent>> = _activityLog.asStateFlow()

    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var coroutineScope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var sessionHandle: String? = null
    private var systemInstruction: String? = null
    private var voiceName: String = DEFAULT_VOICE

    /**
     * Bumped for every socket we open. Callbacks capture the generation they were created with
     * and are ignored once it is stale, so a late onClosed/onFailure from a socket that was
     * already replaced can never tear down (or tear up) the current connection.
     */
    @Volatile private var socketGeneration = 0

    /** Set only after the server acknowledges setup. Mic frames before that are dropped. */
    @Volatile private var isSessionReady = false

    /** The generation whose setup completed. Tool responses from any other socket are dropped. */
    @Volatile private var readyGeneration = -1

    @Volatile private var hasStartedAudio = false

    /** Live sends each turn as many partial transcript messages; they are joined per turn. */
    private val userTranscriptBuffer = StringBuilder()
    private val assistantTranscriptBuffer = StringBuilder()

    @Volatile private var reconnectAttempt = 0

    /** Set by [endSession] so nothing reconnects behind the user's back. */
    @Volatile private var isSessionEnded = false

    /**
     * Generation whose teardown already ran. OkHttp can deliver onClosing, onClosed *and*
     * onFailure for one socket; without this the same failure was counted against the ladder
     * several times and each call re-armed [scheduleReconnect], cancelling the in-flight
     * backoff delay so a flapping server was retried at 1s forever.
     */
    @Volatile private var tornDownGeneration = -1

    /** Model of the live socket, so a failure is attributed to the rung that actually failed. */
    @Volatile private var lastModel: String = LiveModelLadder.DEFAULT_MODELS.first()

    private val wsClient = okHttpClient.newBuilder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for websockets
        // The API key travels in the query string, so a redirect would hand it to another host.
        // Failing closed here is safer than following one to an unexpected origin.
        .followRedirects(false)
        .followSslRedirects(false)
        // OkHttp's automatic retry can silently re-send a request after a connection failure;
        // for a streaming socket the ladder/reconnect path owns retries instead.
        .retryOnConnectionFailure(false)
        .build()

    init {
        audioEngine.onBargeInDetected = {
            handleBargeIn()
        }
    }

    // region Public API

    fun startSession(
        scope: CoroutineScope,
        systemInstruction: String,
        voiceName: String = DEFAULT_VOICE
    ) {
        coroutineScope = scope
        this.systemInstruction = systemInstruction
        this.voiceName = voiceName
        isSessionEnded = false

        // A resumption handle belongs to one session; a user-initiated start is a new session.
        dropSessionHandle()
        ladder.clearAll()
        reconnectAttempt = 0

        connectOffMainThread(model = ladder.currentModel(), freshSession = true)
    }

    fun endSession() {
        reconnectJob?.cancel()
        reconnectJob = null

        // Without this the pending reconnect coroutine would open a fresh socket seconds after
        // the user stopped the session.
        isSessionEnded = true
        // Invalidate outstanding callbacks before tearing the socket down.
        socketGeneration++
        stopAudio()

        sessionHandle = null
        secureStorage.clearSessionHandle()
        ladder.clearAll()

        webSocket?.close(1000, "User ended session")
        webSocket = null

        isSessionReady = false
        readyGeneration = -1
        _sessionState.value = LiveSessionState.DISCONNECTED
    }

    fun sendTextMessage(text: String) {
        addLog("user", text)
        val socket = webSocket
        if (socket == null || !isSessionReady) {
            addLog("system", "Text dropped: session is not ready yet")
            return
        }

        send(socket, buildRealtimeTextMessage(text))
    }

    fun addLog(type: String, content: String, cardData: Map<String, Any?>? = null) {
        val current = _activityLog.value.toMutableList()
        current.add(LiveActivityEvent(type = type, content = content, cardData = cardData))
        if (current.size > 100) {
            current.removeAt(0) // Keep context trimmed
        }
        _activityLog.value = current
    }

    fun sendRealtimeAudio(pcmBytes: ByteArray) {
        val socket = webSocket ?: return
        // Dropping these used to mean the server's VAD never saw a turn boundary, so it sat
        // silent after setup. Conversely, forwarding them before setup makes the server reject
        // the stream, so they are dropped until setupComplete.
        if (!isSessionReady) return

        send(socket, buildRealtimeAudioMessage(pcmBytes))
    }

    /**
     * Reconnects on the stored session handle so the conversation continues in the same
     * context, instead of throwing the history away on every voice change.
     */
    fun changeVoice(newVoice: String) {
        if (newVoice == voiceName && isSessionReady) return
        voiceName = newVoice

        if (systemInstruction == null || coroutineScope == null) {
            addLog("system", "Voice set to $newVoice; it applies when the session starts")
            return
        }
        if (webSocket == null) {
            addLog("system", "Voice set to $newVoice")
            return
        }

        addLog("system", "Switching voice to $newVoice")
        // [connect] reads the key from encrypted preferences. selectVoice calls this from the
        // viewModelScope (Main), so the keystore read must not happen on the caller's thread.
        connectOffMainThread(model = ladder.currentModel(), freshSession = false)
    }

    // endregion

    // region Connection handling

    /**
     * Every [connect] entry point goes through here. [connect] reads the API key from encrypted
     * preferences and writes the session handle back to them, so running it on the caller's
     * thread meant keystore disk I/O on Main for startSession/selectVoice, and on the socket
     * thread for teardown. [reconnectScope] is used rather than the UI scope so a session outlives
     * a configuration change.
     */
    private fun connectOffMainThread(model: String, freshSession: Boolean) {
        reconnectScope.launch(Dispatchers.IO) {
            connect(model, freshSession)
        }
    }

    private fun connect(model: String, freshSession: Boolean) {
        if (freshSession) {
            dropSessionHandle()
        }

        val generation = ++socketGeneration
        // Cleared before the socket exists so this generation's own callbacks can tear down.
        tornDownGeneration = -1
        // A previous socket may still be open: close it before replacing the reference so its
        // close callback cannot be mistaken for this generation's socket.
        webSocket?.cancel()
        webSocket = null
        stopAudio()
        lastModel = model

        _sessionState.value = LiveSessionState.CONNECTING

        val instruction = systemInstruction ?: DEFAULT_SYSTEM_INSTRUCTION
        val apiKey = secureStorage.getApiKey()
        if (apiKey.isEmpty()) {
            _sessionState.value = LiveSessionState.ERROR
            addLog("system", "Gemini API Key missing. Please provide key in settings.")
            return
        }

        val wsUrl = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

        val request = Request.Builder()
            .url(wsUrl)
            // The key appears in the query string because the Live endpoint requires it there;
            // it is also sent as the documented x-goog-api-key header. It is a per-session runtime
            // value read from encrypted storage, never a build-time constant. Redirects are off so
            // it cannot be replayed to another host.
            .header("x-goog-api-key", apiKey)
            .build()

        addLog("system", "Connecting to $model")
        val socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(generation)) {
                    Log.d(TAG, "Ignoring onOpen from stale socket $generation")
                    return
                }
                Log.d(TAG, "Connected to Gemini Live WebSocket: $model")
                // No audio here: sending setup before the server is ready produced a dead stream.
                sendSetupMessage(webSocket, instruction, voiceName, model)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(generation)) return
                handleServerMessage(generation, webSocket, text)
            }

            /**
             * The Live endpoint frequently delivers JSON as binary frames. Without this override
             * OkHttp drops them and the session receives setupComplete-less silence forever.
             */
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrent(generation)) return
                val text = runCatching { bytes.utf8() }
                    .onFailure { Log.w(TAG, "Could not decode binary frame", it) }
                    .getOrNull() ?: return
                handleServerMessage(generation, webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(generation)) return
                Log.d(TAG, "WebSocket closing: $code $reason")
                // Teardown here, not on onClosed: a server-initiated close can stall without ever
                // delivering onClosed, which left the app "connected" to a dead socket forever.
                onSocketGone(
                    generation,
                    "Connection closing ($code)",
                    if (code == 1000) LiveFailure.TRANSIENT else classifyFailure(reason, code)
                )
                // Cancel instead of complete: a closing socket will never deliver more frames.
                runCatching { webSocket.cancel() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(generation)) return
                Log.d(TAG, "WebSocket closed: $code $reason")
                // A normal close (1000) still ends the session, and a reconnect is scheduled for
                // it too: the previous code only reconnected from onFailure.
                onSocketGone(
                    generation,
                    "Connection closed ($code)",
                    if (code == 1000) LiveFailure.TRANSIENT else classifyFailure(reason, code)
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(generation)) return
                val detail = describeFailure(t, response)
                Log.e(TAG, "Live WebSocket failure: ${t.message}", t)
                onSocketGone(
                    generation,
                    detail.ifBlank { t.message ?: "Connection lost" },
                    classifyFailure(detail, response?.code)
                )
            }
        })
        webSocket = socket
    }

    private fun isCurrent(generation: Int): Boolean = generation == socketGeneration

    private fun onSocketGone(generation: Int, reason: String, failure: LiveFailure) {
        // One socket can fire onClosing + onClosed + onFailure. Only the first is a real event.
        if (tornDownGeneration == generation) return
        tornDownGeneration = generation

        // A handle is only usable if the session it belongs to reached setupComplete. Replaying
        // a handle from a half-open session makes the server reject the session outright.
        if (!isSessionReady) {
            dropSessionHandle()
        }

        stopAudio()
        isSessionReady = false
        webSocket = null

        if (generation == readyGeneration) {
            readyGeneration = -1
        }

        // Consulted before the ladder: a rejected key is fatal, not a rung to step down from.
        val action = ladder.noteFailure(lastModel, failure)
        if (action == LadderAction.STOP) {
            _sessionState.value = LiveSessionState.ERROR
            addLog("system", "Gemini rejected the API key. Please check it in settings.")
            return
        }
        if (action == LadderAction.STEP_DOWN) {
            Log.w(TAG, "Stepping down from $lastModel to ${ladder.currentModel()}")
        } else if (action == LadderAction.WAIT) {
            addLog("system", "All Live models are cooling down; waiting before the next attempt.")
        }

        _sessionState.value = LiveSessionState.DISCONNECTED
        addLog("system", "Connection interrupted: $reason")

        if (sessionHandle == null) {
            // Re-read the persisted handle: a handle may have arrived on the socket that just died.
            // This is a keystore read, which is why every caller of this path is on a background
            // dispatcher.
            sessionHandle = secureStorage.getSessionHandle()
        }

        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (webSocket != null || isSessionEnded) return

        reconnectJob?.cancel()
        reconnectJob = reconnectScope.launch {
            if (ladder.isFatal || isSessionEnded) return@launch

            // When every model is cooling down, retrying on the usual backoff just burns
            // requests against a model that is known to be unusable.
            val cooldownWait = if (ladder.availableModels().isEmpty()) {
                ladder.nextRetryDelayMs()
            } else {
                0L
            }
            val waitMs = maxOf(computeBackoffMs(), cooldownWait)
            val model = ladder.currentModel()
            Log.d(TAG, "Reconnecting to $model in ${waitMs}ms (attempt $reconnectAttempt)")

            delay(waitMs)

            if (ladder.isFatal || isSessionEnded) return@launch
            if (webSocket != null) return@launch

            // Re-read after the delay: the cooldown that forced the long wait may have expired
            // while we slept, and reconnecting on the model we knew was cooling would just burn
            // another failure. currentModel() returns the best rung available right now.
            _sessionState.value = LiveSessionState.CONNECTING
            connect(model = ladder.currentModel(), freshSession = false)
        }
    }

    /** 1s doubling to a 30s ceiling, plus jitter so reconnects from many clients do not sync up. */
    private fun computeBackoffMs(): Long {
        val ceiling = MAX_BACKOFF_MS
        val base = min(ceiling, MIN_BACKOFF_MS shl min(reconnectAttempt, 5))
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
        // Jitter is applied to base and then capped at the ceiling, so the total stay inside the
        // documented 1-30s window. Capping base+jitter instead let a saturated server see 39s.
        val jitter = (Random.nextDouble() * JITTER_RATIO * base).toLong()
        return (base + jitter).coerceIn(base, ceiling)
    }

    private fun stopAudio() {
        if (hasStartedAudio) {
            hasStartedAudio = false
            audioEngine.stopRecording()
            audioEngine.stopPlayback()
        }
    }

    private fun startAudio(scope: CoroutineScope, generation: Int) {
        if (hasStartedAudio) return
        // Claimed synchronously: two setupComplete frames arriving back to back would otherwise
        // both pass the guard and open two AudioRecord instances.
        hasStartedAudio = true

        scope.launch(Dispatchers.IO) {
            // Re-checked after the hop: the socket may have died while we were switching threads.
            if (!isCurrent(generation) || !isSessionReady) {
                hasStartedAudio = false
                return@launch
            }

            val audioScope = coroutineScope ?: scope
            audioEngine.startRecording(audioScope) { pcmChunk ->
                sendRealtimeAudio(pcmChunk)
            }
            audioEngine.startPlayback(audioScope)
            _sessionState.value = LiveSessionState.LISTENING
        }
    }

    // endregion

    // region Outbound messages

    private fun sendSetupMessage(
        socket: WebSocket,
        instruction: String,
        voice: String,
        model: String
    ) {
        val handle = sessionHandle
        if (handle != null) {
            Log.d(TAG, "Replaying session resumption handle")
        }
        send(socket, buildSetupMessage(instruction, voice, model, handle, actionRegistry))
    }

    private fun send(socket: WebSocket, payload: JSONObject) {
        runCatching { socket.send(payload.toString()) }
            .onFailure { Log.w(TAG, "WebSocket send failed", it) }
    }

    private fun sendToolResponse(
        socket: WebSocket,
        generation: Int,
        callId: String,
        name: String,
        result: ActionResult
    ) {
        // The socket can be replaced while a slow tool is still running. Answering on the new
        // socket would attach the response to a different conversation.
        if (!isCurrent(generation)) {
            Log.d(TAG, "Dropping tool response for $name: socket generation changed")
            return
        }
        val resp = JSONObject()
            .put("id", callId)
            .put("name", name)
            .put("response", JSONObject().put("output", result.spokenResult))

        send(
            socket,
            JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", JSONArray().put(resp))
            )
        )
    }

    // endregion

    // region Inbound messages

    private fun handleServerMessage(generation: Int, socket: WebSocket, jsonText: String) {
        try {
            val root = JSONObject(jsonText)

root.optJSONObject("sessionResumptionUpdate")
                ?.optString("newHandle", "")
                ?.takeIf { it.isNotBlank() }
                ?.let { newHandle ->
                    sessionHandle = newHandle
                    secureStorage.setSessionHandle(newHandle)
                    Log.d(TAG, "Stored session resumption handle")
                }

            // Checked before setupComplete handling: an error frame means the socket is finished, so
            // marking the session ready first would open the microphone on a dead session.
            root.optJSONObject("error")?.let { error ->
                handleServerError(generation, error)
                return
            }

            // Setup acknowledgement: the only safe point to open the microphone and speaker.
            if (root.optJSONObject("setupComplete") != null) {
                Log.d(TAG, "setupComplete received, starting audio")
                isSessionReady = true
                readyGeneration = generation
                // A working session resets the backoff, so the next unrelated drop starts at 1s
                // again instead of inheriting the ceiling from an old outage.
                reconnectAttempt = 0
                _sessionState.value = LiveSessionState.CONNECTED
                startAudio(coroutineScope ?: reconnectScope, generation)
            }

            val serverContent = root.optJSONObject("serverContent")
            if (serverContent != null) {
                handleServerContent(serverContent)
            }

            root.optJSONObject("toolCall")?.let { toolCall ->
                handleToolCall(generation, socket, toolCall)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message", e)
        }
    }

    private fun handleServerContent(serverContent: JSONObject) {
        serverContent.optJSONObject("modelTurn")
            ?.optJSONArray("parts")
            ?.let { parts ->
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    part.optJSONObject("inlineData")?.let { inlineData ->
                        val mime = inlineData.optString("mimeType", "")
                        val data = inlineData.optString("data", "")
                        if (data.isNotEmpty()) {
                            if (mime.startsWith("audio/")) {
                                audioEngine.queueAudioOutput(Base64.decode(data, Base64.NO_WRAP))
                                if (_sessionState.value == LiveSessionState.LISTENING) {
                                    _sessionState.value = LiveSessionState.SPEAKING
                                }
                            } else {
                                Log.d(TAG, "Ignoring inline data of type $mime")
                            }
                        }
                    }
                }
            }

        // Authoritative "the user cut you off" signal. Flushing keeps the playback loop alive.
        if (serverContent.optBoolean("interrupted", false)) {
            Log.d(TAG, "Server signalled interruption, dropping queued audio")
            audioEngine.interruptPlayback()
            if (isSessionReady) {
                _sessionState.value = LiveSessionState.LISTENING
            }
        }

        // Transcripts arrive in fragments; joining per turn keeps the log readable and avoids
        // one log entry per fragment.
        serverContent.optJSONObject("inputTranscription")
            ?.optString("text", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { userTranscriptBuffer.append(it) }

        serverContent.optJSONObject("outputTranscription")
            ?.optString("text", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { assistantTranscriptBuffer.append(it) }

        if (serverContent.optBoolean("turnComplete", false)) {
            flushTranscripts()
            if (isSessionReady) {
                _sessionState.value = LiveSessionState.LISTENING
            }
        }
    }

    private fun flushTranscripts() {
        val user = userTranscriptBuffer.toString().trim()
        val assistant = assistantTranscriptBuffer.toString().trim()
        userTranscriptBuffer.setLength(0)
        assistantTranscriptBuffer.setLength(0)
        if (user.isNotEmpty()) addLog("user", user)
        if (assistant.isNotEmpty()) addLog("assistant", assistant)
    }

    private fun handleToolCall(generation: Int, socket: WebSocket, toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        _sessionState.value = LiveSessionState.THINKING
        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.optJSONObject(i) ?: continue
            val callId = call.optString("id")
            val functionName = call.optString("name")
            if (callId.isEmpty() || functionName.isEmpty()) continue
            val argsObj = call.optJSONObject("args") ?: JSONObject()

            val argsMap = mutableMapOf<String, Any?>()
            val keys = argsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                argsMap[k] = argsObj.get(k)
            }

            // Executed off the socket thread so a slow action cannot stall the voice loop.
            val scope = coroutineScope
            if (scope == null || !isCurrent(generation)) {
                Log.d(TAG, "Dropping tool call $functionName: session ended")
                return
            }
            scope.launch(Dispatchers.IO) {
                addLog("tool", "Executing $functionName...")
                val result = try {
                    actionRegistry.executeAction(functionName, argsMap)
                } catch (e: CancellationException) {
                    // Swallowing this would keep a cancelled tool running and answer a dead socket.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Action $functionName threw", e)
                    ActionResult(
                        spokenResult = "The $functionName action could not be completed.",
                        isError = true
                    )
                }
                addLog("tool", "Completed $functionName: ${result.spokenResult}", result.cardData)

                sendToolResponse(socket, generation, callId, functionName, result)
            }
        }
    }

    private fun handleServerError(generation: Int, error: JSONObject) {
        if (!isCurrent(generation)) return

        val message = error.optString("message", "")
        val status = error.optInt("code", 0)
        val failure = classifyFailure(message, status.takeIf { it != 0 })
        val detail = "Live error ${status.takeIf { it != 0 } ?: ""}: ${message}".trim()

        // A server-side error frame means this socket is finished, so it goes through the same
        // teardown path as a close: otherwise scheduleReconnect() returns immediately because a
        // socket is still attached, and the session stays broken for good.
        onSocketGone(generation, detail.ifBlank { "Live error" }, failure)

        // Cancelled after teardown so the ordered session is really closed, not left half-open
        // pushing frames at a server that already rejected us.
        runCatching { webSocket?.cancel() }
        webSocket = null
    }

    // endregion

    private fun handleBargeIn() {
        Log.d(TAG, "Handling barge-in: interrupting speech output")
        // Must not be stopPlayback(): that cancels the playback loop and nothing restarts it
        // until the whole session is recreated, which left JARVIS permanently mute.
        // The server ends its own turn on the audio that follows, so no interruption notice is
        // sent: audioStreamEnd closes the input stream and made the model stop listening.
        audioEngine.interruptPlayback()
        if (isSessionReady) {
            _sessionState.value = LiveSessionState.LISTENING
        }
    }

    private fun dropSessionHandle() {
        sessionHandle = null
        secureStorage.clearSessionHandle()
    }

    private fun describeFailure(t: Throwable, response: Response?): String {
        val body = runCatching { response?.body?.string() }.getOrNull().orEmpty()
        return listOfNotNull(
            t.localizedMessage?.takeIf { it.isNotBlank() },
            body.takeIf { it.isNotBlank() }
        ).joinToString(" ")
    }

    private companion object {
        const val TAG = "GeminiLiveSession"
        const val DEFAULT_VOICE = "Puck"
        const val DEFAULT_SYSTEM_INSTRUCTION =
            "You are JARVIS, a concise and friendly voice assistant. Keep spoken replies short."

        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_EXPONENT = 5
        const val JITTER_RATIO = 0.3
    }
}

/**
 * Builds the `setup` frame.
 *
 * Extracted from the socket path so the wire format can be asserted directly: the previous
 * inline version sent `sessionResumptionHandle` (not a real field) and omitted
 * `contextWindowCompression`, neither of which any test could catch.
 */
internal fun buildSetupMessage(
    instruction: String,
    voice: String,
    model: String,
    sessionHandle: String?,
    actionRegistry: ActionRegistry
): JSONObject {
    val setupObj = JSONObject()
    setupObj.put("model", "models/$model")

    val genConfig = JSONObject()
    genConfig.put("responseModalities", JSONArray().put("AUDIO"))
    genConfig.put(
        "speechConfig",
        JSONObject().put(
            "voiceConfig",
            JSONObject().put(
                "prebuiltVoiceConfig",
                JSONObject().put("voiceName", voice)
            )
        )
    )
    setupObj.put("generationConfig", genConfig)

    setupObj.put(
        "systemInstruction",
        JSONObject().put(
            "parts",
            JSONArray().put(JSONObject().put("text", instruction))
        )
    )

    // With responseModalities = AUDIO the model emits no text parts, so without these the
    // activity log stays empty even while audio is flowing.
    setupObj.put("inputAudioTranscription", JSONObject())
    setupObj.put("outputAudioTranscription", JSONObject())

    setupObj.put("tools", actionRegistry.buildGeminiToolsJson())

    // Sliding-window compression is what keeps a long session alive: without it the context
    // grows until the server stops accepting turns.
    setupObj.put(
        "contextWindowCompression",
        JSONObject().put("slidingWindow", JSONObject())
    )

    // The field is an object. An empty object still enables resumption, and the server answers
    // with sessionResumptionUpdate.newHandle for the new session.
    val resumption = JSONObject()
    if (sessionHandle != null) {
        resumption.put("handle", sessionHandle)
    }
    setupObj.put("sessionResumption", resumption)

    return JSONObject().put("setup", setupObj)
}

/**
 * Wraps raw PCM input as `realtimeInput.audio`.
 *
 * `mediaChunks` was removed from the Live API; sending it silently produced a session that
 * accepted the setup and then never responded.
 */
internal fun buildRealtimeAudioMessage(pcmBytes: ByteArray): JSONObject {
    val audio = JSONObject()
        .put("mimeType", "audio/pcm;rate=16000")
        .put("data", Base64.encodeToString(pcmBytes, Base64.NO_WRAP))

    return JSONObject().put(
        "realtimeInput",
        JSONObject().put("audio", audio).put("turnComplete", false)
    )
}

/** Wraps text input as `realtimeInput.text`. */
internal fun buildRealtimeTextMessage(text: String): JSONObject {
    val realtimeInput = JSONObject().put("text", text).put("turnComplete", true)
    return JSONObject().put("realtimeInput", realtimeInput)
}

/**
 * Maps a failure onto a [LiveFailure].
 *
 * Order matters here. Quota is checked first because a 429 body routinely mentions the API key
 * ("Quota exceeded for API key ..."), and treating that as a key error would make a recoverable
 * rate limit permanently fatal. A bare 403 stays ambiguous on purpose: only an explicit
 * key/permission message makes it fatal, otherwise it means the model is unavailable.
 */
internal fun classifyFailure(message: String, statusCode: Int?): LiveFailure {
    val text = message.lowercase()
    fun contains(vararg needles: String) = needles.any { text.contains(it) }

    // Quota is checked first on purpose. A 429 body routinely mentions the API key ("quota
    // exceeded for API key ..."), and matching the generic "api key" phrase there would mark a
    // recoverable quota problem as a fatal key error and permanently stop the session.
    if (statusCode == 429 || contains("resource_exhausted", "rate limit", "quota", "too many requests")) {
        return LiveFailure.QUOTA
    }

    if (contains(
            "api key not valid", "api_key_invalid", "invalid api key", "apikey",
            "unauthenticated", "permission_denied", "api key should be passed",
            "missing api key", "no api key", "invalid credentials"
        )
    ) {
        return LiveFailure.INVALID_API_KEY
    }
    if (statusCode == 401) {
        return LiveFailure.INVALID_API_KEY
    }
    if (statusCode == 404 || statusCode == 403 ||
        contains("not found", "is not found for api version", "not supported", "unsupported model")
    ) {
        return LiveFailure.MODEL_UNAVAILABLE
    }
    return LiveFailure.TRANSIENT
}
