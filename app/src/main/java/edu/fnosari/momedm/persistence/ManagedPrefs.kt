package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.persistence.preferences.PreferencesProvider
import edu.fnosari.momedm.protocol.Base64Std
import edu.fnosari.momedm.protocol.ChildPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Managed-role settings: controller identity from the QR, our device id, kiosk state. */
class ManagedPrefs(private val p: PreferencesProvider) {
    companion object {
        const val KEY_CONTROLLER_ID = "managed_controller_id"
        const val KEY_SECRET = "managed_secret"
        const val KEY_DEVICE_ID = "managed_device_id"
        const val KEY_KIOSK_PKG = "managed_kiosk_pkg"
        const val KEY_KIOSK_ON = "managed_kiosk_on"
        const val KEY_KIOSK_APPS = "managed_kiosk_apps"
        const val KEY_KIOSK_PAUSE_UNTIL = "managed_kiosk_pause_until"
        const val KEY_PREF_LANGUAGE = "managed_pref_language"
        const val KEY_PREF_THEME = "managed_pref_theme"
        const val KEY_PREF_ACCENT = "managed_pref_accent"
        const val KEY_PIN_SALT = "managed_pin_salt"
        const val KEY_PIN_HASH = "managed_pin_hash"
        const val KEY_PIN_FAILURES = "managed_pin_failures"
        const val KEY_PIN_LOCK_UNTIL = "managed_pin_lock_until"
    }
    val controllerId: Flow<String> = p.readString(KEY_CONTROLLER_ID, "")
    val secretBase64: Flow<String> = p.readString(KEY_SECRET, "")
    val deviceId: Flow<String> = p.readString(KEY_DEVICE_ID, "")
    val kioskPkg: Flow<String> = p.readString(KEY_KIOSK_PKG, "")
    val kioskOn: Flow<Boolean> = p.readBoolean(KEY_KIOSK_ON, false)
    private val kioskApps: Flow<String> = p.readString(KEY_KIOSK_APPS, "")
    private val pauseUntil: Flow<String> = p.readString(KEY_KIOSK_PAUSE_UNTIL, "0")  // Long stored as string (provider has no Long)
    /** Whole child-mode configuration ([kioskPkg] is its `pinned`). */
    val kioskConfig: Flow<KioskConfig> = combine(kioskOn, kioskPkg, kioskApps, pauseUntil) { on, pkg, apps, pause ->
        KioskConfig(on, KioskConfig.decodeApps(apps), pkg.ifEmpty { null }, pause.toLongOrNull() ?: 0L)
    }
    val childPrefs: Flow<ChildPrefs> = combine(p.readString(KEY_PREF_LANGUAGE, "system"), p.readString(KEY_PREF_THEME, "system"),
        p.readInt(KEY_PREF_ACCENT, ChildPrefs.DEFAULT_ACCENT), p.readString(KEY_PIN_SALT, ""), p.readString(KEY_PIN_HASH, "")) { l, t, a, s, h ->
        ChildPrefs(l, t, a, s.ifEmpty { null }, h.ifEmpty { null })
    }
    /** Consecutive wrong parent-PIN attempts; drives the launcher's lockout backoff across process death. */
    val pinFailures: Flow<Int> = p.readInt(KEY_PIN_FAILURES, 0)
    /** Epoch ms until which the PIN dialog refuses input; 0 = not locked out. */
    val pinLockUntil: Flow<Long> = p.readString(KEY_PIN_LOCK_UNTIL, "0").map { it.toLongOrNull() ?: 0L }  // Long stored as string (provider has no Long)

    /** Persists the controller identity (id + secret) received from the QR admin extras. */
    suspend fun saveProvisioning(controllerId: String, secretBase64: String) { p.write(KEY_CONTROLLER_ID, controllerId); p.write(KEY_SECRET, secretBase64) }
    /** True once a controller secret has been saved via [saveProvisioning]. */
    suspend fun isProvisioned(): Boolean = secretBase64.first().isNotEmpty()
    /** Decodes the stored secret, or null if none has been saved yet. */
    suspend fun secretBytes(): ByteArray? = secretBase64.first().takeIf { it.isNotEmpty() }?.let { Base64Std.decode(it) }
    /** Returns this device's id, generating and persisting one on first call. */
    suspend fun ensureDeviceId(): String {
        val existing = deviceId.first()
        if (existing.isNotEmpty()) return existing
        return UUID.randomUUID().toString().also { p.write(KEY_DEVICE_ID, it) }
    }
    /** Persists the kiosk on/off state and the kiosk package (empty string when [pkg] is null). */
    suspend fun setKiosk(on: Boolean, pkg: String?) { p.write(KEY_KIOSK_ON, on); p.write(KEY_KIOSK_PKG, pkg ?: "") }
    /** Persists the full child-mode configuration. */
    suspend fun setKioskConfig(c: KioskConfig) {
        p.write(KEY_KIOSK_ON, c.on); p.write(KEY_KIOSK_PKG, c.pinned ?: ""); p.write(KEY_KIOSK_APPS, KioskConfig.encodeApps(c.apps)); p.write(KEY_KIOSK_PAUSE_UNTIL, c.pauseUntil.toString())
    }
    suspend fun setPauseUntil(t: Long) = p.write(KEY_KIOSK_PAUSE_UNTIL, t.toString())
    suspend fun setChildPrefs(c: ChildPrefs) {
        p.write(KEY_PREF_LANGUAGE, c.language); p.write(KEY_PREF_THEME, c.theme); p.write(KEY_PREF_ACCENT, c.accent)
        p.write(KEY_PIN_SALT, c.pinSalt ?: ""); p.write(KEY_PIN_HASH, c.pinHash ?: "")
    }
    /**
     * Persists the PIN brute-force lockout: [failures] consecutive wrong attempts and the epoch-ms
     * deadline [untilMs] until which the dialog refuses input (both 0 to reset after a correct PIN).
     * Only the counter and the deadline are stored — never a PIN, clear or hashed — so force-stopping
     * the launcher no longer wipes the backoff.
     */
    suspend fun setPinLock(failures: Int, untilMs: Long) { p.write(KEY_PIN_FAILURES, failures); p.write(KEY_PIN_LOCK_UNTIL, untilMs.toString()) }
}
