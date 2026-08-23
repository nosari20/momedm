package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LockScheduleTest {
    private val paris: ZoneId = ZoneId.of("Europe/Paris")
    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // 2026-08-24 is a Monday, 2026-08-21 a Friday (both verified against the ISO calendar).
    private val schedule = LockSchedule(enabled = true, weekdayStart = 21 * 60, weekdayEnd = 7 * 60,
        weekendStart = 22 * 60, weekendEnd = 8 * 60)

    @Test fun disabledScheduleNeverLocks() {
        assertFalse(schedule.copy(enabled = false).isLockedAt(ms("2026-08-24T23:00:00Z"), paris))
        assertNull(schedule.copy(enabled = false).nextTransition(ms("2026-08-24T23:00:00Z"), paris))
    }

    @Test fun weekdayNightLocksAcrossMidnight() {
        // Monday 22:00 Paris (= 20:00Z) is inside the 21:00->07:00 window.
        assertTrue(schedule.isLockedAt(ms("2026-08-24T20:00:00Z"), paris))
        // Tuesday 03:00 Paris (= 01:00Z) is still inside Monday's window.
        assertTrue(schedule.isLockedAt(ms("2026-08-25T01:00:00Z"), paris))
        // Tuesday 08:00 Paris (= 06:00Z) is after it ended.
        assertFalse(schedule.isLockedAt(ms("2026-08-25T06:00:00Z"), paris))
        // Monday 20:00 Paris (= 18:00Z) is before it starts.
        assertFalse(schedule.isLockedAt(ms("2026-08-24T18:00:00Z"), paris))
    }

    @Test fun fridayAndSaturdayNightsUseWeekendTimes() {
        // Friday 21:30 Paris (= 19:30Z): weekday window would lock, weekend window (22:00) must not.
        assertFalse(schedule.isLockedAt(ms("2026-08-21T19:30:00Z"), paris))
        // Friday 22:30 Paris (= 20:30Z): inside the weekend window.
        assertTrue(schedule.isLockedAt(ms("2026-08-21T20:30:00Z"), paris))
        // Saturday 07:30 Paris (= 05:30Z): weekend window ends at 08:00, so still locked.
        assertTrue(schedule.isLockedAt(ms("2026-08-22T05:30:00Z"), paris))
        // Sunday 21:30 Paris (= 19:30Z): Sunday night is a school night, so locked.
        assertTrue(schedule.isLockedAt(ms("2026-08-23T19:30:00Z"), paris))
    }

    @Test fun equalStartAndEndDisablesThatDayType() {
        val s = schedule.copy(weekdayStart = 21 * 60, weekdayEnd = 21 * 60)
        assertFalse(s.isLockedAt(ms("2026-08-24T20:00:00Z"), paris))   // Monday 22:00 Paris
        assertTrue(s.isLockedAt(ms("2026-08-21T20:30:00Z"), paris))    // Friday still uses weekend times
    }

    @Test fun sameDayWindowIsSupported() {
        val nap = LockSchedule(enabled = true, weekdayStart = 13 * 60, weekdayEnd = 15 * 60,
            weekendStart = 13 * 60, weekendEnd = 15 * 60)
        assertTrue(nap.isLockedAt(ms("2026-08-24T12:00:00Z"), paris))   // Monday 14:00 Paris
        assertFalse(nap.isLockedAt(ms("2026-08-24T14:00:00Z"), paris))  // Monday 16:00 Paris
    }

    @Test fun windowEndIsTheEndOfTheCurrentWindow() {
        // Monday 22:00 Paris -> window ends Tuesday 07:00 Paris = 05:00Z.
        assertEquals(ms("2026-08-25T05:00:00Z"), schedule.windowEndAt(ms("2026-08-24T20:00:00Z"), paris))
        assertNull(schedule.windowEndAt(ms("2026-08-24T18:00:00Z"), paris))
    }

    @Test fun nextTransitionIsTheEarliestBoundaryAhead() {
        // Monday 20:00 Paris -> next boundary is the 21:00 start = 19:00Z.
        assertEquals(ms("2026-08-24T19:00:00Z"), schedule.nextTransition(ms("2026-08-24T18:00:00Z"), paris))
        // Monday 22:00 Paris -> next boundary is the 07:00 end = 05:00Z next day.
        assertEquals(ms("2026-08-25T05:00:00Z"), schedule.nextTransition(ms("2026-08-24T20:00:00Z"), paris))
    }

    @Test fun dstSpringForwardShiftsAMissingLocalTime() {
        // Europe/Paris springs forward 2026-03-29 02:00 -> 03:00 (a Sunday, so weekday times apply).
        // A 02:30 start does not exist that day; java.time shifts it to 03:30 CEST = 01:30Z.
        val s = LockSchedule(enabled = true, weekdayStart = 2 * 60 + 30, weekdayEnd = 4 * 60,
            weekendStart = 2 * 60 + 30, weekendEnd = 4 * 60)
        assertEquals(ms("2026-03-29T01:30:00Z"), s.nextTransition(ms("2026-03-29T00:30:00Z"), paris))
        assertTrue(s.isLockedAt(ms("2026-03-29T01:35:00Z"), paris))
    }

    @Test fun dstFallBackUsesTheEarlierOffset() {
        // Europe/Paris falls back 2026-10-25 03:00 -> 02:00 (a Sunday). 02:30 happens twice;
        // java.time picks the earlier offset (CEST, +02:00) = 00:30Z.
        val s = LockSchedule(enabled = true, weekdayStart = 2 * 60 + 30, weekdayEnd = 4 * 60,
            weekendStart = 2 * 60 + 30, weekendEnd = 4 * 60)
        assertEquals(ms("2026-10-25T00:30:00Z"), s.nextTransition(ms("2026-10-24T23:00:00Z"), paris))
    }

    @Test fun sanitizedClampsOutOfRangeValuesToDefaults() {
        val s = LockSchedule(enabled = true, weekdayStart = -5, weekdayEnd = 5000,
            weekendStart = 1440, weekendEnd = 8 * 60).sanitized()
        assertEquals(21 * 60, s.weekdayStart); assertEquals(7 * 60, s.weekdayEnd)
        assertEquals(22 * 60, s.weekendStart); assertEquals(8 * 60, s.weekendEnd)
        assertTrue(s.enabled)
    }

    @Test fun lockStateManualBeatsSchedule() {
        val now = ms("2026-08-24T12:00:00Z")   // Monday 14:00 Paris, no window
        val st = LockState.evaluate(schedule, manualLock = true, pauseUntil = 0L, nowMs = now, zone = paris)
        assertTrue(st.locked); assertEquals(LockState.REASON_MANUAL, st.reason); assertNull(st.until)
    }

    @Test fun lockStateNightCarriesItsEnd() {
        val now = ms("2026-08-24T20:00:00Z")
        val st = LockState.evaluate(schedule, manualLock = false, pauseUntil = 0L, nowMs = now, zone = paris)
        assertTrue(st.locked); assertEquals(LockState.REASON_NIGHT, st.reason)
        assertEquals(ms("2026-08-25T05:00:00Z"), st.until)
    }

    @Test fun lockStatePauseBeatsEverything() {
        val now = ms("2026-08-24T20:00:00Z")
        val st = LockState.evaluate(schedule, manualLock = true, pauseUntil = now + 60_000L, nowMs = now, zone = paris)
        assertFalse(st.locked); assertNull(st.reason)
    }

    @Test fun lockStateUnlockedOutsideAnyWindow() {
        val st = LockState.evaluate(schedule, manualLock = false, pauseUntil = 0L,
            nowMs = ms("2026-08-24T12:00:00Z"), zone = paris)
        assertFalse(st.locked); assertNull(st.reason); assertNull(st.until)
    }
}
