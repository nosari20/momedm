package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointLoopbackTest {
    private val secret = ByteArray(32) { 5 }

    /** Wires two endpoints through in-memory frame lists, delivering frames in order. */
    private class Wire {
        val toController = ArrayDeque<String>(); val toManaged = ArrayDeque<String>()
        lateinit var managed: ManagedEndpoint; lateinit var controller: ControllerEndpoint
        fun pump() { while (toController.isNotEmpty() || toManaged.isNotEmpty()) {
            toController.removeFirstOrNull()?.let { controller.onFrame(it) }
            toManaged.removeFirstOrNull()?.let { managed.onFrame(it) } } }
    }

    /** [wire], the delivered [Message.Cmd]s, the delivered controller-side [Message]s, and every reason
     * string reported to each side's `onProtocolError`, so error-path tests can assert on them directly. */
    private data class Built(
        val wire: Wire,
        val cmds: MutableList<Message.Cmd>,
        val ctrlMsgs: MutableList<Message>,
        val managedErrors: MutableList<String>,
        val ctrlErrors: MutableList<String>,
    )

    private fun build(mtu: Int, managedSecret: ByteArray = secret): Built {
        val w = Wire(); val cmds = mutableListOf<Message.Cmd>(); val ctrlMsgs = mutableListOf<Message>()
        val managedErrors = mutableListOf<String>(); val ctrlErrors = mutableListOf<String>()
        w.managed = ManagedEndpoint(managedSecret, "dev-1", "Pixel", { w.toController.add(it) }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {}
            override fun onCommand(cmd: Message.Cmd) { cmds.add(cmd) }
            override fun onProtocolError(reason: String) { managedErrors.add(reason) }
        })
        w.controller = ControllerEndpoint(secret, { w.toManaged.add(it) }, object : ControllerEndpoint.Listener {
            override fun onAuthenticated(hello: Message.Hello) {}
            override fun onMessage(m: Message) { ctrlMsgs.add(m) }
            override fun onProtocolError(reason: String) { ctrlErrors.add(reason) }
        })
        w.managed.onConnected(mtu); w.pump()
        return Built(w, cmds, ctrlMsgs, managedErrors, ctrlErrors)
    }

    @Test fun handshakeAndCommandAtMtu517() {
        val (w, cmds, ctrlMsgs) = build(517)
        assertTrue(w.managed.authenticated); assertTrue(w.controller.authenticated)
        assertEquals("dev-1", w.controller.deviceId); assertEquals(517, w.controller.mtu)
        w.controller.send(Message.Cmd("c1", CmdType.KIOSK_ON, "com.example")); w.pump()
        assertEquals(listOf(Message.Cmd("c1", CmdType.KIOSK_ON, "com.example")), cmds)
        w.managed.send(Message.Result("c1", true, "ok")); w.pump()
        assertEquals(Message.Result("c1", true, "ok"), ctrlMsgs.last())
    }

    @Test fun bigMessageAtMtu23IsChunked() {
        val (w, _, ctrlMsgs) = build(23)
        val apps = Message.Apps((1..40).map { AppInfo("com.pkg.number$it", "Application numéro $it") })
        w.managed.send(apps)
        assertTrue(w.toController.size > 100)   // many 5-char chunks
        assertTrue(w.toController.all { it.length <= 23 - 3 }) // every frame fits the ATT payload budget
        w.pump()
        assertEquals(apps, ctrlMsgs.last())
    }

    @Test fun wrongSecretNeverAuthenticates() {
        val (w, _, _) = build(517, managedSecret = ByteArray(32) { 6 })
        assertFalse(w.managed.authenticated); assertFalse(w.controller.authenticated)
    }

    @Test fun helloMtuIsClampedOnController() {
        // A managed device that reports an out-of-range MTU must not be able to push the controller's
        // outgoing chunk size out of the valid BLE ATT MTU range [23, 517].
        val tooLow = build(0)
        assertEquals(23, tooLow.wire.controller.mtu)
        val tooHigh = build(10_000)
        assertEquals(517, tooHigh.wire.controller.mtu)
    }

    @Test fun controllerSessionLossRecoversViaRehello() {
        // The controller's GATT server restarted (or it dropped the session) while the BLE link stayed up:
        // the managed side still believes it is authenticated and keeps sending sealed frames. The controller
        // must answer with a plain REHELLO and the managed side must re-run the handshake.
        val b = build(517); val w = b.wire
        assertTrue(w.managed.authenticated)
        w.controller.reset()                                 // simulate controller-side session loss
        w.managed.send(Message.Status(false, null, false, 50, null)); w.pump()
        assertTrue(w.managed.authenticated); assertTrue(w.controller.authenticated)
        assertEquals("dev-1", w.controller.deviceId)
        assertTrue(b.managedErrors.isEmpty())
        w.controller.send(Message.Cmd("c9", CmdType.GET_STATUS)); w.pump()
        assertEquals(CmdType.GET_STATUS, b.cmds.last().type)
    }

    @Test fun rehelloBeforeAnyLinkIsIgnored() {
        // A REHELLO must not make a never-connected endpoint emit a HELLO (there is no link/mtu yet).
        val sent = mutableListOf<String>()
        val managed = ManagedEndpoint(secret, "dev-1", "Pixel", { sent.add(it) }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {}
            override fun onCommand(cmd: Message.Cmd) {}
            override fun onProtocolError(reason: String) {}
        })
        for (f in frames(Message.Rehello)) managed.onFrame(f)
        assertTrue(sent.isEmpty())
    }

    @Test fun malformedFrameTriggersProtocolError() {
        val b = build(517)
        b.wire.controller.onFrame("garbage")
        assertTrue(b.ctrlErrors.isNotEmpty())
    }

    /** Encodes [m] as a plain (unsealed) envelope and splits it into transport frames. */
    private fun frames(m: Message): List<String> =
        Framer.split(1, MessageCodec.encodeEnvelope(Envelope.plain(m)), 1000)

    @Test fun malformedNonceRejected() {
        // Nonces are the only attacker-controlled input to the handshake HMACs. Anything that is not
        // exactly 32 lower-case hex chars (what Crypto.randomHex(16) produces) is a protocol error on
        // both sides, checked before any proof is computed — so a chosen-length/chosen-content nonce
        // can never reach the HMAC at all.
        val ctrlErrors = mutableListOf<String>()
        val controller = ControllerEndpoint(secret, { }, object : ControllerEndpoint.Listener {
            override fun onAuthenticated(hello: Message.Hello) {}
            override fun onMessage(m: Message) {}
            override fun onProtocolError(reason: String) { ctrlErrors.add(reason) }
        })
        for (f in frames(Message.Hello("dev-1", "Pixel", "abc", 517))) controller.onFrame(f)
        assertFalse(controller.authenticated)
        assertTrue(ctrlErrors.isNotEmpty())

        val managedErrors = mutableListOf<String>()
        val managed = ManagedEndpoint(secret, "dev-1", "Pixel", { }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {}
            override fun onCommand(cmd: Message.Cmd) {}
            override fun onProtocolError(reason: String) { managedErrors.add(reason) }
        })
        managed.onConnected(517)
        // 31 hex chars: one short of a valid nonce. The proof is irrelevant — the nonce is rejected first.
        for (f in frames(Message.Challenge("0".repeat(31), "00".repeat(32)))) managed.onFrame(f)
        assertFalse(managed.authenticated)
        assertTrue(managedErrors.isNotEmpty())
    }

    private fun corruptMac(frame: String): String {
        val key = "\"mac\":\""
        val idx = frame.indexOf(key)
        require(idx >= 0) { "no mac field in frame: $frame" }
        val charIdx = idx + key.length
        val flipped = if (frame[charIdx] == '0') '1' else '0'
        return frame.substring(0, charIdx) + flipped + frame.substring(charIdx + 1)
    }

    @Test fun replayAfterProtocolErrorIsRejected() {
        // A MAC failure must wipe the whole session (handshake + channel), not just the channel: otherwise
        // a captured CHALLENGE/AUTH exchange (or a captured sealed command) can be replayed afterwards and
        // silently re-derive/reuse the same session.
        val toManaged = ArrayDeque<String>(); val toController = ArrayDeque<String>()
        val cmds = mutableListOf<Message.Cmd>()
        lateinit var managed: ManagedEndpoint; lateinit var controller: ControllerEndpoint
        managed = ManagedEndpoint(secret, "dev-1", "Pixel", { toController.add(it) }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {}
            override fun onCommand(cmd: Message.Cmd) { cmds.add(cmd) }
            override fun onProtocolError(reason: String) {}
        })
        controller = ControllerEndpoint(secret, { toManaged.add(it) }, object : ControllerEndpoint.Listener {
            override fun onAuthenticated(hello: Message.Hello) {}
            override fun onMessage(m: Message) {}
            override fun onProtocolError(reason: String) {}
        })
        fun pumpOnce(from: ArrayDeque<String>, to: (String) -> Unit) { while (from.isNotEmpty()) to(from.removeFirst()) }

        // 1. HELLO -> controller; capture the CHALLENGE frame(s) it replies with.
        managed.onConnected(517)
        pumpOnce(toController) { controller.onFrame(it) }
        val challengeFrames = toManaged.toList()

        // 2. Drain the handshake to completion (CHALLENGE -> managed -> AUTH -> controller -> AUTH_OK -> managed).
        pumpOnce(toManaged) { managed.onFrame(it) }
        pumpOnce(toController) { controller.onFrame(it) }
        pumpOnce(toManaged) { managed.onFrame(it) }
        assertTrue(managed.authenticated); assertTrue(controller.authenticated)

        // 3. A legitimate command, delivered once; capture its sealed frame(s) too.
        controller.send(Message.Cmd("c1", CmdType.KIOSK_ON, "com.example"))
        val cmdFrames = toManaged.toList()
        pumpOnce(toManaged) { managed.onFrame(it) }
        assertEquals(1, cmds.size)

        // 4. Corrupt the mac of the captured command frame and feed it straight to the managed endpoint.
        val corrupted = cmdFrames.map { corruptMac(it) }
        for (f in corrupted) managed.onFrame(f)
        assertFalse(managed.authenticated)

        // 5. Replay the captured CHALLENGE and (uncorrupted) Cmd frames: with the session wiped, the managed
        //    endpoint has no handshake in progress and no channel, so both must be silently rejected.
        for (f in challengeFrames) managed.onFrame(f)
        for (f in cmdFrames) managed.onFrame(f)
        assertEquals(1, cmds.size) // unchanged
        assertFalse(managed.authenticated)
    }
}
