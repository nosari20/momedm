package edu.fnosari.momedm.persistence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerPrefsTest {
    @Test fun identityIsNullUntilEnsured() = runTest {
        val p = ControllerPrefs(InMemoryPreferencesProvider())
        assertNull(p.identity())

        val id = p.ensureIdentity()
        assertEquals(36, id.controllerId.length)
        assertEquals(32, id.secretBytes.size)

        val again = p.ensureIdentity()
        assertEquals(id.controllerId, again.controllerId)
        assertEquals(id.secretBase64, again.secretBase64)

        assertEquals(id, p.identity())
    }

    @Test fun regenerateKeepsControllerIdChangesSecret() = runTest {
        val p = ControllerPrefs(InMemoryPreferencesProvider())
        val original = p.ensureIdentity()

        val regenerated = p.regenerateSecret()
        assertEquals(original.controllerId, regenerated.controllerId)
        assertNotEquals(original.secretBase64, regenerated.secretBase64)

        assertEquals(regenerated, p.identity())
    }

    @Test fun wifiAndAdvertiseFlagRoundTrip() = runTest {
        val p = ControllerPrefs(InMemoryPreferencesProvider())
        p.setWifi("MANUAL", "ssid", "pw", "https://x/apk")
        assertEquals("MANUAL", p.wifiMode.first())
        assertEquals("ssid", p.manualSsid.first())
        assertEquals("pw", p.manualPassword.first())
        assertEquals("https://x/apk", p.customUrl.first())

        assertTrue(p.advertiseOnLaunch.first())
        p.setAdvertiseOnLaunch(false)
        assertEquals(false, p.advertiseOnLaunch.first())
    }
}
