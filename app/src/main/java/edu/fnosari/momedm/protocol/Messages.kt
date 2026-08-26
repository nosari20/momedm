package edu.fnosari.momedm.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class CmdType { KIOSK_ON, KIOSK_OFF, INSTALL, ADD_ACCOUNT, LIST_APPS, GET_STATUS, SET_PREFS, SET_SCHEDULE, LOCK_NOW, UNLOCK, SEARCH_APP, SET_SAFETY, GET_APP_SCHEMA }

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
    /**
     * Replaces unsupported language/theme values with "system"; the accent is any ARGB int.
     *
     * The PIN pair is kept only when *both* halves are well-formed lower-case hex of the exact length
     * [PinHash] produces (16-byte salt, 32-byte hash); anything else — a half pair, an odd-length or
     * non-hex string from a peer — nulls both. Otherwise `PinHash.verify` would later raise on
     * `Hex.decode`, turning a malformed push into a crash on the child's PIN dialog.
     */
    fun sanitized(): ChildPrefs {
        val validPin = pinSalt != null && pinHash != null && SALT_RE.matches(pinSalt) && HASH_RE.matches(pinHash)
        return copy(
            language = if (language in LANGUAGES) language else "system",
            theme = if (theme in THEMES) theme else "system",
            pinSalt = if (validPin) pinSalt else null, pinHash = if (validPin) pinHash else null,
        )
    }
    companion object {
        /** MaClasse green — the default seed colour of both apps. */
        const val DEFAULT_ACCENT: Int = 0xFF3D5A8F.toInt()
        val LANGUAGES = setOf("system", "fr", "en")
        val THEMES = setOf("system", "light", "dark")
        /** 16 bytes of lower-case hex — what [PinHash.newSalt] emits. */
        private val SALT_RE = Regex("^[0-9a-f]{32}$")
        /** 32 bytes of lower-case hex — what [PinHash.hash] emits. */
        private val HASH_RE = Regex("^[0-9a-f]{64}$")
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
        /** True while a complete lock (night window or parent "Lock now") is in force. */
        val locked: Boolean = false,
        /** [LockState.REASON_NIGHT], [LockState.REASON_MANUAL], or null when unlocked. */
        val lockReason: String? = null,
        /** Epoch ms the night window ends; null for a manual lock and when unlocked. */
        val lockUntil: Long? = null,
        /** The child's current lock schedule, so the parent UI can render it after a restart. */
        val schedule: LockSchedule? = null,
        /** Content-restriction level currently applied on the child. */
        val safetyLevel: SafetyLevel = SafetyLevel.OFF,
        /**
         * The whole restriction config in force. The parent keeps nothing of its own, so without this
         * it could not merge: editing one app's settings would overwrite every other app's.
         */
        val safety: SafetyConfig? = null,
    ) : Message()
    @Serializable @SerialName("APPS")      data class Apps(val apps: List<AppInfo>) : Message()
    /** Reply to [CmdType.GET_APP_SCHEMA]: the managed-configuration settings [pkg] declares, empty when it declares none. */
    @Serializable @SerialName("SCHEMA")    data class Schema(val pkg: String, val entries: List<SchemaEntry>) : Message()
    /**
     * The outcome of one command.
     *
     * [msg] is diagnostic text for a log and is never shown to anyone: it is written on the child, in
     * whatever words the child's code happens to use, and the parent may well be reading in another
     * language. [code] is what the parent's UI actually renders — a stable identifier it maps to its
     * own translated string, with [arg] filling the one number some of them carry. Defaults keep the
     * field optional on the wire, so a peer that predates it still parses.
     */
    @Serializable @SerialName("RESULT")    data class Result(
        val cmdId: String, val ok: Boolean, val msg: String,
        val code: String? = null, val arg: Int? = null,
    ) : Message() {
        companion object {
            const val KIOSK_ON = "kiosk_on"; const val KIOSK_OFF = "kiosk_off"
            const val PLAY_OPENED = "play_opened"; const val ACCOUNT = "account"
            const val APPS = "apps"; const val STATUS = "status"; const val SCHEMA = "schema"
            const val PREFS = "prefs"; const val SAFETY = "safety"; const val SCHEDULE = "schedule"
            const val LOCKED = "locked"; const val UNLOCKED = "unlocked"
            /** The parent asked for something incomplete — a bug on their side, not the child's. */
            const val BAD_REQUEST = "bad_request"
            /** The child tried and the platform refused. */
            const val FAILED = "failed"
        }
    }
    @Serializable @SerialName("CMD")       data class Cmd(
        val id: String, val type: CmdType, val pkg: String? = null,
        /** KIOSK_ON: allowed apps (non-empty). */ val apps: List<String> = emptyList(),
        /** KIOSK_ON: the single app to pin, must be in [apps]. */ val pinned: String? = null,
        /** SET_PREFS payload. */ val prefs: ChildPrefs? = null,
        /** SET_SCHEDULE payload. */ val schedule: LockSchedule? = null,
        /** SET_SAFETY payload. */ val safety: SafetyConfig? = null,
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
