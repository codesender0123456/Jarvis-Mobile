package com.example.core.gemini

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

data class AudioVisualState(
    val inputAmplitude: Float = 0f,
    val outputAmplitude: Float = 0f,
    val dominantFrequency: Float = 0f,
    val isSpeaking: Boolean = false,
    val isListening: Boolean = false
)

class AudioEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Audio configs
    private val inputSampleRate = 16000
    private val outputSampleRate = 24000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null

    private val _visualState = MutableStateFlow(AudioVisualState())
    val visualState: StateFlow<AudioVisualState> = _visualState.asStateFlow()

    @Volatile private var isRecording = false
    @Volatile private var isPlaying = false
    private val outputQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var playbackJob: Job? = null
    @Volatile private var recordJob: Job? = null

    // Self-echo guard: mic chunks are treated as echo while output is active within this window.
    // Monotonic clock: System.currentTimeMillis() can jump backwards on an NTP/user clock
    // change, which would make the echo window permanently "active" and mute the mic.
    @Volatile private var outputActiveTimestamp = 0L

    // Amplitude of the most recent chunk handed to the speaker, used as the echo floor when
    // deciding whether mic energy is the assistant's own voice or the user interrupting.
    @Volatile private var lastOutputAmplitude = 0f

    private const val ECHO_GUARD_WINDOW_MS = 250L
    private const val BARGE_IN_MARGIN = 1.6f
    private const val BARGE_IN_FLOOR = 0.05f
    private const val MAX_QUEUED_CHUNKS = 64

    // Barge-in must be sustained. The speaker bleeds into the microphone on cheap hardware, so
    // a single loud chunk is usually echo, not the user. Requiring energy above the floor
    // continuously for this long stops JARVIS from cutting itself off mid-sentence.
    private const val BARGE_IN_HOLD_MS = 320L
    private const val BARGE_IN_COOLDOWN_MS = 1500L

    @Volatile private var bargeInSince = 0L
    @Volatile private var lastBargeInAt = 0L
    @Volatile private var lastLevelPublishAt = 0L

    /** Last capture error, surfaced to the UI so a dead microphone is not silent. */
    @Volatile var lastRecordError: String? = null
        private set

    // Barge-in callback
    var onBargeInDetected: (() -> Unit)? = null

    init {
        runCatching { initAudioTrack() }
            .onFailure { Log.e("AudioEngine", "Failed to initialise AudioTrack", it) }
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            outputSampleRate,
            channelConfigOut,
            audioFormat
        )
        // getMinBufferSize returns ERROR (-2) / ERROR_BAD_VALUE (-3) for unsupported configs,
        // which would make the multiplication below negative and throw inside the Builder.
        val bufferSizeInBytes =
            if (minBufferSize <= 0) DEFAULT_BUFFER_BYTES else (minBufferSize * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(outputSampleRate)
                    .setChannelMask(channelConfigOut)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeInBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun startRecording(scope: CoroutineScope, onAudioChunk: (ByteArray) -> Unit) {
        if (isRecording) return

        // Constructing AudioRecord without RECORD_AUDIO throws SecurityException, which was
        // previously swallowed into a bare log, leaving the app "connected" but permanently
        // deaf with no explanation in the UI.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            lastRecordError = "Microphone permission is not granted"
            Log.e("AudioEngine", "Cannot start recording: RECORD_AUDIO not granted")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            inputSampleRate,
            channelConfigIn,
            audioFormat
        )
        // ERROR (-2) and ERROR_BAD_VALUE (-3) mean the device cannot do 16 kHz here. Passing a
        // negative size to the constructor throws, which silently killed capture before.
        if (minBufferSize <= 0) {
            lastRecordError = "Device does not support ${inputSampleRate}Hz mono PCM capture"
            Log.e("AudioEngine", "Unsupported capture config, minBufferSize=$minBufferSize")
            return
        }
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        val recorder = createRecorder(bufferSize)
        if (recorder == null) {
            _visualState.update { it.copy(isListening = false) }
            return
        }

        audioRecord = recorder
        isRecording = true
        lastRecordError = null

        recordJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            try {
                while (isActive && isRecording) {
                    val read = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (e: IllegalStateException) {
                        Log.w("AudioEngine", "AudioRecord read failed, stopping capture", e)
                        break
                    }
                    if (read > 0) {
                        val pcmData = buffer.copyOf(read)
                        val amp = calculateRms(pcmData)
                        publishInputLevel(amp)

                        val now = SystemClock.elapsedRealtime()
                        val outputActive = now - outputActiveTimestamp < ECHO_GUARD_WINDOW_MS
                        val isLikelyEcho = outputActive && amp <= lastOutputAmplitude * BARGE_IN_MARGIN + BARGE_IN_FLOOR

                        if (isLikelyEcho) {
                            bargeInSince = 0L
                        } else if (outputActive) {
                            // Mic energy clearly exceeds the assistant's own output level and is
                            // not explained by echo. Require it to persist before acting.
                            if (bargeInSince == 0L) bargeInSince = now
                            val sustained = now - bargeInSince >= BARGE_IN_HOLD_MS
                            val cooledDown = now - lastBargeInAt >= BARGE_IN_COOLDOWN_MS

                            if (sustained && cooledDown) {
                                bargeInSince = 0L
                                lastBargeInAt = now
                                Log.d("AudioEngine", "Barge-in triggered by sustained user speech")
                                interruptPlayback()
                                onBargeInDetected?.invoke()
                            }
                            // Always forwarded: the server does its own turn detection, and
                            // dropping the chunk would leave it waiting on silence.
                            onAudioChunk(pcmData)
                        } else {
                            bargeInSince = 0L
                            onAudioChunk(pcmData)
                        }
                    }
                }
            } finally {
                isRecording = false
            }
        }
    }

    /**
     * Tries each capture source in turn. VOICE_RECOGNITION gives the AGC/noise-suppression the
     * Live API expects, but it is unimplemented or refuses 16 kHz on a number of budget
 * devices, and a plain MIC is far more widely supported. Falling back keeps the mic alive
 * instead of leaving the user with a session that never hears anything.
     */
    private fun createRecorder(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )

        for (source in sources) {
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord(
                    source,
                    inputSampleRate,
                    channelConfigIn,
                    audioFormat,
                    bufferSize
                )

                if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w("AudioEngine", "AudioSource $source did not initialise, trying next")
                    runCatching { candidate.release() }
                    continue
                }

                candidate.startRecording()
                if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w("AudioEngine", "AudioSource $source would not start recording")
                    runCatching { candidate.stop(); candidate.release() }
                    continue
                }

                Log.i("AudioEngine", "Recording via AudioSource $source at ${inputSampleRate}Hz")
                return candidate
            } catch (e: Exception) {
                Log.w("AudioEngine", "AudioSource $source failed", e)
                if (candidate != null) {
                    runCatching { candidate.release() }
                }
            }
        }

        lastRecordError = "Could not open the microphone on this device"
        Log.e("AudioEngine", "All capture sources failed")
        return null
    }

    /**
     * Amplitude drives the avatar and waveform on every recomposition. Publishing on each
     * ~64 ms chunk caused ~15 recompositions a second of a Canvas-heavy HUD, which is
     * measurable on a 3 GB device, so the value is sampled at a fixed rate instead.
     */
    private fun publishInputLevel(amp: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevelPublishAt < LEVEL_PUBLISH_INTERVAL_MS) return
        lastLevelPublishAt = now
        _visualState.update { it.copy(inputAmplitude = amp, isListening = true) }
    }

    fun stopRecording() {
        isRecording = false
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w("AudioEngine", "Error stopping AudioRecord", e)
        }
        audioRecord = null
        _visualState.update { it.copy(inputAmplitude = 0f, isListening = false) }
    }

    fun queueAudioOutput(pcmChunk: ByteArray) {
        // Bound the queue: a fast producer must not be able to OOM the process.
        while (outputQueue.size >= MAX_QUEUED_CHUNKS) {
            outputQueue.poll() ?: break
        }
        outputQueue.offer(pcmChunk)
    }

    fun startPlayback(scope: CoroutineScope) {
        if (isPlaying) return
        isPlaying = true

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                ensureTrackPlaying()

                while (isActive && isPlaying) {
                    val chunk = outputQueue.poll()
                    if (chunk != null) {
                        val amp = calculateRms(chunk)
                        lastOutputAmplitude = amp
                        outputActiveTimestamp = SystemClock.elapsedRealtime()

                        if (writeChunk(chunk) < 0) {
                            Log.w("AudioEngine", "AudioTrack write failed, reinitialising track")
                            runCatching { initAudioTrack() }
                            ensureTrackPlaying()
                        }

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLevelPublishAt >= LEVEL_PUBLISH_INTERVAL_MS) {
                            lastLevelPublishAt = now
                            val freq = estimateFormantFreq(chunk)
                            _visualState.update {
                                it.copy(
                                    outputAmplitude = amp,
                                    dominantFrequency = freq,
                                    isSpeaking = true
                                )
                            }
                        }
                    } else {
                        _visualState.update { it.copy(outputAmplitude = 0f, isSpeaking = false) }
                        delay(20)
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioEngine", "Playback loop terminated", e)
            } finally {
                // Without this a crashed loop left isPlaying == true, so every later
                // startPlayback() early-returned and the assistant stayed mute.
                isPlaying = false
            }
        }
    }

    private fun ensureTrackPlaying() {
        try {
            val track = audioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w("AudioEngine", "AudioTrack not initialised, recreating")
                initAudioTrack()
            }
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w("AudioEngine", "AudioTrack.play() failed, recreating track", e)
            runCatching { initAudioTrack() }
            runCatching { audioTrack?.play() }
        }
    }

    private fun writeChunk(chunk: ByteArray): Int {
        val track = audioTrack ?: return 0
        return try {
            track.write(chunk, 0, chunk.size)
        } catch (e: Exception) {
            Log.w("AudioEngine", "AudioTrack.write threw", e)
            -1
        }
    }

    /**
     * Discards audio the assistant has not spoken yet without tearing the playback loop down.
     *
     * This MUST be used for barge-in and for the server's `interrupted` signal. Calling
     * [stopPlayback] there cancelled the playback job permanently: nothing calls
     * [startPlayback] again until the whole session is restarted, so JARVIS went silent for
     * the remainder of the session after a single interruption.
     */
    fun interruptPlayback() {
        outputQueue.clear()
        lastOutputAmplitude = 0f
        _visualState.update { it.copy(outputAmplitude = 0f, isSpeaking = false) }
        runCatching {
            // flush() drops everything already buffered and the track keeps running, so the
            // next model chunk is audible immediately.
            audioTrack?.flush()
        }.onFailure { Log.w("AudioEngine", "Error flushing AudioTrack", it) }
    }

    fun stopPlayback() {
        // isPlaying MUST be cleared here: startPlayback() early-returns while it is true,
        // so leaving it set means AudioTrack.play() is never called again and the assistant
        // stays silent for the rest of the process lifetime.
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        outputQueue.clear()
        lastOutputAmplitude = 0f
        _visualState.update { it.copy(outputAmplitude = 0f, isSpeaking = false) }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.w("AudioEngine", "Error stopping AudioTrack", e)
        }
    }

    fun getAvailableAudioDevices(): List<String> {
        val list = mutableListOf("Default Device")
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
        for (dev in devices) {
            val name = dev.productName.toString()
            val type = when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microphone"
                else -> "Audio Device"
            }
            list.add("$name ($type)")
        }
        return list.distinct()
    }

    private fun calculateRms(bytes: ByteArray): Float {
        var sum = 0.0
        val numSamples = bytes.size / 2
        if (numSamples == 0) return 0f

        for (i in 0 until numSamples) {
            val sample = (bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)
            sum += sample.toDouble() * sample
        }
        val rms = sqrt(sum / numSamples)
        return (rms / 32767.0).toFloat().coerceIn(0f, 1f)
    }

    private fun estimateFormantFreq(bytes: ByteArray): Float {
        // Zero-crossing rate pitch estimate (not a true formant frequency).
        var zeroCrossings = 0
        val numSamples = bytes.size / 2
        if (numSamples < 2) return 0f

        var prev = (bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)
        for (i in 1 until numSamples) {
            val curr = (bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)
            if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                zeroCrossings++
            }
            prev = curr
        }
        val durationSec = numSamples.toFloat() / outputSampleRate
        return if (durationSec > 0) (zeroCrossings / (2f * durationSec)).coerceIn(100f, 3500f) else 0f
    }

    fun release() {
        stopRecording()
        stopPlayback()
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w("AudioEngine", "Error releasing AudioTrack", e)
        }
        audioTrack = null
    }

    private companion object {
        const val DEFAULT_BUFFER_BYTES = 4096
        const val LEVEL_PUBLISH_INTERVAL_MS = 50L
    }
}
