package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.ui.components.ApiKeyDialog
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.HudScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.JarvisTheme
import com.example.ui.viewmodel.JarvisViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startListeningImmediately = intent?.getBooleanExtra("EXTRA_START_LISTENING", false) ?: false

        setContent {
            val userSettings by viewModel.userSettings.collectAsState()
            val currentScreen by viewModel.currentScreen.collectAsState()
            val apiKeyConfigured by viewModel.apiKeyConfigured.collectAsState()
            val savingApiKey by viewModel.savingApiKey.collectAsState()
            val apiKeyStatusLoaded by viewModel.apiKeyStatusLoaded.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.refreshApiKeyStatus()
            }

            // Shown only after permissions are handled: prompting first interrupted onboarding,
            // and nothing works without the key anyway.
            //
            // Purely a function of stored state. There is deliberately no "postponed" flag: a
            // flag meant a user could dismiss the prompt and still sit in an app whose every AI
            // feature was dead, until the next cold launch happened to bring it back.
            val showApiKeyPrompt = apiKeyStatusLoaded &&
                userSettings.hasCompletedOnboarding &&
                !apiKeyConfigured

            LaunchedEffect(startListeningImmediately) {
                if (startListeningImmediately) {
                    viewModel.connectLiveSession()
                }
            }

            JarvisTheme(primaryHue = userSettings.themeHue) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding()
                ) {
                    when (currentScreen) {
                        "onboarding" -> {
                            OnboardingScreen(
                                onComplete = { viewModel.completeOnboarding() }
                            )
                        }
                        "memory" -> {
                            val memories by viewModel.memories.collectAsState()
                            BackHandler { viewModel.navigateTo("hud") }
                            MemoryScreen(
                                memories = memories,
                                onBack = { viewModel.navigateTo("hud") },
                                onDeleteMemory = { viewModel.deleteMemory(it) },
                                onAddMemory = { key, fact, cat -> viewModel.addMemory(key, fact, cat) }
                            )
                        }
                        "about" -> {
                            BackHandler { viewModel.navigateTo("hud") }
                            AboutScreen(
                                onBack = { viewModel.navigateTo("hud") }
                            )
                        }
                        else -> {
                            HudScreen(
                                viewModel = viewModel,
                                onNavigateToMemory = { viewModel.navigateTo("memory") },
                                onNavigateToAbout = { viewModel.navigateTo("about") },
                                onOpenPermissions = { viewModel.navigateTo("onboarding") }
                            )
                        }
                    }

                    if (showApiKeyPrompt) {
                        ApiKeyDialog(
                            isFirstRun = !userSettings.hasCompletedOnboarding,
                            isSaving = savingApiKey,
                            onSubmit = { viewModel.saveApiKey(it) }
                        )
                    }
                }
            }
        }
    }
}
