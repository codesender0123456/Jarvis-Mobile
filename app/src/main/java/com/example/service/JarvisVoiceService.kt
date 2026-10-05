package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class JarvisVoiceService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var isListening = false
    @Volatile private var lastSpeechTime = SystemClock.elapsedRealtime()
    @Volatile private var isAsleep = false

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isListening) {
            startWakeWordMonitor()
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Android 14+ throws SecurityException from startForeground(TYPE_MICROPHONE)
            // without this, and there is no useful way to run the monitor anyway.
            Log.e("JarvisVoiceService", "RECORD_AUDIO not granted; stopping voice service")
            stopSelf()
            return
        }

        val channelId = CHANNEL_ID
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "JARVIS Background Listener",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors for wake word and background voice commands"
        }
        manager.createNotificationChannel(channel)

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS Active")
            .setContentText("Awaiting voice command or 'Hey Jarvis'")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE and the typed startForeground
        // overload are API 29+. minSdk is 26, so the typed call would throw
        // NoSuchMethodError on Android 8/9 instead of starting the service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startWakeWordMonitor() {
        isListening = true
        lastSpeechTime = SystemClock.elapsedRealtime()

        serviceScope.launch {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                ).also { it.startRecording() }
            } catch (e: Exception) {
                Log.e("JarvisVoiceService", "Failed to start AudioRecord in service", e)
                isListening = false
                return@launch
            }

            try {
                val buffer = ByteArray(bufferSize)
                while (isActive && isListening) {
                    val read = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (e: IllegalStateException) {
                        Log.w("JarvisVoiceService", "AudioRecord read failed", e)
                        break
                    }
                    if (read <= 0) continue

                    val energy = calculateRms(buffer, read)

                    if (isAsleep) {
                        // Low-power mode: the recorder stays open because there is no way to
                        // observe a wake word otherwise, but we poll rarely and require a much
                        // louder burst before acting.
                        if (energy > WAKE_ENERGY_THRESHOLD) {
                            isAsleep = false
                            lastSpeechTime = SystemClock.elapsedRealtime()
                            Log.d("JarvisVoiceService", "Waking from low-power sleep.")
                            onWakeWordDetected()
                            delay(POLL_INTERVAL_MS)
                        } else {
                            delay(WAKE_POLL_INTERVAL_MS)
                        }
                        continue
                    }

                    if (energy > SPEECH_ENERGY_THRESHOLD) {
                        lastSpeechTime = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - lastSpeechTime > SLEEP_AFTER_MS) {
                        // Prolonged silence: drop to low-power polling rather than ending the
                        // coroutine. Returning here released the microphone and left the service
                        // permanently inert, because no further onStartCommand arrives to restart
                        // the monitor, so the wake word silently stopped working.
                        isAsleep = true
                        Log.d("JarvisVoiceService", "Entering low-power sleep after prolonged silence.")
                        delay(WAKE_POLL_INTERVAL_MS)
                        continue
                    }

                    if (energy > WAKE_ENERGY_THRESHOLD) {
                        onWakeWordDetected()
                    }
                    delay(POLL_INTERVAL_MS)
                }
            } finally {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
                isListening = false
            }
        }
    }

    /**
     * The monitor is an energy gate, not a speech recogniser: it cannot verify the phrase
     * "Hey Jarvis", so it opens the HUD and starts the live session, which does the actual
     * recognition. [pendingWakeAt] debounces so one utterance triggers a single activation.
     */
    private fun onWakeWordDetected() {
        val now = SystemClock.elapsedRealtime()
        if (now - pendingWakeAt < WAKE_DEBOUNCE_MS) return
        pendingWakeAt = now

        Log.d("JarvisVoiceService", "Wake energy trigger detected, opening HUD")
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_START_LISTENING, true)
        }
        runCatching {
            startActivity(launchIntent)
        }.onFailure { Log.w("JarvisVoiceService", "Could not open HUD from background", it) }
    }

    private fun calculateRms(bytes: ByteArray, size: Int): Float {
        var sum = 0.0
        val samples = size / 2
        if (samples == 0) return 0f
        for (i in 0 until samples) {
            val s = (bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)
            sum += s * s
        }
        return (sqrt(sum / samples) / 32767.0).toFloat().coerceIn(0f, 1f)
    }

    override fun onDestroy() {
        isListening = false
        isAsleep = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "jarvis_voice_service_channel"
        const val EXTRA_START_LISTENING = "EXTRA_START_LISTENING"

        private const val SPEECH_ENERGY_THRESHOLD = 0.15f
        private const val WAKE_ENERGY_THRESHOLD = 0.45f
        private const val SLEEP_AFTER_MS = 120_000L
        private const val POLL_INTERVAL_MS = 30L
        private const val WAKE_POLL_INTERVAL_MS = 500L
        private const val WAKE_DEBOUNCE_MS = 3_000L

        @Volatile
        private var pendingWakeAt = 0L

        fun start(context: Context) {
            // Check before startForegroundService. Once the service is started this way,
            // Android 12+ requires startForeground() within ~5s or it kills the process with
            // ForegroundServiceDidNotStartInTimeException, so bailing out inside onCreate is
            // already too late.
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                Log.w(
                    "JarvisVoiceService",
                    "RECORD_AUDIO not granted; not starting background listener"
                )
                return
            }

            val intent = Intent(context, JarvisVoiceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java)
            context.stopService(intent)
        }
    }
}
