package com.almog.radioclip.core

import com.almog.radioclip.core.Advertisement.PeerClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisementTest {
    private val keyA = Crypto.deriveKey("shared pairing code")
    private val keyB = Crypto.deriveKey("some other pairing code")

    @Test
    fun encodedAdvertisementFitsTheSpaceAvailable() {
        // 31-byte legacy limit - 3 (flags) - 18 (128-bit service uuid) - 2 (length/type) - 2 (company id)
        assertEquals(6, Advertisement.encode(keyA, 1).size)
        assertTrue(Advertisement.DATA_SIZE <= 31 - 3 - 18 - 4)
    }

    @Test
    fun roundTrip() {
        val info = Advertisement.decode(Advertisement.encode(keyA, 0xCAFEBABE.toInt()))!!
        assertEquals(0xCAFEBABE.toInt(), info.nodeId)
        assertArrayEquals(Advertisement.tagFor(keyA), info.tag)
    }

    @Test
    fun wrongSizesDecodeToNull() {
        assertNull(Advertisement.decode(null))
        assertNull(Advertisement.decode(ByteArray(5)))
        assertNull(Advertisement.decode(ByteArray(7)))
    }

    @Test
    fun sameCodeIsSameGroup() {
        val remote = Advertisement.decode(Advertisement.encode(keyA, 5))
        assertEquals(PeerClass.SAME_GROUP, Advertisement.classify(keyA, remote))
    }

    @Test
    fun differentCodeIsOtherGroup() {
        val remote = Advertisement.decode(Advertisement.encode(keyB, 5))
        assertEquals(PeerClass.OTHER_GROUP, Advertisement.classify(keyA, remote))
    }

    @Test
    fun unpairedRemoteIsRecognised() {
        val remote = Advertisement.decode(Advertisement.encode(null, 5))
        assertNotNull(remote)
        assertTrue(remote!!.isUnpaired)
        assertEquals(PeerClass.UNPAIRED_REMOTE, Advertisement.classify(keyA, remote))
    }

    @Test
    fun missingTagMeansRelyOnEncryption() {
        assertEquals(PeerClass.NO_TAG, Advertisement.classify(keyA, null))
    }

    @Test
    fun exactlyOneSideInitiates() {
        val ids = listOf(0, 1, 100, Int.MAX_VALUE, Int.MIN_VALUE, -1, 0xCAFEBABE.toInt())
        for (a in ids) for (b in ids) {
            if (a == b) continue
            assertTrue(
                "ids $a and $b: exactly one side must initiate",
                Advertisement.shouldInitiate(a, b) != Advertisement.shouldInitiate(b, a)
            )
        }
    }

    @Test
    fun comparisonIsUnsigned() {
        // 0xFFFFFFFF is the biggest id even though it is negative as a signed Int
        assertTrue(Advertisement.shouldInitiate(-1, 5))
        assertFalse(Advertisement.shouldInitiate(5, -1))
    }

    @Test
    fun equalIdsLetBothTry() {
        assertTrue(Advertisement.shouldInitiate(7, 7))
    }
}
