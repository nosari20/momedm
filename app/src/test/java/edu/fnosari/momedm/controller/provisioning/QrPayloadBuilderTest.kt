package edu.fnosari.momedm.controller.provisioning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QrPayloadBuilderTest {
    private val base = ProvisioningParams("http://192.168.1.5:8080/momedm.apk", "abc_-", "MyNet", "pw123", "cid", "c2VjcmV0")

    @Test fun containsAllProvisioningKeys() {
        val o = Json.parseToJsonElement(QrPayloadBuilder.build(base)).jsonObject
        assertEquals("edu.fnosari.momedm/.managed.AdminReceiver", o["android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"]!!.jsonPrimitive.content)
        assertEquals(base.apkUrl, o["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"]!!.jsonPrimitive.content)
        assertEquals("abc_-", o["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"]!!.jsonPrimitive.content)
        assertEquals("MyNet", o["android.app.extra.PROVISIONING_WIFI_SSID"]!!.jsonPrimitive.content)
        assertEquals("pw123", o["android.app.extra.PROVISIONING_WIFI_PASSWORD"]!!.jsonPrimitive.content)
        assertEquals("WPA", o["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"]!!.jsonPrimitive.content)
        assertEquals(true, o["android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, o["android.app.extra.PROVISIONING_SKIP_ENCRYPTION"]!!.jsonPrimitive.content.toBoolean())
        val extras = o["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!.jsonObject
        assertEquals("cid", extras["controller_id"]!!.jsonPrimitive.content)
        assertEquals("c2VjcmV0", extras["secret"]!!.jsonPrimitive.content)
    }
    @Test fun omitsWifiWhenNull() {
        val o = Json.parseToJsonElement(QrPayloadBuilder.build(base.copy(wifiSsid = null, wifiPassword = null))).jsonObject
        assertFalse(o.containsKey("android.app.extra.PROVISIONING_WIFI_SSID"))
        assertFalse(o.containsKey("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"))
    }
    @Test fun apkUrl() { assertEquals("http://10.0.0.2:8080/momedm.apk", QrPayloadBuilder.apkUrl("10.0.0.2")) }
}
