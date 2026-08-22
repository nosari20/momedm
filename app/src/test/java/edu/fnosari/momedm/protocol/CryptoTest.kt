package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    @Test fun hmacKnownVector() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        val mac = Crypto.hmacHex("Jefe".toByteArray(), "what do ya want for nothing?")
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac)
    }
    @Test fun randomHexLengthAndUniqueness() {
        val a = Crypto.randomHex(16); val b = Crypto.randomHex(16)
        assertEquals(32, a.length); assertNotEquals(a, b)
    }
    @Test fun constantTimeEquals() {
        assertTrue(Crypto.constantTimeEquals("abc", "abc"))
        assertFalse(Crypto.constantTimeEquals("abc", "abd"))
        assertFalse(Crypto.constantTimeEquals("abc", "abcd"))
    }
}
