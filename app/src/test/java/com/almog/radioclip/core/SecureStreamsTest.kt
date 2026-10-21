package com.almog.radioclip.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Random

class SecureStreamsTest {
    private val key = Crypto.deriveKey("stream test code")

    private fun encrypted(block: (DataOutputStream) -> Unit): ByteArray {
        val wire = ByteArrayOutputStream()
        val out = DataOutputStream(SecureOutputStream(wire, key))
        block(out)
        out.flush()
        return wire.toByteArray()
    }

    private fun reader(wire: ByteArray, k: ByteArray = key) =
        DataInputStream(SecureInputStream(ByteArrayInputStream(wire), k))

    @Test
    fun structuredMessageRoundTrips() {
        val wire = encrypted {
            it.writeByte(2)
            it.writeUTF("id-1")
            it.writeLong(1234567890123L)
            it.writeInt(42)
        }
        val input = reader(wire)
        assertEquals(2, input.readByte().toInt())
        assertEquals("id-1", input.readUTF())
        assertEquals(1234567890123L, input.readLong())
        assertEquals(42, input.readInt())
        assertEquals(-1, input.read())
    }

    @Test
    fun payloadSizesAroundFrameBoundaries() {
        val rnd = Random(7)
        for (size in listOf(1, 2, SECURE_FRAME_SIZE - 1, SECURE_FRAME_SIZE, SECURE_FRAME_SIZE + 1, 3 * SECURE_FRAME_SIZE + 17)) {
            val data = ByteArray(size).also { rnd.nextBytes(it) }
            val wire = encrypted { it.write(data) }
            val back = ByteArray(size)
            reader(wire).readFully(back)
            assertArrayEquals("size $size", data, back)
        }
    }

    @Test
    fun plaintextDoesNotAppearOnTheWire() {
        val secret = "TOP-SECRET-CLIPBOARD-TEXT".toByteArray()
        val wire = encrypted { it.write(secret) }
        val asString = String(wire, Charsets.ISO_8859_1)
        assertTrue(!asString.contains("TOP-SECRET"))
    }

    @Test
    fun wrongKeyFailsOnFirstRead() {
        val wire = encrypted { it.writeUTF("hello") }
        try {
            reader(wire, Crypto.deriveKey("a different code")).readUTF()
            fail("expected authentication failure")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun tamperedByteIsDetected() {
        val wire = encrypted { it.writeUTF("hello") }
        for (i in wire.indices) {
            val copy = wire.copyOf()
            copy[i] = (copy[i].toInt() xor 0x01).toByte()
            try {
                reader(copy).readUTF()
                fail("flipping byte $i went unnoticed")
            } catch (expected: IOException) {
            }
        }
    }

    @Test
    fun truncatedStreamIsDetected() {
        val wire = encrypted { it.write(ByteArray(100)) }
        for (cut in 1 until wire.size) {
            try {
                reader(wire.copyOf(cut)).readFully(ByteArray(100))
                fail("truncation at $cut went unnoticed")
            } catch (expected: IOException) {
            }
        }
    }

    @Test
    fun droppedOrReorderedFramesAreDetected() {
        val wire = encrypted { it.write(ByteArray(SECURE_FRAME_SIZE * 3)) }
        val prefixSize = 8
        val frameLen = 4 + SECURE_FRAME_SIZE + Crypto.TAG_BYTES
        val first = wire.copyOfRange(prefixSize, prefixSize + frameLen)
        val second = wire.copyOfRange(prefixSize + frameLen, prefixSize + 2 * frameLen)
        val third = wire.copyOfRange(prefixSize + 2 * frameLen, prefixSize + 3 * frameLen)
        val header = wire.copyOfRange(0, prefixSize)

        // Missing middle frame
        try {
            reader(header + first + third).readFully(ByteArray(SECURE_FRAME_SIZE * 2))
            fail("dropped frame went unnoticed")
        } catch (expected: IOException) {
        }
        // Swapped frames
        try {
            reader(header + second + first + third).readFully(ByteArray(SECURE_FRAME_SIZE))
            fail("reordered frames went unnoticed")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun oversizedFrameLengthIsRejectedWithoutAllocating() {
        val bogus = ByteArray(8) + byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        try {
            reader(bogus).read()
            fail("huge frame length accepted")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun emptyStreamIsCleanEof() {
        assertEquals(-1, reader(ByteArray(0)).read())
    }
}
