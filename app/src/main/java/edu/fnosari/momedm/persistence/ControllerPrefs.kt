package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.controller.provisioning.ControllerIdentity
import edu.fnosari.momedm.persistence.preferences.PreferencesProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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
    }
    val controllerId: Flow<String> = p.readString(KEY_CONTROLLER_ID, "")
    val secretBase64: Flow<String> = p.readString(KEY_SECRET, "")
    val wifiMode: Flow<String> = p.readString(KEY_WIFI_MODE, MODE_HOTSPOT)
    val manualSsid: Flow<String> = p.readString(KEY_MANUAL_SSID, "")
    val manualPassword: Flow<String> = p.readString(KEY_MANUAL_PASS, "")
    val customUrl: Flow<String> = p.readString(KEY_CUSTOM_URL, "")
    val registryJson: Flow<String> = p.readString(KEY_REGISTRY, "")
    val advertiseOnLaunch: Flow<Boolean> = p.readBoolean(KEY_ADVERTISE_ON_LAUNCH, true)

    suspend fun identity(): ControllerIdentity? {
        val id = controllerId.first(); val s = secretBase64.first()
        return if (id.isEmpty() || s.isEmpty()) null else ControllerIdentity(id, s)
    }
    suspend fun ensureIdentity(): ControllerIdentity = identity() ?: regenerateSecret()
    suspend fun regenerateSecret(): ControllerIdentity {
        val existingId = controllerId.first().ifEmpty { ControllerIdentity.generate().controllerId }
        val fresh = ControllerIdentity(existingId, ControllerIdentity.generate().secretBase64)
        p.write(KEY_CONTROLLER_ID, fresh.controllerId); p.write(KEY_SECRET, fresh.secretBase64)
        return fresh
    }
    suspend fun setWifi(mode: String, ssid: String, pass: String, url: String) {
        p.write(KEY_WIFI_MODE, mode); p.write(KEY_MANUAL_SSID, ssid); p.write(KEY_MANUAL_PASS, pass); p.write(KEY_CUSTOM_URL, url)
    }
    suspend fun saveRegistry(json: String) = p.write(KEY_REGISTRY, json)
    suspend fun setAdvertiseOnLaunch(v: Boolean) = p.write(KEY_ADVERTISE_ON_LAUNCH, v)
}
