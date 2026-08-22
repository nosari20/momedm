package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class DeviceRecord(val deviceId: String, val model: String, val lastSeen: Long, val lastStatus: Message.Status? = null)

/** JSON codec for the registry blob persisted in [ControllerPrefs]. */
object DeviceRegistryCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(DeviceRecord.serializer())
    fun encode(list: List<DeviceRecord>): String = json.encodeToString(ser, list)
    fun decode(s: String): List<DeviceRecord> = if (s.isBlank()) emptyList() else try { json.decodeFromString(ser, s) } catch (e: Exception) { emptyList() }
}

/** Known managed devices, persisted as JSON in [ControllerPrefs]. Loaded once on construction. */
class DeviceRegistry(private val prefs: ControllerPrefs, scope: CoroutineScope) {
    private val mutex = Mutex()
    private val _devices = MutableStateFlow<List<DeviceRecord>>(emptyList())
    val devices: StateFlow<List<DeviceRecord>> = _devices.asStateFlow()
    private val loaded = scope.launch { _devices.value = DeviceRegistryCodec.decode(prefs.registryJson.first()) }

    fun get(deviceId: String): DeviceRecord? = _devices.value.firstOrNull { it.deviceId == deviceId }

    suspend fun upsertSeen(deviceId: String, model: String, nowMs: Long) = mutate { list ->
        val old = list.firstOrNull { it.deviceId == deviceId }
        list.filter { it.deviceId != deviceId } + DeviceRecord(deviceId, model, nowMs, old?.lastStatus)
    }
    suspend fun updateStatus(deviceId: String, status: Message.Status, nowMs: Long) = mutate { list ->
        val old = list.firstOrNull { it.deviceId == deviceId } ?: DeviceRecord(deviceId, "?", nowMs)
        list.filter { it.deviceId != deviceId } + old.copy(lastSeen = nowMs, lastStatus = status)
    }
    private suspend fun mutate(f: (List<DeviceRecord>) -> List<DeviceRecord>) {
        loaded.join()
        mutex.withLock {
            _devices.value = f(_devices.value).sortedByDescending { it.lastSeen }
            prefs.saveRegistry(DeviceRegistryCodec.encode(_devices.value))
        }
    }
}
