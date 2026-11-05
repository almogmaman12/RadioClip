package com.almog.radioclip.core

import java.nio.ByteBuffer
import java.util.UUID

/** Application-level messages carried inside the encrypted BLE channel. */
sealed class BleMessage {
    /** Clipboard text. [id] is shared with the Wi-Fi transport so duplicates can be ignored. */
    data class Text(val id: String, val text: String) : BleMessage()

    /** Tells the peer our device name (sent after connecting, so the name never goes over the air in clear). */
    data class Hello(val deviceName: String, val nodeId: Int = 0) : BleMessage()
}

/** Wire format: `[type: 1 byte][body]`. Text body = `[uuid: 16 bytes][utf-8 text]`, hello body = `[node id: 4 bytes][utf-8 name]` (the node id identifies the phone even when its Bluetooth address changes). */
object BleMessages {
    private const val TYPE_TEXT = 1
    private const val TYPE_HELLO = 2
    private const val UUID_BYTES = 16
    private const val MAX_NAME_BYTES = 96

    fun encodeText(id: String, text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + UUID_BYTES + body.size).apply {
            put(TYPE_TEXT.toByte())
            val uuid = toUuid(id)
            putLong(uuid.mostSignificantBits)
            putLong(uuid.leastSignificantBits)
            put(body)
        }.array()
    }

    fun encodeHello(name: String, nodeId: Int = 0): ByteArray {
        val clean = cleanName(name)
        val bytes = truncateUtf8(clean, MAX_NAME_BYTES)
        return ByteBuffer.allocate(1 + 4 + bytes.size).apply {
            put(TYPE_HELLO.toByte())
            putInt(nodeId)
            put(bytes)
        }.array()
    }

    /** Returns null for anything malformed or unknown, so garbage can never crash the receiver. */
    fun decode(data: ByteArray): BleMessage? {
        if (data.isEmpty()) return null
        return when (data[0].toInt()) {
            TYPE_TEXT -> {
                if (data.size < 1 + UUID_BYTES) return null
                val buffer = ByteBuffer.wrap(data, 1, UUID_BYTES)
                val uuid = UUID(buffer.long, buffer.long)
                BleMessage.Text(
                    id = uuid.toString(),
                    text = String(data, 1 + UUID_BYTES, data.size - 1 - UUID_BYTES, Charsets.UTF_8)
                )
            }
            TYPE_HELLO -> {
                if (data.size < 1 + 4) return null
                val nodeId = ByteBuffer.wrap(data, 1, 4).int
                val name = cleanName(String(data, 5, data.size - 5, Charsets.UTF_8))
                if (name.isEmpty()) null else BleMessage.Hello(name, nodeId)
            }
            else -> null
        }
    }

    /** Removes control characters and surrounding whitespace, and limits the length. */
    fun cleanName(raw: String): String =
        raw.filter { !it.isISOControl() }.trim().take(Settings.MAX_NAME_LENGTH)

    private fun toUuid(id: String): UUID =
        try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8))
        }

    /** Cuts at a character boundary so the result is always valid UTF-8. */
    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val out = StringBuilder()
        var size = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chars = Character.charCount(cp)
            val piece = String(Character.toChars(cp))
            val n = piece.toByteArray(Charsets.UTF_8).size
            if (size + n > maxBytes) break
            out.append(piece)
            size += n
            i += chars
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }
}
