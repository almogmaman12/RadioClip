package com.almog.radioclip.core

import java.util.Locale

/** Small helpers that turn numbers into text for the UI. */
object Format {
    fun bytes(size: Long): String {
        if (size < 1024) return "$size B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = size / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    fun relativeTime(then: Long, now: Long): String {
        val seconds = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            seconds < 10 -> "just now"
            seconds < 60 -> "$seconds s ago"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }

    /** Signal strength in words, from a BLE RSSI in dBm. */
    fun signalLabel(rssi: Int?): String = when {
        rssi == null -> ""
        rssi >= -60 -> "Strong signal"
        rssi >= -75 -> "Good signal"
        rssi >= -88 -> "Weak signal"
        else -> "Very weak signal"
    }

    /** Rounds an RSSI to 10 dB steps so the UI doesn't redraw for every tiny fluctuation. */
    fun quantizeRssi(rssi: Int): Int = Math.floorDiv(rssi, 10) * 10
}
