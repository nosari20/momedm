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
}
