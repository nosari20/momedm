package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    @Test fun roundTripAllTypes() {
        val msgs = listOf(
            Message.Hello("d1", "Pixel", "aa", 517), Message.Challenge("bb", "cc"), Message.Auth("dd"), Message.AuthOk,
            Message.Status(true, "com.x", false, 77, "com.x"), Message.Apps(listOf(AppInfo("com.a", "A"))),
            Message.Result("c1", true, "ok"), Message.Cmd("c1", CmdType.KIOSK_ON, "com.a"), Message.Cmd("c2", CmdType.GET_STATUS, null),
        )
        for (m in msgs) assertEquals(m, MessageCodec.decodeMessage(MessageCodec.encodeMessage(m)))
    }
    @Test fun typeDiscriminatorIsT() {
        assertTrue(MessageCodec.encodeMessage(Message.AuthOk).contains("\"t\":\"AUTH_OK\""))
    }
    @Test fun nonAsciiIsEscaped() {
        val enc = MessageCodec.encodeMessage(Message.Apps(listOf(AppInfo("p", "Paramètres 日本"))))
        assertTrue(enc.all { it.code in 0x20..0x7e })
        assertEquals("Paramètres 日本", (MessageCodec.decodeMessage(enc) as Message.Apps).apps[0].label)
    }
    @Test fun envelopeRoundTrip() {
        val e = Envelope(7, MessageCodec.encodeMessage(Message.AuthOk), "ab")
        assertEquals(e, MessageCodec.decodeEnvelope(MessageCodec.encodeEnvelope(e)))
        assertEquals(0L, Envelope.plain(Message.AuthOk).seq)
    }
}
