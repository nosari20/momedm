package edu.fnosari.momedm.protocol

import java.util.Base64

/** Lower-case hex codec. */
object Hex {
    private const val DIGITS = "0123456789abcdef"
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xff; sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f]) }
        return sb.toString()
    }
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i -> ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte() }
    }
}

/** URL-safe base64 without padding (format required by PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM). */
object Base64Url {
    fun encodeNoPad(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Standard base64 (secret transport inside the QR admin-extras bundle). */
object Base64Std {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
}
