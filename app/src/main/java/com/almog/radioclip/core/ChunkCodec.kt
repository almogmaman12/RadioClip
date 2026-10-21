package com.almog.radioclip.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Splits a message into BLE-sized packets and puts it back together.
 *
 * Packet layout: `[4-byte message id][2-byte total chunks][2-byte chunk index][data]`.
 */
object ChunkCodec {
    const val HEADER_SIZE = 8
    const val MAX_MESSAGE_BYTES = 64 * 1024
    private const val ATT_OVERHEAD = 3
    private const val MAX_CHUNKS = 0xFFFF

    /** Largest data slice that fits in one packet for a negotiated ATT MTU. */
    fun maxChunkData(mtu: Int, cap: Int = 500): Int =
        (mtu - ATT_OVERHEAD - HEADER_SIZE).coerceIn(1, cap)

    fun encode(messageId: Int, data: ByteArray, maxChunkData: Int): List<ByteArray> {
        require(maxChunkData > 0) { "maxChunkData must be positive" }
        val total = ((data.size + maxChunkData - 1) / maxChunkData).coerceAtLeast(1)
        require(total <= MAX_CHUNKS) { "Message too large for ${maxChunkData}-byte chunks" }

        return List(total) { index ->
            val start = index * maxChunkData
            val end = minOf(start + maxChunkData, data.size)
            val length = (end - start).coerceAtLeast(0)
            ByteBuffer.allocate(HEADER_SIZE + length).apply {
                putInt(messageId)
                putShort(total.toShort())
                putShort(index.toShort())
                if (length > 0) put(data, start, length)
            }.array()
        }
    }
}

/**
 * Reassembles chunks coming from ONE sender. Chunks must arrive in order; anything out of order,
 * oversized or stale is discarded so a lost packet can never produce a truncated message.
 */
class ChunkReassembler(
    private val maxMessageBytes: Int = ChunkCodec.MAX_MESSAGE_BYTES,
    private val staleAfterMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Partial(val total: Int, var next: Int, var updatedAt: Long) {
        val out = ByteArrayOutputStream()
    }

    private val partials = HashMap<Int, Partial>()

    /** Returns the complete message when [packet] was its last chunk, otherwise null. */
    @Synchronized
    fun accept(packet: ByteArray): ByteArray? {
        if (packet.size < ChunkCodec.HEADER_SIZE) return null
        val now = clock()
        partials.entries.removeAll { now - it.value.updatedAt > staleAfterMs }

        val buffer = ByteBuffer.wrap(packet)
        val id = buffer.int
        val total = buffer.short.toInt() and 0xFFFF
        val index = buffer.short.toInt() and 0xFFFF
        val dataLength = packet.size - ChunkCodec.HEADER_SIZE
        if (total == 0 || index >= total) return null

        val partial: Partial
        if (index == 0) {
            if (partials.size >= MAX_IN_FLIGHT) partials.clear()
            partial = Partial(total, 0, now)
            partials[id] = partial
        } else {
            val existing = partials[id]
            if (existing == null || existing.total != total || existing.next != index) {
                partials.remove(id)
                return null
            }
            partial = existing
        }

        if (partial.out.size() + dataLength > maxMessageBytes) {
            partials.remove(id)
            return null
        }

        partial.out.write(packet, ChunkCodec.HEADER_SIZE, dataLength)
        partial.next++
        partial.updatedAt = now

        if (partial.next == partial.total) {
            partials.remove(id)
            return partial.out.toByteArray()
        }
        return null
    }

    private companion object {
        const val MAX_IN_FLIGHT = 8
    }
}
