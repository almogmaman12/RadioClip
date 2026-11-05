package com.almog.radioclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HelpersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---- Format ----

    @Test
    fun byteSizes() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("1023 B", Format.bytes(1023))
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("1.5 MB", Format.bytes(1_572_864))
        assertEquals("2.0 GB", Format.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun relativeTimes() {
        assertEquals("just now", Format.relativeTime(1_000, 1_000))
        assertEquals("just now", Format.relativeTime(5_000, 1_000))      // clock skew never goes negative
        assertEquals("30 s ago", Format.relativeTime(0, 30_000))
        assertEquals("5 min ago", Format.relativeTime(0, 5 * 60_000))
        assertEquals("2 h ago", Format.relativeTime(0, 2 * 3_600_000L))
        assertEquals("3 d ago", Format.relativeTime(0, 3 * 86_400_000L))
    }

    @Test
    fun signalLabelsAndQuantizing() {
        assertEquals("", Format.signalLabel(null))
        assertEquals("Strong signal", Format.signalLabel(-50))
        assertEquals("Good signal", Format.signalLabel(-70))
        assertEquals("Weak signal", Format.signalLabel(-80))
        assertEquals("Very weak signal", Format.signalLabel(-95))
        assertEquals(-60, Format.quantizeRssi(-55))
        assertEquals(-50, Format.quantizeRssi(-50))
        assertEquals(-60, Format.quantizeRssi(-60))
    }

    // ---- Wi-Fi device types ----

    @Test
    fun phonesAndUnknownDevicesAreKept() {
        assertTrue(WifiDeviceTypes.isPhoneLike("10-0050F204-5"))
        assertTrue(WifiDeviceTypes.isPhoneLike("1-0050F204-1"))
        assertTrue(WifiDeviceTypes.isPhoneLike(null))
        assertTrue(WifiDeviceTypes.isPhoneLike(""))
        assertTrue(WifiDeviceTypes.isPhoneLike("weird"))
    }

    @Test
    fun tvsAndPrintersAreFilteredOut() {
        assertFalse(WifiDeviceTypes.isPhoneLike("7-0050F204-1"))   // display / TV
        assertFalse(WifiDeviceTypes.isPhoneLike("3-0050F204-1"))   // printer
        assertFalse(WifiDeviceTypes.isPhoneLike("11-0050F204-1"))  // audio
    }

    // ---- Settings ----

    @Test
    fun announcedNameFallsBackAndIsLimited() {
        assertEquals("Pixel 7", Settings().announcedName("Pixel 7"))
        assertEquals("Mine", Settings(deviceName = "  Mine ").announcedName("Pixel 7"))
        assertEquals("Android", Settings().announcedName("   "))
        assertEquals(Settings.MAX_NAME_LENGTH, Settings(deviceName = "x".repeat(100)).announcedName("a").length)
    }

    // ---- File retention ----

    private fun fileAged(name: String, ageDays: Int, now: Long) =
        tmp.newFile(name).also { it.setLastModified(now - ageDays * 86_400_000L - 1000) }

    @Test
    fun retentionDeletesOnlyOldFiles() {
        val now = System.currentTimeMillis()
        val old = fileAged("old.txt", 10, now)
        val fresh = fileAged("fresh.txt", 1, now)
        assertEquals(listOf(old), FileRetention.expired(listOf(old, fresh), 7, now))
    }

    @Test
    fun zeroDaysKeepsEverything() {
        val now = System.currentTimeMillis()
        val old = fileAged("ancient.txt", 400, now)
        assertTrue(FileRetention.expired(listOf(old), 0, now).isEmpty())
        assertEquals(0, FileRetention.cleanup(tmp.root, 0, now))
        assertTrue(old.exists())
    }

    @Test
    fun cleanupRemovesExpiredFilesAndCountsThem() {
        val now = System.currentTimeMillis()
        val a = fileAged("a.bin", 40, now)
        val b = fileAged("b.bin", 31, now)
        val keep = fileAged("keep.bin", 2, now)
        assertEquals(2, FileRetention.cleanup(tmp.root, 30, now))
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun cleanupOfMissingFolderIsHarmless() {
        assertEquals(0, FileRetention.cleanup(java.io.File(tmp.root, "nope"), 7))
    }

    // ---- Diagnostics log ----

    @Test
    fun logBufferKeepsOnlyTheNewestLines() {
        val log = LogBuffer(capacity = 3)
        (1..5).forEach { log.add("line $it") }
        val lines = log.snapshot()
        assertEquals(3, lines.size)
        assertTrue(lines.first().endsWith("line 3"))
        assertTrue(lines.last().endsWith("line 5"))
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun logLinesStartWithATime() {
        val log = LogBuffer()
        log.add("hello")
        assertTrue(Regex("\\d\\d:\\d\\d:\\d\\d {2}hello").matches(log.snapshot().single()))
    }

    @Test
    fun addressesAreMasked() {
        assertEquals("\u2026DD:EE", DiagLog.mask("AA:BB:CC:DD:EE").let { it })
        assertEquals("?", DiagLog.mask(null))
    }
}
