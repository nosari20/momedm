package edu.fnosari.momedm.controller

import edu.fnosari.momedm.protocol.ControllerEndpoint
import edu.fnosari.momedm.protocol.Message

/** Tracks one [ControllerEndpoint] per connected central (keyed by BLE address). Pure Kotlin. */
class SessionManager(
    private val secret: ByteArray,
    private val transport: Transport,
    private val events: Events,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object { const val AUTH_TIMEOUT_MS = 5_000L }
    interface Transport { fun sendFrame(key: String, frame: String); fun disconnect(key: String) }
    interface Events { fun onAuthenticated(key: String, hello: Message.Hello); fun onMessage(key: String, deviceId: String, m: Message); fun onDropped(key: String, deviceId: String?) }

    private class Session(val key: String, val endpoint: ControllerEndpoint, val connectedAt: Long)
    private val sessions = LinkedHashMap<String, Session>()

    @Synchronized fun onConnected(key: String) {
        val ep = ControllerEndpoint(secret, { f -> transport.sendFrame(key, f) }, object : ControllerEndpoint.Listener {
            override fun onAuthenticated(hello: Message.Hello) {
                // one session per deviceId: drop an older link of the same device
                sessions.values.filter { it.key != key && it.endpoint.deviceId == hello.deviceId }.forEach { transport.disconnect(it.key) }
                events.onAuthenticated(key, hello)
            }
            override fun onMessage(m: Message) { sessions[key]?.endpoint?.deviceId?.let { events.onMessage(key, it, m) } }
            override fun onProtocolError(reason: String) { transport.disconnect(key) }
        }, clock)
        sessions[key] = Session(key, ep, clock())
    }
    @Synchronized fun onDisconnected(key: String) { sessions.remove(key)?.let { events.onDropped(key, it.endpoint.deviceId) } }
    @Synchronized fun onFrame(key: String, frame: String) { sessions[key]?.endpoint?.onFrame(frame) }
    @Synchronized fun send(deviceId: String, m: Message): Boolean {
        val s = sessions.values.firstOrNull { it.endpoint.authenticated && it.endpoint.deviceId == deviceId } ?: return false
        return try { s.endpoint.send(m); true } catch (e: IllegalStateException) { false }
    }
    @Synchronized fun onlineDeviceIds(): Set<String> = sessions.values.filter { it.endpoint.authenticated }.mapNotNull { it.endpoint.deviceId }.toSet()
    /** Disconnects centrals that have not authenticated within [AUTH_TIMEOUT_MS]. Call periodically. */
    @Synchronized fun tick(nowMs: Long) {
        sessions.values.filter { !it.endpoint.authenticated && nowMs - it.connectedAt > AUTH_TIMEOUT_MS }.forEach { transport.disconnect(it.key) }
    }
}
