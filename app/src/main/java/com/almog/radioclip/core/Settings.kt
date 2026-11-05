package com.almog.radioclip.core

/** How aggressively Bluetooth advertises and scans: faster discovery costs more battery. */
enum class BleMode { LOW_POWER, BALANCED, LOW_LATENCY }

/** All user-changeable options. Plain data so it can be tested without Android. */
data class Settings(
    /** Name other devices see. Blank means "use the phone model". */
    val deviceName: String = "",

    // Clipboard
    val clipboardAutoSend: Boolean = true,
    val clipboardAutoApply: Boolean = true,
    val clipboardSkipSensitive: Boolean = true,
    val clipboardHidePreview: Boolean = false,
    val notifyOnText: Boolean = false,

    // Bluetooth
    val bleMode: BleMode = BleMode.BALANCED,

    // Wi-Fi Direct
    val useWifiDirect: Boolean = true,
    val wifiShowAll: Boolean = true,

    // Files and app
    val autoDeleteDays: Int = 0,
    val startOnBoot: Boolean = false
) {
    /** The name to announce to peers: trimmed, limited in length, never empty. */
    fun announcedName(fallback: String): String =
        deviceName.trim().take(MAX_NAME_LENGTH).ifBlank { fallback.trim().take(MAX_NAME_LENGTH) }
            .ifBlank { "Android" }

    companion object {
        const val MAX_NAME_LENGTH = 32
        val DELETE_AFTER_DAYS_OPTIONS = listOf(0, 1, 7, 30)
    }
}
