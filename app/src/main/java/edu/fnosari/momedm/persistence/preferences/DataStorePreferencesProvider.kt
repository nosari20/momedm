package edu.fnosari.momedm.persistence.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * [PreferencesProvider] backed by Jetpack DataStore.
 *
 * Reads are non-blocking [Flow]s (DataStore reads from disk on a background
 * dispatcher); writes suspend until persisted. A corrupt store no longer crashes
 * reads — it falls back to empty preferences.
 *
 * @param context any context; only the application context is retained.
 */
class DataStorePreferencesProvider(context: Context) : PreferencesProvider {

    private val appContext = context.applicationContext

    companion object {
        private const val LOG_TAG = "DataStorePreferencesProvider"

        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("preferences")
    }

    private fun <T> read(key: Preferences.Key<T>, default: T): Flow<T> =
        appContext.dataStore.data
            .catch { e ->
                // IOException = corrupt/unreadable store; recover with empty prefs.
                if (e is IOException) {
                    Log.e(LOG_TAG, "Error reading preferences for ${key.name}", e)
                    emit(emptyPreferences())
                } else {
                    throw e
                }
            }
            .map { prefs -> prefs[key] ?: default }

    override fun readString(key: String, default: String): Flow<String> =
        read(stringPreferencesKey(key), default)

    override fun readInt(key: String, default: Int): Flow<Int> =
        read(intPreferencesKey(key), default)

    override fun readBoolean(key: String, default: Boolean): Flow<Boolean> =
        read(booleanPreferencesKey(key), default)

    override fun readDouble(key: String, default: Double): Flow<Double> =
        read(doublePreferencesKey(key), default)

    override suspend fun write(key: String, value: String) {
        // Never log a string value: the shared HMAC secret, the controller id and the Wi-Fi
        // passphrase all round-trip through here, and logcat is readable by adb/bug reports.
        Log.d(LOG_TAG, "Writing string $key (${value.length} chars)")
        appContext.dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun write(key: String, value: Int) {
        Log.d(LOG_TAG, "Writing int $key = $value")
        appContext.dataStore.edit { it[intPreferencesKey(key)] = value }
    }

    override suspend fun write(key: String, value: Boolean) {
        Log.d(LOG_TAG, "Writing boolean $key = $value")
        appContext.dataStore.edit { it[booleanPreferencesKey(key)] = value }
    }

    override suspend fun write(key: String, value: Double) {
        Log.d(LOG_TAG, "Writing double $key = $value")
        appContext.dataStore.edit { it[doublePreferencesKey(key)] = value }
    }
}
