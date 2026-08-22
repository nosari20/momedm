package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.persistence.preferences.PreferencesProvider
import edu.fnosari.momedm.protocol.Base64Std
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/** Managed-role settings: controller identity from the QR, our device id, kiosk state. */
class ManagedPrefs(private val p: PreferencesProvider) {
    companion object {
        const val KEY_CONTROLLER_ID = "managed_controller_id"
        const val KEY_SECRET = "managed_secret"
        const val KEY_DEVICE_ID = "managed_device_id"
        const val KEY_KIOSK_PKG = "managed_kiosk_pkg"
        const val KEY_KIOSK_ON = "managed_kiosk_on"
    }
    val controllerId: Flow<String> = p.readString(KEY_CONTROLLER_ID, "")
    val secretBase64: Flow<String> = p.readString(KEY_SECRET, "")
    val deviceId: Flow<String> = p.readString(KEY_DEVICE_ID, "")
    val kioskPkg: Flow<String> = p.readString(KEY_KIOSK_PKG, "")
    val kioskOn: Flow<Boolean> = p.readBoolean(KEY_KIOSK_ON, false)

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
}
