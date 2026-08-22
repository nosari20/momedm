package edu.fnosari.momedm.protocol

class ProtocolException(message: String) : Exception(message)

/**
 * Per-session integrity, direction-bound: `mac = HMAC(sessionKey, "$outDir|$seq|$body")` on the way out,
 * verified against `HMAC(sessionKey, "$inDir|$seq|$body")` on the way in. Binding the mac to a direction
 * tag stops a sealed envelope captured off the wire from being replayed back at its own sender and
 * accepted as if it had come from the peer ("reflection"). seq is strictly increasing per direction.
 *
 * @param outDir single-char tag stamped into this endpoint's own outgoing macs (`'C'` for the controller
 *   side, `'M'` for the managed side).
 * @param inDir single-char tag this endpoint requires in macs coming from its peer — i.e. the peer's [outDir].
 */
class SecureChannel(private val sessionKey: ByteArray, private val outDir: Char, private val inDir: Char) {
    private var outSeq = 0L
    private var lastInSeq = 0L

    fun seal(m: Message): Envelope {
        val seq = ++outSeq
        val body = MessageCodec.encodeMessage(m)
        return Envelope(seq, body, Crypto.hmacHex(sessionKey, "$outDir|$seq|$body"))
    }

    @Throws(ProtocolException::class)
    fun open(e: Envelope): Message {
        if (e.seq <= lastInSeq) throw ProtocolException("replay or out-of-order seq ${e.seq} (last ${lastInSeq})")
        if (!Crypto.constantTimeEquals(e.mac, Crypto.hmacHex(sessionKey, "$inDir|${e.seq}|${e.body}"))) throw ProtocolException("bad mac")
        lastInSeq = e.seq
        return try { MessageCodec.decodeMessage(e.body) } catch (ex: Exception) { throw ProtocolException("undecodable body: ${ex.message}") }
    }
}
