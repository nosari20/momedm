package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {
    @Test fun deterministicAndSaltDependent() {
        val salt = "0123456789abcdef0123456789abcdef"
        val h1 = PinHash.hash("1234", salt); val h2 = PinHash.hash("1234", salt)
        assertEquals(h1, h2); assertEquals(64, h1.length)
        assertNotEquals(h1, PinHash.hash("1234", "ffffffffffffffffffffffffffffffff"))
        assertNotEquals(h1, PinHash.hash("1235", salt))
    }
    @Test fun verify() {
        val salt = PinHash.newSalt(); assertEquals(32, salt.length)
        val h = PinHash.hash("482913", salt)
        assertTrue(PinHash.verify("482913", salt, h)); assertFalse(PinHash.verify("482914", salt, h)); assertFalse(PinHash.verify("", salt, h))
    }
    @Test fun pinValidation() {
        assertTrue(PinHash.isValidPin("1234")); assertTrue(PinHash.isValidPin("123456"))
        assertFalse(PinHash.isValidPin("123")); assertFalse(PinHash.isValidPin("1234567")); assertFalse(PinHash.isValidPin("12a4")); assertFalse(PinHash.isValidPin(" 1234"))
    }
}
