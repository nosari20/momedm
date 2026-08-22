package edu.fnosari.momedm.protocol

/** Sends one BLE-sized frame string over whatever transport the host provides. */
fun interface FrameSink { fun send(frame: String) }

/** True when [env] is a plain (unsealed) envelope carrying [Message.Rehello]. Never throws. */
internal fun isPlainRehello(env: Envelope): Boolean =
    env.seq == 0L && try { MessageCodec.decodeMessage(env.body) is Message.Rehello } catch (e: Exception) { false }

/** Shared chunking/reassembly for both endpoints. */
internal class FrameLayer(private val sink: FrameSink, private val clock: () -> Long) {
    private val reassembler = Reassembler()
    private var nextMsgId = 0
    var mtu: Int = 23
    fun sendEnvelope(e: Envelope) {
        val payload = MessageCodec.encodeEnvelope(e)
        nextMsgId = (nextMsgId + 1) and 0xffff
        for (f in Framer.split(nextMsgId, payload, Framer.maxChunk(mtu))) sink.send(f)
    }
    /** Returns a full envelope when [frame] completes one. @throws ProtocolException if [frame] fails to parse. */
    fun receive(frame: String): Envelope? {
        if (Framer.parse(frame) == null) throw ProtocolException("malformed frame")
        return reassembler.feed(frame, clock())?.let { MessageCodec.decodeEnvelope(it) }
    }
}

/**
 * Managed-device protocol endpoint: drives the handshake then delivers [Message.Cmd]s. Transport-agnostic.
 *
 * **Threading:** all entry points ([onConnected], [onFrame], [send], [reset]) are synchronized on this
 * instance; callbacks run on the caller's thread. The host drives this from at least two threads — BLE
 * GATT callbacks arrive on binder threads while the service's own coroutines run on the main thread —
 * and the session state (handshake progress, secure channel, sequence numbers, reassembly buffers) is
 * plain mutable state that must not be touched concurrently.
 */
class ManagedEndpoint(
    private val secret: ByteArray,
    private val deviceId: String,
    private val model: String,
    sink: FrameSink,
    private val listener: Listener,
    clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onAuthenticated()
        fun onCommand(cmd: Message.Cmd)
        fun onProtocolError(reason: String)
    }
    private val frames = FrameLayer(sink, clock)
    private var handshake: ManagedHandshake? = null
    private var channel: SecureChannel? = null
    /** True only once the controller's sealed AUTH_OK has been verified — not merely after we sent our AUTH. */
    private var confirmed = false
    /** MTU of the current link (0 = [onConnected] never called); reused when the controller asks for a REHELLO. */
    private var linkMtu = 0
    val authenticated: Boolean get() = confirmed

    /** Call once the link is up and MTU known: resets state and sends HELLO. */
    @Synchronized fun onConnected(mtu: Int) {
        reset(); frames.mtu = mtu; linkMtu = mtu
        handshake = ManagedHandshake(secret, deviceId, model, mtu).also { frames.sendEnvelope(Envelope.plain(it.hello())) }
    }
    /** Clears all session state (handshake progress, secure channel, auth confirmation). After this, the
     * endpoint only resumes when the host calls [onConnected] again — any frame belonging to the old
     * session (e.g. a replayed CHALLENGE/AUTH or a captured sealed message) is then rejected as premature. */
    @Synchronized fun reset() { handshake = null; channel = null; confirmed = false }

    @Synchronized fun onFrame(frame: String) {
        val env = try { frames.receive(frame) } catch (e: Exception) { reset(); listener.onProtocolError("bad frame: ${e.message}"); return } ?: return
        // Controller lost its session state for this link (e.g. its GATT server restarted while the BLE link
        // stayed up) and asks us to start over. Only honoured once we have a link (onConnected was called);
        // the worst an impostor on the same link could do is force a re-handshake.
        if (env.seq == 0L && linkMtu > 0 && isPlainRehello(env)) { onConnected(linkMtu); return }
        val ch = channel
        if (ch == null) {
            val hs = handshake ?: run { reset(); listener.onProtocolError("message before HELLO"); return }
            val m = try { MessageCodec.decodeMessage(env.body) } catch (e: Exception) { reset(); listener.onProtocolError("bad handshake body"); return }
            val c = m as? Message.Challenge ?: run { reset(); listener.onProtocolError("expected CHALLENGE, got ${m::class.simpleName}"); return }
            // Checked before any HMAC is computed: nonceS is peer-controlled and feeds the auth proof and
            // the session key, so only the exact shape Crypto.randomHex(16) produces is ever accepted.
            if (!isValidNonce(c.nonceS)) { reset(); listener.onProtocolError("malformed nonceS"); return }
            val auth = hs.onChallenge(c) ?: run { reset(); listener.onProtocolError("controller proof invalid"); return }
            channel = SecureChannel(hs.sessionKey, outDir = 'M', inDir = 'C')
            frames.sendEnvelope(Envelope.plain(auth))
            // Controller confirms with a sealed AUTH_OK; `authenticated` only flips true once that lands (see `confirmed`).
            return
        }
        val m = try { ch.open(env) } catch (e: ProtocolException) { reset(); listener.onProtocolError(e.message ?: "protocol error"); return }
        when (m) {
            is Message.AuthOk -> { confirmed = true; listener.onAuthenticated() }
            is Message.Cmd -> listener.onCommand(m)
            else -> { reset(); listener.onProtocolError("unexpected ${m::class.simpleName}") }
        }
    }

    /** Sends a sealed message; only valid after authentication. */
    @Synchronized fun send(m: Message) {
        val ch = channel ?: throw IllegalStateException("not authenticated")
        frames.sendEnvelope(ch.seal(m))
    }
}

/**
 * Controller-side endpoint for ONE connected managed device.
 *
 * **Threading:** all entry points ([onFrame], [send], [reset]) are synchronized on this instance;
 * callbacks run on the caller's thread. Frames arrive on GATT binder threads while sends are issued
 * from the controller's own threads, and the session state must not be mutated concurrently.
 */
class ControllerEndpoint(
    private val secret: ByteArray,
    sink: FrameSink,
    private val listener: Listener,
    clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onAuthenticated(hello: Message.Hello)
        fun onMessage(m: Message)
        fun onProtocolError(reason: String)
    }
    private val frames = FrameLayer(sink, clock)
    private var handshake: ControllerHandshake? = null
    private var channel: SecureChannel? = null
    val authenticated: Boolean get() = channel != null
    val deviceId: String? get() = handshake?.hello?.deviceId
    val mtu: Int get() = frames.mtu
    /** True once a HELLO has been received on this session (handshake started). */
    val helloReceived: Boolean get() = handshake != null

    /** Asks the peer to (re)start the handshake with a plain REHELLO. Used by the host to probe a link that
     * stays silent after (re)connecting — typically a managed device that still believes an older session
     * is alive. Harmless if the peer has no link state yet (it ignores it). */
    @Synchronized fun requestRehello() { frames.sendEnvelope(Envelope.plain(Message.Rehello)) }

    /** Clears all session state, including the negotiated MTU (reset to the pre-connection default of 23).
     * After this, the endpoint only resumes when it receives a fresh HELLO — a replayed CHALLENGE/AUTH or a
     * captured sealed message from the old session is then rejected as premature. */
    @Synchronized fun reset() { handshake = null; channel = null; frames.mtu = 23 }

    @Synchronized fun onFrame(frame: String) {
        val env = try { frames.receive(frame) } catch (e: Exception) { reset(); listener.onProtocolError("bad frame: ${e.message}"); return } ?: return
        val ch = channel
        if (ch == null) {
            // A sealed frame on a link we have no session for: the peer still believes it is authenticated
            // (our server restarted / our session was dropped while the BLE link stayed up). Ask it to
            // start over instead of erroring — a plain REHELLO makes the managed side re-send HELLO.
            if (env.seq > 0L) { frames.sendEnvelope(Envelope.plain(Message.Rehello)); return }
            val m = try { MessageCodec.decodeMessage(env.body) } catch (e: Exception) { reset(); listener.onProtocolError("bad handshake body"); return }
            when (m) {
                is Message.Hello -> {
                    // Checked before any HMAC is computed: nonceC is peer-controlled and is the sole input
                    // to the CHALLENGE proof we hand back, so only the exact shape Crypto.randomHex(16)
                    // produces is ever accepted — a chosen-length nonce can never reach the HMAC.
                    if (!isValidNonce(m.nonceC)) { reset(); listener.onProtocolError("malformed nonceC"); return }
                    // Clamp to the valid BLE ATT MTU range so a bogus/out-of-range value from the managed
                    // side can't push our outgoing chunk size below 1 or into an unnegotiated MTU.
                    frames.mtu = m.mtu.coerceIn(23, 517)
                    handshake = ControllerHandshake(secret).also { frames.sendEnvelope(Envelope.plain(it.onHello(m))) }
                }
                is Message.Auth -> {
                    val hs = handshake ?: run { reset(); listener.onProtocolError("AUTH before HELLO"); return }
                    if (!hs.onAuth(m)) { reset(); listener.onProtocolError("managed proof invalid"); return }
                    val c = SecureChannel(hs.sessionKey, outDir = 'C', inDir = 'M'); channel = c
                    frames.sendEnvelope(c.seal(Message.AuthOk))
                    listener.onAuthenticated(hs.hello!!)
                }
                else -> { reset(); listener.onProtocolError("unexpected ${m::class.simpleName} before auth") }
            }
            return
        }
        val m = try { ch.open(env) } catch (e: ProtocolException) { reset(); listener.onProtocolError(e.message ?: "protocol error"); return }
        listener.onMessage(m)
    }

    @Synchronized fun send(m: Message) {
        val ch = channel ?: throw IllegalStateException("not authenticated")
        frames.sendEnvelope(ch.seal(m))
    }
}
