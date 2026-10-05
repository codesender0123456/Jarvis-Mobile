package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.actions.ActionRegistry
import com.example.actions.SafetyGuard
import com.example.actions.UndoAction
import com.example.actions.UndoStack
import com.example.actions.impl.AccessibilityControlAction
import com.example.actions.impl.AgentModeAction
import com.example.actions.impl.AppLauncherAction
import com.example.actions.impl.BrightnessControlAction
import com.example.actions.impl.BrowserControlAction
import com.example.actions.impl.CalendarActions
import com.example.actions.impl.ClipboardAssistantAction
import com.example.actions.impl.ContactsLookupAction
import com.example.actions.impl.CreateReminderAction
import com.example.actions.impl.DeviceHealthAction
import com.example.actions.impl.DoNotDisturbAction
import com.example.actions.impl.FileOperationsActions
import com.example.actions.impl.FlashlightControlAction
import com.example.actions.impl.MakePhoneCallAction
import com.example.actions.impl.MediaPlaybackControlAction
import com.example.actions.impl.MediaVideoAction
import com.example.actions.impl.NavigationAction
import com.example.actions.impl.NetworkPanelsAction
import com.example.actions.impl.RecallMemoryAction
import com.example.actions.impl.RememberFactAction
import com.example.actions.impl.SendMessagingAppAction
import com.example.actions.impl.SendSmsAction
import com.example.actions.impl.SetAlarmAction
import com.example.actions.impl.SetTimerAction
import com.example.actions.impl.VideoPlayerController
import com.example.actions.impl.VisionContextAction
import com.example.actions.impl.VolumeControlAction
import com.example.actions.impl.WeatherAction
import com.example.actions.impl.WebSearchAction
import com.example.core.config.AppPreferences
import com.example.core.config.SecureStorage
import com.example.core.gemini.AudioEngine
import com.example.core.gemini.GeminiClient
import com.example.core.gemini.GeminiLiveSession
import com.example.memory.MemoryDatabase
import com.example.memory.MemoryRepository
import com.example.service.DailyBriefingWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class JarvisApp : Application() {

    lateinit var secureStorage: SecureStorage
    lateinit var appPreferences: AppPreferences
    lateinit var memoryRepository: MemoryRepository
    lateinit var undoStack: UndoStack
    lateinit var safetyGuard: SafetyGuard
    lateinit var actionRegistry: ActionRegistry
    lateinit var okHttpClient: OkHttpClient
    lateinit var geminiClient: GeminiClient
    lateinit var audioEngine: AudioEngine
    lateinit var liveSession: GeminiLiveSession
    lateinit var videoPlayerController: VideoPlayerController

    override fun onCreate() {
        super.onCreate()

        // 1. Storage & Config
        secureStorage = SecureStorage(this)
        appPreferences = AppPreferences(this)

        // The Gemini API key is deliberately NOT injected here. It used to be seeded from
        // .env via a removed secrets plugin, which baked the key into the APK and silently
        // overrode the user's own setting. The user now supplies it on first launch and it
        // lives only in EncryptedSharedPreferences.

        // 2. Memory Database
        val memoryDb = MemoryDatabase.getInstance(this)
        memoryRepository = MemoryRepository(memoryDb.memoryDao())

        // 3. Safety & Undo
        undoStack = UndoStack()
        safetyGuard = SafetyGuard()

        // 4. Networking & AI Clients
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        geminiClient = GeminiClient(secureStorage, okHttpClient)
        audioEngine = AudioEngine(this)
        videoPlayerController = VideoPlayerController()

        // 5. Actions Registry & Bundled Tools Auto-Registration
        actionRegistry = ActionRegistry(undoStack, safetyGuard)
        registerBundledActions()

        // 6. Gemini Live Session
        liveSession = GeminiLiveSession(secureStorage, actionRegistry, audioEngine, okHttpClient)

        // 7. Notification Channels & Background Tasks
        createNotificationChannels()
        DailyBriefingWorker.scheduleDaily(this)
    }

    private fun registerBundledActions() {
        // App Launching
        actionRegistry.register(AppLauncherAction(this))

        // Device Controls
        actionRegistry.register(VolumeControlAction(this, undoStack))
        actionRegistry.register(FlashlightControlAction(this, undoStack))
        actionRegistry.register(NetworkPanelsAction(this))
        actionRegistry.register(MediaPlaybackControlAction(this))
        actionRegistry.register(BrightnessControlAction(this, undoStack))
        actionRegistry.register(DoNotDisturbAction(this))

        // Communication
        actionRegistry.register(MakePhoneCallAction(this, safetyGuard))
        actionRegistry.register(SendSmsAction(this, safetyGuard))
        actionRegistry.register(SendMessagingAppAction(this))
        actionRegistry.register(ContactsLookupAction(this))

        // Alarms, Timers, Reminders
        actionRegistry.register(SetAlarmAction(this))
        actionRegistry.register(SetTimerAction(this))
        actionRegistry.register(CreateReminderAction(this))

        // Calendar & Navigation
        actionRegistry.register(CalendarActions(this))
        actionRegistry.register(NavigationAction(this))

        // Weather & Web Search
        actionRegistry.register(WeatherAction(okHttpClient))
        actionRegistry.register(WebSearchAction(okHttpClient, this))
        actionRegistry.register(BrowserControlAction(this))

        // Clipboard & Files
        actionRegistry.register(ClipboardAssistantAction(this, geminiClient))
        actionRegistry.register(FileOperationsActions(this, geminiClient, undoStack, safetyGuard))

        // Vision & Sensors
        actionRegistry.register(DeviceHealthAction(this))
        actionRegistry.register(VisionContextAction(this))

        // Accessibility & Media Video
        actionRegistry.register(AccessibilityControlAction())
        actionRegistry.register(MediaVideoAction(videoPlayerController))

        // Memory Actions
        actionRegistry.register(RememberFactAction(memoryRepository))
        actionRegistry.register(RecallMemoryAction(memoryRepository))

        // Multi-step Agent Mode
        actionRegistry.register(AgentModeAction(geminiClient))

        // Undo (registered last so its description is not shadowed by a same-named tool)
        actionRegistry.register(UndoAction(undoStack))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val voiceChannel = NotificationChannel(
                "jarvis_voice_service_channel",
                "JARVIS Foreground Listener",
                NotificationManager.IMPORTANCE_LOW
            )
            val reminderChannel = NotificationChannel(
                "jarvis_reminders_channel",
                "JARVIS Reminders & Alarms",
                NotificationManager.IMPORTANCE_HIGH
            )
            val briefingChannel = NotificationChannel(
                "jarvis_briefing_channel",
                "JARVIS Morning Briefing",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            manager.createNotificationChannel(voiceChannel)
            manager.createNotificationChannel(reminderChannel)
            manager.createNotificationChannel(briefingChannel)
        }
    }

    /** Restarts the morning briefing if boot-time recovery kicked in. */
    fun rescheduleDailyBriefing() {
        DailyBriefingWorker.scheduleDaily(this)
    }
}
