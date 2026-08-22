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

    /** Drives one [SessionManager] against any number of fake managed peers (each a [ManagedEndpoint]),
     * keyed by BLE address, routed through in-memory frame queues. */
    private class Rig(private val secret: ByteArray) {
        val disconnected = mutableListOf<String>()
        val dropped = mutableListOf<Pair<String, String?>>()
        val msgs = mutableListOf<Pair<String, Message>>()
        var now = 0L
        private val peers = LinkedHashMap<String, Peer>()
        val sm = SessionManager(secret, object : SessionManager.Transport {
            override fun sendFrame(key: String, frame: String) { peers.getValue(key).toManaged.add(frame) }
            override fun disconnect(key: String) { disconnected.add(key) }
        }, object : SessionManager.Events {
            override fun onAuthenticated(key: String, hello: Message.Hello) {}
            override fun onMessage(key: String, deviceId: String, m: Message) { msgs.add(deviceId to m) }
            override fun onDropped(key: String, deviceId: String?) { dropped.add(key to deviceId) }
        }, clock = { now })

        private class Peer(secret: ByteArray, deviceId: String) {
            val toManaged = ArrayDeque<String>(); val toCtrl = ArrayDeque<String>(); val cmds = mutableListOf<Message.Cmd>()
            val managed = ManagedEndpoint(secret, deviceId, "Pixel", { toCtrl.add(it) }, object : ManagedEndpoint.Listener {
                override fun onAuthenticated() {}
                override fun onCommand(cmd: Message.Cmd) { cmds.add(cmd) }
                override fun onProtocolError(reason: String) {}
            })
        }

        /** Registers a fake managed device at [key] with [deviceId] and runs the handshake to completion. */
        fun connect(key: String, deviceId: String, managedSecret: ByteArray = secret) {
            peers[key] = Peer(managedSecret, deviceId)
            sm.onConnected(key)
            peers.getValue(key).managed.onConnected(517)
            pump()
        }
        fun cmds(key: String) = peers.getValue(key).cmds
        fun sendFromManaged(key: String, m: Message) { peers.getValue(key).managed.send(m) }
        fun pump() {
            var progressed = true
            while (progressed) {
                progressed = false
                for ((key, peer) in peers) {
                    peer.toCtrl.removeFirstOrNull()?.let { sm.onFrame(key, it); progressed = true }
                    peer.toManaged.removeFirstOrNull()?.let { peer.managed.onFrame(it); progressed = true }
                }
            }
        }
    }

    @Test fun authenticatesAndRoutesByDeviceId() {
        val r = Rig(secret); r.connect("AA", "dev-A")
        assertEquals(setOf("dev-A"), r.sm.onlineDeviceIds())
        assertTrue(r.sm.send("dev-A", Message.Cmd("1", CmdType.GET_STATUS))); r.pump()
        assertEquals(CmdType.GET_STATUS, r.cmds("AA").single().type)
        r.sendFromManaged("AA", Message.Result("1", true, "ok")); r.pump()
        assertEquals("dev-A" to Message.Result("1", true, "ok"), r.msgs.single())
    }
    @Test fun sendToUnknownDeviceFails() { val r = Rig(secret); r.connect("AA", "dev-A"); assertFalse(r.sm.send("nope", Message.Cmd("1", CmdType.GET_STATUS))) }
    @Test fun disconnectRemovesSession() { val r = Rig(secret); r.connect("AA", "dev-A"); r.sm.onDisconnected("AA"); assertTrue(r.sm.onlineDeviceIds().isEmpty()) }
    @Test fun unauthenticatedSessionIsDroppedAfterTimeout() {
        val r = Rig(secret); r.connect("AA", "dev-A", managedSecret = ByteArray(32) { 2 })
        assertTrue(r.sm.onlineDeviceIds().isEmpty())
        r.now = 6_000; r.sm.tick(r.now)
        assertEquals(listOf("AA"), r.disconnected)
        assertEquals(listOf("AA" to null), r.dropped)
        // session was removed on the first tick: a second tick must not disconnect it again
        r.now = 7_000; r.sm.tick(r.now)
        assertEquals(listOf("AA"), r.disconnected)
    }
    @Test fun duplicateDeviceIdDisconnectsOlderLink() {
        val r = Rig(secret)
        r.connect("AA", "dev-A")
        r.connect("BB", "dev-A")
        assertEquals(listOf("AA"), r.disconnected)
    }
    @Test fun frameFromForgottenKeyIsRejected() {
        // The controller dropped the session (auth timeout / server restart) but the BLE link survived: the
        // managed peer keeps sending sealed frames on the same key. onFrame must report false (the transport
        // then fails the write) and must not resurrect a session for it — the peer reconnects instead.
        val r = Rig(secret); r.connect("AA", "dev-A")
        r.sm.onDisconnected("AA")
        assertFalse(r.sm.onFrame("AA", "0001:0/1:x"))
        assertTrue(r.sm.onlineDeviceIds().isEmpty())
        assertFalse(r.sm.onFrame("BB", "0001:0/1:x"))
    }
    @Test fun silentStaleLinkIsProbedWithRehello() {
        // Server restart: the BLE link re-attaches (onConnected fires again) but the managed peer believes it
        // is authenticated and sends nothing. After REHELLO_AFTER_MS the tick must probe it with REHELLO so it
        // re-handshakes — without waiting for its next periodic STATUS.
        val r = Rig(secret); r.connect("AA", "dev-A")
        r.sm.onDisconnected("AA"); r.now = 10_000; r.sm.onConnected("AA")   // stale link, peer silent
        r.now = 10_000 + SessionManager.REHELLO_AFTER_MS + 1; r.sm.tick(r.now); r.pump()
        assertEquals(setOf("dev-A"), r.sm.onlineDeviceIds())
        assertTrue(r.disconnected.isEmpty())
        // a probed-but-still-silent session is still dropped after the auth timeout
        val r2 = Rig(secret); r2.connect("BB", "dev-B", managedSecret = ByteArray(32) { 9 })
        r2.now = SessionManager.REHELLO_AFTER_MS + 1; r2.sm.tick(r2.now)
        r2.now = SessionManager.REHELLO_AFTER_MS + SessionManager.AUTH_TIMEOUT_MS + 2; r2.sm.tick(r2.now)
        assertEquals(listOf("BB"), r2.disconnected)
    }
    @Test fun protocolErrorDisconnectsKey() {
        val r = Rig(secret); r.connect("AA", "dev-A")
        r.sm.onFrame("AA", "not a valid frame")
        assertEquals(listOf("AA"), r.disconnected)
    }
}
