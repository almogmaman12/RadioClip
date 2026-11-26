package com.almog.radioclip.security

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GcmEnvelopeTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val otherKey = SecretKeySpec(ByteArray(32) { (it + 32).toByte() }, "AES")

    @Test
    fun roundTripUsingProviderGeneratedIv() {
        val message = "Clipboard from RadioClip שלום 🎵".toByteArray()
        val associatedData = "text-v2".toByteArray()
        val sealed = GcmEnvelope.seal(key, message, associatedData)
        assertArrayEquals(message, GcmEnvelope.open(key, sealed, associatedData))
    }

    @Test
    fun encryptedPayloadGetsFreshRandomIvEachTime() {
        val message = "same message".toByteArray()
        val first = GcmEnvelope.seal(key, message)
        val second = GcmEnvelope.seal(key, message)
        assertFalse(first.contentEquals(second))
    }

    @Test(expected = AEADBadTagException::class)
    fun rejectsIncorrectPairingKey() {
        val encrypted = GcmEnvelope.seal(key, "hello".toByteArray())
        GcmEnvelope.open(otherKey, encrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun rejectsModifiedAssociatedData() {
        val encrypted = GcmEnvelope.seal(key, "hello".toByteArray(), "correct".toByteArray())
        GcmEnvelope.open(key, encrypted, "wrong".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedEnvelope() {
        GcmEnvelope.open(key, ByteArray(20))
    }

    @Test(expected = AEADBadTagException::class)
    fun rejectsTamperedCiphertext() {
        val encrypted = GcmEnvelope.seal(key, "hello".toByteArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0x01).toByte()
        GcmEnvelope.open(key, encrypted)
    }
}
