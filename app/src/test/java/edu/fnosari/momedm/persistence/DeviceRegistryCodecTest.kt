package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceRegistryCodecTest {
    @Test fun roundTrip() {
        val list = listOf(DeviceRecord("d1", "Pixel", 10L, Message.Status(false, null, true, 50, "x")), DeviceRecord("d2", "Nokia", 20L))
        assertEquals(list, DeviceRegistryCodec.decode(DeviceRegistryCodec.encode(list)))
        assertEquals(emptyList<DeviceRecord>(), DeviceRegistryCodec.decode("")); assertEquals(emptyList<DeviceRecord>(), DeviceRegistryCodec.decode("garbage"))
    }
    @Test fun upsertKeepsStatusAndUpdatesSeen() = runTest {
        val prefs = ControllerPrefs(InMemoryPreferencesProvider())
        val reg = DeviceRegistry(prefs, this)
        reg.upsertSeen("d1", "Pixel", 1L)
        reg.updateStatus("d1", Message.Status(true, "k", false, 9, "k"), 2L)
        reg.upsertSeen("d1", "Pixel", 3L)
        val r = reg.get("d1")!!
        assertEquals(3L, r.lastSeen); assertEquals("k", r.lastStatus?.kioskPkg)
        assertNull(reg.get("nope"))
        assertEquals(DeviceRegistryCodec.encode(reg.devices.value), DeviceRegistryCodec.decode(DeviceRegistryCodec.encode(reg.devices.value)).let { DeviceRegistryCodec.encode(it) })
    }
}
