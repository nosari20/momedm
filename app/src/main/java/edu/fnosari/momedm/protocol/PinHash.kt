package edu.fnosari.momedm.protocol

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Parent-PIN hashing: PBKDF2-HMAC-SHA256, 20k iterations, 32-byte output, 16-byte random salt. Pure JVM. */
object PinHash {
    const val ITERATIONS = 20_000
    private const val KEY_BITS = 256
    private val PIN_RE = Regex("^[0-9]{4,6}$")

    fun isValidPin(pin: String): Boolean = PIN_RE.matches(pin)
    fun newSalt(): String = Crypto.randomHex(16)
    fun hash(pin: String, saltHex: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Hex.decode(saltHex), ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return Hex.encode(key.encoded)
    }
    fun verify(pin: String, saltHex: String, hashHex: String): Boolean =
        isValidPin(pin) && Crypto.constantTimeEquals(hash(pin, saltHex), hashHex)
}
