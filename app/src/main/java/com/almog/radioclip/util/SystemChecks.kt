package com.almog.radioclip.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Permissions the app asks for. */
object AppPermissions {
    fun required(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    /** Missing permissions that actually stop the app from working (notifications don't). */
    fun missingCritical(context: Context): List<String> = missing(context).filter {
        it != Manifest.permission.POST_NOTIFICATIONS &&
            !(it == Manifest.permission.ACCESS_FINE_LOCATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    }
}

/** A problem on the phone that stops RadioClip from working, with a button that helps fix it. */
data class SystemIssue(
    val message: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

object SystemChecks {

    fun issues(context: Context, wifiDirectEnabled: Boolean, requestPermissions: () -> Unit): List<SystemIssue> {
        val list = mutableListOf<SystemIssue>()

        if (AppPermissions.missingCritical(context).isNotEmpty()) {
            list.add(SystemIssue("RadioClip needs a few permissions to find nearby devices.", "Allow", requestPermissions))
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            list.add(SystemIssue("This device has no Bluetooth."))
        } else if (!adapter.isEnabled) {
            list.add(
                SystemIssue(
                    "Bluetooth is off. RadioClip uses it to find your other devices.",
                    "Open settings"
                ) { open(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
            )
        }

        if (wifiDirectEnabled) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null && !wifi.isWifiEnabled) {
                list.add(
                    SystemIssue(
                        "Wi-Fi is off. Sending files uses Wi-Fi Direct, which needs Wi-Fi turned on (it does not need internet).",
                        "Open settings"
                    ) { open(context, Settings.ACTION_WIFI_SETTINGS) }
                )
            }
        }

        // Up to Android 12 the system itself requires the Location switch for Bluetooth and Wi-Fi Direct scanning.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val location = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (location != null && !location.isLocationEnabled) {
                list.add(
                    SystemIssue(
                        "Location is off. On this Android version it must be on to find nearby devices (RadioClip never uses your location).",
                        "Open settings"
                    ) { open(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
                )
            }
        }
        return list
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBatterySettings(context: Context) = open(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private fun open(context: Context, action: String) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Some phones don't have every settings screen; fall back to the main settings page.
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
