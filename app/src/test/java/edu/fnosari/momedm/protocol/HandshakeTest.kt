package edu.fnosari.momedm.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {
    private val secret = ByteArray(32) { 7 }

    @Test fun successfulMutualAuth() {
        val m = ManagedHandshake(secret, "dev", "Pixel", 517, nonceC = "01".repeat(16))
        val c = ControllerHandshake(secret, nonceS = "02".repeat(16))
        val challenge = c.onHello(m.hello())
        val auth = m.onChallenge(challenge); assertNotNull(auth)
        assertTrue(c.onAuth(auth!!))
        assertArrayEquals(m.sessionKey, c.sessionKey)
    }
    @Test fun managedRejectsWrongControllerSecret() {
        val m = ManagedHandshake(secret, "dev", "Pixel", 517)
        val c = ControllerHandshake(ByteArray(32) { 9 })
        assertNull(m.onChallenge(c.onHello(m.hello())))
    }
    @Test fun controllerRejectsWrongManagedSecret() {
        val m = ManagedHandshake(ByteArray(32) { 9 }, "dev", "Pixel", 517)
        val c = ControllerHandshake(secret)
        val ch = c.onHello(m.hello())
        // forge an Auth with the wrong key
        assertFalse(c.onAuth(Message.Auth(Crypto.hmacHex(ByteArray(32) { 9 }, ch.nonceS))))
    }
}
