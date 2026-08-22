package edu.fnosari.momedm.controller.provisioning

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** First non-loopback IPv4, preferring interfaces whose name starts with one of [preferPrefixes] (hotspot first). */
    fun localIpv4(preferPrefixes: List<String> = listOf("ap", "swlan", "wlan")): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback } ?: return null
        val ordered = ifaces.sortedBy { i -> preferPrefixes.indexOfFirst { i.name.startsWith(it) }.let { if (it < 0) 99 else it } }
        for (i in ordered) for (a in i.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
        return null
    }
}
