package edu.fnosari.momedm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationSmokeTest {
    @Serializable
    data class Probe(val a: Int, val b: String)

    @Test
    fun roundTrip() {
        val json = Json.encodeToString(Probe.serializer(), Probe(1, "x"))
        assertEquals("""{"a":1,"b":"x"}""", json)
        assertEquals(Probe(1, "x"), Json.decodeFromString(Probe.serializer(), json))
    }
}
