package edu.fnosari.momedm.controller.provisioning

import java.net.Inet4Address
import java.net.NetworkInterface

/** Picks the controller's own IPv4 address for the managed device to reach it over. */
object NetUtils {
    private val PREFERRED_PREFIXES = listOf("ap", "swlan", "wlan")

    /**
     * First eligible IPv4 across "up", non-loopback interfaces. When [preferGateway], an address
     * ending in ".1" wins (the convention for a local-only-hotspot AP interface); otherwise
     * selection falls back to interface-name preference (`ap*`, `swlan*`, `wlan*`, then anything).
     * Link-local (`169.254.*`) addresses — common right after an interface comes up, before DHCP/AP
     * configuration lands — are never returned.
     */
    fun localIpv4(preferGateway: Boolean = false): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback } ?: return null
        val candidates = ifaces.flatMap { i ->
            i.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.mapNotNull { it.hostAddress }.map { i.name to it }
        }
        return pick(candidates, preferGateway)
    }

    /**
     * Pure selection over (interface name, IPv4 address) [candidates] — factored out of [localIpv4]
     * so the choice logic can be unit tested on the JVM without real network interfaces. Always skips
     * link-local (`169.254.*`) addresses. When [preferGateway], the first address ending in ".1" wins;
     * otherwise (or if none end in ".1") the candidate whose interface name starts with the
     * earliest-listed [PREFERRED_PREFIXES] entry wins, falling back to the first remaining candidate.
     */
    fun pick(candidates: List<Pair<String, String>>, preferGateway: Boolean): String? {
        val eligible = candidates.filterNot { (_, ip) -> ip.startsWith("169.254.") }
        if (eligible.isEmpty()) return null
        if (preferGateway) eligible.firstOrNull { (_, ip) -> ip.endsWith(".1") }?.let { return it.second }
        return eligible.sortedBy { (name, _) -> PREFERRED_PREFIXES.indexOfFirst { name.startsWith(it) }.let { if (it < 0) PREFERRED_PREFIXES.size else it } }.first().second
    }
}
