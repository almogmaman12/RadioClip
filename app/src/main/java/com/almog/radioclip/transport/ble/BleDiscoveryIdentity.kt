package com.almog.radioclip.transport.ble

import java.security.SecureRandom
import java.util.UUID

/**
 * A transient instance ID carried in BLE scan-response data.
 *
 * Android can rotate the address used for BLE advertising. RadioClip keeps one
 * stable identity per running advertiser session instead of treating every
 * randomized address as another device. The identity is intentionally NOT
 * persisted across service restarts to limit long-term Bluetooth tracking.
 */
internal object BleDiscoveryIdentity {
    // The prefix identifies a RadioClip ephemeral identity service UUID.
    // A second custom 128-bit UUID fits inside a legacy BLE scan response.
    val PREFIX_MSB: Long = UUID.fromString("C2E7D45B-487A-4572-0000-000000000000").mostSignificantBits

    fun newSessionUuid(): UUID = UUID(PREFIX_MSB, SecureRandom().nextLong())

    fun fromAdvertisedUuids(uuids: List<UUID>?, localUuid: UUID): UUID? =
        uuids?.firstOrNull { it.mostSignificantBits == PREFIX_MSB && it != localUuid }
}

/**
 * Keeps only recent unique BLE *identity* UUIDs, not rotating MAC addresses.
 * Each observation refreshes the latest address for a single discovered peer.
 *
 * Pure Kotlin (no Android APIs), allowing deterministic JVM regression tests.
 */
internal class BlePeerDirectory(
    private val expiryMs: Long = 45_000L,
    private val maxPeers: Int = 24
) {
    data class Peer(
        val identity: UUID,
        val address: String,
        val name: String,
        val lastSeen: Long
    )

    private val entries = LinkedHashMap<UUID, Peer>()

    @Synchronized
    fun observe(identity: UUID, address: String, name: String, now: Long): List<Peer> {
        if (address.isBlank()) return snapshot(now)
        pruneInternal(now)
        entries[identity] = Peer(identity, address, name, now)
        trimInternal()
        return entries.values.sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun snapshot(now: Long): List<Peer> {
        pruneInternal(now)
        return entries.values.sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun latestAddress(id: UUID, now: Long): String? {
        pruneInternal(now)
        return entries[id]?.address
    }

    @Synchronized
    fun identityForAddress(address: String, now: Long): UUID? {
        pruneInternal(now)
        return entries.values.firstOrNull { it.address == address }?.identity
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun pruneInternal(now: Long) {
        entries.entries.removeAll { now - it.value.lastSeen > expiryMs }
    }

    private fun trimInternal() {
        while (entries.size > maxPeers) {
            val oldest = entries.values.minByOrNull { it.lastSeen } ?: break
            entries.remove(oldest.identity)
        }
    }
}
