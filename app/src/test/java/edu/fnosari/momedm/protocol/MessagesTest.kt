package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    @Test fun roundTripAllTypes() {
        val msgs = listOf(
            Message.Hello("d1", "Pixel", "aa", 517), Message.Challenge("bb", "cc"), Message.Auth("dd"), Message.AuthOk, Message.Rehello, Message.Ping,
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
    @Test fun cmdWithAppsPinnedAndPrefsRoundTrips() {
        val prefs = ChildPrefs(language = "fr", theme = "dark", accent = 0xFF1D6FA5.toInt(), pinSalt = "00".repeat(16), pinHash = "ab".repeat(32))
        val cmd = Message.Cmd("c1", CmdType.KIOSK_ON, apps = listOf("com.a", "com.b"), pinned = "com.a")
        val set = Message.Cmd("c2", CmdType.SET_PREFS, prefs = prefs)
        assertEquals(cmd, MessageCodec.decodeMessage(MessageCodec.encodeMessage(cmd)))
        assertEquals(set, MessageCodec.decodeMessage(MessageCodec.encodeMessage(set)))
        // old-style Cmd without the new fields still decodes (defaults)
        val legacy = MessageCodec.decodeMessage("""{"t":"CMD","id":"x","type":"KIOSK_OFF"}""") as Message.Cmd
        assertEquals(emptyList<String>(), legacy.apps); assertEquals(null, legacy.pinned); assertEquals(null, legacy.prefs)
    }
    @Test fun statusNewFieldsRoundTripAndDefault() {
        val s = Message.Status(true, "com.a", false, 50, "com.a", kioskApps = listOf("com.a", "com.b"), kioskPaused = true, pauseEndsAt = 123L)
        assertEquals(s, MessageCodec.decodeMessage(MessageCodec.encodeMessage(s)))
        val old = MessageCodec.decodeMessage("""{"t":"STATUS","kiosk":false,"kioskPkg":null,"account":false,"battery":1,"currentApp":null}""") as Message.Status
        assertEquals(emptyList<String>(), old.kioskApps); assertFalse(old.kioskPaused); assertEquals(null, old.pauseEndsAt)
    }
    @Test fun childPrefsSanitized() {
        val p = ChildPrefs(language = "de", theme = "neon", accent = 1).sanitized()
        assertEquals("system", p.language); assertEquals("system", p.theme); assertEquals(1, p.accent)
        assertEquals(ChildPrefs(), ChildPrefs(language = "system", theme = "system").sanitized())
        // A half or malformed PIN pair is dropped wholesale: PinHash.verify would throw on it.
        assertEquals(ChildPrefs(), ChildPrefs(pinSalt = "00".repeat(16)).sanitized())
        assertEquals(ChildPrefs(), ChildPrefs(pinHash = "ab".repeat(32)).sanitized())
        assertEquals(ChildPrefs(), ChildPrefs(pinSalt = "0".repeat(31), pinHash = "ab".repeat(32)).sanitized())
        assertEquals(ChildPrefs(), ChildPrefs(pinSalt = "ZZ".repeat(16), pinHash = "ab".repeat(32)).sanitized())
        assertEquals(ChildPrefs(), ChildPrefs(pinSalt = "00".repeat(16), pinHash = "ab".repeat(31)).sanitized())
        val valid = ChildPrefs(pinSalt = "00".repeat(16), pinHash = "ab".repeat(32))
        assertEquals(valid, valid.sanitized())
    }

    @Test fun scheduleCommandRoundTrips() {
        val s = LockSchedule(enabled = true, weekdayStart = 20 * 60, weekdayEnd = 6 * 60)
        val cmd = Message.Cmd("c1", CmdType.SET_SCHEDULE, schedule = s)
        val back = MessageCodec.decodeMessage(MessageCodec.encodeMessage(cmd)) as Message.Cmd
        assertEquals(CmdType.SET_SCHEDULE, back.type); assertEquals(s, back.schedule)
    }

    @Test fun lockCommandsRoundTrip() {
        for (t in listOf(CmdType.LOCK_NOW, CmdType.UNLOCK)) {
            val back = MessageCodec.decodeMessage(MessageCodec.encodeMessage(Message.Cmd("c2", t))) as Message.Cmd
            assertEquals(t, back.type)
        }
    }

    @Test fun statusCarriesLockFields() {
        val s = Message.Status(kiosk = false, kioskPkg = null, account = false, battery = 50, currentApp = null,
            locked = true, lockReason = LockState.REASON_NIGHT, lockUntil = 1_800_000_000_000L,
            schedule = LockSchedule(enabled = true))
        val back = MessageCodec.decodeMessage(MessageCodec.encodeMessage(s)) as Message.Status
        assertEquals(true, back.locked); assertEquals("night", back.lockReason)
        assertEquals(1_800_000_000_000L, back.lockUntil); assertEquals(LockSchedule(enabled = true), back.schedule)
    }

    @Test fun statusLockFieldsDefaultWhenAbsent() {
        // A peer that predates this feature sends no lock fields; decoding must not fail.
        val json = """{"t":"STATUS","kiosk":false,"kioskPkg":null,"account":false,"battery":10,"currentApp":null}"""
        val back = MessageCodec.decodeMessage(json) as Message.Status
        assertEquals(false, back.locked); assertEquals(null, back.lockReason)
        assertEquals(null, back.lockUntil); assertEquals(null, back.schedule)
    }
}
