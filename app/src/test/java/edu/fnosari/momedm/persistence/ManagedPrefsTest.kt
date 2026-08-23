package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.protocol.LockSchedule
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
    @Test fun kioskConfigAndChildPrefsRoundTrip() = runTest {
        val p = ManagedPrefs(InMemoryPreferencesProvider())
        assertEquals(KioskConfig(), p.kioskConfig.first())
        p.setKioskConfig(KioskConfig(on = true, apps = listOf("a", "b"), pinned = "a", pauseUntil = 0L))
        assertEquals(KioskConfig(true, listOf("a", "b"), "a", 0L), p.kioskConfig.first())
        assertTrue(p.kioskOn.first()); assertEquals("a", p.kioskPkg.first())
        p.setPauseUntil(42L); assertEquals(42L, p.kioskConfig.first().pauseUntil)
        p.setKiosk(false, null); assertFalse(p.kioskConfig.first().on); assertEquals(listOf("a", "b"), p.kioskConfig.first().apps)
        val cp = edu.fnosari.momedm.protocol.ChildPrefs("fr", "dark", 7, "00".repeat(16), "11".repeat(32))
        p.setChildPrefs(cp); assertEquals(cp, p.childPrefs.first())
    }
    @Test fun pinLockoutRoundTrip() = runTest {
        val p = ManagedPrefs(InMemoryPreferencesProvider())
        assertEquals(0, p.pinFailures.first()); assertEquals(0L, p.pinLockUntil.first())
        p.setPinLock(3, 1_700_000_000_000L)
        assertEquals(3, p.pinFailures.first()); assertEquals(1_700_000_000_000L, p.pinLockUntil.first())
        p.setPinLock(0, 0L)
        assertEquals(0, p.pinFailures.first()); assertEquals(0L, p.pinLockUntil.first())
    }

    @Test fun lockScheduleDefaultsThenRoundTrips() = runTest {
        val prefs = ManagedPrefs(InMemoryPreferencesProvider())
        assertEquals(LockSchedule(), prefs.lockSchedule.first())
        val s = LockSchedule(enabled = true, weekdayStart = 20 * 60 + 30, weekdayEnd = 6 * 60 + 45,
            weekendStart = 23 * 60, weekendEnd = 9 * 60)
        prefs.setLockSchedule(s)
        assertEquals(s, prefs.lockSchedule.first())
    }

    @Test fun manualLockDefaultsFalseAndRoundTrips() = runTest {
        val prefs = ManagedPrefs(InMemoryPreferencesProvider())
        assertFalse(prefs.manualLock.first())
        prefs.setManualLock(true)
        assertTrue(prefs.manualLock.first())
        prefs.setManualLock(false)
        assertFalse(prefs.manualLock.first())
    }
}
