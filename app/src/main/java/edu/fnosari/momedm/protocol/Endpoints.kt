package edu.fnosari.momedm.protocol

/** Sends one BLE-sized frame string over whatever transport the host provides. */
fun interface FrameSink { fun send(frame: String) }

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
    /** Returns a full envelope when [frame] completes one. */
    fun receive(frame: String): Envelope? = reassembler.feed(frame, clock())?.let { MessageCodec.decodeEnvelope(it) }
}

/** Managed-device protocol endpoint: drives the handshake then delivers [Message.Cmd]s. Transport-agnostic. */
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
    val authenticated: Boolean get() = channel != null

    /** Call once the link is up and MTU known: resets state and sends HELLO. */
    fun onConnected(mtu: Int) {
        reset(); frames.mtu = mtu
        handshake = ManagedHandshake(secret, deviceId, model, mtu).also { frames.sendEnvelope(Envelope.plain(it.hello())) }
    }
    fun reset() { handshake = null; channel = null }

    fun onFrame(frame: String) {
        val env = try { frames.receive(frame) } catch (e: Exception) { listener.onProtocolError("bad frame: ${e.message}"); return } ?: return
        val ch = channel
        if (ch == null) {
            val hs = handshake ?: run { listener.onProtocolError("message before HELLO"); return }
            val m = try { MessageCodec.decodeMessage(env.body) } catch (e: Exception) { listener.onProtocolError("bad handshake body"); return }
            val c = m as? Message.Challenge ?: run { listener.onProtocolError("expected CHALLENGE, got ${m::class.simpleName}"); return }
            val auth = hs.onChallenge(c) ?: run { listener.onProtocolError("controller proof invalid"); return }
            channel = SecureChannel(hs.sessionKey)
            frames.sendEnvelope(Envelope.plain(auth))
            // Controller confirms with a sealed AUTH_OK; until then we are optimistic (seq starts at 1 both ways).
            return
        }
        val m = try { ch.open(env) } catch (e: ProtocolException) { listener.onProtocolError(e.message ?: "protocol error"); channel = null; return }
        when (m) {
            is Message.AuthOk -> listener.onAuthenticated()
            is Message.Cmd -> listener.onCommand(m)
            else -> listener.onProtocolError("unexpected ${m::class.simpleName}")
        }
    }

    /** Sends a sealed message; only valid after authentication. */
    fun send(m: Message) {
        val ch = channel ?: throw IllegalStateException("not authenticated")
        frames.sendEnvelope(ch.seal(m))
    }
}

/** Controller-side endpoint for ONE connected managed device. */
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

    fun reset() { handshake = null; channel = null; frames.mtu = 23 }

    fun onFrame(frame: String) {
        val env = try { frames.receive(frame) } catch (e: Exception) { listener.onProtocolError("bad frame: ${e.message}"); return } ?: return
        val ch = channel
        if (ch == null) {
            val m = try { MessageCodec.decodeMessage(env.body) } catch (e: Exception) { listener.onProtocolError("bad handshake body"); return }
            when (m) {
                is Message.Hello -> {
                    frames.mtu = m.mtu
                    handshake = ControllerHandshake(secret).also { frames.sendEnvelope(Envelope.plain(it.onHello(m))) }
                }
                is Message.Auth -> {
                    val hs = handshake ?: run { listener.onProtocolError("AUTH before HELLO"); return }
                    if (!hs.onAuth(m)) { listener.onProtocolError("managed proof invalid"); return }
                    val c = SecureChannel(hs.sessionKey); channel = c
                    frames.sendEnvelope(c.seal(Message.AuthOk))
                    listener.onAuthenticated(hs.hello!!)
                }
                else -> listener.onProtocolError("unexpected ${m::class.simpleName} before auth")
            }
            return
        }
        val m = try { ch.open(env) } catch (e: ProtocolException) { listener.onProtocolError(e.message ?: "protocol error"); channel = null; return }
        listener.onMessage(m)
    }

    fun send(m: Message) {
        val ch = channel ?: throw IllegalStateException("not authenticated")
        frames.sendEnvelope(ch.seal(m))
    }
}
