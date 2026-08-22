package edu.fnosari.momedm.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EncodingTest {
    @Test fun hexRoundTrip() {
        val b = byteArrayOf(0, 1, 0x7f, -1, 0x10)
        assertEquals("00017fff10", Hex.encode(b))
        assertArrayEquals(b, Hex.decode("00017fff10"))
    }
    @Test fun base64UrlNoPadding() {
        // "any carnal pleas" -> standard "YW55IGNhcm5hbCBwbGVhcw==" ; url-safe no pad drops '=='
        assertEquals("YW55IGNhcm5hbCBwbGVhcw", Base64Url.encodeNoPad("any carnal pleas".toByteArray()))
        assertEquals("-_8", Base64Url.encodeNoPad(byteArrayOf(-5, -1)))
    }
    @Test fun base64StdRoundTrip() {
        val b = ByteArray(32) { it.toByte() }
        assertArrayEquals(b, Base64Std.decode(Base64Std.encode(b)))
    }
}
