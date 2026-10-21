package com.almog.radioclip.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ChunkCodecTest {

    private fun randomBytes(size: Int, seed: Long = 1) = ByteArray(size).also { Random(seed).nextBytes(it) }

    @Test
    fun roundTripForManySizesAndChunkSizes() {
        for (size in listOf(0, 1, 11, 12, 13, 499, 500, 501, 20_000)) {
            for (chunk in listOf(1, 12, 20, 500)) {
                val data = randomBytes(size, size * 31L + chunk)
                val packets = ChunkCodec.encode(42, data, chunk)
                val reassembler = ChunkReassembler()
                var result: ByteArray? = null
                packets.forEachIndexed { i, p ->
                    val out = reassembler.accept(p)
                    if (i < packets.size - 1) assertNull(out) else result = out
                }
                assertArrayEquals("size=$size chunk=$chunk", data, result)
            }
        }
    }

    @Test
    fun packetsNeverExceedTheAttPayload() {
        val mtu = 23
        val max = ChunkCodec.maxChunkData(mtu)
        val packets = ChunkCodec.encode(1, randomBytes(1000), max)
        assertTrue(packets.all { it.size <= mtu - 3 })
    }

    @Test
    fun defaultMtuLeavesRoomForTheHeader() {
        assertEquals(12, ChunkCodec.maxChunkData(23))
        assertEquals(500, ChunkCodec.maxChunkData(512))
        assertEquals(1, ChunkCodec.maxChunkData(5))
    }

    @Test
    fun lostChunkNeverProducesATruncatedMessage() {
        val packets = ChunkCodec.encode(7, randomBytes(100), 10)
        val reassembler = ChunkReassembler()
        packets.forEachIndexed { i, p ->
            if (i != 4) assertNull(reassembler.accept(p)) // chunk 4 is lost
        }
    }

    @Test
    fun outOfOrderChunksAreDiscarded() {
        val packets = ChunkCodec.encode(7, randomBytes(100), 10)
        val reassembler = ChunkReassembler()
        assertNull(reassembler.accept(packets[0]))
        assertNull(reassembler.accept(packets[2]))
        // the message is gone; the remaining chunks must not complete it either
        packets.drop(1).forEach { assertNull(reassembler.accept(it)) }
    }

    @Test
    fun recoversAfterAFailedMessage() {
        val reassembler = ChunkReassembler()
        val broken = ChunkCodec.encode(1, randomBytes(50), 10)
        reassembler.accept(broken[0])
        reassembler.accept(broken[3])

        val data = randomBytes(30, 9)
        var result: ByteArray? = null
        ChunkCodec.encode(2, data, 10).forEach { result = reassembler.accept(it) ?: result }
        assertArrayEquals(data, result)
    }

    @Test
    fun staleMessagesAreEvicted() {
        var now = 0L
        val reassembler = ChunkReassembler(staleAfterMs = 1_000, clock = { now })
        val packets = ChunkCodec.encode(3, randomBytes(30), 10)
        assertNull(reassembler.accept(packets[0]))
        now = 5_000
        assertNull(reassembler.accept(packets[1]))
        assertNull(reassembler.accept(packets[2]))
    }

    @Test
    fun oversizedMessagesAreRejected() {
        val reassembler = ChunkReassembler(maxMessageBytes = 100)
        val packets = ChunkCodec.encode(5, randomBytes(500), 50)
        assertTrue(packets.all { reassembler.accept(it) == null })
    }

    @Test
    fun garbageIsIgnored() {
        val reassembler = ChunkReassembler()
        assertNull(reassembler.accept(ByteArray(0)))
        assertNull(reassembler.accept(ByteArray(7)))
        assertNull(reassembler.accept(ByteArray(8)))                    // total = 0
        assertNull(reassembler.accept(byteArrayOf(0, 0, 0, 1, 0, 2, 0, 5, 9))) // index >= total
    }

    @Test
    fun interleavedMessagesStaySeparate() {
        val a = randomBytes(40, 1)
        val b = randomBytes(40, 2)
        val pa = ChunkCodec.encode(10, a, 10)
        val pb = ChunkCodec.encode(11, b, 10)
        val reassembler = ChunkReassembler()
        var ra: ByteArray? = null
        var rb: ByteArray? = null
        for (i in pa.indices) {
            reassembler.accept(pa[i])?.let { ra = it }
            reassembler.accept(pb[i])?.let { rb = it }
        }
        assertArrayEquals(a, ra)
        assertArrayEquals(b, rb)
    }
}
