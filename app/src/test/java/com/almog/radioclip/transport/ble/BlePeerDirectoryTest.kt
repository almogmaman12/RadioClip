package com.almog.radioclip.transport.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BlePeerDirectoryTest {
    private val tablet = BleDiscoveryIdentity.newSessionUuid()
    private val tablet2 = BleDiscoveryIdentity.newSessionUuid()
    private val ownId = BleDiscoveryIdentity.newSessionUuid()

    @Test
    fun rotatingAddressesOfSameTabletProduceOneEntry() {
        val directory = BlePeerDirectory()
        directory.observe(tablet, "65:7B:7A:CE:DD:4C", "Lenovo Idea Tab Pro", 1000)
        directory.observe(tablet, "7C:7B:BC:43:03:71", "Lenovo Idea Tab Pro", 2000)
        directory.observe(tablet, "77:09:A4:3C:9F:F1", "Lenovo Idea Tab Pro", 3000)

        val visible = directory.snapshot(3000)
        assertEquals(1, visible.size)
        assertEquals(tablet, visible.single().identity)
        assertEquals("77:09:A4:3C:9F:F1", visible.single().address)
        assertEquals(3000, visible.single().lastSeen)
        assertNull(directory.identityForAddress("65:7B:7A:CE:DD:4C", 3000))
    }

    @Test
    fun twoTabletsWithSameNameRemainSeparate() {
        val directory = BlePeerDirectory()
        directory.observe(tablet, "A0", "Lenovo Idea Tab Pro", 0)
        directory.observe(tablet2, "B0", "Lenovo Idea Tab Pro", 0)
        assertEquals(2, directory.snapshot(0).size)
    }

    @Test
    fun stalePeersArePrunedAndCannotBeConnectedByOldIdentity() {
        val directory = BlePeerDirectory(expiryMs = 5_000)
        directory.observe(tablet, "A0", "Lenovo", 100)
        assertEquals("A0", directory.latestAddress(tablet, 5_100))
        assertNull(directory.latestAddress(tablet, 5_101))
        assertTrue(directory.snapshot(5_101).isEmpty())
    }

    @Test
    fun memoryUseAndVisibleListAreBounded() {
        val directory = BlePeerDirectory(expiryMs = 100_000, maxPeers = 3)
        repeat(20) {
            directory.observe(UUID(7, it.toLong()), "ADDR$it", "Tablet $it", it.toLong())
        }
        assertEquals(3, directory.snapshot(20).size)
        assertNull(directory.latestAddress(UUID(7, 0), 20))
        assertEquals("ADDR19", directory.snapshot(20).first().address)
    }

    @Test
    fun onlyTheIdentityUuidMatchesTheRadioClipPrefix() {
        val advertised = listOf(UUID.fromString("C2E7D459-487A-4572-8492-6B3DFBB50316"), tablet)
        assertEquals(tablet, BleDiscoveryIdentity.fromAdvertisedUuids(advertised, ownId))
        assertNull(BleDiscoveryIdentity.fromAdvertisedUuids(listOf(ownId), ownId))
        assertNull(BleDiscoveryIdentity.fromAdvertisedUuids(null, ownId))
        assertFalse(tablet == tablet2)
    }
}
