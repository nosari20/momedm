package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.persistence.preferences.PreferencesProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryPreferencesProvider : PreferencesProvider {
    private val store = MutableStateFlow<Map<String, Any>>(emptyMap())
    @Suppress("UNCHECKED_CAST") private fun <T> read(key: String, default: T): Flow<T> = store.map { (it[key] as? T) ?: default }
    private fun put(key: String, v: Any) { store.value = store.value + (key to v) }
    override fun readString(key: String, default: String) = read(key, default)
    override fun readInt(key: String, default: Int) = read(key, default)
    override fun readBoolean(key: String, default: Boolean) = read(key, default)
    override fun readDouble(key: String, default: Double) = read(key, default)
    override suspend fun write(key: String, value: String) = put(key, value)
    override suspend fun write(key: String, value: Int) = put(key, value)
    override suspend fun write(key: String, value: Boolean) = put(key, value)
    override suspend fun write(key: String, value: Double) = put(key, value)
}
