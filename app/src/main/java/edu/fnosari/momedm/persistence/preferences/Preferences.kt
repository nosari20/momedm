package edu.fnosari.momedm.persistence.preferences

/**
 * A typed preference: its [key] plus the [default] returned until a value is
 * stored. The concrete subclass fixes the value type, so the settings UI can
 * dispatch on it exhaustively and reads/writes stay type-checked — no reflection.
 *
 * @property key The unique identifier for the preference.
 * @property default The value used when nothing is stored yet.
 */
sealed class Preference<T>(val key: String, val default: T) {
    class StringPreference(key: String, default: String = "") : Preference<String>(key, default)
    class IntPreference(key: String, default: Int = 0) : Preference<Int>(key, default)
    class BooleanPreference(key: String, default: Boolean = false) : Preference<Boolean>(key, default)
    class DoublePreference(key: String, default: Double = 0.0) : Preference<Double>(key, default)
}
