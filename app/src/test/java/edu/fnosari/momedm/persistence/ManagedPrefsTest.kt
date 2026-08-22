package edu.fnosari.momedm.persistence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedPrefsTest {
    @Test fun provisioningAndDeviceId() = runTest {
        val p = ManagedPrefs(InMemoryPreferencesProvider())
        assertFalse(p.isProvisioned())
        p.saveProvisioning("cid", "AAAA")
        assertTrue(p.isProvisioned()); assertEquals("cid", p.controllerId.first())
        val id1 = p.ensureDeviceId(); val id2 = p.ensureDeviceId()
        assertEquals(id1, id2); assertEquals(36, id1.length)
        p.setKiosk(true, "com.k"); assertTrue(p.kioskOn.first()); assertEquals("com.k", p.kioskPkg.first())
        p.setKiosk(false, null); assertFalse(p.kioskOn.first()); assertEquals("", p.kioskPkg.first())
    }
}
