# Complete Lock (night schedule + manual) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A child device locks itself completely on a nightly schedule or on the parent's command, and unlocks when the window ends or a parent PIN is entered.

**Architecture:** The lock decision is a pure function of (schedule, manualLock, pauseUntil, now) — never a persisted boolean. `LockSchedule`/`LockState` live in `protocol/` as pure Kotlin and carry all the date maths; `LockController` on the managed side re-evaluates that function on every trigger (alarm, boot, time change, launcher resume, command) and applies the result through `PolicyManager`. Alarms only *wake* the device to re-evaluate, so a missed or stale alarm cannot strand it in the wrong state.

**Tech Stack:** Kotlin, `java.time`, kotlinx-serialization, AlarmManager, DevicePolicyManager lock task, Jetpack Compose (Material3, BOM 2024.09.00), DataStore via the project's `PreferencesProvider`, JUnit 4 + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-08-23-lock-and-safety-design.md` (Part 1). Read it alongside this plan.

## Global Constraints

- `protocol/` stays pure Kotlin — no Android imports. `java.time` is allowed there.
- `connectivity/ble/` stays app-agnostic; do not touch it.
- No new dependencies. Core Material icons only — **never** `material-icons-extended` (it compiled but crashed at runtime on this Compose BOM).
- Every new string goes in **both** `app/src/main/res/values/strings.xml` and `app/src/main/res/values-fr/strings.xml`, key-for-key. `StringsParityTest` must stay green.
- Never log a PIN, secret, or BLE payload in clear.
- Parent- and child-facing copy uses family vocabulary (parent / enfant, nuit) — never "MDM", "kiosk", "lock task", "device owner".
- Windows/Gradle: if a build fails with `Unable to delete directory …app\build`, run `./gradlew --stop; rm -rf app/build` and retry. Write multi-line files with the Write tool, not bash heredocs.
- Build and test with `./gradlew :app:assembleDebug :app:testDebugUnitTest` from `C:/Users/ACH02/Documents/Projects/Android/momedm`.

## Deviation from the spec (deliberate)

Spec §1.8 lists `locked`, `lockReason`, `lockUntil` as the new `Status` fields. This plan also adds **`schedule: LockSchedule?`** to `Status`, because the parent UI in §1.9 must render the child's *current* schedule values after the parent app restarts, and there is no other channel that carries them. Same command, same shape, one extra optional field.

---

### Task 1: `LockSchedule` and `LockState` (pure Kotlin)

**Files:**
- Create: `app/src/main/java/edu/fnosari/momedm/protocol/LockSchedule.kt`
- Test: `app/src/test/java/edu/fnosari/momedm/protocol/LockScheduleTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `LockSchedule(enabled: Boolean, weekdayStart: Int, weekdayEnd: Int, weekendStart: Int, weekendEnd: Int)` — minutes since local midnight; `sanitized(): LockSchedule`; `isLockedAt(nowMs: Long, zone: ZoneId): Boolean`; `windowEndAt(nowMs: Long, zone: ZoneId): Long?`; `nextTransition(nowMs: Long, zone: ZoneId): Long?`
  - `LockState(locked: Boolean, reason: String?, until: Long?)` with `LockState.evaluate(schedule, manualLock, pauseUntil, nowMs, zone): LockState`, and constants `LockState.REASON_NIGHT = "night"`, `LockState.REASON_MANUAL = "manual"`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/edu/fnosari/momedm/protocol/LockScheduleTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*LockScheduleTest*"`
Expected: FAIL — compilation error, `LockSchedule` unresolved.

- [ ] **Step 3: Implement `LockSchedule.kt`**

Create `app/src/main/java/edu/fnosari/momedm/protocol/LockSchedule.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*LockScheduleTest*"`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/edu/fnosari/momedm/protocol/LockSchedule.kt app/src/test/java/edu/fnosari/momedm/protocol/LockScheduleTest.kt
git commit -m "feat: LockSchedule and LockState as pure functions"
```

---

### Task 2: Persist the schedule and the manual-lock flag

**Files:**
- Modify: `app/src/main/java/edu/fnosari/momedm/persistence/ManagedPrefs.kt`
- Test: `app/src/test/java/edu/fnosari/momedm/persistence/ManagedPrefsTest.kt` (add cases to the existing file)

**Interfaces:**
- Consumes: `LockSchedule` from Task 1.
- Produces: `ManagedPrefs.lockSchedule: Flow<LockSchedule>`, `ManagedPrefs.manualLock: Flow<Boolean>`, `suspend fun setLockSchedule(s: LockSchedule)`, `suspend fun setManualLock(on: Boolean)`.

- [ ] **Step 1: Write the failing tests**

Append to `ManagedPrefsTest.kt` (keep the file's existing imports and helper style; it builds a `ManagedPrefs` over `InMemoryPreferencesProvider`):

```kotlin
    @Test fun lockScheduleDefaultsThenRoundTrips() = runTest {
        val prefs = ManagedPrefs(InMemoryPreferencesProvider())
        assertEquals(LockSchedule(), prefs.lockSchedule.first())
        val s = LockSchedule(enabled = true, weekdayStart = 20 * 60 + 30, weekdayEnd = 6 * 60 + 45,
            weekendStart = 23 * 60, weekendEnd = 9 * 60)
        prefs.setLockSchedule(s)
        assertEquals(s, prefs.lockSchedule.first())
    }

    @Test fun manualLockDefaultsFalseAndRoundTrips() = runTest {
        val prefs = ManagedPrefs(InMemoryPreferencesProvider())
        assertFalse(prefs.manualLock.first())
        prefs.setManualLock(true)
        assertTrue(prefs.manualLock.first())
        prefs.setManualLock(false)
        assertFalse(prefs.manualLock.first())
    }
```

Add `import edu.fnosari.momedm.protocol.LockSchedule` and any missing `assertTrue`/`assertFalse` imports.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*ManagedPrefsTest*"`
Expected: FAIL — `lockSchedule` / `manualLock` unresolved.

- [ ] **Step 3: Implement the storage**

In `ManagedPrefs.kt`, add to the `companion object`:

```kotlin
        const val KEY_LOCK_ENABLED = "managed_lock_enabled"
        const val KEY_LOCK_WD_START = "managed_lock_wd_start"
        const val KEY_LOCK_WD_END = "managed_lock_wd_end"
        const val KEY_LOCK_WE_START = "managed_lock_we_start"
        const val KEY_LOCK_WE_END = "managed_lock_we_end"
        const val KEY_LOCK_MANUAL = "managed_lock_manual"
```

and, next to the other flows and setters:

```kotlin
    /** The parent's nightly lock window. Defaults match [LockSchedule]'s own defaults. */
    val lockSchedule: Flow<LockSchedule> = combine(
        p.readBoolean(KEY_LOCK_ENABLED, false),
        p.readInt(KEY_LOCK_WD_START, 21 * 60), p.readInt(KEY_LOCK_WD_END, 7 * 60),
        p.readInt(KEY_LOCK_WE_START, 22 * 60), p.readInt(KEY_LOCK_WE_END, 8 * 60),
    ) { on, ws, we, es, ee -> LockSchedule(on, ws, we, es, ee) }

    /**
     * True while the parent's "Lock now" is in force. Unlike a PIN pause this **survives reboot** —
     * a manual lock is a deliberate parent act and must not be undone by the child restarting.
     */
    val manualLock: Flow<Boolean> = p.readBoolean(KEY_LOCK_MANUAL, false)

    suspend fun setLockSchedule(s: LockSchedule) {
        p.write(KEY_LOCK_ENABLED, s.enabled); p.write(KEY_LOCK_WD_START, s.weekdayStart); p.write(KEY_LOCK_WD_END, s.weekdayEnd)
        p.write(KEY_LOCK_WE_START, s.weekendStart); p.write(KEY_LOCK_WE_END, s.weekendEnd)
    }

    suspend fun setManualLock(on: Boolean) = p.write(KEY_LOCK_MANUAL, on)
```

Add `import edu.fnosari.momedm.protocol.LockSchedule`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ManagedPrefsTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/edu/fnosari/momedm/persistence/ManagedPrefs.kt app/src/test/java/edu/fnosari/momedm/persistence/ManagedPrefsTest.kt
git commit -m "feat: persist lock schedule and manual-lock flag"
```

---

### Task 3: Wire protocol — new commands and status fields

**Files:**
- Modify: `app/src/main/java/edu/fnosari/momedm/protocol/Messages.kt`
- Test: `app/src/test/java/edu/fnosari/momedm/protocol/MessagesTest.kt` (add cases)

**Interfaces:**
- Consumes: `LockSchedule` from Task 1.
- Produces: `CmdType.SET_SCHEDULE`, `CmdType.LOCK_NOW`, `CmdType.UNLOCK`; `Message.Cmd.schedule: LockSchedule?`; `Message.Status.locked: Boolean`, `.lockReason: String?`, `.lockUntil: Long?`, `.schedule: LockSchedule?`.

- [ ] **Step 1: Write the failing tests**

Append to `MessagesTest.kt`:

```kotlin
    @Test fun scheduleCommandRoundTrips() {
        val s = LockSchedule(enabled = true, weekdayStart = 20 * 60, weekdayEnd = 6 * 60)
        val cmd = Message.Cmd("c1", CmdType.SET_SCHEDULE, schedule = s)
        val back = MessageCodec.decodeMessage(MessageCodec.encodeMessage(cmd)) as Message.Cmd
        assertEquals(CmdType.SET_SCHEDULE, back.type); assertEquals(s, back.schedule)
    }

    @Test fun lockCommandsRoundTrip() {
        for (t in listOf(CmdType.LOCK_NOW, CmdType.UNLOCK)) {
            val back = MessageCodec.decodeMessage(MessageCodec.encodeMessage(Message.Cmd("c2", t))) as Message.Cmd
            assertEquals(t, back.type)
        }
    }

    @Test fun statusCarriesLockFields() {
        val s = Message.Status(kiosk = false, kioskPkg = null, account = false, battery = 50, currentApp = null,
            locked = true, lockReason = LockState.REASON_NIGHT, lockUntil = 1_800_000_000_000L,
            schedule = LockSchedule(enabled = true))
        val back = MessageCodec.decodeMessage(MessageCodec.encodeMessage(s)) as Message.Status
        assertEquals(true, back.locked); assertEquals("night", back.lockReason)
        assertEquals(1_800_000_000_000L, back.lockUntil); assertEquals(LockSchedule(enabled = true), back.schedule)
    }

    @Test fun statusLockFieldsDefaultWhenAbsent() {
        // A peer that predates this feature sends no lock fields; decoding must not fail.
        val json = """{"t":"STATUS","kiosk":false,"kioskPkg":null,"account":false,"battery":10,"currentApp":null}"""
        val back = MessageCodec.decodeMessage(json) as Message.Status
        assertEquals(false, back.locked); assertEquals(null, back.lockReason)
        assertEquals(null, back.lockUntil); assertEquals(null, back.schedule)
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*MessagesTest*"`
Expected: FAIL — `SET_SCHEDULE` and the new fields unresolved.

- [ ] **Step 3: Implement the wire changes**

In `Messages.kt`:

```kotlin
enum class CmdType { KIOSK_ON, KIOSK_OFF, INSTALL, ADD_ACCOUNT, LIST_APPS, GET_STATUS, SET_PREFS, SET_SCHEDULE, LOCK_NOW, UNLOCK }
```

Add to `Message.Status`, after `pauseEndsAt`:

```kotlin
        /** True while a complete lock (night window or parent "Lock now") is in force. */
        val locked: Boolean = false,
        /** [LockState.REASON_NIGHT], [LockState.REASON_MANUAL], or null when unlocked. */
        val lockReason: String? = null,
        /** Epoch ms the night window ends; null for a manual lock and when unlocked. */
        val lockUntil: Long? = null,
        /** The child's current lock schedule, so the parent UI can render it after a restart. */
        val schedule: LockSchedule? = null,
```

Add to `Message.Cmd`, after `prefs`:

```kotlin
        /** SET_SCHEDULE payload. */ val schedule: LockSchedule? = null,
```

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. `SerializationSmokeTest` and `EndpointLoopbackTest` also exercise these types — if either fails, fix the cause before continuing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/edu/fnosari/momedm/protocol/Messages.kt app/src/test/java/edu/fnosari/momedm/protocol/MessagesTest.kt
git commit -m "feat: lock commands and status fields on the wire"
```

---

### Task 4: `PolicyManager` lock primitives

**Files:**
- Modify: `app/src/main/java/edu/fnosari/momedm/managed/PolicyManager.kt`

**Interfaces:**
- Consumes: `ManagedPrefs.lockSchedule`, `.manualLock` (Task 2); `LockSchedule.isLockedAt` (Task 1).
- Produces: `suspend fun PolicyManager.lockComplete(): Result<Unit>`, `suspend fun PolicyManager.restoreNormal(): Result<Unit>`, and a `pause()` that also works when no child mode is on.

There is no unit test here: every line is a `DevicePolicyManager` call, which has no JVM fake in this project. It is covered by the rig pass in Task 9. Keep the methods thin so that is honest.

- [ ] **Step 1: Extract the plain-home launch helper**

`kioskOff()` builds a non-locked home intent inline. Pull it out so `restoreNormal()` can reuse it — put this next to `launchHomeLocked()`:

```kotlin
    /** Launches our launcher *without* lock task (used when leaving a lock). */
    private fun launchHomePlain() {
        val home = Intent(context, ManagedHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(home)
    }
```

and replace the body of the `runCatching { … }` inside `kioskOff()` with `runCatching { launchHomePlain() }`, keeping its existing `.onFailure { … }` log.

- [ ] **Step 2: Add `lockComplete()`**

```kotlin
    /**
     * Applies a complete lock: nothing but this app is launchable, and the launcher is brought up in
     * lock task showing its bedtime state.
     *
     * `GLOBAL_ACTIONS` is requested explicitly — [kioskOn] passes `SYSTEM_INFO` alone, which disables
     * the power menu, and the power menu is the route to the system emergency dialer. A phone a child
     * carries must still be able to call for help at night.
     */
    suspend fun lockComplete(): Result<Unit> = try {
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        launchHomeLocked()
        Log.d(LOG_TAG, "Complete lock applied")
        Result.success(Unit)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }
```

- [ ] **Step 3: Add `restoreNormal()`**

```kotlin
    /**
     * Returns the device to whatever was configured before the lock: child mode with its allowlist
     * when it is on, otherwise a free device. Idempotent — [LockController] calls it on every
     * re-evaluation that ends unlocked.
     */
    suspend fun restoreNormal(): Result<Unit> = try {
        val c = prefs.kioskConfig.first()
        if (c.on && c.apps.isNotEmpty()) {
            kioskOn(c.apps, c.pinned).map { }
        } else {
            dpm.setLockTaskPackages(admin, emptyArray())
            runCatching { launchHomePlain() }.onFailure { Log.w(LOG_TAG, "Failed to launch home after unlock", it) }
            Log.d(LOG_TAG, "Complete lock released")
            Result.success(Unit)
        }
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }
```

- [ ] **Step 4: Make `pause()` cover a complete lock**

`pause()` currently returns 0L unless child mode is on, so a parent PIN would do nothing on a device that is night-locked with child mode off. Replace its guard:

```kotlin
    suspend fun pause(nowMs: Long = System.currentTimeMillis()): Long {
        val kioskOn = prefs.kioskConfig.first().on
        val lockOn = prefs.manualLock.first() || prefs.lockSchedule.first().isLockedAt(nowMs, ZoneId.systemDefault())
        if (!kioskOn && !lockOn) return 0L
        val until = nowMs + KioskConfig.PAUSE_MS
        prefs.setPauseUntil(until); Log.d(LOG_TAG, "Paused until $until")
        ManagedLinkState.statusPushRequests.tryEmit(Unit)
        return until
    }
```

Add `import java.time.ZoneId`.

- [ ] **Step 5: Build and commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests still pass.

```bash
git add app/src/main/java/edu/fnosari/momedm/managed/PolicyManager.kt
git commit -m "feat: complete-lock and restore primitives in PolicyManager"
```

---

### Task 5: Command handling for the three new commands

**Files:**
- Modify: `app/src/main/java/edu/fnosari/momedm/managed/CommandExecutor.kt`
- Test: `app/src/test/java/edu/fnosari/momedm/managed/CommandExecutorTest.kt`

**Interfaces:**
- Consumes: `CmdType.SET_SCHEDULE/LOCK_NOW/UNLOCK`, `Cmd.schedule` (Task 3).
- Produces: two new `PolicyActions` members — `suspend fun setSchedule(schedule: LockSchedule): Result<Unit>` and `suspend fun setManualLock(on: Boolean): Result<Unit>` — implemented by `PolicyManager` in this task.

- [ ] **Step 1: Write the failing tests**

In `CommandExecutorTest.kt`, add to `FakePolicy`:

```kotlin
        var schedule: LockSchedule? = null; var manual: Boolean? = null
        override suspend fun setSchedule(schedule: LockSchedule) = run { this.schedule = schedule; Result.success(Unit) }
        override suspend fun setManualLock(on: Boolean) = run { manual = on; Result.success(Unit) }
```

and add these tests:

```kotlin
    @Test fun setScheduleSanitizesAndReturnsStatus() = runTest {
        val p = FakePolicy()
        val out = CommandExecutor(p, FakeStatus()).execute(
            Message.Cmd("20", CmdType.SET_SCHEDULE, schedule = LockSchedule(enabled = true, weekdayStart = 9999)))
        assertEquals(Message.Result("20", true, "schedule set"), out[0]); assertTrue(out[1] is Message.Status)
        assertEquals(21 * 60, p.schedule?.weekdayStart)   // clamped by sanitized()
        assertEquals(true, p.schedule?.enabled)
    }

    @Test fun setScheduleWithoutPayloadFails() = runTest {
        val out = CommandExecutor(FakePolicy(), FakeStatus()).execute(Message.Cmd("21", CmdType.SET_SCHEDULE))
        val r = out[0] as Message.Result; assertFalse(r.ok); assertEquals("missing schedule", r.msg)
    }

    @Test fun lockNowAndUnlockSetTheFlag() = runTest {
        val p = FakePolicy(); val ex = CommandExecutor(p, FakeStatus())
        val locked = ex.execute(Message.Cmd("22", CmdType.LOCK_NOW))
        assertEquals(Message.Result("22", true, "locked"), locked[0]); assertTrue(locked[1] is Message.Status)
        assertEquals(true, p.manual)
        val unlocked = ex.execute(Message.Cmd("23", CmdType.UNLOCK))
        assertEquals(Message.Result("23", true, "unlocked"), unlocked[0]); assertEquals(false, p.manual)
    }
```

Add `import edu.fnosari.momedm.protocol.LockSchedule`.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*CommandExecutorTest*"`
Expected: FAIL — `setSchedule` is not a member of `PolicyActions`.

- [ ] **Step 3: Extend the interface and the executor**

In `CommandExecutor.kt`, add to `PolicyActions`:

```kotlin
    /** Persists the parent's nightly lock window and re-evaluates the lock immediately. */
    suspend fun setSchedule(schedule: LockSchedule): Result<Unit>
    /** Sets or clears the parent's manual lock and re-evaluates the lock immediately. */
    suspend fun setManualLock(on: Boolean): Result<Unit>
```

and add these branches to `execute`'s `when`:

```kotlin
            CmdType.SET_SCHEDULE -> {
                val s = cmd.schedule ?: return listOf(Message.Result(cmd.id, false, "missing schedule"))
                val r = policy.setSchedule(s.sanitized())
                if (r.isSuccess) listOf(res(r, "schedule set"), status.collect()) else listOf(res(r, ""))
            }
            CmdType.LOCK_NOW -> { val r = policy.setManualLock(true); if (r.isSuccess) listOf(res(r, "locked"), status.collect()) else listOf(res(r, "")) }
            CmdType.UNLOCK -> { val r = policy.setManualLock(false); if (r.isSuccess) listOf(res(r, "unlocked"), status.collect()) else listOf(res(r, "")) }
```

Add `import edu.fnosari.momedm.protocol.LockSchedule`.

- [ ] **Step 4: Implement them in `PolicyManager`**

Add to `PolicyManager.kt`. These persist only — Task 6 adds the `LockController.reevaluate()` call to both, once that class exists. Until then a schedule change takes effect at the next trigger rather than instantly, which is correct but slower:

```kotlin
    override suspend fun setSchedule(schedule: LockSchedule): Result<Unit> = try {
        prefs.setLockSchedule(schedule)
        Log.d(LOG_TAG, "Lock schedule set (enabled=${schedule.enabled})")
        Result.success(Unit)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }

    override suspend fun setManualLock(on: Boolean): Result<Unit> = try {
        prefs.setManualLock(on)
        Log.d(LOG_TAG, "Manual lock = $on")
        Result.success(Unit)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }
```

Add `import edu.fnosari.momedm.protocol.LockSchedule`.

- [ ] **Step 5: Run tests, build, commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: PASS.

```bash
git add app/src/main/java/edu/fnosari/momedm/managed/CommandExecutor.kt app/src/main/java/edu/fnosari/momedm/managed/PolicyManager.kt app/src/test/java/edu/fnosari/momedm/managed/CommandExecutorTest.kt
git commit -m "feat: handle SET_SCHEDULE, LOCK_NOW and UNLOCK"
```

---

### Task 6: `LockController`, alarms, receivers, status reporting

**Files:**
- Create: `app/src/main/java/edu/fnosari/momedm/managed/LockController.kt`
- Create: `app/src/main/java/edu/fnosari/momedm/managed/LockAlarms.kt`
- Create: `app/src/main/java/edu/fnosari/momedm/managed/TimeChangeReceiver.kt`
- Modify: `app/src/main/java/edu/fnosari/momedm/managed/BootReceiver.kt`
- Modify: `app/src/main/java/edu/fnosari/momedm/managed/PolicyManager.kt` (call the controller from the two setters)
- Modify: `app/src/main/java/edu/fnosari/momedm/managed/StatusCollector.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `LockState.evaluate` (Task 1), `ManagedPrefs.lockSchedule`/`.manualLock` (Task 2), `PolicyManager.lockComplete()`/`.restoreNormal()` (Task 4).
- Produces: `LockController(context, prefs, policy).reevaluate()`; `LockAlarms.armNext(context, atMs: Long?)`; `LockAlarmReceiver`; `TimeChangeReceiver`.

- [ ] **Step 1: Write `LockController.kt`**

```kotlin
package edu.fnosari.momedm.managed

import android.content.Context
import android.util.Log
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.LockState
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * The single place that decides whether the device is completely locked and makes reality match.
 *
 * [reevaluate] is idempotent and cheap, and is called from every trigger that could change the
 * answer: an alarm, boot, a clock or timezone change, the launcher resuming, a PIN pause expiring,
 * and each of the three lock commands. Nothing persists "locked" — see [LockState].
 */
class LockController(private val context: Context, private val prefs: ManagedPrefs, private val policy: PolicyManager) {
    companion object {
        private const val LOG_TAG = "LockController"

        /** Convenience for the receivers, which have only a [Context]. */
        fun of(context: Context): LockController {
            val p = ManagedSetup.prefs(context)
            return LockController(context.applicationContext, p, PolicyManager(context.applicationContext, p))
        }
    }

    suspend fun reevaluate() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val schedule = prefs.lockSchedule.first()
        val manual = prefs.manualLock.first()
        val pauseUntil = prefs.kioskConfig.first().pauseUntil
        val state = LockState.evaluate(schedule, manual, pauseUntil, now, zone)
        Log.d(LOG_TAG, "Re-evaluated: locked=${state.locked} reason=${state.reason}")

        val applied = if (state.locked) policy.lockComplete() else policy.restoreNormal()
        // A failure is left for the next trigger to retry: nothing was persisted from the attempt,
        // so a retry starts from the same inputs and reaches the same decision.
        applied.onFailure { Log.w(LOG_TAG, "Could not apply lock state; will retry on the next trigger", it) }

        // Wake up at whichever comes first: the next window boundary, or the end of a PIN pause
        // (which must re-lock even if this process is gone by then).
        val next = listOfNotNull(schedule.nextTransition(now, zone), pauseUntil.takeIf { it > now }).minOrNull()
        LockAlarms.armNext(context, next)
        ManagedLinkState.statusPushRequests.tryEmit(Unit)
    }
}
```

- [ ] **Step 2: Write `LockAlarms.kt`**

```kotlin
package edu.fnosari.momedm.managed

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Arms the single alarm that wakes the device to re-evaluate its lock state. */
object LockAlarms {
    private const val LOG_TAG = "LockAlarms"
    private const val REQUEST_CODE = 4711

    /** Schedules a wake-up at [atMs], or cancels the pending one when [atMs] is null. */
    fun armNext(context: Context, atMs: Long?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        if (atMs == null) { am.cancel(pi); Log.d(LOG_TAG, "No next transition; alarm cancelled"); return }
        // Exact where allowed. An inexact alarm only costs a few minutes of drift at the boundary,
        // never a wrong state, because resume/boot/time-change re-evaluation is the real net.
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        else { Log.w(LOG_TAG, "Exact alarms unavailable; using an inexact alarm"); am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi) }
        Log.d(LOG_TAG, "Next lock re-evaluation armed for $atMs")
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, LockAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Fired by [LockAlarms]; re-evaluates the lock and re-arms the next alarm. */
class LockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try { LockController.of(app).reevaluate() } finally { pending.finish() }
        }
    }
}
```

- [ ] **Step 3: Write `TimeChangeReceiver.kt`**

```kotlin
package edu.fnosari.momedm.managed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-evaluates the lock when the clock or the timezone moves. Without this, a child who changes the
 * device clock would shift the bedtime window, and every armed alarm would be pointing at the wrong
 * instant.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    companion object { private const val LOG_TAG = "TimeChangeReceiver" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        Log.d(LOG_TAG, "Clock changed (${intent.action}); re-evaluating lock")
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try { LockController.of(app).reevaluate() } finally { pending.finish() }
        }
    }
}
```

- [ ] **Step 4: Call the controller from boot and from the two setters**

In `BootReceiver.onReceive`, after the existing `ManagedLinkService.start(context, fromBoot = true)`:

```kotlin
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try { LockController.of(app).reevaluate() } finally { pending.finish() }
        }
```

with imports `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.launch`.

In `PolicyManager`, make the two setters written in Task 5 re-evaluate after persisting — replace the `Result.success(Unit)` line of each with:

```kotlin
        LockController(context, prefs, this).reevaluate()
        Result.success(Unit)
```

- [ ] **Step 5: Report the lock in `Status`**

In `StatusCollector.collect()`, after the existing `val c = prefs.kioskConfig.first(); val now = …`:

```kotlin
        val schedule = prefs.lockSchedule.first()
        val lock = LockState.evaluate(schedule, prefs.manualLock.first(), c.pauseUntil, now, ZoneId.systemDefault())
```

and add to the `Message.Status(...)` construction:

```kotlin
            locked = lock.locked, lockReason = lock.reason, lockUntil = lock.until, schedule = schedule,
```

Extend the existing log line with ` locked=${s.locked}`. Add imports `edu.fnosari.momedm.protocol.LockState` and `java.time.ZoneId`.

- [ ] **Step 6: Declare the receivers and the permission**

In `app/src/main/AndroidManifest.xml`, next to the other `<uses-permission>` entries:

```xml
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

and inside `<application>`, next to the existing receivers:

```xml
        <receiver
            android:name=".managed.LockAlarmReceiver"
            android:exported="false" />
        <receiver
            android:name=".managed.TimeChangeReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.TIME_SET" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 7: Build, test, commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

```bash
git add app/src/main/java/edu/fnosari/momedm/managed/ app/src/main/AndroidManifest.xml
git commit -m "feat: LockController with alarm, boot and clock-change re-evaluation"
```

---

### Task 7: Bedtime screen on the child device

**Files:**
- Create: `app/src/main/java/edu/fnosari/momedm/activities/managed/screens/BedtimeScreen.kt`
- Modify: `app/src/main/java/edu/fnosari/momedm/activities/managed/ManagedViewModel.kt`
- Modify: `app/src/main/java/edu/fnosari/momedm/activities/managed/ManagedHomeActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Consumes: `LockState` (Task 1), `ManagedPrefs.lockSchedule`/`.manualLock` (Task 2), `LockController.reevaluate()` (Task 6).
- Produces: `ManagedViewModel.lockState: StateFlow<LockState?>` (null while loading) and `@Composable fun BedtimeScreen(vm: ManagedViewModel, onUnlocked: () -> Unit)`.

- [ ] **Step 1: Add the strings to both locale files**

`values/strings.xml`, in the child-launcher block:

```xml
    <string name="bedtime_title">Good night!</string>
    <string name="bedtime_until">Unlocks at %1$s</string>
    <string name="bedtime_manual">A parent locked this phone</string>
```

`values-fr/strings.xml`, same keys, same block:

```xml
    <string name="bedtime_title">Bonne nuit&#160;!</string>
    <string name="bedtime_until">Déverrouillage à %1$s</string>
    <string name="bedtime_manual">Un parent a verrouillé ce téléphone</string>
```

Run `./gradlew :app:testDebugUnitTest --tests "*StringsParityTest*"` — expected PASS.

- [ ] **Step 2: Expose the lock state from the view model**

In `ManagedViewModel`, add:

```kotlin
    /**
     * The current complete-lock state, or null while the persisted inputs are still loading (the
     * launcher must not flash its tiles at a device that turns out to be locked).
     *
     * Recomputed on every input change, on each ON_RESUME (via [resumeTick]) and once a minute, so a
     * window that opens while the screen is on takes effect without waiting for the alarm.
     */
    val lockState: StateFlow<LockState?> = combine(
        prefs.lockSchedule, prefs.manualLock, prefs.kioskConfig, resumeTick,
        flow { while (true) { emit(Unit); delay(30_000L) } },
    ) { schedule, manual, config, _, _ ->
        LockState.evaluate(schedule, manual, config.pauseUntil, System.currentTimeMillis(), ZoneId.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

with imports `edu.fnosari.momedm.protocol.LockState`, `kotlinx.coroutines.flow.combine`, `kotlinx.coroutines.flow.flow`, `java.time.ZoneId`.

Then make the end of a pause re-evaluate the whole lock rather than only child mode. In `trackPause`, replace both `policy.resume()` calls with:

```kotlin
                LockController(getApplication(), prefs, policy).reevaluate()
```

and do the same in `relock()`. `reevaluate()` restores child mode when that is all there is, and re-locks when a night window is still open — which `policy.resume()` alone would miss.

- [ ] **Step 3: Write `BedtimeScreen.kt`**

```kotlin
package edu.fnosari.momedm.activities.managed.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.ManagedViewModel
import edu.fnosari.momedm.activities.managed.components.PinDialog
import edu.fnosari.momedm.protocol.LockState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What the child sees while the device is completely locked: a deliberately quiet screen so it reads
 * as "closed", with no app tiles at all. A long-press anywhere opens the parent PIN dialog (only when
 * a PIN is set) — the same hidden affordance as the day launcher, so a child cannot find it by sight.
 */
@Composable
fun BedtimeScreen(vm: ManagedViewModel, onUnlocked: () -> Unit) {
    val lock by vm.lockState.collectAsState()
    val pinSet by vm.pinSet.collectAsState()
    val pinError by vm.pinError.collectAsState()
    val pinLockedRemaining by vm.pinLockedRemainingMs.collectAsState()
    val showPin by vm.pinDialogOpen.collectAsState()

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); tick++ } }
    val clock = remember(tick) {
        val c = Calendar.getInstance()
        String.format(Locale.getDefault(), "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    val night = lock?.reason == LockState.REASON_NIGHT
    val until = lock?.until
    val subtitle = if (night && until != null)
        stringResource(R.string.bedtime_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until)))
    else stringResource(R.string.bedtime_manual)

    val a11y = stringResource(R.string.launcher_lock_cd)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)))
            .then(
                if (pinSet) Modifier
                    .semantics { contentDescription = a11y }
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { vm.pinDialogOpen.value = true }) }
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(clock, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.bedtime_title), style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }

    if (showPin) PinDialog(
        onDismiss = { vm.pinDialogOpen.value = false; vm.clearPinError() },
        onSubmit = { pin -> vm.tryPin(pin) { vm.pinDialogOpen.value = false; onUnlocked() } },
        error = pinError,
        lockedForMs = pinLockedRemaining,
    )
}
```

- [ ] **Step 4: Route to it from the activity**

In `ManagedHomeActivity`, inside the `else` branch that today calls `ChildLauncherScreen`, read the lock state and branch:

```kotlin
                    val lock by vm.lockState.collectAsState()
                    val onUnlocked: () -> Unit = {
                        // PolicyManager.pause() already persisted the deadline; release lock task here.
                        runCatching { stopLockTask() }.onFailure { Log.w(LOG_TAG, "stopLockTask failed", it) }
                    }
                    if (lock?.locked == true) BedtimeScreen(vm, onUnlocked) else ChildLauncherScreen(vm, onUnlocked)
```

with imports `androidx.compose.runtime.collectAsState`, `androidx.compose.runtime.getValue`, and `edu.fnosari.momedm.activities.managed.screens.BedtimeScreen`.

Then stop the pinned-app bounce from firing over the bedtime screen — in the bounce `LaunchedEffect`, add `vm.lockState.value?.locked != true &&` to **both** the outer condition and the post-delay re-check:

```kotlin
                            if (vm.lockState.value?.locked != true && c.isLocked(now) && c.pinned != null && …
```

```kotlin
                                if (vm.lockState.value?.locked != true && !vm.pinDialogOpen.value && …
```

- [ ] **Step 5: Build, test, commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass including `StringsParityTest`.

```bash
git add app/src/main/java/edu/fnosari/momedm/activities/managed/ app/src/main/res/values/strings.xml app/src/main/res/values-fr/strings.xml
git commit -m "feat: bedtime screen for a completely locked child device"
```

---

### Task 8: Parent controls for the night lock

**Files:**
- Create: `app/src/main/java/edu/fnosari/momedm/activities/main/components/TimeRangeRow.kt`
- Modify: `app/src/main/java/edu/fnosari/momedm/activities/main/ControllerViewModel.kt`
- Modify: `app/src/main/java/edu/fnosari/momedm/activities/main/screens/DeviceScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Consumes: `CmdType.SET_SCHEDULE/LOCK_NOW/UNLOCK`, `Message.Status.locked/.lockReason/.lockUntil/.schedule` (Task 3).
- Produces: `ControllerViewModel.setSchedule(deviceId, schedule)`, `.lockNow(deviceId)`, `.unlock(deviceId)`; `@Composable fun TimeRangeRow(label: String, startMinutes: Int, endMinutes: Int, onChange: (Int, Int) -> Unit)`.

- [ ] **Step 1: Add the strings to both locale files**

`values/strings.xml`:

```xml
    <string name="child_night_section">Night lock</string>
    <string name="child_night_enable">Lock the phone at night</string>
    <string name="child_night_school">School nights (Sun–Thu)</string>
    <string name="child_night_weekend">Friday and Saturday nights</string>
    <string name="child_lock_now">Lock now</string>
    <string name="child_unlock">Unlock</string>
    <string name="child_locked_until">Locked until %1$s</string>
    <string name="child_locked_manual">Locked by you</string>
    <string name="child_unlocked">Unlocked</string>
```

`values-fr/strings.xml`:

```xml
    <string name="child_night_section">Verrouillage la nuit</string>
    <string name="child_night_enable">Verrouiller le téléphone la nuit</string>
    <string name="child_night_school">Nuits d\'école (dim.–jeu.)</string>
    <string name="child_night_weekend">Nuits du vendredi et du samedi</string>
    <string name="child_lock_now">Verrouiller maintenant</string>
    <string name="child_unlock">Déverrouiller</string>
    <string name="child_locked_until">Verrouillé jusqu\'à %1$s</string>
    <string name="child_locked_manual">Verrouillé par vous</string>
    <string name="child_unlocked">Déverrouillé</string>
```

- [ ] **Step 2: Add the commands to the view model**

In `ControllerViewModel`:

```kotlin
    /** Pushes the nightly lock window to [deviceId]. */
    fun setSchedule(deviceId: String, schedule: LockSchedule) {
        val id = ControllerLink.sendCmd(deviceId) { Message.Cmd(it, CmdType.SET_SCHEDULE, schedule = schedule) }
        Log.d(LOG_TAG, "setSchedule -> $deviceId: enabled=${schedule.enabled}: ${if (id == null) "offline" else "sent (id=$id)"}")
        announce(id)
    }
    fun lockNow(deviceId: String) { send(deviceId, CmdType.LOCK_NOW) }
    fun unlock(deviceId: String) { send(deviceId, CmdType.UNLOCK) }
```

with `import edu.fnosari.momedm.protocol.LockSchedule`.

- [ ] **Step 3: Write the time-range row**

```kotlin
package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import edu.fnosari.momedm.R
import java.util.Locale

/** Formats minutes-since-midnight as HH:mm for display. */
fun formatMinutes(minutes: Int): String = String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)

/**
 * One "label — start to end" row; tapping either time opens a clock picker. Times are minutes since
 * local midnight, matching [edu.fnosari.momedm.protocol.LockSchedule].
 */
@Composable
fun TimeRangeRow(label: String, startMinutes: Int, endMinutes: Int, onChange: (Int, Int) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }   // "start", "end", or null
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { editing = "start" }) { Text(formatMinutes(startMinutes)) }
            Text("→")   // language-neutral, so it needs no string resource
            TextButton(onClick = { editing = "end" }) { Text(formatMinutes(endMinutes)) }
        }
    }
    editing?.let { which ->
        val initial = if (which == "start") startMinutes else endMinutes
        val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.hour * 60 + state.minute
                    if (which == "start") onChange(picked, endMinutes) else onChange(startMinutes, picked)
                    editing = null
                }) { Text(stringResource(R.string.settings_dialog_confirm)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
        )
    }
}
```

Add `import androidx.compose.ui.unit.dp`.

> `TimePicker` ships in Material3 1.3.0 (Compose BOM 2024.09.00), so it is available. This project has been bitten once by a Material3 composable that compiled and then threw at runtime (`FlowRow`), so **run the parent app on the emulator and open this dialog** as part of Step 5 rather than trusting the build. If it throws, replace the picker with two `OutlinedTextField`s taking `HH:mm` and parsing to minutes — no new dependency either way.

- [ ] **Step 4: Add the Night lock card to `DeviceScreen`**

Insert after the existing status `Card` and before the child-mode `Button`:

```kotlin
        val schedule = s?.schedule ?: LockSchedule()
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.child_night_section))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.child_night_enable), modifier = Modifier.weight(1f))
                    Switch(checked = schedule.enabled, onCheckedChange = { viewModel.setSchedule(deviceId, schedule.copy(enabled = it)) })
                }
                TimeRangeRow(stringResource(R.string.child_night_school), schedule.weekdayStart, schedule.weekdayEnd) { st, en ->
                    viewModel.setSchedule(deviceId, schedule.copy(weekdayStart = st, weekdayEnd = en))
                }
                TimeRangeRow(stringResource(R.string.child_night_weekend), schedule.weekendStart, schedule.weekendEnd) { st, en ->
                    viewModel.setSchedule(deviceId, schedule.copy(weekendStart = st, weekendEnd = en))
                }
                Text(
                    when {
                        s?.lockReason == "manual" -> stringResource(R.string.child_locked_manual)
                        s?.locked == true -> stringResource(R.string.child_locked_until,
                            s.lockUntil?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "—")
                        else -> stringResource(R.string.child_unlocked)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { if (s?.locked == true) viewModel.unlock(deviceId) else viewModel.lockNow(deviceId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (s?.locked == true) R.string.child_unlock else R.string.child_lock_now)) }
            }
        }
```

Add imports `androidx.compose.material3.Switch`, `edu.fnosari.momedm.activities.main.components.TimeRangeRow`, `edu.fnosari.momedm.protocol.LockSchedule`.

- [ ] **Step 5: Build, test, sanity-run, commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

Install on the controller emulator and open a child's page, then open one time picker (see the note in Step 3):

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n edu.fnosari.momedm/.activities.main.MainActivity
```

```bash
git add app/src/main/java/edu/fnosari/momedm/activities/main/ app/src/main/res/values/strings.xml app/src/main/res/values-fr/strings.xml
git commit -m "feat: parent night-lock controls"
```

---

### Task 9: End-to-end verification on the two-emulator rig

**Files:**
- Modify: `docs/testing.md` (record what was run and what was found)

**Interfaces:**
- Consumes: everything above.
- Produces: a verification record, plus fixes for anything it breaks.

This task exists because `PolicyManager`'s lock calls have no JVM test: lock task, alarms, and the emergency path can only be judged on a device.

- [ ] **Step 1: Bring up the rig**

Follow `docs/testing.md` ("Emulator test rig"): two emulators (controller on 5554, managed on 5556), install the debug APK on both, make 5556 device owner, provision it with the controller's id and secret, and wait for `ManagedLinkService: Authenticated` in logcat.

- [ ] **Step 2: Verify a night window locks the device**

From the parent app, enable the night lock and set the school-night window to start about a minute ahead and end an hour later. Wait for the boundary, then:

```bash
adb -s emulator-5556 shell dumpsys activity activities | grep -E "mLockTaskModeState|topResumedActivity"
adb -s emulator-5556 exec-out screencap -p > bedtime.png
```

Expected: `mLockTaskModeState=LOCKED`, `ManagedHomeActivity` resumed, and the screenshot shows the bedtime screen with no tiles. **Look at the screenshot.**

- [ ] **Step 3: Verify the PIN pause and the automatic re-lock**

Long-press the bedtime screen, enter the parent PIN, confirm the device unlocks (`mLockTaskModeState=NONE`) and shows the normal launcher. Then confirm it re-locks when the 10-minute pause lapses — to avoid waiting, re-run the check after forcing the pause to expire:

```bash
adb -s emulator-5556 shell am force-stop edu.fnosari.momedm
adb -s emulator-5556 shell am start -n edu.fnosari.momedm/.activities.managed.ManagedHomeActivity
adb -s emulator-5556 shell dumpsys activity activities | grep mLockTaskModeState
```

Expected: locked again, because the state is recomputed rather than remembered.

- [ ] **Step 4: Verify reboot inside a window**

```bash
adb -s emulator-5556 reboot
adb -s emulator-5556 wait-for-device
```

After boot completes, expect `mLockTaskModeState=LOCKED` and the bedtime screen — driven by `BootReceiver`.

- [ ] **Step 5: Verify manual lock and unlock**

With the schedule disabled, press **Lock now** on the parent, confirm the child locks and the bedtime screen says a parent locked it; press **Unlock**, confirm the child returns to its previous state (child mode if it was on, free device otherwise). Reboot while manually locked and confirm it is still locked.

- [ ] **Step 6: Check the emergency path (spec §1.6, unproven)**

While the device is locked, long-press power and look for an **Emergency** entry:

```bash
adb -s emulator-5556 shell input keyevent --longpress KEYCODE_POWER
adb -s emulator-5556 exec-out screencap -p > power-menu.png
```

Record honestly what you see. If Emergency is not reachable on this image, that is a finding to report — not something to paper over. Do **not** change the design in this task; note it and let the human decide.

- [ ] **Step 7: Verify the clock-change trigger**

```bash
adb -s emulator-5556 shell su 0 date $(date -d "+3 hours" +%m%d%H%M%y) 2>/dev/null || \
adb -s emulator-5556 shell "settings put global auto_time 0"
```

Move the device clock into and back out of the window and confirm the lock follows within seconds (the receiver fires on `TIME_SET`). Restore `auto_time` to 1 afterwards.

- [ ] **Step 8: Record the results and commit**

Add an "Emulator checks — complete lock" section to `docs/testing.md` listing each check above with its outcome, including anything that failed or could not be verified.

```bash
git add docs/testing.md
git commit -m "docs: record complete-lock emulator verification"
```

---

## Self-review notes

Checked against spec Part 1, section by section:

- §1.1 data model + sanitizing → Task 1; the two semantic rules are tested explicitly.
- §1.2 pure functions incl. DST → Task 1 (`dstSpringForward…`, `dstFallBack…`).
- §1.3 effective state, manual survives reboot, independence from child mode → Tasks 1, 2, 4 (`restoreNormal`), 9 (Step 5).
- §1.4 all six re-evaluation triggers → alarm/boot/time-change (Task 6), launcher resume + pause expiry (Task 7), commands (Task 5 + Task 6 Step 4).
- §1.5 alarm strategy + fallback → Task 6 Step 2.
- §1.6 lock task features incl. `GLOBAL_ACTIONS` → Task 4 Step 2, verified in Task 9 Step 6.
- §1.7 bedtime screen → Task 7.
- §1.8 protocol → Task 3 (plus the documented `schedule` field).
- §1.9 parent UI → Task 8.
- §1.10 failure modes → covered by Tasks 6 (retry-on-next-trigger), 7, 9.
- §1.11 testing → Tasks 1–3 unit tests, Task 9 rig.
