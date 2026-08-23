# Complete lock & app safety — design

**Date:** 2026-08-23
**Status:** approved design, not yet implemented
**Builds on:** `2026-08-22-kiosk-v2-parent-ux-design.md` (child mode, parent PIN, pushed prefs)

Two features for Môme DM, both parent-controlled over BLE and both required to keep
working while the parent phone is out of range:

1. **Complete lock** — the child device locks itself on a nightly schedule, or on demand
   from the parent ("Lock now"). Nothing is usable while locked except the system
   emergency call path.
2. **App safety presets** — the parent picks a safety level; the child applies Chrome
   managed configuration and a family-filter DNS resolver that the child cannot change.

They share new plumbing (command types, `Status` fields, parent device-screen sections)
but are otherwise independent and ship as two implementation plans, in the order above.

## Non-goals

- Per-app time budgets or screen-time reports.
- Web-history reporting to the parent.
- Blocking specific URLs by hand in v1 (`URLBlocklist` exists in the schema and the storage
  model supports it; no parent UI for it yet).
- Any dependency on a Google account, Family Link, or a Play/EMM enrollment.

## Vocabulary

The user-facing vocabulary stays the one established in kiosk v2: *parent* / *enfant*,
*mode enfant*, never "MDM", "kiosk", or "device owner". The bedtime state is *nuit* /
*night* in copy — never "lock task".

---

# Part 1 — Complete lock (night schedule + manual)

## 1.1 Data model

Lives in `protocol/` (pure Kotlin, no Android imports, shared by both roles):

```kotlin
@Serializable
data class LockSchedule(
    val enabled: Boolean = false,
    /** Minutes since local midnight. */
    val weekdayStart: Int = 21 * 60, val weekdayEnd: Int = 7 * 60,
    val weekendStart: Int = 22 * 60, val weekendEnd: Int = 8 * 60,
)
```

All four values are sanitized into `0..1439` on receipt (`sanitized()`, following the
`ChildPrefs.sanitized()` precedent); out-of-range values fall back to the defaults above.
A window whose start equals its end is treated as **disabled for that day type** rather
than as a 24-hour lock — the safer reading of an accidental value.

Two semantic rules, chosen because they are the ambiguous part:

- **A window wraps midnight when `start > end`.** `21:00 → 07:00` spans into the next day.
  `13:00 → 15:00` is a same-day window and is legal (an afternoon quiet period).
- **A night belongs to the day it starts.** Windows starting **Friday or Saturday** use the
  weekend times; windows starting **Sunday–Thursday** use the weekday times. This matches
  how families state the rule ("Friday night they can stay up later").

## 1.2 Behaviour as pure functions

`LockSchedule` carries the whole decision, with no Android and no stored "am I locked" flag:

```kotlin
fun isLockedAt(nowMs: Long, zone: ZoneId): Boolean
fun nextTransition(nowMs: Long, zone: ZoneId): Long?   // epoch ms of the next boundary, null when disabled
```

`isLockedAt` evaluates the window that started *today* and the one that started
*yesterday* (to catch a wrapped window still running past midnight) and returns true when
`now` falls inside either. Boundaries are computed with `java.time`
(`LocalDate.atTime(...).atZone(zone)`), so DST is handled by the platform: a boundary that
falls in a spring-forward gap resolves to the shifted-later instant, and one in a
fall-back overlap resolves to the earlier offset — deterministic, and unit-tested at both.

`nextTransition` returns the earliest boundary strictly after `now`, and is the only input
to alarm scheduling.

## 1.3 Effective lock state

The device's actual state is recomputed from scratch at every decision point:

```
lockedNow = (manualLock || schedule.isLockedAt(now, zone)) && pauseUntil <= now
```

- `manualLock` — a persisted boolean set by the parent's **Lock now**, cleared by the
  parent's **Unlock**. Survives reboot (a manual lock is a deliberate parent act).
- `pauseUntil` — reuses the existing parent-PIN pause (`KioskConfig.PAUSE_MS`, 10 min). A
  correct PIN on the child device pauses a night lock exactly as it pauses child mode; when
  the pause expires and the window is still open, the device re-locks. A pause does **not**
  survive reboot (existing behaviour, unchanged).

**Complete lock is independent of child mode.** A parent who wants a bedtime lock is not
forced to also turn on the app allowlist. When the lock lifts, the device returns to
whatever was configured before: child mode with its allowlist if it was on, plain home if
it was not.

Nothing persists `lockedNow`. Alarms do not *set* state; they only wake the device to
re-evaluate. A missed, duplicated, or stale alarm therefore cannot strand the device in the
wrong state — the failure mode this design exists to prevent.

## 1.4 Re-evaluation triggers

`LockController.reevaluate()` runs on every one of these, and is idempotent:

| Trigger | Source |
|---|---|
| Alarm fires | `AlarmManager` at `nextTransition` |
| Device boot | existing `BootReceiver` |
| `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED` | new manifest receiver |
| Launcher resumes | `ManagedHomeActivity.onResume` |
| PIN pause expires | existing pause watchdog |
| `SET_SCHEDULE` / `LOCK_NOW` / `UNLOCK` received | `CommandExecutor` |

After every re-evaluation the controller re-arms the alarm for the new `nextTransition` and
pushes a `Status` to the parent (`ManagedLinkState.statusPushRequests`, the existing path).

## 1.5 Alarms

`AlarmManager.setExactAndAllowWhileIdle` at `nextTransition`. The app declares
`USE_EXACT_ALARM` (granted at install, no user prompt, and this is not a Play-distributed
app). If `canScheduleExactAlarms()` ever returns false, the controller falls back to
`setAndAllowWhileIdle` and logs it once — with resume/boot/time-change re-evaluation as the
net, a late alarm costs at most a few minutes of drift at the boundary, never a wrong state.

## 1.6 Applying the lock

**Locking:**

```kotlin
dpm.setLockTaskPackages(admin, arrayOf(context.packageName))   // nothing else launchable
dpm.setLockTaskFeatures(admin, LOCK_TASK_FEATURE_SYSTEM_INFO or LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
```

then launch `ManagedHomeActivity` with `setLockTaskEnabled(true)`.

`GLOBAL_ACTIONS` is set **explicitly** because the existing `kioskOn` passes `SYSTEM_INFO`
alone, which disables the power menu. The power menu is the route to the system emergency
dialer, and the user requirement is that emergency calling survives a night lock.

> **To verify on the rig, not assume:** that the power-menu **Emergency** entry is actually
> reachable under lock task on the API 35 emulator image. If it is not, that is reported as
> a finding and the fallback (adding the dialer package to the lock-task allowlist for the
> emergency path only) is designed then — not silently claimed to work.

**Unlocking** restores the prior configuration: `kioskOn(config.apps, config.pinned)` when
child mode is on, otherwise `setLockTaskPackages(admin, emptyArray())` and a plain home
launch — the existing `kioskOff` tail.

## 1.7 Bedtime screen

A new state of the launcher, not a new Activity (`ManagedHomeActivity` already owns the
lock-task lifecycle, the PIN dialog, and the themed shell):

- Calm, dark, quiet — deliberately duller than the day launcher so it reads as "closed".
- Large clock, `Bonne nuit !` / `Good night!`, and a line naming when it opens again
  (`Déverrouillage à 07:00` / `Unlocks at 07:00`). For a manual lock, the line instead says
  a parent locked the device, with no time.
- **No app tiles at all.**
- Same hidden affordance as the day launcher: long-press the header opens the parent PIN
  dialog (only when a PIN is set). The a11y label is the existing `launcher_lock_cd`.

Reuses `PinDialog`, `MomeDMTheme`, and the pushed accent/language, so a locked device still
looks like the family's device.

## 1.8 Protocol changes

```kotlin
enum class CmdType { …existing…, SET_SCHEDULE, LOCK_NOW, UNLOCK }

Message.Cmd(… , val schedule: LockSchedule? = null)

Message.Status(… ,
    val locked: Boolean = false,
    val lockReason: String? = null,   // "night" | "manual" | null
    val lockUntil: Long? = null,      // epoch ms the night window ends; null for manual
)
```

Both roles ship in the same APK, so version skew is limited to a device that has not been
updated; `kotlinx.serialization` defaults plus `ignoreUnknownKeys` (already configured)
cover it. `LOCK_NOW`/`UNLOCK` set and clear `manualLock`, then re-evaluate. `SET_SCHEDULE`
persists the sanitized schedule, then re-evaluates.

## 1.9 Parent UI

A **Night lock** section on `DeviceScreen`:

- Enable toggle.
- Two rows, *School nights* and *Weekends (Fri & Sat)*, each opening a start/end time
  picker. The weekend label states the Friday/Saturday rule so §1.1 is visible, not folded
  into code.
- **Lock now** / **Unlock** button, reflecting `Status.lockReason`.
- A state line: `Locked until 07:00`, `Locked by you`, or `Unlocked`.

All strings in `values/` and `values-fr/`, key-for-key, enforced by the existing
`StringsParityTest`.

## 1.10 Failure modes

| Failure | Handling |
|---|---|
| Alarm never fires (doze, OEM killer) | Resume/boot/time-change re-evaluation catches it |
| Clock or timezone changed by the child | Receiver re-evaluates and re-arms immediately |
| Reboot mid-window | `BootReceiver` re-evaluates; a night lock resumes, a PIN pause does not |
| Child mode off, night lock on | Supported by design (§1.3) |
| `setLockTaskPackages` throws | Logged, `Result.failure` to the parent, state left unchanged so the next re-evaluation retries |
| PIN pause expires while still in window | Re-locks (this is the specified behaviour, not a bug) |

## 1.11 Testing

**Pure unit tests** (JVM, no Android) are the backbone, since §1.2 is pure:
midnight-wrapped windows; same-day windows; the Friday/Saturday rule at every day
boundary; `start == end` disabling; DST spring-forward and fall-back at both boundaries;
`nextTransition` ordering; `sanitized()` clamping; and the `lockedNow` expression across
`manualLock` × `schedule` × `pauseUntil`.

**Two-emulator rig** (`docs/testing.md`): a window starting ~1 minute out locks the device;
the bedtime screen renders in FR and dark; long-press → PIN → 10-minute pause → re-lock;
reboot mid-window stays locked; `Lock now` / `Unlock` from the parent; power-menu Emergency
check per §1.6; `dumpsys activity activities` confirms `mLockTaskModeState=LOCKED`.

---

# Part 2 — App safety presets

## 2.1 What is actually possible (verified, not assumed)

Checked against the shipping APKs on the API 35 emulator image, 2026-08-23:

- `com.android.chrome` declares `android.content.APP_RESTRICTIONS` with **159 restriction
  entries**, including `IncognitoModeAvailability`, `ForceGoogleSafeSearch`,
  `ForceYouTubeRestrict`, `SafeSitesFilterBehavior`, `URLBlocklist`, `URLAllowlist`.
- `com.google.android.youtube` declares **no** `APP_RESTRICTIONS` meta-data at all.
  `setApplicationRestrictions` on the YouTube app is a no-op. No MDM can turn on Restricted
  Mode inside the YouTube app; that is a Family-Link-only control.

The documented way to force YouTube Restricted Mode for a whole device or network is
**DNS**: resolve `www.youtube.com`, `m.youtube.com`, `youtubei.googleapis.com`,
`youtube.googleapis.com`, `www.youtube-nocookie.com` to `restrictmoderate.youtube.com`
(moderate) or `restrict.youtube.com` (strict) — which is exactly what family-filter
resolvers do, and it applies **inside the YouTube app**. As device owner we can pin the
resolver so the child cannot change it. That is why DNS is part of this feature and not an
optional extra.

## 2.2 Data model

```kotlin
enum class SafetyLevel { OFF, MODERATE, STRICT }

@Serializable
data class SafetyConfig(
    val level: SafetyLevel = SafetyLevel.OFF,
    /** DNS-over-TLS hostname; null = do not manage private DNS. */
    val dnsHost: String? = null,
    /** pkg -> managed-config key/values, applied verbatim via setApplicationRestrictions. */
    val appConfigs: Map<String, JsonObject> = emptyMap(),
)
```

`appConfigs` is deliberately **generic** rather than a fixed set of Chrome fields. A preset
is a *generator* of that map, so adding a Chrome key later — or configuring a different app
entirely (Part 3) — needs no protocol change and no new command. `dnsHost` is validated as
a hostname (`[a-z0-9.-]`, ≤253 chars, at least one dot) before use; anything else is
rejected and reported, never passed to the platform.

## 2.3 Preset contents

| Key (Chrome) | OFF | MODERATE | STRICT |
|---|---|---|---|
| `IncognitoModeAvailability` | — | `1` (disabled) | `1` |
| `ForceGoogleSafeSearch` | — | `true` | `true` |
| `SafeSitesFilterBehavior` | — | `1` (filter adult) | `1` |
| `ForceYouTubeRestrict` | — | `1` (moderate) | `2` (strict) |
| private DNS | opportunistic | `dnsHost` | `dnsHost` |

OFF clears the Chrome bundle (empty `Bundle`) and calls
`setGlobalPrivateDnsModeOpportunistic`.

**The level and the DNS choice are independent axes.** `level` decides only the Chrome
keys above; which resolver is used is whatever the parent picked in `dnsHost`, unchanged by
moving between MODERATE and STRICT. Setting `level = OFF` is the one case that also releases
private DNS, because "off" must mean nothing is being enforced.

## 2.4 Applying

`SafetyManager.apply(config)` performs two independent steps and reports each separately —
one failing must not hide the other's success:

1. `dpm.setApplicationRestrictions(admin, pkg, bundle)` for each entry in `appConfigs`.
   `JsonObject` → `Bundle` conversion is explicit and total: boolean → `putBoolean`,
   int → `putInt`, string → `putString`, array-of-strings → `putStringArray`; anything else
   is skipped with a warning rather than crashing. A package that is not installed is
   skipped with a note (the config stays stored, so a later install can be re-applied).
2. `dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)` when `level != OFF` and
   `dnsHost != null`; `dpm.setGlobalPrivateDnsModeOpportunistic(admin)` when `level == OFF`;
   private DNS is left untouched when `dnsHost` is null — **off the main thread**, since
   it validates the resolver over the network. Its `PRIVATE_DNS_SET_*` result code is
   mapped to a human sentence in the `RESULT` sent back to the parent (notably
   `HOST_NOT_SERVING` → "that DNS server did not answer").

Re-applied on boot and on every `SET_SAFETY`. (Re-applying when a *new app is installed* is
deferred; noted in follow-ups.)

## 2.5 Protocol changes

```kotlin
enum class CmdType { …, SET_SAFETY }
Message.Cmd(… , val safety: SafetyConfig? = null)
Message.Status(… , val safetyLevel: SafetyLevel = SafetyLevel.OFF)
```

## 2.6 Parent UI

A **Safety** section on `DeviceScreen`: current level, opening a dialog with the three
levels, a DNS choice (CleanBrowsing Family / AdGuard Family / custom hostname / none), and
short honest copy:

- the YouTube *app* is covered only by the DNS filter;
- Restricted Mode is YouTube's own filter, not a whitelist — it hides most, not all;
- picking "none" for DNS leaves the YouTube app unfiltered.

Not every family-filter resolver forces YouTube Restricted Mode (Cloudflare's family
resolver blocks adult sites but does not); the offered choices are ones that do, and the
copy says so.

## 2.7 Testing

Unit: preset → `appConfigs` mapping for all three levels; `JsonObject` → `Bundle`
conversion including the skip path; hostname validation accept/reject cases.

Rig: apply MODERATE and STRICT, then confirm on the child with
`dumpsys device_policy` (application restrictions on `com.android.chrome`) and
`settings get global private_dns_mode` / `private_dns_specifier`; confirm OFF clears both;
confirm a bad hostname surfaces an error to the parent instead of bricking resolution.

---

# Part 3 — Advanced: generic managed-config editor (designed, later plan)

`RestrictionsManager.getManifestRestrictions(pkg)` returns the declared schema
(`List<RestrictionEntry>`: key, type, title, description, choices, default) for **any**
installed package, with no special permission. The schema lives on the child device, so the
parent must fetch it over BLE:

```kotlin
enum class CmdType { …, GET_APP_SCHEMA }          // Cmd.pkg names the app
@SerialName("SCHEMA") data class Schema(val pkg: String, val entries: List<SchemaEntry>) : Message()
```

The parent renders a generic form from the entry types and writes results straight into
`SafetyConfig.appConfigs[pkg]` — which Part 2 already applies verbatim, so no new apply path.

**Gate before this plan is written:** Chrome's schema is 159 entries; serialized with
titles and descriptions it may be tens of KB over chunked frames at MTU 517. The first task
of that plan measures the real payload. If it is unworkable, the fallback is child-side
filtering (parent sends a search term, child returns only matching entries) rather than
shipping the whole schema.

This screen sits behind **Advanced**, with a warning: it writes arbitrary managed
configuration to arbitrary apps, and a wrong value can make an app misbehave.

---

# Cross-cutting constraints

Carried forward from the existing project rules; every task in both plans inherits them:

- `protocol/` stays pure Kotlin — no Android imports. `LockSchedule` uses `java.time` only.
- `connectivity/ble/` stays app-agnostic.
- No new dependencies. Core Material icons only (no `material-icons-extended` — it crashed
  at runtime once already on this Compose BOM).
- Every string in both `values/strings.xml` and `values-fr/strings.xml`, key-for-key;
  `StringsParityTest` must stay green.
- No PIN, secret, or BLE payload logged in clear.
- Parent-facing copy uses parent vocabulary, never MDM jargon.

# Files touched (indicative)

**Part 1:** `protocol/Messages.kt` (+`LockSchedule`, cmd types, status fields) ·
`managed/LockController.kt` *(new)* · `managed/LockAlarms.kt` *(new)* ·
`managed/TimeChangeReceiver.kt` *(new)* · `managed/PolicyManager.kt` ·
`managed/CommandExecutor.kt` · `persistence/ManagedPrefs.kt` ·
`activities/managed/screens/BedtimeScreen.kt` *(new)* ·
`activities/managed/ManagedHomeActivity.kt` · `activities/managed/ManagedViewModel.kt` ·
`activities/main/screens/DeviceScreen.kt` · `activities/main/ControllerViewModel.kt` ·
both `strings.xml` · `AndroidManifest.xml` (receiver, `USE_EXACT_ALARM`).

**Part 2:** `protocol/Messages.kt` (+`SafetyConfig`) · `managed/SafetyManager.kt` *(new)* ·
`managed/CommandExecutor.kt` · `persistence/ManagedPrefs.kt` ·
`activities/main/screens/DeviceScreen.kt` · `activities/main/components/SafetyDialog.kt`
*(new)* · both `strings.xml`.

# Open risks

1. **Emergency call under lock task** — unproven on the emulator image; §1.6 says how it is
   verified and what happens if it fails.
2. **Private DNS on the emulator** — `setGlobalPrivateDnsModeSpecifiedHost` validates the
   resolver over the network; the emulator's NAT may make this flaky. If it cannot be
   verified there, that is reported rather than assumed working.
3. **Restricted Mode is not a whitelist** — a determined 14-year-old will find gaps. The
   parent copy is honest about this rather than promising safety.
