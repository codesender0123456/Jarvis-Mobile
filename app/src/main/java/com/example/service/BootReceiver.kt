package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.JarvisApp
import com.example.core.config.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // goAsync(): WorkManager + DataStore access can outlast the 10s broadcast window.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Pending ReminderWorker requests are restored by WorkManager itself, but the
                // briefing must be re-armed explicitly because it is scheduled from code.
                val app = appContext as? com.example.JarvisApp
                app?.rescheduleDailyBriefing() ?: DailyBriefingWorker.scheduleDaily(appContext)

                if (AppPreferences(appContext).settingsFlowOnce().autoStartOnBoot) {
                    JarvisVoiceService.start(appContext)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Post-boot re-arming failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
