package edu.fnosari.momedm.persistence.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * No-op [PreferencesProvider]: reads always emit the supplied default and writes
 * are discarded. Useful as a placeholder/fallback or in tests and `@Preview`s
 * where no real storage is available.
 */
class DefaultPreferencesProvider : PreferencesProvider {

    override fun readString(key: String, default: String): Flow<String> = flowOf(default)

    override fun readInt(key: String, default: Int): Flow<Int> = flowOf(default)

    override fun readBoolean(key: String, default: Boolean): Flow<Boolean> = flowOf(default)

    override fun readDouble(key: String, default: Double): Flow<Double> = flowOf(default)

    override suspend fun write(key: String, value: String) {}

    override suspend fun write(key: String, value: Int) {}

    override suspend fun write(key: String, value: Boolean) {}

    override suspend fun write(key: String, value: Double) {}
}
