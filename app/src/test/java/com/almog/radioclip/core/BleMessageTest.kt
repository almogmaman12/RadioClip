package com.almog.radioclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BleMessageTest {
    @Test
    fun textRoundTripKeepsIdAndUnicode() {
        val id = UUID.randomUUID().toString()
        val text = "hello שלום 📋\nsecond line"
        val decoded = BleMessages.decode(BleMessages.encodeText(id, text))
        assertEquals(BleMessage.Text(id, text), decoded)
    }

    @Test
    fun emptyTextIsAllowed() {
        val id = UUID.randomUUID().toString()
        assertEquals(BleMessage.Text(id, ""), BleMessages.decode(BleMessages.encodeText(id, "")))
    }

    @Test
    fun nonUuidIdsStillRoundTripToAStableId() {
        val a = BleMessages.decode(BleMessages.encodeText("not-a-uuid", "x")) as BleMessage.Text
        val b = BleMessages.decode(BleMessages.encodeText("not-a-uuid", "y")) as BleMessage.Text
        assertEquals(a.id, b.id)
    }

    @Test
    fun helloRoundTrip() {
        assertEquals(BleMessage.Hello("Pixel 7"), BleMessages.decode(BleMessages.encodeHello("Pixel 7")))
    }

    @Test
    fun helloNamesAreCleaned() {
        assertEquals(
            BleMessage.Hello("Almog"),
            BleMessages.decode(BleMessages.encodeHello("  Al\u0000mog\n "))
        )
        val long = BleMessages.decode(BleMessages.encodeHello("x".repeat(500))) as BleMessage.Hello
        assertEquals(Settings.MAX_NAME_LENGTH, long.deviceName.length)
    }

    @Test
    fun helloTruncationNeverSplitsACharacter() {
        val emoji = "📱".repeat(Settings.MAX_NAME_LENGTH) // 4 bytes each
        val decoded = BleMessages.decode(BleMessages.encodeHello(emoji)) as BleMessage.Hello
        assertTrue(decoded.deviceName.all { it != '�' })
        assertTrue(decoded.deviceName.isNotEmpty())
    }

    @Test
    fun blankHelloIsRejected() {
        assertNull(BleMessages.decode(BleMessages.encodeHello("   ")))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(BleMessages.decode(ByteArray(0)))
        assertNull(BleMessages.decode(byteArrayOf(99, 1, 2, 3)))
        assertNull(BleMessages.decode(byteArrayOf(1, 0, 0)))   // text message too short for its id
    }

    @Test
    fun helloCarriesNodeId() {
        assertEquals(
            BleMessage.Hello("Pixel", -123456),
            BleMessages.decode(BleMessages.encodeHello("Pixel", -123456))
        )
    }

    @Test
    fun truncatedHelloIsRejected() {
        assertNull(BleMessages.decode(byteArrayOf(2, 0, 0)))
    }
}
