package com.almog.radioclip.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Encrypted framing for TCP streams.
 *
 * Wire format: an 8-byte random nonce prefix, followed by frames of
 * `[4-byte length][AES-GCM ciphertext+tag]`. The GCM nonce of each frame is the prefix plus a
 * frame counter, so frames cannot be reordered, replayed or dropped from the middle without the
 * tag check failing. Wrap a socket stream with these and use it like any other stream.
 */
internal const val SECURE_FRAME_SIZE = 32 * 1024
private const val PREFIX_SIZE = 8
private const val MAX_CIPHERTEXT = SECURE_FRAME_SIZE + Crypto.TAG_BYTES

private fun frameNonce(prefix: ByteArray, counter: Int): ByteArray {
    val nonce = ByteArray(Crypto.NONCE_SIZE)
    System.arraycopy(prefix, 0, nonce, 0, PREFIX_SIZE)
    nonce[8] = (counter ushr 24).toByte()
    nonce[9] = (counter ushr 16).toByte()
    nonce[10] = (counter ushr 8).toByte()
    nonce[11] = counter.toByte()
    return nonce
}

class SecureOutputStream(
    private val out: OutputStream,
    private val key: ByteArray
) : OutputStream() {
    private val prefix = ByteArray(PREFIX_SIZE).also { SecureRandom().nextBytes(it) }
    private val buffer = ByteArray(SECURE_FRAME_SIZE)
    private var count = 0
    private var counter = 0
    private var headerWritten = false

    override fun write(b: Int) {
        if (count == buffer.size) emitFrame()
        buffer[count++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var left = len
        while (left > 0) {
            if (count == buffer.size) emitFrame()
            val n = minOf(left, buffer.size - count)
            System.arraycopy(b, offset, buffer, count, n)
            count += n
            offset += n
            left -= n
        }
    }

    /** Sends any buffered bytes as a frame. Call this when a message is complete. */
    override fun flush() {
        emitFrame()
        out.flush()
    }

    override fun close() {
        try {
            flush()
        } finally {
            out.close()
        }
    }

    private fun emitFrame() {
        if (count == 0) return
        if (counter == Int.MAX_VALUE) throw IOException("Secure stream frame counter exhausted")
        if (!headerWritten) {
            out.write(prefix)
            headerWritten = true
        }
        val sealed = Crypto.seal(key, frameNonce(prefix, counter++), buffer, 0, count)
        val frame = ByteArray(4 + sealed.size)
        frame[0] = (sealed.size ushr 24).toByte()
        frame[1] = (sealed.size ushr 16).toByte()
        frame[2] = (sealed.size ushr 8).toByte()
        frame[3] = sealed.size.toByte()
        System.arraycopy(sealed, 0, frame, 4, sealed.size)
        out.write(frame)
        count = 0
    }
}

class SecureInputStream(
    private val inp: InputStream,
    private val key: ByteArray
) : InputStream() {
    private var prefix: ByteArray? = null
    private var counter = 0
    private var plain = ByteArray(0)
    private var pos = 0
    private var limit = 0
    private var eof = false

    override fun read(): Int {
        if (!fill()) return -1
        return plain[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, limit - pos)
        System.arraycopy(plain, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = limit - pos

    override fun close() = inp.close()

    private fun fill(): Boolean {
        while (pos >= limit) {
            if (eof) return false
            if (!nextFrame()) {
                eof = true
                return false
            }
        }
        return true
    }

    /** Returns false on a clean end of stream (exactly at a frame boundary). */
    private fun nextFrame(): Boolean {
        var p = prefix
        if (p == null) {
            p = ByteArray(PREFIX_SIZE)
            val n = readFully(p)
            if (n == 0) return false
            if (n < PREFIX_SIZE) throw IOException("Truncated secure stream header")
            prefix = p
        }

        val lenBytes = ByteArray(4)
        val lenRead = readFully(lenBytes)
        if (lenRead == 0) return false
        if (lenRead < 4) throw IOException("Truncated frame header")
        val len = ((lenBytes[0].toInt() and 0xFF) shl 24) or
            ((lenBytes[1].toInt() and 0xFF) shl 16) or
            ((lenBytes[2].toInt() and 0xFF) shl 8) or
            (lenBytes[3].toInt() and 0xFF)
        if (len <= Crypto.TAG_BYTES || len > MAX_CIPHERTEXT) throw IOException("Invalid frame length")

        val ciphertext = ByteArray(len)
        if (readFully(ciphertext) < len) throw IOException("Truncated frame")
        if (counter == Int.MAX_VALUE) throw IOException("Secure stream frame counter exhausted")

        plain = Crypto.open(key, frameNonce(p, counter++), ciphertext, 0, len)
            ?: throw IOException("Frame authentication failed (wrong pairing code or tampered data)")
        pos = 0
        limit = plain.size
        return true
    }

    /** Reads up to buf.size bytes; returns how many were read (less only at end of stream). */
    private fun readFully(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = inp.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
