package com.almog.radioclip.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private val key = Crypto.deriveKey("correct horse battery")

    @Test
    fun roundTrip() {
        val message = "clipboard שלום 📋".toByteArray()
        assertArrayEquals(message, Crypto.decrypt(key, Crypto.encrypt(key, message)))
    }

    @Test
    fun emptyMessageRoundTrips() {
        assertArrayEquals(ByteArray(0), Crypto.decrypt(key, Crypto.encrypt(key, ByteArray(0))))
    }

    @Test
    fun sameMessageEncryptsDifferentlyEachTime() {
        val message = "same".toByteArray()
        assertFalse(Crypto.encrypt(key, message).contentEquals(Crypto.encrypt(key, message)))
    }

    @Test
    fun wrongKeyIsRejected() {
        val other = Crypto.deriveKey("some other code")
        assertNull(Crypto.decrypt(other, Crypto.encrypt(key, "secret".toByteArray())))
    }

    @Test
    fun tamperedDataIsRejected() {
        val sealed = Crypto.encrypt(key, "secret".toByteArray())
        for (i in sealed.indices) {
            val copy = sealed.copyOf()
            copy[i] = (copy[i].toInt() xor 0x01).toByte()
            assertNull("flipping byte $i must be detected", Crypto.decrypt(key, copy))
        }
    }

    @Test
    fun truncatedDataIsRejected() {
        assertNull(Crypto.decrypt(key, ByteArray(0)))
        assertNull(Crypto.decrypt(key, ByteArray(Crypto.NONCE_SIZE + Crypto.TAG_BYTES - 1)))
    }

    @Test
    fun keyDerivationIgnoresCaseSpacesAndDashes() {
        assertArrayEquals(Crypto.deriveKey("ABCD-EFGH-JKLM"), Crypto.deriveKey("abcd efgh jklm"))
        assertNotEquals(
            Crypto.deriveKey("ABCD-EFGH-JKLM").toList(),
            Crypto.deriveKey("ABCD-EFGH-JKLN").toList()
        )
        assertEquals(32, Crypto.deriveKey("ABCD-EFGH-JKLM").size)
    }

    @Test
    fun codeLengthRule() {
        assertFalse(Crypto.isAcceptableCode("short"))
        assertFalse(Crypto.isAcceptableCode("ab-cd ef"))      // only 6 real characters
        assertTrue(Crypto.isAcceptableCode("abcd-efgh"))
    }

    @Test
    fun generatedCodesAreValidAndDifferent() {
        val a = Crypto.generateCode()
        val b = Crypto.generateCode()
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}").matches(a))
        assertTrue(Crypto.isAcceptableCode(a))
        assertNotEquals(a, b)
    }
}
