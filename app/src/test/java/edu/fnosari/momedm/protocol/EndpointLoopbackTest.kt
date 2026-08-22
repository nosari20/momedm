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

    private fun build(mtu: Int, managedSecret: ByteArray = secret): Triple<Wire, MutableList<Message.Cmd>, MutableList<Message>> {
        val w = Wire(); val cmds = mutableListOf<Message.Cmd>(); val ctrlMsgs = mutableListOf<Message>()
        w.managed = ManagedEndpoint(managedSecret, "dev-1", "Pixel", { w.toController.add(it) }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {}
            override fun onCommand(cmd: Message.Cmd) { cmds.add(cmd) }
            override fun onProtocolError(reason: String) {}
        })
        w.controller = ControllerEndpoint(secret, { w.toManaged.add(it) }, object : ControllerEndpoint.Listener {
            override fun onAuthenticated(hello: Message.Hello) {}
            override fun onMessage(m: Message) { ctrlMsgs.add(m) }
            override fun onProtocolError(reason: String) {}
        })
        w.managed.onConnected(mtu); w.pump()
        return Triple(w, cmds, ctrlMsgs)
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
        w.pump()
        assertEquals(apps, ctrlMsgs.last())
    }

    @Test fun wrongSecretNeverAuthenticates() {
        val (w, _, _) = build(517, managedSecret = ByteArray(32) { 6 })
        assertFalse(w.managed.authenticated); assertFalse(w.controller.authenticated)
    }
}
