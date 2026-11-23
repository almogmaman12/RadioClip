package com.almog.radioclip.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
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
        private const val KEY_ALIAS = "radioclip-local-key-v3"
        private const val PREFS = "radioclip-secure"
        private const val KEY_DATA = "encrypted-peer-key-v3"
        const val MIN_PAIRING_LENGTH = 5
        private const val PBKDF2_ITERATIONS = 240_000
        private val PAIRING_SALT = "RadioClip v2 pairing phrase AES-256".toByteArray(Charsets.UTF_8)
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
                val decoded = GcmEnvelope.open(localStorageKey(), contents)
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
        require(phrase.length >= MIN_PAIRING_LENGTH) {
            "Enter at least $MIN_PAIRING_LENGTH characters for pairing"
        }
        val spec = PBEKeySpec(phrase.toCharArray(), PAIRING_SALT, PBKDF2_ITERATIONS, 256)
        val derived = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        try {
            // One tested helper handles Android Keystore and software peer keys,
            // always using a provider-generated nonce.
            val encrypted = GcmEnvelope.seal(localStorageKey(), derived)
            check(prefs.edit().putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
                "Could not persist the pairing key"
            }
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
        return GcmEnvelope.seal(key, plaintext, aad)
    }

    fun open(envelope: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        val key = peerKey ?: error("Enter a pairing phrase before receiving")
        return GcmEnvelope.open(key, envelope, aad)
    }

    /** 96-bit MAC used for BLE delivery acknowledgements within a 23-byte MTU. */
    fun authenticationTag(message: ByteArray): ByteArray {
        val key = peerKey ?: error("Enter a pairing phrase before sending")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        return mac.doFinal(message).copyOf(12)
    }

    private fun localStorageKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
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
