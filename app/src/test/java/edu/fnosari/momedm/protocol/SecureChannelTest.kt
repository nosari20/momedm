package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureChannelTest {
    private val key = ByteArray(32) { 3 }

    @Test fun sealOpenIncrementsSeq() {
        val a = SecureChannel(key, outDir = 'C', inDir = 'M'); val b = SecureChannel(key, outDir = 'M', inDir = 'C')
        val e1 = a.seal(Message.AuthOk); val e2 = a.seal(Message.Result("c", true, "ok"))
        assertEquals(1L, e1.seq); assertEquals(2L, e2.seq)
        assertEquals(Message.AuthOk, b.open(e1)); assertEquals(Message.Result("c", true, "ok"), b.open(e2))
    }
    @Test fun replayRejected() {
        val a = SecureChannel(key, outDir = 'C', inDir = 'M'); val b = SecureChannel(key, outDir = 'M', inDir = 'C')
        val e = a.seal(Message.AuthOk); b.open(e)
        assertThrows(ProtocolException::class.java) { b.open(e) }
    }
    @Test fun badMacRejected() {
        val a = SecureChannel(key, outDir = 'C', inDir = 'M'); val b = SecureChannel(ByteArray(32) { 4 }, outDir = 'M', inDir = 'C')
        assertThrows(ProtocolException::class.java) { b.open(a.seal(Message.AuthOk)) }
    }
    @Test fun tamperedBodyRejected() {
        val a = SecureChannel(key, outDir = 'C', inDir = 'M'); val b = SecureChannel(key, outDir = 'M', inDir = 'C')
        val e = a.seal(Message.Cmd("1", CmdType.KIOSK_OFF))
        assertThrows(ProtocolException::class.java) { b.open(e.copy(body = e.body.replace("KIOSK_OFF", "KIOSK_ON"))) }
    }
    @Test fun reflectedMessageRejected() {
        // A sealed envelope's mac is computed with the sender's outDir tag and verified against the
        // receiver's inDir tag. A single channel's own outDir != inDir, so it must reject its own output
        // played back to itself ("reflected") -- this is what stops a captured controller->managed frame
        // from being replayed as if it were a valid managed->controller frame, and vice versa.
        val a = SecureChannel(key, outDir = 'C', inDir = 'M')
        assertThrows(ProtocolException::class.java) { a.open(a.seal(Message.AuthOk)) }
    }
}
