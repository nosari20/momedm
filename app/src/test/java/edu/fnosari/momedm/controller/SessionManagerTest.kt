package edu.fnosari.momedm.controller

import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.ManagedEndpoint
import edu.fnosari.momedm.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private val secret = ByteArray(32) { 1 }

    /** One fake managed device wired to the session manager through in-memory frames. */
    private class Harness(secret: ByteArray, managedSecret: ByteArray = secret) {
        val toManaged = ArrayDeque<String>(); val toCtrl = ArrayDeque<String>()
        val disconnected = mutableListOf<String>(); val msgs = mutableListOf<Pair<String, Message>>(); val cmds = mutableListOf<Message.Cmd>()
        var now = 0L
        val sm = SessionManager(secret, object : SessionManager.Transport {
            override fun sendFrame(key: String, frame: String) { toManaged.add(frame) }
            override fun disconnect(key: String) { disconnected.add(key) }
        }, object : SessionManager.Events {
            override fun onAuthenticated(key: String, hello: Message.Hello) {}
            override fun onMessage(key: String, deviceId: String, m: Message) { msgs.add(deviceId to m) }
            override fun onDropped(key: String, deviceId: String?) {}
        }, clock = { now })
        val managed = ManagedEndpoint(managedSecret, "dev-A", "Pixel", { toCtrl.add(it) }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {}
            override fun onCommand(cmd: Message.Cmd) { cmds.add(cmd) }
            override fun onProtocolError(reason: String) {}
        })
        fun pump() { while (toCtrl.isNotEmpty() || toManaged.isNotEmpty()) { toCtrl.removeFirstOrNull()?.let { sm.onFrame("AA", it) }; toManaged.removeFirstOrNull()?.let { managed.onFrame(it) } } }
        fun connect() { sm.onConnected("AA"); managed.onConnected(517); pump() }
    }

    @Test fun authenticatesAndRoutesByDeviceId() {
        val h = Harness(secret); h.connect()
        assertEquals(setOf("dev-A"), h.sm.onlineDeviceIds())
        assertTrue(h.sm.send("dev-A", Message.Cmd("1", CmdType.GET_STATUS))); h.pump()
        assertEquals(CmdType.GET_STATUS, h.cmds.single().type)
        h.managed.send(Message.Result("1", true, "ok")); h.pump()
        assertEquals("dev-A" to Message.Result("1", true, "ok"), h.msgs.single())
    }
    @Test fun sendToUnknownDeviceFails() { val h = Harness(secret); h.connect(); assertFalse(h.sm.send("nope", Message.Cmd("1", CmdType.GET_STATUS))) }
    @Test fun disconnectRemovesSession() { val h = Harness(secret); h.connect(); h.sm.onDisconnected("AA"); assertTrue(h.sm.onlineDeviceIds().isEmpty()) }
    @Test fun unauthenticatedSessionIsDroppedAfterTimeout() {
        val h = Harness(secret, managedSecret = ByteArray(32) { 2 }); h.connect()
        assertTrue(h.sm.onlineDeviceIds().isEmpty())
        h.now = 6_000; h.sm.tick(h.now)
        assertEquals(listOf("AA"), h.disconnected)
    }
}
