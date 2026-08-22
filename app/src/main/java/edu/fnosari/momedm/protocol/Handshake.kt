package edu.fnosari.momedm.protocol

/**
 * Domain-separation tags for every HMAC the handshake computes.
 *
 * The CHALLENGE proof is an oracle: whoever sends the HELLO picks `nonceC` and gets
 * `HMAC(secret, <tagged nonceC>)` back. If the challenge proof, the auth proof and the session key all
 * hashed bare nonce material with the same key, a forged HELLO carrying `nonceC = realNonceC + nonceS`
 * would make the returned proof *be* the real session's key. Prefixing each computation with a distinct,
 * unambiguous label (and separating the two nonces of the session key with `|`, which never occurs in a
 * hex nonce) makes the three input spaces disjoint, so no reply from one can ever be a value of another.
 */
private const val TAG_CHALLENGE = "momedm/challenge|"
private const val TAG_AUTH = "momedm/auth|"
private const val TAG_SESSION = "momedm/session|"

/**
 * A nonce is exactly what [Crypto.randomHex] (16) produces: 32 lower-case hex chars. Both endpoints
 * validate the peer's nonce against this before it reaches any HMAC, so the only attacker-controlled
 * handshake input is fixed-length and fixed-alphabet.
 */
internal val NONCE_RE = Regex("^[0-9a-f]{32}$")

/** True when [nonce] is exactly 32 lower-case hex chars — the only shape either endpoint accepts. */
internal fun isValidNonce(nonce: String): Boolean = NONCE_RE.matches(nonce)

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

    /**
     * Verifies the controller's proof of [nonceC]; returns our AUTH or null when the controller is an
     * impostor. Caller must have validated `c.nonceS` with [isValidNonce] first.
     */
    fun onChallenge(c: Message.Challenge): Message.Auth? {
        if (!Crypto.constantTimeEquals(c.proof, Crypto.hmacHex(secret, TAG_CHALLENGE + nonceC))) return null
        _sessionKey = Crypto.hmacSha256(secret, "$TAG_SESSION$nonceC|${c.nonceS}".toByteArray(Charsets.UTF_8))
        return Message.Auth(Crypto.hmacHex(secret, TAG_AUTH + c.nonceS))
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

    /** Answers [h] with our CHALLENGE. Caller must have validated `h.nonceC` with [isValidNonce] first. */
    fun onHello(h: Message.Hello): Message.Challenge {
        hello = h
        return Message.Challenge(nonceS, Crypto.hmacHex(secret, TAG_CHALLENGE + h.nonceC))
    }

    fun onAuth(a: Message.Auth): Boolean {
        val h = hello ?: return false
        if (!Crypto.constantTimeEquals(a.proof, Crypto.hmacHex(secret, TAG_AUTH + nonceS))) return false
        _sessionKey = Crypto.hmacSha256(secret, "$TAG_SESSION${h.nonceC}|$nonceS".toByteArray(Charsets.UTF_8))
        return true
    }
}
