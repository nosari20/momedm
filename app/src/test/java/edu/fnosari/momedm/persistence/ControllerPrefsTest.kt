package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.protocol.ChildPrefs
import edu.fnosari.momedm.protocol.PinHash
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun pinAndChildPrefs() = runTest {
        val p = ControllerPrefs(InMemoryPreferencesProvider())
        assertFalse(p.pinSet.first()); assertEquals(ChildPrefs(), p.childPrefsNow())
        p.setUiPrefs("fr", "dark", 0x11223344)
        p.setPin("4321")
        assertTrue(p.pinSet.first())
        val cp = p.childPrefsNow()
        assertEquals("fr", cp.language); assertEquals("dark", cp.theme); assertEquals(0x11223344, cp.accent)
        assertTrue(PinHash.verify("4321", cp.pinSalt!!, cp.pinHash!!)); assertFalse(PinHash.verify("1234", cp.pinSalt!!, cp.pinHash!!))
        p.clearPin(); assertFalse(p.pinSet.first())
        assertEquals(null, p.childPrefsNow().pinHash); assertEquals(null, p.childPrefsNow().pinSalt)
    }
    @Test fun setPinRejectsInvalid() = runTest {
        val p = ControllerPrefs(InMemoryPreferencesProvider())
        assertFalse(p.setPin("12")); assertFalse(p.setPin("abcd")); assertFalse(p.pinSet.first())
        assertTrue(p.setPin("123456"))
    }
}
