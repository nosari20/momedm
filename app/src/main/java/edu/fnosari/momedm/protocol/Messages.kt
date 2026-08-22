package edu.fnosari.momedm.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class CmdType { KIOSK_ON, KIOSK_OFF, INSTALL, ADD_ACCOUNT, LIST_APPS, GET_STATUS, SET_PREFS }

@Serializable
data class AppInfo(val pkg: String, val label: String)

/** Parent-chosen preferences pushed to a child device with [CmdType.SET_PREFS]. Unknown values fall back to defaults via [sanitized]. */
@Serializable
data class ChildPrefs(
    val language: String = "system",
    val theme: String = "system",
    val accent: Int = DEFAULT_ACCENT,
    /** Hex salt (16 bytes) for the parent PIN; null = no PIN set. */
    val pinSalt: String? = null,
    /** Hex [PinHash.hash] of the parent PIN; null = no PIN set. */
    val pinHash: String? = null,
) {
    /** Replaces unsupported language/theme values with "system"; the accent is any ARGB int. */
    fun sanitized(): ChildPrefs = copy(
        language = if (language in LANGUAGES) language else "system",
        theme = if (theme in THEMES) theme else "system",
        pinSalt = pinSalt?.takeIf { pinHash != null }, pinHash = pinHash?.takeIf { pinSalt != null },
    )
    companion object {
        /** MaClasse green — the default seed colour of both apps. */
        const val DEFAULT_ACCENT: Int = 0xFF16866F.toInt()
        val LANGUAGES = setOf("system", "fr", "en")
        val THEMES = setOf("system", "light", "dark")
    }
}

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
    @Serializable @SerialName("STATUS")    data class Status(
        val kiosk: Boolean, val kioskPkg: String?, val account: Boolean, val battery: Int, val currentApp: String?,
        /** Allowed apps while child mode is on (empty when off). */
        val kioskApps: List<String> = emptyList(),
        /** True while a parent-PIN pause is active (lock task released until [pauseEndsAt]). */
        val kioskPaused: Boolean = false,
        val pauseEndsAt: Long? = null,
    ) : Message()
    @Serializable @SerialName("APPS")      data class Apps(val apps: List<AppInfo>) : Message()
    @Serializable @SerialName("RESULT")    data class Result(val cmdId: String, val ok: Boolean, val msg: String) : Message()
    @Serializable @SerialName("CMD")       data class Cmd(
        val id: String, val type: CmdType, val pkg: String? = null,
        /** KIOSK_ON: allowed apps (non-empty). */ val apps: List<String> = emptyList(),
        /** KIOSK_ON: the single app to pin, must be in [apps]. */ val pinned: String? = null,
        /** SET_PREFS payload. */ val prefs: ChildPrefs? = null,
    ) : Message()
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
