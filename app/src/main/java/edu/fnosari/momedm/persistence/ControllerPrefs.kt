package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.controller.provisioning.ControllerIdentity
import edu.fnosari.momedm.persistence.preferences.PreferencesProvider
import edu.fnosari.momedm.protocol.Base64Std
import edu.fnosari.momedm.protocol.ChildPrefs
import edu.fnosari.momedm.protocol.Crypto
import edu.fnosari.momedm.protocol.PinHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Controller-role settings: identity/secret, provisioning Wi-Fi choices, device registry blob. */
class ControllerPrefs(private val p: PreferencesProvider) {
    companion object {
        const val KEY_CONTROLLER_ID = "ctrl_controller_id"
        const val KEY_SECRET = "ctrl_secret"
        const val KEY_WIFI_MODE = "ctrl_wifi_mode"
        const val KEY_MANUAL_SSID = "ctrl_manual_ssid"
        const val KEY_MANUAL_PASS = "ctrl_manual_pass"
        const val KEY_CUSTOM_URL = "ctrl_custom_url"
        const val KEY_REGISTRY = "ctrl_registry_json"
        const val KEY_ADVERTISE_ON_LAUNCH = "ctrl_advertise_on_launch"
        const val MODE_HOTSPOT = "HOTSPOT"; const val MODE_MANUAL = "MANUAL"; const val MODE_CUSTOM_URL = "CUSTOM_URL"
        const val KEY_LANGUAGE = "ctrl_language"; const val KEY_THEME = "ctrl_theme"; const val KEY_ACCENT = "ctrl_accent"
        const val KEY_PIN_SALT = "ctrl_pin_salt"; const val KEY_PIN_HASH = "ctrl_pin_hash"
    }
    val controllerId: Flow<String> = p.readString(KEY_CONTROLLER_ID, "")
    val secretBase64: Flow<String> = p.readString(KEY_SECRET, "")
    val wifiMode: Flow<String> = p.readString(KEY_WIFI_MODE, MODE_HOTSPOT)
    val manualSsid: Flow<String> = p.readString(KEY_MANUAL_SSID, "")
    val manualPassword: Flow<String> = p.readString(KEY_MANUAL_PASS, "")
    val customUrl: Flow<String> = p.readString(KEY_CUSTOM_URL, "")
    val registryJson: Flow<String> = p.readString(KEY_REGISTRY, "")
    val advertiseOnLaunch: Flow<Boolean> = p.readBoolean(KEY_ADVERTISE_ON_LAUNCH, true)
    val language: Flow<String> = p.readString(KEY_LANGUAGE, "system")
    val theme: Flow<String> = p.readString(KEY_THEME, "system")
    val accent: Flow<Int> = p.readInt(KEY_ACCENT, ChildPrefs.DEFAULT_ACCENT)
    val pinSalt: Flow<String> = p.readString(KEY_PIN_SALT, "")
    val pinHash: Flow<String> = p.readString(KEY_PIN_HASH, "")
    val pinSet: Flow<Boolean> = pinHash.map { it.isNotEmpty() }
    /** What gets pushed to children with SET_PREFS. */
    val childPrefs: Flow<ChildPrefs> = combine(language, theme, accent, pinSalt, pinHash) { l, t, a, s, h -> ChildPrefs(l, t, a, s.ifEmpty { null }, h.ifEmpty { null }) }

    /** Returns the stored identity, or null if no controller id/secret has been saved yet. */
    suspend fun identity(): ControllerIdentity? {
        val id = controllerId.first(); val s = secretBase64.first()
        return if (id.isEmpty() || s.isEmpty()) null else ControllerIdentity(id, s)
    }
    /** Returns the stored identity, generating and persisting a fresh one if none exists yet. */
    suspend fun ensureIdentity(): ControllerIdentity = identity() ?: regenerateSecret()
    /** Rotates the secret in place: keeps the existing controller id (generating one if absent) and persists a new random secret. */
    suspend fun regenerateSecret(): ControllerIdentity {
        val existingId = controllerId.first().ifEmpty { ControllerIdentity.generate().controllerId }
        val fresh = ControllerIdentity(existingId, Base64Std.encode(Crypto.randomBytes(32)))
        p.write(KEY_CONTROLLER_ID, fresh.controllerId); p.write(KEY_SECRET, fresh.secretBase64)
        return fresh
    }
    /** Persists the chosen provisioning Wi-Fi mode and its associated SSID/password/custom URL fields. */
    suspend fun setWifi(mode: String, ssid: String, pass: String, url: String) {
        p.write(KEY_WIFI_MODE, mode); p.write(KEY_MANUAL_SSID, ssid); p.write(KEY_MANUAL_PASS, pass); p.write(KEY_CUSTOM_URL, url)
    }
    /** Persists the device registry as its encoded JSON blob. */
    suspend fun saveRegistry(json: String) = p.write(KEY_REGISTRY, json)
    /** Persists whether the controller should start BLE advertising automatically on launch. */
    suspend fun setAdvertiseOnLaunch(v: Boolean) = p.write(KEY_ADVERTISE_ON_LAUNCH, v)
    /** Reads the current [childPrefs] snapshot without collecting the flow. */
    suspend fun childPrefsNow(): ChildPrefs = childPrefs.first()
    /**
     * Persists the parent-chosen UI language/theme/accent pushed to children.
     * Callers must emit `ControllerLink.prefsChanged` afterwards so online children receive SET_PREFS
     * (persistence must not depend on the controller package).
     */
    suspend fun setUiPrefs(language: String, theme: String, accent: Int) { p.write(KEY_LANGUAGE, language); p.write(KEY_THEME, theme); p.write(KEY_ACCENT, accent) }
    /**
     * Hashes and stores [pin] (4-6 digits) with a fresh salt. The clear PIN is never persisted. Returns false when [pin] is invalid.
     * Callers must emit `ControllerLink.prefsChanged` afterwards so online children receive SET_PREFS
     * (persistence must not depend on the controller package).
     */
    suspend fun setPin(pin: String): Boolean {
        if (!PinHash.isValidPin(pin)) return false
        val salt = PinHash.newSalt()
        // 20k PBKDF2 iterations; the caller is the settings screen's main-thread coroutine.
        val hash = withContext(Dispatchers.Default) { PinHash.hash(pin, salt) }
        p.write(KEY_PIN_SALT, salt); p.write(KEY_PIN_HASH, hash); return true
    }
    /**
     * Clears the stored PIN salt/hash: no PIN required after this.
     * Callers must emit `ControllerLink.prefsChanged` afterwards so online children receive SET_PREFS
     * (persistence must not depend on the controller package).
     */
    suspend fun clearPin() { p.write(KEY_PIN_SALT, ""); p.write(KEY_PIN_HASH, "") }
}
