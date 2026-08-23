package edu.fnosari.momedm.protocol

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A parent-chosen nightly lock window, pushed to a child device. Pure Kotlin.
 *
 * Times are minutes since local midnight. Two rules decide what a window covers:
 *  - it **wraps midnight when start > end** (21:00 -> 07:00 runs into the next day); a window with
 *    start < end is a same-day window (an afternoon quiet period) and is equally legal;
 *  - a night **belongs to the day it starts**, so windows starting Friday or Saturday use the
 *    weekend times and windows starting Sunday-Thursday use the weekday times. That is how families
 *    state the rule ("Friday night they can stay up later").
 *
 * `start == end` disables that day type rather than locking for 24 hours — the safer reading of an
 * accidental value.
 */
@Serializable
data class LockSchedule(
    val enabled: Boolean = false,
    val weekdayStart: Int = 21 * 60,
    val weekdayEnd: Int = 7 * 60,
    val weekendStart: Int = 22 * 60,
    val weekendEnd: Int = 8 * 60,
) {
    /** Replaces any out-of-range minute value with this class's default for that field. */
    fun sanitized(): LockSchedule = copy(
        weekdayStart = clamp(weekdayStart, 21 * 60), weekdayEnd = clamp(weekdayEnd, 7 * 60),
        weekendStart = clamp(weekendStart, 22 * 60), weekendEnd = clamp(weekendEnd, 8 * 60),
    )

    fun isLockedAt(nowMs: Long, zone: ZoneId): Boolean = windowAt(nowMs, zone) != null

    /** End (epoch ms) of the window containing [nowMs], or null when [nowMs] is inside none. */
    fun windowEndAt(nowMs: Long, zone: ZoneId): Long? = windowAt(nowMs, zone)?.second

    /** Earliest window boundary strictly after [nowMs] — the only input to alarm scheduling. */
    fun nextTransition(nowMs: Long, zone: ZoneId): Long? {
        if (!enabled) return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return listOf(today.minusDays(1), today, today.plusDays(1))
            .mapNotNull { window(it, zone) }
            .flatMap { listOf(it.first, it.second) }
            .filter { it > nowMs }
            .minOrNull()
    }

    /**
     * The window containing [nowMs], as (startMs, endMs). Both the window that started today and the
     * one that started yesterday are considered — a wrapped window is still running after midnight.
     */
    private fun windowAt(nowMs: Long, zone: ZoneId): Pair<Long, Long>? {
        if (!enabled) return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return listOf(today.minusDays(1), today)
            .mapNotNull { window(it, zone) }
            .firstOrNull { nowMs >= it.first && nowMs < it.second }
    }

    /** The window that *starts* on [startDate], or null when that day type has start == end. */
    private fun window(startDate: LocalDate, zone: ZoneId): Pair<Long, Long>? {
        val weekend = startDate.dayOfWeek == DayOfWeek.FRIDAY || startDate.dayOfWeek == DayOfWeek.SATURDAY
        val s = if (weekend) weekendStart else weekdayStart
        val e = if (weekend) weekendEnd else weekdayEnd
        if (s == e) return null
        val endDate = if (e > s) startDate else startDate.plusDays(1)
        return at(startDate, s, zone) to at(endDate, e, zone)
    }

    private companion object {
        fun clamp(v: Int, fallback: Int): Int = if (v in 0..1439) v else fallback

        /**
         * Local wall-clock [minutes] on [date] as an instant. Built with `atZone` rather than by
         * adding minutes to midnight so DST is resolved by the platform: a time inside a
         * spring-forward gap shifts later by the gap, and one inside a fall-back overlap takes the
         * earlier offset. Adding a duration to midnight would silently drift an hour on those days.
         */
        fun at(date: LocalDate, minutes: Int, zone: ZoneId): Long =
            date.atTime(minutes / 60, minutes % 60).atZone(zone).toInstant().toEpochMilli()
    }
}

/**
 * The device's complete-lock state. Always recomputed from its inputs, never persisted: a stored
 * "locked" flag would survive a missed alarm, a reboot, or a clock change and strand the device.
 */
data class LockState(val locked: Boolean, val reason: String? = null, val until: Long? = null) {
    companion object {
        const val REASON_NIGHT = "night"
        const val REASON_MANUAL = "manual"

        /** `(manualLock || schedule.isLockedAt(now)) && pauseUntil <= now`, with the reason and end time. */
        fun evaluate(schedule: LockSchedule, manualLock: Boolean, pauseUntil: Long, nowMs: Long, zone: ZoneId): LockState {
            if (pauseUntil > nowMs) return LockState(false)
            if (manualLock) return LockState(true, REASON_MANUAL, null)
            val end = schedule.windowEndAt(nowMs, zone) ?: return LockState(false)
            return LockState(true, REASON_NIGHT, end)
        }
    }
}
