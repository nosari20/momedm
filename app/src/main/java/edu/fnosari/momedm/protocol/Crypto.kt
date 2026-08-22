package edu.fnosari.momedm.protocol

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 helpers and nonces. Pure JVM — no Android. */
object Crypto {
    private const val ALGO = "HmacSHA256"
    private val random = SecureRandom()

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGO)
        mac.init(SecretKeySpec(key, ALGO))
        return mac.doFinal(data)
    }
    fun hmacHex(key: ByteArray, data: String): String = Hex.encode(hmacSha256(key, data.toByteArray(Charsets.UTF_8)))
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
    fun randomHex(nBytes: Int): String = Hex.encode(randomBytes(nBytes))
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
