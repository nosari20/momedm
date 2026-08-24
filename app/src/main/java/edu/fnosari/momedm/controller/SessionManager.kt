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
    companion object {
        /** A session that has not authenticated this long after (re)connecting is disconnected and forgotten. */
        const val AUTH_TIMEOUT_MS = 5_000L
        /** A (re)connected session that has not even sent HELLO after this long is probed once with REHELLO —
         * it is most likely a managed device whose link survived a controller-side restart and that still
         * believes an older session is alive. A fresh HELLO arrives well within this window. */
        const val REHELLO_AFTER_MS = 3_000L
    }
    interface Transport { fun sendFrame(key: String, frame: String); fun disconnect(key: String) }
    interface Events { fun onAuthenticated(key: String, hello: Message.Hello); fun onMessage(key: String, deviceId: String, m: Message); fun onDropped(key: String, deviceId: String?) }

    private class Session(val key: String, val endpoint: ControllerEndpoint, var connectedAt: Long) {
        /** REHELLO probe already sent (at most one per session). */
        var probed = false
    }
    private val sessions = LinkedHashMap<String, Session>()

    /** Starts tracking a newly connected central. Replaces (and drops) any stale session already at [key]. */
    @Synchronized fun onConnected(key: String) {
        sessions.remove(key)?.let { events.onDropped(key, it.endpoint.deviceId) }
        val ep = ControllerEndpoint(secret, { f -> transport.sendFrame(key, f) }, object : ControllerEndpoint.Listener {
            override fun onAuthenticated(hello: Message.Hello) {
                // One session per deviceId, and the NEWER link wins. Deliberate, and load-bearing:
                // BLE links die without a clean disconnect all the time, so a child coming back has to
                // be able to reclaim its slot. Refusing the newcomer instead — which is what a review
                // suggested, to stop one child evicting a sibling by claiming its id — would mean a
                // device whose link died silently could not reconnect until the stale session aged
                // out, and the parent would have lost control of it in the meantime.
                //
                // That the id is self-asserted, and that every child of one parent shares a secret, is
                // real: a sibling can knock another out of the parent's list. It costs presence in the
                // UI, not enforcement — the evicted child keeps applying its rules locally, since none
                // of that depends on the link. Per-device secrets would close it, and have been
                // deliberately rejected: one secret and one PIN for the whole family is the product
                // decision (docs/architecture.md, Known limitations). So this is a consequence to
                // live with, not a bug awaiting a fix. Pinned by
                // SessionManagerTest.duplicateDeviceIdDisconnectsOlderLink.
                sessions.values.filter { it.key != key && it.endpoint.deviceId == hello.deviceId }.forEach { transport.disconnect(it.key) }
                events.onAuthenticated(key, hello)
            }
            override fun onMessage(m: Message) { sessions[key]?.endpoint?.deviceId?.let { events.onMessage(key, it, m) } }
            override fun onProtocolError(reason: String) { transport.disconnect(key) }
        }, clock)
        sessions[key] = Session(key, ep, clock())
    }
    /** Stops tracking the central at [key] (e.g. after the transport reports a disconnect). */
    @Synchronized fun onDisconnected(key: String) { sessions.remove(key)?.let { events.onDropped(key, it.endpoint.deviceId) } }
    /** Feeds one raw frame received from the central at [key] into its session's endpoint. Returns false —
     * and feeds nothing — when [key] has no session (the BLE link outlived our session state, e.g. after a
     * server restart or an auth timeout): the transport should then fail the write so the peer notices the
     * dead session and reconnects, which is the only reliable recovery when notifications no longer reach it. */
    @Synchronized fun onFrame(key: String, frame: String): Boolean {
        val s = sessions[key] ?: return false
        s.endpoint.onFrame(frame)
        return true
    }
    /** Sends [m] to the authenticated session for [deviceId]. Returns false if no such session exists. */
    @Synchronized fun send(deviceId: String, m: Message): Boolean {
        val s = sessions.values.firstOrNull { it.endpoint.authenticated && it.endpoint.deviceId == deviceId } ?: return false
        return try { s.endpoint.send(m); true } catch (e: IllegalStateException) { false }
    }
    /** Device ids of all currently authenticated sessions. */
    @Synchronized fun onlineDeviceIds(): Set<String> = sessions.values.filter { it.endpoint.authenticated }.mapNotNull { it.endpoint.deviceId }.toSet()
    /** Probes silent sessions with REHELLO after [REHELLO_AFTER_MS], and disconnects and forgets centrals that
     * have not authenticated within [AUTH_TIMEOUT_MS] (measured from connect or from the probe). Call
     * periodically; idempotent — a session is only ever probed once and disconnected/dropped once. */
    @Synchronized fun tick(nowMs: Long) {
        // Probe silent (no HELLO yet) sessions once; the probe also restarts their auth-timeout clock so a
        // peer that answers the REHELLO has the full AUTH_TIMEOUT_MS to complete the new handshake.
        sessions.values.filter { !it.endpoint.helloReceived && !it.probed && nowMs - it.connectedAt > REHELLO_AFTER_MS }.forEach {
            it.probed = true; it.connectedAt = nowMs; it.endpoint.requestRehello()
        }
        val timedOut = sessions.values.filter { !it.endpoint.authenticated && nowMs - it.connectedAt > AUTH_TIMEOUT_MS }
        for (s in timedOut) {
            sessions.remove(s.key)
            transport.disconnect(s.key)
            events.onDropped(s.key, null)
        }
    }
}
