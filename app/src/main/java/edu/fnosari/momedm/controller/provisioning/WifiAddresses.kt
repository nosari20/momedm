package edu.fnosari.momedm.controller.provisioning

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address

/**
 * What the phone knows about the Wi-Fi network it is *joined to* (as opposed to one it is hosting).
 *
 * Asking [ConnectivityManager] which network carries the Wi-Fi transport is reliable in a way that
 * guessing from interface names is not: a phone running a local-only hotspot has two `wlan*`
 * interfaces and nothing in their names says which is which.
 */
object WifiAddresses {
    private const val LOG_TAG = "WifiAddresses"

    /** IPv4 address of the Wi-Fi network this phone is joined to, or null when it is not on Wi-Fi. */
    fun clientWifiIpv4(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrElse { Log.w(LOG_TAG, "Could not read the client Wi-Fi address: ${it::class.simpleName}"); null }

    /**
     * SSID of the Wi-Fi network this phone is joined to, or null when unavailable.
     *
     * Used only to pre-fill the Shared Wi-Fi field — the child device has to join the same network
     * the parent is on, so the parent's current network is very nearly always the right answer and
     * typing it by hand is a needless chance to get it wrong.
     *
     * Returns null rather than the platform's `<unknown ssid>` placeholder, which is what
     * [WifiManager] hands back when the caller lacks location permission.
     */
    fun currentSsid(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        val ssid = info?.ssid?.trim('"').orEmpty()
        ssid.takeIf { it.isNotBlank() && !it.equals(WifiManager.UNKNOWN_SSID.trim('"'), ignoreCase = true) && it != "0x" }
    }.getOrElse { Log.w(LOG_TAG, "Could not read the current SSID: ${it::class.simpleName}"); null }
}
