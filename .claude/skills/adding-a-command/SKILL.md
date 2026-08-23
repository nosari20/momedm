---
name: adding-a-command
description: Use when adding a new parent→child management command (a new CmdType) or a new field on Status — walks the change end-to-end in dependency order, with the traps that have bitten this repo.
---

# Adding a management command

A command touches seven places. Doing them out of order means fighting the
compiler; skipping one means a command that looks wired up and silently does
nothing. This is the order that works, using `SET_SCHEDULE` (the night-lock
command) as the worked example.

## 1. The payload type — `protocol/`

If the command carries structured data, give it a `@Serializable` type in
`protocol/`, **pure Kotlin, no Android imports** (`java.time` is allowed).

Give it a `sanitized()` that clamps or drops anything out of range, the way
`ChildPrefs` and `LockSchedule` do. This is a trust boundary: the value arrives
over BLE from a peer you authenticated but did not write.

Unit-test the type on its own. `LockScheduleTest` is the model — it pins DST
behaviour, midnight wrap, and clamping, none of which need Android.

## 2. The wire — `protocol/Messages.kt`

```kotlin
enum class CmdType { …existing…, SET_SCHEDULE }

Message.Cmd(… , val schedule: LockSchedule? = null)   // payload field
Message.Status(… , val locked: Boolean = false)        // any new report-back field
```

**Every new field needs a default.** A peer running an older build sends
messages without it, and decoding must not throw. Add a test that decodes a
hand-written JSON string with the field *absent* — not merely null — the way
`statusLockFieldsDefaultWhenAbsent` does.

> **Trap:** adding a `CmdType` entry breaks Kotlin's exhaustive `when` in
> `CommandExecutor`. Fill the branch in step 4 of the same change. If you must
> stage it, make the placeholder return `Result(ok = false)` — fail closed, never
> silently succeed.

## 3. The action — `managed/CommandExecutor.kt` + `managed/PolicyManager.kt`

Add the method to the `PolicyActions` interface. That interface is why
`CommandExecutor` is JVM-testable: tests implement it with a fake.

```kotlin
suspend fun setSchedule(schedule: LockSchedule): Result<Unit>
```

Implement it in `PolicyManager` using the file's error idiom **exactly**:

```kotlin
override suspend fun setSchedule(schedule: LockSchedule): Result<Unit> = try {
    prefs.setLockSchedule(schedule)
    LockController(context, prefs, this).reevaluate()
    Result.success(Unit)
} catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }
```

Cancellation is always rethrown — converting it to a failed `Result` tells the
coroutine machinery the work completed when it did not.

## 4. The branch — `managed/CommandExecutor.kt`

```kotlin
CmdType.SET_SCHEDULE -> {
    val s = cmd.schedule ?: return listOf(Message.Result(cmd.id, false, "missing schedule"))
    val r = policy.setSchedule(s.sanitized())
    if (r.isSuccess) listOf(res(r, "schedule set"), status.collect()) else listOf(res(r, ""))
}
```

Follow the established shape: a failure returns **only** the `RESULT`; a success
returns `RESULT` then a fresh `Status`. Sanitize here, at the boundary.

Add cases to `CommandExecutorTest` with a fake policy. Assert on the *stored*
value, not merely that the call happened — that is what catches a missing
`sanitized()`.

## 5. Persistence — `persistence/ManagedPrefs.kt`

Add keys to the companion object and expose a `Flow` plus a `suspend fun set…`,
following the existing `combine(...)` style. `PreferencesProvider` has no `Long`
support — store epoch millis as strings, as the existing code does.

Test with `InMemoryPreferencesProvider`: default value, then round trip.

## 6. Sending it — `activities/main/ControllerViewModel.kt`

```kotlin
fun setSchedule(deviceId: String, schedule: LockSchedule) {
    val id = ControllerLink.sendCmd(deviceId) { Message.Cmd(it, CmdType.SET_SCHEDULE, schedule = schedule) }
    announce(id)
}
```

Use `send(...)` for payload-free commands and `sendCmd { }` for ones with a
payload, then `announce(id)`. Those helpers handle an offline device and match
the result snackbar. Hand-rolled sending skips both.

## 7. Reporting back — `managed/StatusCollector.kt`

If the parent UI needs to render the new state, fill the `Status` field here —
and derive it from the same function the rest of the app uses. Two code paths
computing "is it locked" will disagree eventually.

## 8. The parent UI — `activities/main/screens/DeviceScreen.kt`

Render from `Status`; persist nothing on the parent side. Use the constants from
`protocol/` (`LockState.REASON_MANUAL`), never string literals — a rename should
break the build, not the UI silently.

New strings go in **both** `values/strings.xml` and `values-fr/strings.xml`, key
for key. See the `localized-strings` skill.

## Before you call it done

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Then run it on the rig (see the `emulator-rig` skill) — send the command from
the parent and confirm the child actually changed, from `dumpsys`, not from the
UI's own claim. A command that returns `ok = true` while nothing happened is the
exact failure this checklist exists to prevent.
