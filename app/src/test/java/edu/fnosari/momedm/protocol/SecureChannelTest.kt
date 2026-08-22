package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureChannelTest {
    private val key = ByteArray(32) { 3 }

    @Test fun sealOpenIncrementsSeq() {
        val a = SecureChannel(key); val b = SecureChannel(key)
        val e1 = a.seal(Message.AuthOk); val e2 = a.seal(Message.Result("c", true, "ok"))
        assertEquals(1L, e1.seq); assertEquals(2L, e2.seq)
        assertEquals(Message.AuthOk, b.open(e1)); assertEquals(Message.Result("c", true, "ok"), b.open(e2))
    }
    @Test fun replayRejected() {
        val a = SecureChannel(key); val b = SecureChannel(key)
        val e = a.seal(Message.AuthOk); b.open(e)
        assertThrows(ProtocolException::class.java) { b.open(e) }
    }
    @Test fun badMacRejected() {
        val a = SecureChannel(key); val b = SecureChannel(ByteArray(32) { 4 })
        assertThrows(ProtocolException::class.java) { b.open(a.seal(Message.AuthOk)) }
    }
    @Test fun tamperedBodyRejected() {
        val a = SecureChannel(key); val b = SecureChannel(key)
        val e = a.seal(Message.Cmd("1", CmdType.KIOSK_OFF))
        assertThrows(ProtocolException::class.java) { b.open(e.copy(body = e.body.replace("KIOSK_OFF", "KIOSK_ON"))) }
    }
}
