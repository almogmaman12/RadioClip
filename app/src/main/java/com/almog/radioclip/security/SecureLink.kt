package com.almog.radioclip.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protects RadioClip payload contents with an agreed, high-entropy pairing phrase.
 *
 * Devices must be given the SAME phrase locally. The phrase is never transmitted.
 * A locally encrypted derived key is persisted through Android Keystore.
 *
 * This is shared-group-key pairing, not per-device identity / verification.
 * File names and sizes remain visible to nearby Wi-Fi Direct peers.
 */
class SecureLink(private val context: Context) {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "radioclip-local-key-v2"
        private const val PREFS = "radioclip-secure"
        private const val KEY_DATA = "encrypted-peer-key"
        private const val PBKDF2_ITERATIONS = 240_000
        private val PAIRING_SALT = "RadioClip v2 pairing phrase AES-256".toByteArray(Charsets.UTF_8)
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private val random = SecureRandom()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    @Volatile
    private var peerKey: SecretKeySpec? = null

    init {
        try {
            val stored = prefs.getString(KEY_DATA, null)
            if (stored != null) {
                val contents = Base64.decode(stored, Base64.NO_WRAP)
                require(contents.size > IV_LENGTH)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, localStorageKey(), GCMParameterSpec(TAG_BITS, contents, 0, IV_LENGTH))
                val decoded = cipher.doFinal(contents, IV_LENGTH, contents.size - IV_LENGTH)
                require(decoded.size == 32)
                peerKey = SecretKeySpec(decoded, "AES")
                decoded.fill(0)
                _configured.value = true
            }
        } catch (e: Exception) {
            // If local Keystore data becomes inaccessible, ask the user to pair again.
            peerKey = null
            _configured.value = false
            prefs.edit().remove(KEY_DATA).apply()
        }
    }

    @Synchronized
    fun configurePhrase(phrase: String) {
        require(phrase.length >= 16 && phrase.any { it.isLetter() } && phrase.any { it.isDigit() }) {
            "Use a pairing phrase of at least 16 characters with letters and numbers"
        }
        val spec = PBEKeySpec(phrase.toCharArray(), PAIRING_SALT, PBKDF2_ITERATIONS, 256)
        val derived = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        try {
            val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, localStorageKey(), GCMParameterSpec(TAG_BITS, iv))
            val encrypted = iv + cipher.doFinal(derived)
            prefs.edit().putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
            peerKey = SecretKeySpec(derived, "AES")
            _configured.value = true
        } finally {
            derived.fill(0)
        }
    }

    @Synchronized
    fun forgetPhrase() {
        peerKey = null
        _configured.value = false
        prefs.edit().remove(KEY_DATA).commit()
    }

    /** Returns [12-byte IV][AES-GCM encrypted bytes + 16-byte tag]. */
    fun seal(plaintext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        val key = peerKey ?: error("Enter a pairing phrase before sending")
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    fun open(envelope: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        val key = peerKey ?: error("Enter a pairing phrase before receiving")
        require(envelope.size >= IV_LENGTH + TAG_BITS / 8) { "Truncated encrypted payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope, 0, IV_LENGTH))
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope, IV_LENGTH, envelope.size - IV_LENGTH)
    }

    private fun localStorageKey(): java.security.Key {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }
}
