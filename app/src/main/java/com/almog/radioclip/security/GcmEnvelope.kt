package com.almog.radioclip.security

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Binary envelope: 12-byte provider-generated IV followed by ciphertext+16-byte tag.
 *
 * Crucially, encryption NEVER supplies a caller-provided IV. This is required
 * by AndroidKeyStore-backed AES-GCM keys and works with software peer keys.
 */
internal object GcmEnvelope {
    const val IV_BYTES = 12
    const val TAG_BITS = 128

    fun seal(key: SecretKey, plain: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected AES-GCM IV length" }
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    fun open(key: SecretKey, envelope: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        require(envelope.size >= IV_BYTES + TAG_BITS / 8) { "Truncated encrypted payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope, 0, IV_BYTES)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope, IV_BYTES, envelope.size - IV_BYTES)
    }
}
