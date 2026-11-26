package com.almog.radioclip.transport.wifi

/**
 * Wi-Fi Direct discovery must filter by a RadioClip DNS-SD advertisement.
 * Device names, vendors, and general P2P discovery are not reliable app identity.
 */
internal object RadioClipServiceIdentity {
    const val INSTANCE = "RadioClip"
    const val TYPE = "_radioclip._tcp"
    const val PROTOCOL = "2"

    fun matchesService(instance: String, registrationType: String): Boolean {
        val normalized = registrationType.lowercase()
            .removeSuffix(".local.")
            .trimEnd('.')
        return instance.equals(INSTANCE, ignoreCase = true) &&
            normalized == TYPE
    }

    fun matchesRecord(domain: String, record: Map<String, String>): Boolean {
        val normalized = domain.lowercase().trimEnd('.')
        return normalized == "${INSTANCE.lowercase()}.$TYPE.local" &&
            record["protocol"] == PROTOCOL
    }
}
