package edu.fnosari.momedm.controller.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ControllerIdentityTest {
    @Test fun generateIsRandomAnd32Bytes() {
        val a = ControllerIdentity.generate(); val b = ControllerIdentity.generate()
        assertEquals(32, a.secretBytes.size)
        assertNotEquals(a.secretBase64, b.secretBase64); assertNotEquals(a.controllerId, b.controllerId)
        assertEquals(36, a.controllerId.length) // UUID
    }
}
