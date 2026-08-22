package edu.fnosari.momedm.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    @Test fun challengeProofIsNotSessionKeyForConcatenatedNonce() {
        // The CHALLENGE proof is an oracle: an attacker who can forge a HELLO chooses nonceC freely and
        // gets HMAC(secret, <chosen>) back. Without domain separation, choosing nonceC = realNonceC+nonceS
        // makes that reply *be* the session key. Domain-separated inputs must break both equalities.
        val nonceC = "01".repeat(16); val nonceS = "02".repeat(16)
        val m = ManagedHandshake(secret, "dev", "Pixel", 517, nonceC = nonceC)
        val c = ControllerHandshake(secret, nonceS = nonceS)
        val auth = m.onChallenge(c.onHello(m.hello()))!!
        assertTrue(c.onAuth(auth))
        val sessionKeyHex = Hex.encode(c.sessionKey)

        // The old formula (plain concatenation, no domain tag) must no longer produce the session key.
        assertNotEquals(Crypto.hmacHex(secret, nonceC + nonceS), sessionKeyHex)

        // And the oracle itself: a forged HELLO carrying nonceC = realNonceC + nonceS yields a CHALLENGE
        // proof that is not the session key of the real session.
        val forged = ControllerHandshake(secret, nonceS = "03".repeat(16))
        val oracle = forged.onHello(Message.Hello("dev", "Pixel", nonceC + nonceS, 517))
        assertNotEquals(oracle.proof, sessionKeyHex)
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
