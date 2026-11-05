package com.almog.radioclip.core

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a RadioClip device puts into its Bluetooth advertisement (6 bytes of manufacturer data):
 * a 2-byte tag derived from the pairing key, and a 4-byte random node id.
 *
 * - The tag lets paired devices find each other and ignore everyone else's RadioClip devices.
 *   It is only a hint: real protection is the encryption, which every message must pass.
 * - The node id breaks the tie so only ONE of two nearby phones opens the connection. Before,
 *   both connected to each other, giving two links per pair.
 */
object Advertisement {
    const val COMPANY_ID = 0xFFFF            // "reserved for testing" id; no real company owns it
    const val TAG_SIZE = 2
    const val DATA_SIZE = TAG_SIZE + 4

    data class Info(val tag: ByteArray, val nodeId: Int) {
        override fun equals(other: Any?) = other is Info && nodeId == other.nodeId && tag.contentEquals(other.tag)
        override fun hashCode() = 31 * nodeId + tag.contentHashCode()
        val isUnpaired: Boolean get() = tag.all { it == 0.toByte() }
    }

    enum class PeerClass {
        /** Same pairing code: the device we want. */
        SAME_GROUP,
        /** A RadioClip device paired with a different code. */
        OTHER_GROUP,
        /** A RadioClip device that has no pairing code yet. */
        UNPAIRED_REMOTE,
        /** No readable tag (older version, or the advertisement had to be shortened): rely on encryption. */
        NO_TAG
    }

    private val UNPAIRED_TAG = ByteArray(TAG_SIZE)

    fun tagFor(key: ByteArray?): ByteArray {
        if (key == null) return UNPAIRED_TAG
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal("radioclip-adv-v1".toByteArray(Charsets.UTF_8)).copyOf(TAG_SIZE)
    }

    fun encode(key: ByteArray?, nodeId: Int): ByteArray =
        ByteBuffer.allocate(DATA_SIZE).put(tagFor(key)).putInt(nodeId).array()

    fun decode(data: ByteArray?): Info? {
        if (data == null || data.size != DATA_SIZE) return null
        val buffer = ByteBuffer.wrap(data)
        val tag = ByteArray(TAG_SIZE).also { buffer.get(it) }
        return Info(tag, buffer.int)
    }

    /** Classifies a remote device. [ownKey] is null while we are not paired ourselves. */
    fun classify(ownKey: ByteArray?, remote: Info?): PeerClass {
        if (remote == null) return PeerClass.NO_TAG
        if (remote.isUnpaired) return PeerClass.UNPAIRED_REMOTE
        if (ownKey == null) return PeerClass.OTHER_GROUP
        return if (tagFor(ownKey).contentEquals(remote.tag)) PeerClass.SAME_GROUP else PeerClass.OTHER_GROUP
    }

    /** The device with the higher (unsigned) node id opens the connection; ties mean both may try. */
    fun shouldInitiate(ownId: Int, remoteId: Int): Boolean = Integer.compareUnsigned(ownId, remoteId) >= 0
}
