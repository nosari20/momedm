package edu.fnosari.momedm.controller.provisioning

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Wraps [WifiManager.startLocalOnlyHotspot]; SSID/passphrase come from the system. Needs NEARBY_WIFI_DEVICES. */
class HotspotManager(context: Context) {
    companion object { private const val LOG_TAG = "HotspotManager" }
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun start(onReady: (ssid: String, pass: String) -> Unit, onFailed: (reason: String) -> Unit) {
        // Belt and braces with ProvisioningController.start()'s own guard: overwriting a live
        // reservation leaks the hotspot it was holding, and only process death releases it.
        if (reservation != null) { Log.d(LOG_TAG, "Hotspot already reserved; ignoring start"); return }
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val cfg = r.softApConfiguration
                    val ssid = cfg.wifiSsid?.toString()?.trim('"') ?: @Suppress("DEPRECATION") cfg.ssid ?: ""
                    val pass = cfg.passphrase ?: ""
                    Log.d(LOG_TAG, "Hotspot started: $ssid"); onReady(ssid, pass)
                }
                override fun onStopped() { Log.d(LOG_TAG, "Hotspot stopped"); reservation = null }
                override fun onFailed(reason: Int) { Log.w(LOG_TAG, "Hotspot failed: $reason"); onFailed("hotspot error $reason") }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) { onFailed("missing permission: ${e.message}") }
        catch (e: IllegalStateException) { onFailed(e.message ?: "hotspot unavailable") }
    }
    fun stop() { reservation?.close(); reservation = null }
}
