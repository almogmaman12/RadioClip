package com.almog.radioclip.transport.wifi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class FileTransferSafetyTest {
    @Test
    fun stripsPathComponentsAndControlCharacters() {
        assertEquals("password.txt", FileTransferSafety.safeName("../../password.txt"))
        assertEquals("image.png", FileTransferSafety.safeName("..\\image.png"))
        assertEquals("received_file", FileTransferSafety.safeName("..."))
        assertEquals("hello_world", FileTransferSafety.safeName("hello\u0000world"))
    }

    @Test
    fun copiesExactlyTheDeclaredNumberOfBytes() {
        val target = ByteArrayOutputStream()
        FileTransferSafety.copyExactly(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), target, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.toByteArray())
    }

    @Test(expected = EOFException::class)
    fun rejectsTruncatedTransfer() {
        FileTransferSafety.copyExactly(
            ByteArrayInputStream(byteArrayOf(1, 2)),
            ByteArrayOutputStream(),
            3
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeSize() {
        FileTransferSafety.checkSize(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedTransfer() {
        FileTransferSafety.checkSize(FileTransferSafety.MAX_FILE_SIZE_BYTES + 1)
    }
}
