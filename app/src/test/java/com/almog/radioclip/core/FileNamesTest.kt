package com.almog.radioclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileNamesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun directoryPartsAreStripped() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.sh", FileNames.sanitize("/data/data/app/evil.sh"))
        assertEquals("a.txt", FileNames.sanitize("C:\\Users\\x\\a.txt"))
        assertEquals("photo.jpg", FileNames.sanitize("dir/sub/photo.jpg"))
    }

    @Test
    fun dotOnlyAndEmptyNamesFallBack() {
        for (bad in listOf("", "   ", ".", "..", "...", "/", "../", null)) {
            assertEquals("received_file", FileNames.sanitize(bad))
        }
    }

    @Test
    fun hiddenAndControlCharactersAreRemoved() {
        assertEquals("hidden", FileNames.sanitize(".hidden"))
        assertEquals("ab.txt", FileNames.sanitize("a\u0000b\n.txt"))
        assertEquals("abc", FileNames.sanitize("a<b>c"))
    }

    @Test
    fun normalNamesAreKept() {
        assertEquals("My Report (final).pdf", FileNames.sanitize("My Report (final).pdf"))
        assertEquals("\u05e7\u05d5\u05d1\u05e5.txt", FileNames.sanitize("\u05e7\u05d5\u05d1\u05e5.txt"))
    }

    @Test
    fun longNamesAreShortenedButKeepTheExtension() {
        val name = "x".repeat(500) + ".mp4"
        val clean = FileNames.sanitize(name)
        assertEquals(FileNames.MAX_NAME_LENGTH, clean.length)
        assertTrue(clean.endsWith(".mp4"))
    }

    @Test
    fun reservedFilesStayInsideTheFolder() {
        val dir = tmp.newFolder("received")
        val file = FileNames.reserveUniqueFile(dir, "../../outside.txt")
        assertEquals(dir.canonicalFile, file.canonicalFile.parentFile)
        assertEquals("outside.txt", file.name)
        assertFalse(java.io.File(dir.parentFile.parentFile, "outside.txt").exists())
    }

    @Test
    fun existingFilesAreNeverOverwritten() {
        val dir = tmp.newFolder("received")
        val first = FileNames.reserveUniqueFile(dir, "a.txt")
        first.writeText("original")
        val second = FileNames.reserveUniqueFile(dir, "a.txt")
        val third = FileNames.reserveUniqueFile(dir, "a.txt")

        assertEquals("a.txt", first.name)
        assertEquals("a (1).txt", second.name)
        assertEquals("a (2).txt", third.name)
        assertEquals("original", first.readText())
    }

    @Test
    fun namesWithoutExtensionGetNumberedToo() {
        val dir = tmp.newFolder("received")
        FileNames.reserveUniqueFile(dir, "notes")
        assertEquals("notes (1)", FileNames.reserveUniqueFile(dir, "notes").name)
    }
}
