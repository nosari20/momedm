package edu.fnosari.momedm.protocol

class ProtocolException(message: String) : Exception(message)

/** Per-session integrity: `mac = HMAC(sessionKey, "$seq|$body")`, seq strictly increasing per direction. */
class SecureChannel(private val sessionKey: ByteArray) {
    private var outSeq = 0L
    private var lastInSeq = 0L

    fun seal(m: Message): Envelope {
        val seq = ++outSeq
        val body = MessageCodec.encodeMessage(m)
        return Envelope(seq, body, Crypto.hmacHex(sessionKey, "$seq|$body"))
    }

    @Throws(ProtocolException::class)
    fun open(e: Envelope): Message {
        if (e.seq <= lastInSeq) throw ProtocolException("replay or out-of-order seq ${e.seq} (last ${lastInSeq})")
        if (!Crypto.constantTimeEquals(e.mac, Crypto.hmacHex(sessionKey, "${e.seq}|${e.body}"))) throw ProtocolException("bad mac")
        lastInSeq = e.seq
        return try { MessageCodec.decodeMessage(e.body) } catch (ex: Exception) { throw ProtocolException("undecodable body: ${ex.message}") }
    }
}
