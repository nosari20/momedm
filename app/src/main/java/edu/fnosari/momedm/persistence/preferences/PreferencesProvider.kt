package edu.fnosari.momedm.persistence.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Manages key-value preferences.
 *
 * Reads return a [Flow] that emits the current value and every subsequent change,
 * so the UI can observe preferences reactively without ever blocking a thread on
 * disk I/O. Writes are `suspend` functions and must be called from a coroutine.
 *
 * Implement this interface to back it with a concrete store (see
 * [DataStorePreferencesProvider]) or a no-op stub ([DefaultPreferencesProvider]).
 */
interface PreferencesProvider {

    /** Emits the String for [key], or [default] until/unless one is stored. */
    fun readString(key: String, default: String): Flow<String>

    /** Emits the Int for [key], or [default] until/unless one is stored. */
    fun readInt(key: String, default: Int): Flow<Int>

    /** Emits the Boolean for [key], or [default] until/unless one is stored. */
    fun readBoolean(key: String, default: Boolean): Flow<Boolean>

    /** Emits the Double for [key], or [default] until/unless one is stored. */
    fun readDouble(key: String, default: Double): Flow<Double>

    /** Stores a String value for [key]. */
    suspend fun write(key: String, value: String)

    /** Stores an Int value for [key]. */
    suspend fun write(key: String, value: Int)

    /** Stores a Boolean value for [key]. */
    suspend fun write(key: String, value: Boolean)

    /** Stores a Double value for [key]. */
    suspend fun write(key: String, value: Double)
}
