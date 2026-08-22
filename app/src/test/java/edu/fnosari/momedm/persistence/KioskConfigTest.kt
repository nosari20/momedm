package edu.fnosari.momedm.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskConfigTest {
    @Test fun pauseLogic() {
        val c = KioskConfig(on = true, apps = listOf("a"), pauseUntil = 1_000L)
        assertTrue(c.isPaused(500L)); assertFalse(c.isLocked(500L))
        assertFalse(c.isPaused(1_000L)); assertTrue(c.isLocked(1_000L))
        assertFalse(KioskConfig(on = false, pauseUntil = 9_999L).isPaused(0L))
        assertFalse(KioskConfig(on = false).isLocked(0L))
    }
    @Test fun appsCodec() {
        val l = listOf("com.a", "com.b.c")
        assertEquals(l, KioskConfig.decodeApps(KioskConfig.encodeApps(l)))
        assertEquals(emptyList<String>(), KioskConfig.decodeApps("")); assertEquals(emptyList<String>(), KioskConfig.decodeApps("not json"))
    }
}
