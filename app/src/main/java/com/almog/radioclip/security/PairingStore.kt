package com.almog.radioclip.security

import android.content.Context
import android.util.Base64
import com.almog.radioclip.core.Crypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the key derived from the shared pairing code in app-private storage.
 * Until a code is set, nothing is sent and everything received is dropped.
 */
class PairingStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("radioclip_pairing", Context.MODE_PRIVATE)

    private val _isPaired = MutableStateFlow(false)
    val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

    @Volatile
    var key: ByteArray? = null
        private set

    init {
        key = prefs.getString(PREF_KEY, null)?.let {
            try {
                Base64.decode(it, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        _isPaired.value = key != null
    }

    /** Derives and stores the key (CPU-heavy: call off the main thread). Returns false if the code is too short. */
    fun setCode(code: String): Boolean {
        if (!Crypto.isAcceptableCode(code)) return false
        val derived = Crypto.deriveKey(code)
        prefs.edit().putString(PREF_KEY, Base64.encodeToString(derived, Base64.NO_WRAP)).apply()
        key = derived
        _isPaired.value = true
        return true
    }

    fun clear() {
        prefs.edit().remove(PREF_KEY).apply()
        key = null
        _isPaired.value = false
    }

    private companion object {
        const val PREF_KEY = "derived_key"
    }
}
