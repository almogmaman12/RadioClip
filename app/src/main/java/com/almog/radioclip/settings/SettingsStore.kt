package com.almog.radioclip.settings

import android.content.Context
import android.content.SharedPreferences
import com.almog.radioclip.core.BleMode
import com.almog.radioclip.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** Saves [Settings] in app-private storage and shares one live copy between the UI and the service. */
class SettingsStore private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("radioclip_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Random id of this installation, used so only one of two nearby phones opens the Bluetooth link. */
    val nodeId: Int = prefs.getInt(KEY_NODE_ID, 0).let { existing ->
        if (prefs.contains(KEY_NODE_ID)) existing
        else SecureRandom().nextInt().also { prefs.edit().putInt(KEY_NODE_ID, it).apply() }
    }

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        save(updated)
        _settings.value = updated
    }

    private fun load(): Settings {
        val defaults = Settings()
        return Settings(
            deviceName = prefs.getString("deviceName", defaults.deviceName) ?: defaults.deviceName,
            clipboardAutoSend = prefs.getBoolean("clipboardAutoSend", defaults.clipboardAutoSend),
            clipboardAutoApply = prefs.getBoolean("clipboardAutoApply", defaults.clipboardAutoApply),
            clipboardSkipSensitive = prefs.getBoolean("clipboardSkipSensitive", defaults.clipboardSkipSensitive),
            clipboardHidePreview = prefs.getBoolean("clipboardHidePreview", defaults.clipboardHidePreview),
            notifyOnText = prefs.getBoolean("notifyOnText", defaults.notifyOnText),
            bleMode = runCatching { BleMode.valueOf(prefs.getString("bleMode", null) ?: "") }
                .getOrDefault(defaults.bleMode),
            useWifiDirect = prefs.getBoolean("useWifiDirect", defaults.useWifiDirect),
            wifiShowAll = prefs.getBoolean("wifiShowAll", defaults.wifiShowAll),
            autoDeleteDays = prefs.getInt("autoDeleteDays", defaults.autoDeleteDays),
            startOnBoot = prefs.getBoolean("startOnBoot", defaults.startOnBoot)
        )
    }

    private fun save(s: Settings) {
        prefs.edit()
            .putString("deviceName", s.deviceName)
            .putBoolean("clipboardAutoSend", s.clipboardAutoSend)
            .putBoolean("clipboardAutoApply", s.clipboardAutoApply)
            .putBoolean("clipboardSkipSensitive", s.clipboardSkipSensitive)
            .putBoolean("clipboardHidePreview", s.clipboardHidePreview)
            .putBoolean("notifyOnText", s.notifyOnText)
            .putString("bleMode", s.bleMode.name)
            .putBoolean("useWifiDirect", s.useWifiDirect)
            .putBoolean("wifiShowAll", s.wifiShowAll)
            .putInt("autoDeleteDays", s.autoDeleteDays)
            .putBoolean("startOnBoot", s.startOnBoot)
            .apply()
    }

    companion object {
        private const val KEY_NODE_ID = "nodeId"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
