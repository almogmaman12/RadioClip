package com.almog.radioclip.core

import com.almog.radioclip.core.ClipPolicy.SendDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPolicyTest {
    private val on = Settings()

    @Test
    fun normalTextIsSent() {
        assertEquals(SendDecision.SEND, ClipPolicy.decide("hello", null, false, on))
    }

    @Test
    fun disabledBlocksEverything() {
        assertEquals(
            SendDecision.SKIP_DISABLED,
            ClipPolicy.decide("hello", null, false, on.copy(clipboardAutoSend = false))
        )
    }

    @Test
    fun blankAndNullAreSkipped() {
        assertEquals(SendDecision.SKIP_EMPTY, ClipPolicy.decide(null, null, false, on))
        assertEquals(SendDecision.SKIP_EMPTY, ClipPolicy.decide("   \n", null, false, on))
    }

    @Test
    fun textWeJustReceivedIsNotEchoedBack() {
        assertEquals(SendDecision.SKIP_DUPLICATE, ClipPolicy.decide("same", "same", false, on))
    }

    @Test
    fun sensitiveClipsAreSkippedUnlessAllowed() {
        assertEquals(SendDecision.SKIP_SENSITIVE, ClipPolicy.decide("pw", null, true, on))
        assertEquals(
            SendDecision.SEND,
            ClipPolicy.decide("pw", null, true, on.copy(clipboardSkipSensitive = false))
        )
    }

    @Test
    fun hugeTextIsSkipped() {
        val big = "x".repeat(ClipPolicy.MAX_TEXT_BYTES + 1)
        assertEquals(SendDecision.SKIP_TOO_LARGE, ClipPolicy.decide(big, null, false, on))
        val ok = "x".repeat(ClipPolicy.MAX_TEXT_BYTES)
        assertEquals(SendDecision.SEND, ClipPolicy.decide(ok, null, false, on))
    }

    @Test
    fun sizeLimitCountsBytesNotCharacters() {
        val multi = "א".repeat(ClipPolicy.MAX_TEXT_BYTES / 2 + 1)   // 2 bytes each
        assertEquals(SendDecision.SKIP_TOO_LARGE, ClipPolicy.decide(multi, null, false, on))
    }

    @Test
    fun previewIsOneShortLine() {
        assertEquals("a b c", ClipPolicy.preview("  a\n\n b\t c  "))
        val p = ClipPolicy.preview("x".repeat(200), 20)
        assertEquals(20, p.length)
        assertTrue(p.endsWith("…"))
    }

    @Test
    fun recentIdsDetectRepeats() {
        val ids = RecentIds(capacity = 3)
        assertTrue(ids.firstSeen("a"))
        assertFalse(ids.firstSeen("a"))
        assertTrue(ids.firstSeen("b"))
    }

    @Test
    fun recentIdsForgetOldestWhenFull() {
        val ids = RecentIds(capacity = 2)
        ids.firstSeen("a"); ids.firstSeen("b"); ids.firstSeen("c")   // "a" falls out
        assertTrue(ids.firstSeen("a"))
        assertFalse(ids.firstSeen("c"))
    }
}
