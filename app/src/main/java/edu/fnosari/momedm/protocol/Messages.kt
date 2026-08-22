package edu.fnosari.momedm.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class CmdType { KIOSK_ON, KIOSK_OFF, INSTALL, ADD_ACCOUNT, LIST_APPS, GET_STATUS }

@Serializable
data class AppInfo(val pkg: String, val label: String)

/** All wire messages. Serialized with discriminator key "t". */
@Serializable
sealed class Message {
    @Serializable @SerialName("HELLO")     data class Hello(val deviceId: String, val model: String, val nonceC: String, val mtu: Int) : Message()
    @Serializable @SerialName("CHALLENGE") data class Challenge(val nonceS: String, val proof: String) : Message()
    @Serializable @SerialName("AUTH")      data class Auth(val proof: String) : Message()
    @Serializable @SerialName("AUTH_OK")   data object AuthOk : Message()
    /** Plain (unsealed) controller→managed request to restart the handshake: sent when the controller
     * receives a sealed frame on a link it has no session for (e.g. after the controller's GATT server
     * restarted while the BLE link stayed up). The managed side answers with a fresh HELLO. */
    @Serializable @SerialName("REHELLO")   data object Rehello : Message()
    /** Sealed managed→controller keepalive (every minute while authenticated). Carries nothing; the
     * controller only refreshes `lastSeen`. Its purpose is to make the managed side notice a dead session
     * quickly: a PING written on a link the controller no longer holds a session for is rejected at GATT
     * level, which triggers a reconnect (see `BLEClient.BLEClientCallBack.onWriteFailed`). */
    @Serializable @SerialName("PING")      data object Ping : Message()
    @Serializable @SerialName("STATUS")    data class Status(val kiosk: Boolean, val kioskPkg: String?, val account: Boolean, val battery: Int, val currentApp: String?) : Message()
    @Serializable @SerialName("APPS")      data class Apps(val apps: List<AppInfo>) : Message()
    @Serializable @SerialName("RESULT")    data class Result(val cmdId: String, val ok: Boolean, val msg: String) : Message()
    @Serializable @SerialName("CMD")       data class Cmd(val id: String, val type: CmdType, val pkg: String? = null) : Message()
}

/** Outer frame payload: handshake messages use seq=0/mac="", everything else is sealed by [SecureChannel]. */
@Serializable
data class Envelope(val seq: Long, val body: String, val mac: String) {
    companion object {
        fun plain(m: Message): Envelope = Envelope(0, MessageCodec.encodeMessage(m), "")
    }
}

object MessageCodec {
    val json: Json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    fun encodeMessage(m: Message): String = asciiEscape(json.encodeToString(Message.serializer(), m))
    fun decodeMessage(s: String): Message = json.decodeFromString(Message.serializer(), s)
    fun encodeEnvelope(e: Envelope): String = asciiEscape(json.encodeToString(Envelope.serializer(), e))
    fun decodeEnvelope(s: String): Envelope = json.decodeFromString(Envelope.serializer(), s)

    /** Escapes every char outside printable ASCII as a JSON `\uXXXX` so frames are byte == char safe. */
    fun asciiEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) if (ch.code in 0x20..0x7e) sb.append(ch) else sb.append(String.format("\\u%04x", ch.code))
        return sb.toString()
    }
}
