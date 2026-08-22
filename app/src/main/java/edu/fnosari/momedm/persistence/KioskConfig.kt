package edu.fnosari.momedm.persistence

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Child-mode configuration persisted on the managed device. Pure Kotlin. */
data class KioskConfig(
    val on: Boolean = false,
    val apps: List<String> = emptyList(),
    /** Single app to keep in front, must be in [apps]; null = free choice among [apps]. */
    val pinned: String? = null,
    /** Epoch ms until which a parent-PIN pause releases lock task; 0 = no pause. Not honoured across reboot. */
    val pauseUntil: Long = 0L,
) {
    fun isPaused(nowMs: Long): Boolean = on && pauseUntil > nowMs
    fun isLocked(nowMs: Long): Boolean = on && !isPaused(nowMs)
    companion object {
        const val PAUSE_MS = 600_000L
        private val ser = ListSerializer(String.serializer())
        fun encodeApps(list: List<String>): String = Json.encodeToString(ser, list)
        fun decodeApps(s: String): List<String> = if (s.isBlank()) emptyList() else try { Json.decodeFromString(ser, s) } catch (e: Exception) { emptyList() }
    }
}
