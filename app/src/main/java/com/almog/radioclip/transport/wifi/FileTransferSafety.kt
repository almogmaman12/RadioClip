package com.almog.radioclip.transport.wifi

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Validates the untrusted metadata and body of incoming peer-to-peer files. */
internal object FileTransferSafety {
    const val MAX_FILE_SIZE_BYTES = 512L * 1024L * 1024L

    fun checkSize(sizeBytes: Long) {
        require(sizeBytes in 0..MAX_FILE_SIZE_BYTES) {
            "Invalid or oversized file: $sizeBytes bytes (limit 512 MB)"
        }
    }

    fun safeName(untrusted: String): String {
        return untrusted
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[\x00-\x1F\x7F]"""), "_")
            .take(120)
            .trim(' ', '.')
            .ifBlank { "received_file" }
    }

    /** Reads exactly the declared size or throws instead of accepting a truncated file. */
    fun copyExactly(input: InputStream, output: OutputStream, sizeBytes: Long) {
        checkSize(sizeBytes)
        var remaining = sizeBytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) throw EOFException("Transfer interrupted with $remaining bytes remaining")
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
}
