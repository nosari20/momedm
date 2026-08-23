package edu.fnosari.momedm.protocol

import edu.fnosari.momedm.controller.provisioning.ProvisioningParams
import edu.fnosari.momedm.controller.provisioning.QrPayloadBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scanner feeds this whatever the camera decoded, so the interesting cases are all the things
 * that are *not* one of our pairing codes.
 */
class PairingPayloadTest {
    private val secret = "A".repeat(43) + "="

    private fun realQr(id: String = "eb76b426-53d2-470b-8492-4aa1954e2aef", s: String = secret) =
        QrPayloadBuilder.build(
            ProvisioningParams(
                apkUrl = "http://192.168.1.56:8080/momedm.apk",
                signatureChecksum = "abc",
                wifiSsid = "Home",
                wifiPassword = "hunter2",
                controllerId = id,
                secretBase64 = s,
            ),
        )

    @Test fun readsTheIdentityOutOfARealPairingCode() {
        // Built by the same code the parent's screen uses, so this pins the two ends together.
        val got = PairingPayload.parse(realQr())
        assertEquals("eb76b426-53d2-470b-8492-4aa1954e2aef", got?.controllerId)
        assertEquals(secret, got?.secretBase64)
    }

    @Test fun rejectsCodesThatAreNotOurs() {
        for (other in listOf(
            "",
            "https://example.com",
            "WIFI:S:MyNetwork;T:WPA;P:password;;",
            """{"some":"json"}""",
            """{"android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE":{}}""",
            "not json at all {{{",
        )) {
            assertNull("expected to reject: $other", PairingPayload.parse(other))
        }
    }

    @Test fun rejectsAnIdentityWithAMissingOrMisshapenSecret() {
        // A truncated or padded-wrong secret would be accepted and then fail every handshake
        // afterwards, with nothing on screen to explain why — better to refuse the scan.
        assertNull(PairingPayload.parse(realQr(s = "")))
        assertNull(PairingPayload.parse(realQr(s = "tooshort")))
        assertNull(PairingPayload.parse(realQr(s = "B".repeat(44))))
        assertNull(PairingPayload.parse(realQr(s = "!".repeat(43) + "=")))
    }

    @Test fun rejectsABlankControllerId() {
        assertNull(PairingPayload.parse(realQr(id = "")))
    }
}
