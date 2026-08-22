package edu.fnosari.momedm.controller.provisioning

import edu.fnosari.momedm.protocol.ProvisioningExtras
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Parameters used to build the Android Enterprise QR provisioning payload. */
data class ProvisioningParams(
    val apkUrl: String,
    val signatureChecksum: String,
    val wifiSsid: String?,
    val wifiPassword: String?,
    val controllerId: String,
    val secretBase64: String,
)

/** Builds the Android Enterprise QR provisioning JSON (scanned from the Setup Wizard). */
object QrPayloadBuilder {
    const val ADMIN_COMPONENT = "edu.fnosari.momedm/.managed.AdminReceiver"
    const val APK_FILE_NAME = "momedm.apk"
    const val HTTP_PORT = 8080
    private const val P = "android.app.extra."

    /** Builds the URL for the controller's locally-hosted APK, given the controller's own [ip]. */
    fun apkUrl(ip: String): String = "http://$ip:$HTTP_PORT/$APK_FILE_NAME"

    /** Serializes [p] into the QR-encodable Android Enterprise provisioning JSON. */
    fun build(p: ProvisioningParams): String {
        val obj: JsonObject = buildJsonObject {
            put(P + "PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME", ADMIN_COMPONENT)
            put(P + "PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION", p.apkUrl)
            put(P + "PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM", p.signatureChecksum)
            if (!p.wifiSsid.isNullOrBlank()) {
                put(P + "PROVISIONING_WIFI_SSID", p.wifiSsid)
                put(P + "PROVISIONING_WIFI_PASSWORD", p.wifiPassword ?: "")
                put(P + "PROVISIONING_WIFI_SECURITY_TYPE", "WPA")
            }
            put(P + "PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", true)
            put(P + "PROVISIONING_SKIP_ENCRYPTION", true)
            put(P + "PROVISIONING_ADMIN_EXTRAS_BUNDLE", buildJsonObject {
                put(ProvisioningExtras.KEY_CONTROLLER_ID, p.controllerId)
                put(ProvisioningExtras.KEY_SECRET, p.secretBase64)
            })
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }
}
