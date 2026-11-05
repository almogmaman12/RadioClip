package com.almog.radioclip.core

/**
 * Wi-Fi Direct devices report a "primary device type" like `10-0050F204-5`; the number before the
 * first dash is the Wi-Fi Simple Config category (1 computer, 3 printer, 7 display/TV, 10 phone, ...).
 */
object WifiDeviceTypes {
    private val NON_PHONE_CATEGORIES = setOf(2, 3, 4, 5, 6, 7, 8, 9, 11)

    /** True unless the device clearly is a TV, printer, camera, speaker, etc. Unknown types are kept. */
    fun isPhoneLike(primaryType: String?): Boolean {
        val category = primaryType?.trim()?.substringBefore('-')?.toIntOrNull() ?: return true
        return category !in NON_PHONE_CATEGORIES
    }
}
