package edu.fnosari.momedm.protocol

/** Managed-device side of the mutual HMAC handshake: HELLO → (CHALLENGE) → AUTH. */
class ManagedHandshake(
    private val secret: ByteArray,
    private val deviceId: String,
    private val model: String,
    private val mtu: Int,
    private val nonceC: String = Crypto.randomHex(16),
) {
    private var _sessionKey: ByteArray? = null
    val sessionKey: ByteArray get() = _sessionKey ?: error("handshake not complete")

    fun hello(): Message.Hello = Message.Hello(deviceId, model, nonceC, mtu)

    /** Verifies the controller's proof of [nonceC]; returns our AUTH or null when the controller is an impostor. */
    fun onChallenge(c: Message.Challenge): Message.Auth? {
        if (!Crypto.constantTimeEquals(c.proof, Crypto.hmacHex(secret, nonceC))) return null
        _sessionKey = Crypto.hmacSha256(secret, (nonceC + c.nonceS).toByteArray(Charsets.UTF_8))
        return Message.Auth(Crypto.hmacHex(secret, c.nonceS))
    }
}

/** Controller side: (HELLO) → CHALLENGE → (AUTH) → verified. */
class ControllerHandshake(
    private val secret: ByteArray,
    private val nonceS: String = Crypto.randomHex(16),
) {
    var hello: Message.Hello? = null
        private set
    private var _sessionKey: ByteArray? = null
    val sessionKey: ByteArray get() = _sessionKey ?: error("handshake not complete")

    fun onHello(h: Message.Hello): Message.Challenge {
        hello = h
        return Message.Challenge(nonceS, Crypto.hmacHex(secret, h.nonceC))
    }

    fun onAuth(a: Message.Auth): Boolean {
        val h = hello ?: return false
        if (!Crypto.constantTimeEquals(a.proof, Crypto.hmacHex(secret, nonceS))) return false
        _sessionKey = Crypto.hmacSha256(secret, (h.nonceC + nonceS).toByteArray(Charsets.UTF_8))
        return true
    }
}
