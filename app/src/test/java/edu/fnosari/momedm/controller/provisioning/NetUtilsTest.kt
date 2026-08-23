package edu.fnosari.momedm.controller.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for [NetUtils.pick] — the candidate-selection logic factored out of [NetUtils.localIpv4]. */
class NetUtilsTest {
    @Test fun preferGatewayPicksAddressEndingInDotOne() {
        val candidates = listOf("wlan0" to "192.168.1.42", "ap0" to "192.168.43.1")
        assertEquals("192.168.43.1", NetUtils.pick(candidates, preferGateway = true))
    }
    @Test fun linkLocalAddressesAreSkipped() {
        val candidates = listOf("wlan0" to "169.254.12.3", "ap0" to "192.168.43.7")
        assertEquals("192.168.43.7", NetUtils.pick(candidates, preferGateway = false))
        assertNull(NetUtils.pick(listOf("wlan0" to "169.254.12.3"), preferGateway = true))
    }
    @Test fun namePrefixOrderPrefersApThenSwlanThenWlan() {
        val candidates = listOf("wlan0" to "10.0.0.5", "swlan0" to "10.0.0.6", "eth0" to "10.0.0.7", "ap0" to "10.0.0.8")
        assertEquals("10.0.0.8", NetUtils.pick(candidates, preferGateway = false))
        assertEquals("10.0.0.6", NetUtils.pick(candidates.filterNot { it.first == "ap0" }, preferGateway = false))
        assertEquals("10.0.0.5", NetUtils.pick(candidates.filter { it.first == "wlan0" || it.first == "eth0" }, preferGateway = false))
    }

    /**
     * The real shape of a Pixel hosting a local-only hotspot while joined to Wi-Fi, captured from a
     * device: the AP lands on `wlan2` with a randomised subnet and does *not* take the ".1" of it, so
     * the gateway heuristic finds nothing and plain name ordering picks the client interface `wlan0`.
     * The child device is on the hotspot and can never reach that address — the QR advertised it and
     * the download simply timed out.
     */
    @Test fun hotspotPicksTheApAddressNotTheClientWifi() {
        val candidates = listOf(
            "wlan0" to "192.168.1.56",      // joined home Wi-Fi
            "v4-rmnet1" to "192.0.0.4",     // 464XLAT clat, mobile data
            "wlan2" to "10.47.248.185",     // the local-only hotspot AP
        )
        assertEquals("192.168.1.56", NetUtils.pick(candidates, preferGateway = true))   // the old, wrong answer
        assertEquals("10.47.248.185", NetUtils.pick(candidates, preferGateway = true, exclude = setOf("192.168.1.56")))
    }

    @Test fun clatAddressesAreNeverAdvertised() {
        // 192.0.0.0/29 (RFC 7335) is local to the translation layer; no peer can reach it.
        assertNull(NetUtils.pick(listOf("v4-rmnet1" to "192.0.0.4"), preferGateway = false))
        assertEquals("10.0.0.5", NetUtils.pick(listOf("v4-rmnet1" to "192.0.0.4", "wlan0" to "10.0.0.5"), preferGateway = false))
        // Neighbouring addresses outside the /29 are ordinary and must still be usable.
        assertEquals("192.0.0.8", NetUtils.pick(listOf("wlan0" to "192.0.0.8"), preferGateway = false))
    }

    @Test fun excludingEveryCandidateYieldsNull() {
        assertNull(NetUtils.pick(listOf("wlan0" to "192.168.1.56"), preferGateway = true, exclude = setOf("192.168.1.56")))
    }
}
