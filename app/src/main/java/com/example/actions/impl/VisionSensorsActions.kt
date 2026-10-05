package com.example.actions.impl

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition

class DeviceHealthAction(private val context: Context) : Action {

    override val name: String = "check_device_health"
    override val description: String = "Monitors device health metrics: battery level and temperature, RAM usage, storage availability, and network speed."
    override val parameters: Map<String, ParamDefinition> = emptyMap()

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        // Battery and Temperature
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, batteryFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = tempTenths / 10.0

        // RAM
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val usedRamGb = totalRamGb - availRamGb

        // Storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val availStorageGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)

        // Network
        val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connManager.activeNetwork
        val caps = activeNetwork?.let { connManager.getNetworkCapabilities(it) }
        // linkDownstreamBandwidthKbps is a link estimate, not a measured throughput.
        val downstreamMbps = ((caps?.linkDownstreamBandwidthKbps ?: 0) / 1000).coerceAtLeast(0)
        val netType = when {
            caps == null -> "No active connection"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular / Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }

        val spoken = "All systems operational, Sir. Battery is at $batteryPct% at %.1f°C. RAM is %.1f of %.1f GB. %.1f GB storage free. Connected via $netType with an estimated %d Mbps link."
            .format(tempCelsius, usedRamGb, totalRamGb, availStorageGb, downstreamMbps)

        val cardData = mapOf(
            "battery" to "$batteryPct%",
            "temperature" to "%.1f°C".format(tempCelsius),
            "ram" to "%.1f / %.1f GB".format(usedRamGb, totalRamGb),
            "storage" to "%.1f GB free".format(availStorageGb),
            "network" to "$netType ($downstreamMbps Mbps est.)"
        )

        return ActionResult(spokenResult = spoken, cardData = cardData)
    }
}

class VisionContextAction(private val context: Context) : Action {

    override val name: String = "inspect_visual_input"
    override val description: String = "Reports whether on-device visual analysis is available for a camera or screen-capture source. It does not return image content."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "source" to ParamDefinition(
            "string",
            "Visual stream source: 'camera' or 'screen'",
            required = true,
            enumValues = listOf("camera", "screen")
        )
    )

    /**
     * This used to return a fixed, invented description ("shows the user and their room")
     * without opening the camera, so the assistant confidently reported a scene it had never
     * seen. No CameraX frame provider is wired into the app, so there is nothing to analyse
     * yet; the honest behaviour is to say so instead of guessing.
     */
    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val source = (args["source"] as? String)?.lowercase() ?: "camera"

        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val spoken = when {
            source == "camera" && !cameraGranted ->
                "I don't have camera permission yet, so I can't see anything. Grant it in settings first."
            source == "camera" ->
                "On-device visual analysis isn't enabled in this build yet, so I can't describe what the camera sees."
            else ->
                "Screen capture analysis isn't enabled in this build yet, so I can't read what's on screen."
        }

        return ActionResult(
            spokenResult = spoken,
            cardData = mapOf(
                "source" to source,
                "status" to "unavailable",
                "camera_permission" to cameraGranted.toString()
            )
        )
    }
}
