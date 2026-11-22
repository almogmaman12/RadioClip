package com.almog.radioclip.transport.wifi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioClipServiceIdentityTest {
    @Test
    fun acceptsOnlyRadioClipService() {
        assertTrue(RadioClipServiceIdentity.matchesService("RadioClip", "_radioclip._tcp.local."))
        assertTrue(RadioClipServiceIdentity.matchesService("radioclip", "_radioclip._tcp"))
        assertFalse(RadioClipServiceIdentity.matchesService("Printer", "_radioclip._tcp.local."))
        assertFalse(RadioClipServiceIdentity.matchesService("RadioClip", "_ipp._tcp.local."))
        assertFalse(RadioClipServiceIdentity.matchesService("RadioClip", "_radioclip._tcp_fake"))
    }

    @Test
    fun acceptsOnlyMatchingProtocolTxtRecords() {
        assertTrue(
            RadioClipServiceIdentity.matchesRecord(
                "RadioClip._radioclip._tcp.local.",
                mapOf("protocol" to "2")
            )
        )
        assertFalse(
            RadioClipServiceIdentity.matchesRecord(
                "RadioClip._radioclip._tcp.local.",
                mapOf("protocol" to "1")
            )
        )
        assertFalse(
            RadioClipServiceIdentity.matchesRecord(
                "Other._radioclip._tcp.local.",
                mapOf("protocol" to "2")
            )
        )
        assertFalse(
            RadioClipServiceIdentity.matchesRecord(
                "RadioClip._other._tcp.local.",
                mapOf("protocol" to "2")
            )
        )
    }
}
