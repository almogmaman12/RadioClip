package com.almog.radioclip.core

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing-code based authenticated encryption (AES-256-GCM).
 *
 * Both devices enter the same pairing code. The code is stretched with PBKDF2 into a 256-bit key;
 * every message is then encrypted and authenticated with that key. A device that does not know the
 * code can neither read nor inject payloads: anything that fails authentication is dropped.
 */
object Crypto {
    const val NONCE_SIZE = 12
    const val TAG_BYTES = 16
    const val MIN_CODE_LENGTH = 8

    private const val TAG_BITS = TAG_BYTES * 8
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private val SALT = "radioclip-pairing-v1".toByteArray(Charsets.UTF_8)

    // No 0/O/1/I so codes are easy to read out loud and type.
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val GENERATED_CODE_LENGTH = 12

    private val random = SecureRandom()

    /** Removes spaces and dashes and upper-cases, so "abcd-efgh ijkl" equals "ABCDEFGHIJKL". */
    fun normalizeCode(code: String): String =
        code.filterNot { it.isWhitespace() || it == '-' }.uppercase()

    fun isAcceptableCode(code: String): Boolean = normalizeCode(code).length >= MIN_CODE_LENGTH

    /** Random pairing code (60 bits of entropy) formatted as XXXX-XXXX-XXXX. */
    fun generateCode(): String {
        val chars = CharArray(GENERATED_CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
        return String(chars).chunked(4).joinToString("-")
    }

    fun deriveKey(code: String): ByteArray {
        val spec = PBEKeySpec(normalizeCode(code).toCharArray(), SALT, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Returns nonce || ciphertext || tag. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val sealed = seal(key, nonce, plaintext, 0, plaintext.size)
        return nonce + sealed
    }

    /** Returns the plaintext, or null if the data is malformed, tampered with, or uses another key. */
    fun decrypt(key: ByteArray, data: ByteArray): ByteArray? {
        if (data.size < NONCE_SIZE + TAG_BYTES) return null
        val nonce = data.copyOfRange(0, NONCE_SIZE)
        return open(key, nonce, data, NONCE_SIZE, data.size - NONCE_SIZE)
    }

    internal fun seal(key: ByteArray, nonce: ByteArray, data: ByteArray, off: Int, len: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(data, off, len)
    }

    internal fun open(key: ByteArray, nonce: ByteArray, data: ByteArray, off: Int, len: Int): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(data, off, len)
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}
