package edu.fnosari.momedm.controller.provisioning

import java.net.Inet4Address
import java.net.NetworkInterface

/** Picks the controller's own IPv4 address for the managed device to reach it over. */
object NetUtils {
    private val PREFERRED_PREFIXES = listOf("ap", "swlan", "wlan")

    /**
     * First eligible IPv4 across "up", non-loopback interfaces, ignoring anything in [exclude].
     *
     * When [preferGateway], an address ending in ".1" wins (the convention on some AP interfaces);
     * otherwise selection falls back to interface-name preference (`ap*`, `swlan*`, `wlan*`, then
     * anything).
     *
     * [exclude] is how the hotspot path avoids advertising the wrong address: pass the phone's own
     * *client* Wi-Fi address and the AP interface is what remains. Without it, a phone that is both
     * joined to a Wi-Fi network (`wlan0`) and running a hotspot (`wlan2`) advertises `wlan0` — an
     * address the child device cannot reach once it joins the hotspot.
     */
    fun localIpv4(preferGateway: Boolean = false, exclude: Set<String> = emptySet()): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback } ?: return null
        val candidates = ifaces.flatMap { i ->
            i.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.mapNotNull { it.hostAddress }.map { i.name to it }
        }
        return pick(candidates, preferGateway, exclude)
    }

    /**
     * Pure selection over (interface name, IPv4 address) [candidates] — factored out of [localIpv4]
     * so the choice logic can be unit tested on the JVM without real network interfaces.
     *
     * Always skipped, because no peer can reach them:
     *  - link-local (`169.254.*`), common right after an interface comes up, before DHCP/AP config lands;
     *  - the 464XLAT clat range (`192.0.0.0/29`, RFC 7335) that appears on `v4-rmnet*` alongside mobile
     *    data — an address local to the translation layer, not to any network;
     *  - anything in [exclude].
     *
     * When [preferGateway], the first remaining address ending in ".1" wins; otherwise (or if none
     * end in ".1") the candidate whose interface name starts with the earliest-listed
     * [PREFERRED_PREFIXES] entry wins, falling back to the first remaining candidate.
     */
    fun pick(candidates: List<Pair<String, String>>, preferGateway: Boolean, exclude: Set<String> = emptySet()): String? {
        val eligible = candidates.filterNot { (_, ip) ->
            ip.startsWith("169.254.") || isClat(ip) || ip in exclude
        }
        if (eligible.isEmpty()) return null
        if (preferGateway) eligible.firstOrNull { (_, ip) -> ip.endsWith(".1") }?.let { return it.second }
        return eligible.sortedBy { (name, _) -> PREFERRED_PREFIXES.indexOfFirst { name.startsWith(it) }.let { if (it < 0) PREFERRED_PREFIXES.size else it } }.first().second
    }

    /** True for the RFC 7335 464XLAT range `192.0.0.0/29` (`192.0.0.0`–`192.0.0.7`). */
    private fun isClat(ip: String): Boolean {
        if (!ip.startsWith("192.0.0.")) return false
        val last = ip.substringAfterLast('.').toIntOrNull() ?: return false
        return last in 0..7
    }
}
