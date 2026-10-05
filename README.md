# JARVIS Mobile

**JARVIS Mobile** is a production-grade, native Android voice AI assistant built in Kotlin with Jetpack Compose, Material 3, Coroutines/Flow, Room, and DataStore.

It is a standalone mobile port of the desktop assistant **"Mark LV" by FatihMakes** (credited under the Creative Commons Attribution-NonCommercial 4.0 International license, CC BY-NC 4.0).

---

## ⚡ Key Capabilities & Architecture

- **Real-Time Gemini Live Audio Loop** (`GeminiLiveSession`):
  - WebSocket connection to the `v1beta` Live endpoint
    (`BidiGenerateContent`), authenticated with the `?key=` query parameter and the
    `x-goog-api-key` header.
  - **Live model ladder**: two rungs, `gemini-3.1-flash-live-preview` and the fallback
    `gemini-2.5-flash-native-audio-preview-12-2025`. The ladder steps down only on quota (429,
    5-minute cooldown) or lost model access (404/403, 6-hour cooldown), recovers automatically
    once a cooldown lapses, and treats an API-key error as fatal rather than retrying.
  - Streams 16 kHz mono 16-bit PCM from `AudioRecord` and outputs 24 kHz PCM via `AudioTrack`.
  - **setup-gated audio**: `AudioRecord` and `AudioTrack` start only after the server's
    `setupComplete`, and incoming microphone frames are dropped until then. Binary WebSocket
    frames are decoded, because Live frequently sends JSON as binary.
  - **Barge-in**: sustained user speech above the self-echo floor flushes local playback
    immediately without tearing down the playback loop.
  - **Session resumption**: `setup.sessionResumption` always carries an object (the stored
    handle, or `{}`), `sessionResumptionUpdate.newHandle` is persisted with AES-256-GCM, and a
    handle is dropped if the socket fails before `setupComplete`.
  - **Reconnect**: `onClosing`, `onClosed` and `onFailure` all schedule a reconnect with jittered
    exponential backoff (1s to 30s). A generation counter makes callbacks from stale sockets
    no-ops, and tool responses are dropped when the socket changed underneath them.
  - **Sliding-window compression** is enabled in setup so long sessions keep accepting turns.
  - Transcript fragments are joined per turn; voice changes reconnect using the stored handle.
  - Five selectable voices: Puck, Charon, Kore, Fenrir, Aoede.

- **Measured One-Shot Model Ladder**:
  - `GeminiClient` routes one-shot tasks (summaries, translation, code fixing, search analysis) through a measured priority ladder:
    1. `gemini-3.1-flash-lite-preview`
    2. `gemini-2.5-flash-lite`
    3. `gemini-2.5-flash`
    4. `gemini-3.5-flash`
  - Per-model cooldown timers: 5 minutes for HTTP 429, 30 minutes for 503/504, 6 hours for 404. All calls enforce timeouts $\ge$ 10 seconds.

- **Self-Describing Tool / Action Subsystem**:
  - Every action implements `Action` with typed parameters, Gemini-compliant schema generation, and exception-safe `execute()`.
  - Adding a new tool is a clean, single-file addition.
  - Bundled actions include:
    1. `open_app`: Fuzzy application matching via `PackageManager`.
    2. `set_volume`: Media, ring, alarm, notification volume with undo.
    3. `toggle_flashlight`: Torch control via `CameraManager` with undo.
    4. `manage_connectivity`: Internet and Bluetooth panels (explaining Android 10+ WiFi rules).
    5. `control_media`: Play/pause/next/previous playback dispatch.
    6. `set_do_not_disturb`: Interruption filter control.
    7. `set_brightness`: Screen brightness 0–100% with undo. Requires the `WRITE_SETTINGS`
       permission; if it is not granted, the system settings screen is opened instead of
       silently failing.
    8. `make_phone_call`: Contact/number call with safety approval.
    9. `send_sms`: Text composition with safety approval.
    10. `compose_message`: WhatsApp, Telegram, and Email deep links.
    11. `lookup_contact`: Address book search via `ContactsContract`.
    12. `set_alarm` & `set_timer`: Native clock alarms and timers.
    13. `create_reminder`: Persistent reminders surviving reboot via `WorkManager`.
    14. `calendar_event`: Read upcoming agenda and insert events.
    15. `navigate_to`: Turn-by-turn directions via Google Maps.
    16. `get_weather`: Live atmospheric forecast via Open-Meteo.
    17. `web_search`: Multi-mode search (news, research, price, compare).
    18. `open_browser`: URL navigation in default browser.
    19. `clipboard_assistant`: Summarise, translate, explain, or fix copied text.
    20. `file_operations`: Read, create, rename, delete files with undo & safety guard.
    21. `check_device_health`: Real-time battery, temp, RAM, storage, and network speed.
    22. `inspect_visual_input`: Camera & screen vision pipeline.
    23. `accessibility_control`: Tap, scroll, back, home, recents, and screen reading via `AccessibilityService`.
    24. `play_video`: Media3/ExoPlayer video feed with audio ducking.
    25. `remember_fact`: Commit persistent preferences to Room.
    26. `recall_memory`: Search offline memory.
    27. `plan_and_execute`: Autonomous multi-step goal chaining.

- **Local-Only Persistent Memory (Room Database)**:
  - Facts, identity tokens, preferences, and session recaps stored securely in an offline Room database.
  - Compact index injected dynamically into system prompt; remaining facts searchable via `recall_memory`.
  - Dedicated Memory Vault screen with full-text search and individual fact deletion.

- **Cryptographic Safety Guard & Undo Stack**:
  - Reversible actions push to a shared undo stack (`UndoStack`), reversed at any time by saying "undo".
  - Sensitive or irreversible operations (phone calls, SMS, WhatsApp sends, deleting files) require explicit user tap confirmation with a one-time cryptographic token generated by `SafetyGuard`.

- **Futuristic Holographic HUD UI**:
  - Center holographic animated avatar lip-syncing to voice audio amplitude and formant frequencies, showing state via gaze and rotation (listening, thinking, speaking, idle).
  - Real-time reactive waveform reflecting microphone input and assistant speech.
  - Scrolling transcript with dynamic telemetry cards (Weather, Hardware Diagnostics, Web Results, Video Player).
  - Quick command chips for instant voice-assisted queries.
  - Dynamic HUD theme recolorable via interactive Hue slider (0–360°).

- **Always-On Voice & Accessibility**:
  - `JarvisVoiceService`: Background microphone foreground service that sleeps after 2 minutes of silence to conserve battery.
  - `JarvisAccessibilityService`: Enables automated UI gestures and on-screen reading.
  - `JarvisOverlayService`: Floating HUD bubble (`SYSTEM_ALERT_WINDOW`).
  - `JarvisTileService`: Quick Settings tile for immediate voice listening.

---

## 🛠️ Build & Setup Instructions

### Prerequisites
- Android Studio Ladybug or newer.
- Android SDK 35, JDK 17 or JDK 21.
- Minimum supported device: Android 8.0 (API 26), target: Android 15 (API 35).

### API Key Configuration

The key is entered **in the app**, never at build time. There is no Secrets Gradle plugin and no
`.env` injection; a key baked into a build would ship inside the APK.

1. Grant the runtime permissions on first launch.
2. The `AUTHORIZE NEURAL CORE` prompt appears next, on first launch and on any later launch where
   no key is stored. It is modal and has no dismiss affordance: there is no "Later" button and
   `onDismissRequest` is a no-op, because every voice and chat feature is dead without a key and a
   dismissible prompt would leave the user on a HUD that silently does nothing. The key can be
   changed later in the **Settings Drawer**.
3. Create a key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).

Keys are stored with AES-256-GCM in `EncryptedSharedPreferences`. If `AndroidKeyStore` is
unavailable or the encrypted file is unreadable, `SecureStorage` **fails closed**: the unreadable
preferences are reset and secrets are kept in process memory only, never written to disk in
plaintext. Encrypted preferences are also excluded from cloud backup and device transfer, because
the keystore key does not exist on the receiving device.

### Permissions

| Permission | Used for |
| --- | --- |
| `RECORD_AUDIO` | Microphone capture and barge-in |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Gemini Live socket and one-shot calls |
| `WRITE_SETTINGS` | `set_brightness`; a special permission granted from system settings |
| `SYSTEM_ALERT_WINDOW` | Floating HUD overlay |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | `JarvisVoiceService` |
| `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` | Reminders, briefing, and boot recovery |

### Building

The Gradle wrapper is **incomplete**: `gradle/wrapper/gradle-wrapper.properties` is committed, but
`gradlew`, `gradlew.bat` and `gradle-wrapper.jar` are not. `./gradlew` therefore does not work as-is.
Locally, use Android Studio or a system Gradle 9.3.1 installation (the version AGP 9.1.1 requires):

```bash
gradle assembleDebug
gradle :app:testDebugUnitTest
```

### CI

`.github/workflows/debug-apk.yml` builds a **debug APK only** — it never assembles the release
variant and needs no keystore or secrets. It runs on every push to `main`/`master`, on every pull
request, and on manual dispatch.

Because `gradlew` is absent, the workflow does not use `./gradlew`. It installs Gradle 9.3.1
explicitly through `gradle/actions/setup-gradle` and invokes `gradle :app:assembleDebug` directly.
The finished APK is uploaded as the `jarvis-debug-apk` artifact. A separate `unit-tests` job runs
`:app:testDebugUnitTest`, isolated so a failing test cannot block the APK.

To switch back to `./gradlew`, generate the wrapper once with a local Gradle 9.x and commit all three
files:
`gradle wrapper --gradle-version 9.3.1`

The unit tests cover the Live wire format (`GeminiLiveProtocolFormatTest`), the Live model ladder
cooldowns and recovery (`LiveModelLadderTest`), the one-shot ladder and cooldowns
(`ModelLadderTest`), and fail-closed secret storage (`SecureStorageTest`).
