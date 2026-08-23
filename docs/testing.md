# Manual test checklist (two physical devices, or the emulator rig below)

## Emulator test rig (verified 2026-08-22, API 35 images)

Android emulators (API 33+) share an emulated Bluetooth stack (rootcanal), so
the whole BLE link — advertising, scanning, GATT, MTU 517, handshake, commands,
reconnect — runs between two AVDs on one host. Only the QR/Setup-Wizard
provisioning and the hotspot need real hardware.

```bash
SDK=~/AppData/Local/Android/Sdk   # adjust
ADB=$SDK/platform-tools/adb
# 1. two AVDs (any API 33+ phone images); headless is fine
$SDK/emulator/emulator -avd Pixel_9_Pro_API_35   -no-window -gpu swiftshader_indirect -no-audio -wipe-data &
$SDK/emulator/emulator -avd Medium_Phone_API_35  -no-window -gpu swiftshader_indirect -no-audio -wipe-data -port 5556 &
./gradlew :app:assembleDebug
for d in emulator-5554 emulator-5556; do $ADB -s $d install -r app/build/outputs/apk/debug/app-debug.apk; done

# 2. controller = emulator-5554: grant runtime permissions, launch
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE POST_NOTIFICATIONS NEARBY_WIFI_DEVICES; do
  $ADB -s emulator-5554 shell pm grant edu.fnosari.momedm android.permission.$p; done
$ADB -s emulator-5554 shell am start -n edu.fnosari.momedm/.activities.main.MainActivity
# read the controller identity the app generated (debug build → run-as works)
$ADB -s emulator-5554 shell run-as edu.fnosari.momedm cat files/datastore/preferences.preferences_pb | tr -c '[:print:]' '\n' | grep -A1 ctrl_

# 3. managed = emulator-5556: make the app device owner (no accounts on the AVD), grant, inject secret
$ADB -s emulator-5556 shell dpm set-device-owner edu.fnosari.momedm/.managed.AdminReceiver
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS; do
  $ADB -s emulator-5556 shell pm grant edu.fnosari.momedm android.permission.$p; done
$ADB -s emulator-5556 shell am broadcast -a edu.fnosari.momedm.DEBUG_PROVISION \
  -n edu.fnosari.momedm/.managed.DebugProvisionReceiver --es controller_id <uuid> --es secret '<base64>'
# (DebugProvisionReceiver exists only in debug builds; it mimics the QR admin-extras path)
$ADB -s emulator-5556 shell am start -n edu.fnosari.momedm/.activities.main.MainActivity   # → ManagedHomeActivity

# 4. watch: handshake + commands
$ADB -s emulator-5556 logcat -s ManagedLinkService:V BLEClient:V PolicyManager:V
$ADB -s emulator-5554 logcat -s ControllerService:V BLEServer:V
# lock-task state on the managed emulator:
$ADB -s emulator-5556 shell dumpsys activity activities | grep -E 'mLockTaskModeState|topResumedActivity'
# drive the controller UI with `adb shell input tap x y` + `adb exec-out screencap -p > shot.png`
```

Emulator results so far: A (all except hotspot/QR-scan), C (all commands incl.
kiosk lock-task, Play listing, add-account refusal), D reconnect after
advertising toggle (recovered via write-rejection/PING in ~30 s), D reboot with
kiosk ON, E (child mode v2: multi-app, single-app pin, PIN pause/lockout/reboot,
PIN change — see below, all 9 checks pass). Not testable on emulator: hotspot,
SUW QR provisioning, real-radio MTU/throughput.

## A. Controller standalone
- [ ] Fresh install on phone A (not device owner) → permission gate shows 5 permissions; after granting, Devices screen.
- [ ] Advertising toggle ON → persistent notification "Môme DM controller"; OFF → notification gone, banner shown.
- [ ] Provision screen, Hotspot mode → Generate QR → SSID/pass filled, "Serving APK at <ip>" and QR visible. Browser on another device on that hotspot: `http://<ip>:8080/momedm.apk` downloads.
- [ ] Manual mode with your Wi-Fi → QR visible; Custom URL mode → QR visible without server.
- [ ] Settings → Controller: id + fingerprint shown; Regenerate → fingerprint changes, service restarts.

## B. Provisioning device B (factory reset)
- [ ] Tap welcome screen 6×, scan QR (hotspot) → Wi-Fi joins, APK downloads, "Set up your device" → our wizard (account step, usage step) → home = Môme DM managed screen.
- [ ] If download fails on hotspot: retry with Manual (shared LAN) mode; if `http://` refused: Custom URL mode with an https host. Record which worked in this file.
- [ ] Hotspot security type: QR declares WPA; if the hotspot came up WPA3-only the managed device may fail to join → use Manual mode.
- [ ] `adb shell dumpsys device_policy | grep -i owner` shows `edu.fnosari.momedm`.

## C. Link + commands
- [ ] B shows "Looking for controller…" then banner disappears (AUTHENTICATED) within ~10 s of A advertising. A's Devices list shows B online with model.
- [ ] Refresh status → card updates (battery, account yes/no, kiosk no).
- [ ] Kiosk ON → app picker lists B's apps (non-ASCII labels intact) → pick one → B enters lock task in that app; A shows kiosk=pkg. Back/home blocked on B.
- [ ] Kiosk OFF → B returns to managed home; status kiosk=no.
- [ ] Install (e.g. `org.mozilla.firefox`) with account on B → Play listing opens (also while kiosk ON).
- [ ] Install (kiosk OFF) → Play listing opens normally (not pinned in lock task; Back/Home still work on B).
- [ ] Add account → Google sign-in flow opens on B (kiosk OFF).
- [ ] Snackbar on A shows `OK: ...` / `ERR: ...` for every command.

## D. Resilience
- [ ] Toggle Bluetooth off/on on A → B reconnects and re-authenticates (backoff visible in logcat `ManagedLinkService`).
- [ ] Reboot B with kiosk ON → kiosk app relaunches, link re-established.
- [ ] Reboot B with kiosk OFF → managed home, link re-established.
- [ ] Second managed device C → both online simultaneously; commands go to the right device.
- [ ] Controller with a different secret (regenerate on A) → B never authenticates, A shows B offline, B keeps scanning; re-provision fixes it.
- [ ] Logcat on A shows unauthenticated centrals dropped after 5 s.

## E. Child mode v2 (multi-app, PIN, prefs)

Verified on the emulator rig 2026-08-22 (controller = emulator-5554, managed =
emulator-5556, both API 35, Task 7 build). The Medium_Phone_API_35 image has
no Calculator app, so app-picker checks below use **Calendar + Clock** in
place of the brief's "Clock + Calculator" example — behavior is identical,
only the sample packages differ.

- [x] Managed launcher with child mode OFF shows all apps ("All apps" header); tapping a tile (Clock) opens it. — **PASS**. `topResumedActivity` = `com.google.android.deskclock/.DeskClock`.
- [x] Parent: Settings → Controller → *Set PIN* (any 4-digit test PIN) → info text "PIN saved and sent to connected children"; managed logcat `PolicyManager: Prefs applied (lang=system, theme=system, pin=true)`. — **PASS**.
- [x] Parent: Device → *Child mode: choose apps…* → select 2 apps, confirm → managed: `mLockTaskModeState=LOCKED`, launcher shows exactly those 2 tiles, header "Child mode", lock icon visible; parent status shows *Allowed apps: 2 allowed*. — **PASS**. Managed logcat `PolicyManager: Kiosk on: 2 apps, pinned=null`.
- [x] Tap an allowed tile (Clock) → opens inside lock task; Back returns to the launcher (still LOCKED). — **PASS**.
- [x] Parent: choose apps again with *Pin a single app* = Clock → managed bounces into Clock; pressing Home (`adb shell input keyevent KEYCODE_HOME`) lands back in Clock within ~1 s, still LOCKED. — **PASS**. Parent status shows *Single app: com.google.android.deskclock*.
- [x] Managed: lock icon → wrong PIN twice (error "Wrong PIN" + growing countdown: 3 s, then 5–6 s) → correct PIN → banner "Child mode paused · 09:5x", `mLockTaskModeState=NONE`, all apps visible; parent status *Paused until …*; parent *Lock again* → LOCKED again with the same 2 apps. — **PASS**.
- [x] PIN pause again, then `adb reboot` the managed emulator → after boot `mLockTaskModeState=LOCKED` again (pause not honoured across reboot). — **PASS**. Logcat: `BootReceiver: Boot completed; starting link service` → `PolicyManager: Kiosk on: 2 apps, pinned=null` (unconditional re-lock, no residual pause).
- [x] Parent *Turn child mode off* → `mLockTaskModeState=NONE`, launcher shows all apps ("All apps" header). — **PASS**.
- [x] Parent changes PIN (Settings → Controller → *Change PIN*) → managed verifies only the new one: old PIN rejected (still LOCKED, lockout countdown), new PIN accepted (pauses, `mLockTaskModeState=NONE`). — **PASS**.

Screenshots (launcher off/on/paused, parent device page, PIN dialogs) are
under `.superpowers/sdd/2026-08-22-kiosk-v2-plan1/shots/`. No defects found;
one rig-only observation: on this debug-provisioned emulator (bypassing the
Setup-Wizard `PolicyComplianceActivity` step), `setAsDefaultHome()` never
runs, so pressing Home while child mode is *off* goes to the AVD's Nexus
launcher instead of `ManagedHomeActivity` — expected, since only the real
provisioning flow (or QR/SUW) sets the persistent-preferred-HOME activity;
while child mode is *on*, lock task itself keeps Home inside our launcher/pinned
app regardless.

## F. Localization & theming

Verified on the emulator rig 2026-08-23 (same two-AVD rig as section E:
controller = emulator-5554, managed = emulator-5556, both API 35). Both
locale files (`res/values/strings.xml`, `res/values-fr/strings.xml`) must
keep an identical key set at all times — enforced by the JVM test
`edu.fnosari.momedm.res.StringsParityTest`, which parses both files and
diffs the key sets; run it with `./gradlew :app:testDebugUnitTest --tests
"edu.fnosari.momedm.res.*"` after touching either file.

- [x] Parent Settings → Appearance & language → **Français** → parent UI
  switches to French immediately (drawer "Mes enfants", "Associer un
  appareil", "Réglages"); the child receives `SET_PREFS` and its launcher
  header/labels switch to French, logcat `PolicyManager: Prefs applied
  (lang=fr…)`. — **PASS**.
- [x] Theme → **Sombre** → both parent and child render dark
  (`MomeDMTheme(darkTheme = true, …)` on both roles). — **PASS**.
- [x] App colour → pick a preset (blue) and then a custom colour via the
  hex/HSV dialog → parent top bar + primary buttons recolour immediately;
  child launcher header recolours after the `SET_PREFS` push
  (`ManagedThemed` reads the pushed `ChildPrefs.accent`). — **PASS**, after
  the `AccentDialog` fix below.
- [x] Back to **English** + **System** theme + default green accent
  (`Palette.DEFAULT`) → child follows on next push. — **PASS**.
- [x] Parent PIN set/change/remove still works from its own *Parent PIN*
  settings screen (moved off the Advanced/Controller screen); child PIN pad
  shows French labels while the child is set to FR. — **PASS**.
- [x] Full child-mode flow (multi-app allow-list, pin one app, PIN pause) on
  the redesigned French device page — same behavior as section E, French
  strings throughout, no missing keys. — **PASS**.

**Crash found and fixed on the rig:** the accent picker (`AccentDialog` in
`activities/settings/components/ColorDialogs.kt`), carried over from
MaClasse, used `androidx.compose.foundation.layout.FlowRow` to lay out the
colour swatches. On this project's pinned Compose BOM (2024.09.00) that threw
`NoSuchMethodError` at runtime on first open, even though it compiled cleanly
— caught during this emulator pass and fixed by replacing `FlowRow` with a
plain `Palette.PRESETS.chunked(4)` → `Column`/`Row` grid (commit `d794a09`).
See the matching gotcha in `CLAUDE.md`.

## G. Emulator checks — complete lock

Verified on the emulator rig 2026-08-23 (same two-AVD rig as sections E/F:
controller = emulator-5554, managed = emulator-5556, both API 35, branch
`feature/complete-lock`). This pass exists because `PolicyManager`'s lock
calls (lock task, alarms, the emergency path) have no JVM test and can only
be judged on a device. **Two real defects were found and are not fixed by
this task — see "Defects found" below; the human should decide how to
prioritize them.**

- [x] **Step 1: Bring up the rig.** Reinstalled the current debug build on
  both AVDs (`-r`, so provisioning/link state survived), relaunched, and
  confirmed `ManagedLinkService: Authenticated` in the managed logcat within
  seconds. — **PASS**.
- [x] **Step 2: Night window locks the device.** Parent → device page →
  Night lock: set the Sun–Thu window to 12:50→13:50 via the Material3
  `TimePicker` (dial-mode only; see the TimePicker note below), enabled the
  toggle. At 12:50:00 the managed emulator logged `LockController:
  Re-evaluated: locked=true reason=night` → `PolicyManager: Complete lock
  applied`, driven by the armed alarm (not a poll). `dumpsys activity
  activities` showed `mLockTaskModeState=LOCKED`,
  `topResumedActivity=…ManagedHomeActivity`, and the screenshot showed the
  bedtime screen ("Bonne nuit ! / Déverrouillage à 13:50") with no tiles. —
  **PASS**.
- [x] **Step 3: PIN pause and automatic re-lock.** Long-pressed the bedtime
  screen → PIN dialog appeared (only after long-press; PIN masked as dots,
  never shown or logged in the clear) → correct PIN → `mLockTaskModeState`
  went to `NONE` and the device showed the normal child-mode launcher with a
  "Mode enfant en pause · 09:59" banner. Forcing the pause to lapse (see
  "Defects found" — this is exactly how the busy-loop bug below was found)
  and re-evaluating confirmed the device is recomputed back to `LOCKED`
  rather than remembered. — **PASS, but see Defect 1**: naturally letting a
  10-minute pause lapse while both child mode and a complete lock are active
  triggers Defect 1 on this build.
- [x] **Step 4: Reboot inside a window.** `adb reboot` while
  `mLockTaskModeState=LOCKED` (reason=night). After boot, our
  `BootReceiver: Boot completed; starting link service` fired, then
  `LockController: Re-evaluated: locked=true reason=night` →
  `PolicyManager: Complete lock applied`; `dumpsys` showed `LOCKED` and the
  screenshot showed the bedtime screen again. — **PASS**.
- [x] **Step 5: Manual lock and unlock.** With the schedule disabled: parent
  **Lock now** → managed `PolicyManager: Manual lock = true` →
  `LockController: Re-evaluated: locked=true reason=manual` → bedtime screen
  reading "Un parent a verrouillé ce téléphone"; parent state line updated
  to "Locked by you" (see the state-line note below). Parent **Unlock** →
  child returned to its prior child-mode launcher (6 allowed apps), not a
  free device, matching the "returns to what was configured before"
  contract. Reboot while manually locked → `mLockTaskModeState=LOCKED`
  survives and the bedtime screen shows the "locked by you" message again —
  **but see Defect 2**: the DPM state underneath was found to be wrong after
  this exact reboot, even though the screen and `dumpsys` both looked
  correct.
- [x] **Step 6: Emergency path (spec §1.6).** Long-pressed power
  (`adb shell input keyevent --longpress KEYCODE_POWER`) while the device
  was genuinely locked with `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` confirmed
  granted (`dumpsys device_policy` showed `LockTaskPolicy {mPackages=
  edu.fnosari.momedm; mFlags=17}`, i.e. `SYSTEM_INFO|GLOBAL_ACTIONS`). **No
  menu of any kind appeared** — `mCurrentFocus`/`mFocusedApp` stayed on
  `ManagedHomeActivity`, and the screenshot is pixel-identical to the
  bedtime screen before the key event. As a control, the same command on
  the *unlocked* controller emulator does bring up a system sheet — but it
  is the Google Assistant ("Hi. Your Google Assistant here…"), not the
  classic power menu, confirming this AVD image maps a long-press of
  `KEYCODE_POWER` to the Assistant gesture rather than `GlobalActions`, and
  that lock task suppresses even that fallback. **Finding, not fixed**: on
  this image, this task cannot confirm the "Emergency" entry is reachable
  the way the design intends — either because the synthetic long-press
  event doesn't reach `PhoneWindowManager`'s global-actions path the way a
  real hardware long-press would, or because a real long-press on this
  Android build genuinely goes to Assistant first. This is worth re-testing
  on real hardware; §1.6 stays **unproven**.
- [x] **Step 7: Clock-change trigger.** No root shell (`adb root` refused:
  "cannot run as root in production builds"; `adb shell date` refused:
  "Operation not permitted") so the change was made via Settings → Date &
  time → disable **Set time automatically** → set the time forward past the
  window boundary → the managed emulator logged `TimeChangeReceiver: Clock
  changed (android.intent.action.TIME_SET); re-evaluating lock` within
  under a second, followed by a correct re-evaluation once state was clean
  (see Defect 1 for what happened the first time, while a stale pause was
  present). Restored **Set time automatically** afterwards
  (`settings get global auto_time` → `1`). — **PASS** (clean case); the
  same trigger is what surfaced Defect 1 when a pause was stale.
- [x] **Step 8: Record results.** This section.

**Extra checks run opportunistically:**
- [x] The Material3 `TimePicker` dialog (`TimeRangeRow.kt`) renders
  correctly on this Compose BOM (dial mode only — no keyboard-input toggle
  is wired up in this composable) and a changed time reaches the child:
  saving a new start/end time immediately logged `ManagedLinkService:
  Command SET_SCHEDULE` → `PolicyManager: Lock schedule set` on the managed
  side, and the parent's own display of the time updated. — **PASS**.
- [x] **Lock now**/**Unlock** do flip the parent's state line. Each command
  handler (`CommandExecutor.execute` for `LOCK_NOW`/`UNLOCK`) sends a fresh
  `Message.Status` right after the result, and separately every
  `LockController.reevaluate()` call also emits a status push
  (`ManagedLinkState.statusPushRequests`) — both paths were observed firing
  in practice. The device page went "Unlocked" → **Locked by you** → tapped
  **Unlock** → back to "Unlocked" within about a second each time, matching
  the button's own toggled label (**Lock now** ↔ **Unlock**). — **PASS**.

### Defects found (not fixed in this task — flagged for the human)

**Defect 1 — busy-loop when a stale PIN-pause outlives itself under an
active complete lock.** `ManagedViewModel.trackPause()` (activity-scoped
ViewModel, `ManagedViewModel.kt:131`) calls
`LockController(...).reevaluate()` on *every* emission of `kioskConfig`
whenever `c.on && c.pauseUntil > 0L && !c.isPaused(now)` — i.e. whenever
child mode is on and a pause deadline exists but has already passed.
`PolicyManager.lockComplete()` (`PolicyManager.kt:95`), which is what
`reevaluate()` calls when a night-lock or manual lock should be in force,
**never clears `pauseUntil`** — only `kioskOn()` does that (via
`prefs.setKioskConfig(... pauseUntil = 0L)`, `PolicyManager.kt:72`). Each
`reevaluate()` → `lockComplete()` → `launchHomeLocked()` uses
`FLAG_ACTIVITY_CLEAR_TASK`, which recreates `ManagedHomeActivity` and its
(activity-scoped) `ManagedViewModel` — whose fresh `init` immediately
re-collects `kioskConfig`, re-enters the same branch, and repeats. Observed
on the rig: `ActivityTaskManager` `START`/`Displayed` for
`ManagedHomeActivity` every ~300–400 ms for at least 90+ seconds straight,
`top` showing 600% aggregate CPU (system_server alone at 135%), and the app
process was eventually replaced (pid changed under load, consistent with
the OS killing and restarting it). The loop only stops when something calls
`kioskOn()` (clearing `pauseUntil`) — the parent re-sending an app-picker
selection was what stopped it during this run.

Reachable in the ordinary product, not just under clock manipulation:
**any** child-mode + PIN-pause combination (from either the bedtime screen
or the child-mode launcher — both funnel through the same
`KioskConfig.pauseUntil`) whose 10-minute pause naturally lapses while a
manual or scheduled complete lock is (still) supposed to be in effect will
hit this. Requires `c.on` (child/kiosk mode) to be true; a complete lock
with kiosk fully off does not loop (confirmed by reading `trackPause`'s
other branch).

**Defect 2 — a reboot while a complete lock is active silently downgrades
it to the ordinary child-mode kiosk allowlist.** `BootReceiver` starts
`ManagedLinkService` (`fromBoot = true`) *and* separately calls
`LockController.of(app).reevaluate()`, racing each other.
`ManagedLinkService.onStartCommand` (`ManagedLinkService.kt:142`) runs
`if (fromBoot) policy.restoreKiosk()` unconditionally whenever child mode
was on — `PolicyManager.restoreKiosk()` (`PolicyManager.kt:175`) calls
`kioskOn()` with no regard for whether a manual lock or night schedule
should currently override it. On this rig, `restoreKiosk()`'s `kioskOn()`
finished *after* `BootReceiver`'s `reevaluate()` → `lockComplete()`, so it
won: `dumpsys device_policy` showed `LockTaskPolicy {mPackages=
com.google.android.apps.maps, com.google.android.gm, edu.fnosari.momedm,
com.google.android.apps.docs, com.android.vending,
com.google.android.deskclock, com.google.android.gms,
com.google.android.calendar, com.android.chrome; mFlags=1}` — the full
6-app child-mode allowlist plus Play/GMS, features downgraded to
`SYSTEM_INFO` only (no `GLOBAL_ACTIONS`) — **while the launcher UI still
rendered the bedtime screen** ("Bonne nuit ! / Un parent a verrouillé ce
téléphone"), because that text is driven by a separately-computed,
DPM-independent `lockState` flow. Proven exploitable, not just a dumpsys
oddity: `adb shell am start -n com.android.chrome/…Main` **launched Chrome
successfully inside lock task** (`mLockTaskModeState` stayed `LOCKED`
because Chrome is a member of the leaked allowlist) while the phone was
supposedly under a parent's manual complete lock. Re-sending **Unlock**
then **Lock now** from the parent restored the correct
`{mPackages=edu.fnosari.momedm; mFlags=17}` state. This directly
contradicts the Step 5 reboot check's apparent pass: `dumpsys activity
activities` and the on-screen bedtime text both looked right after reboot,
but the actual enforcement was wrong until manually kicked.

Both defects share a root cause: three independent call sites
(`ManagedViewModel.trackPause`'s pause-lapse branch,
`ManagedLinkService.startPauseWatchdog`'s `resume()`, and
`ManagedLinkService`'s boot-time `restoreKiosk()`) can each call
`kioskOn()`/`resume()` directly, and none of them checks whether a
complete lock (manual or scheduled) should currently take precedence
before doing so — only `LockController.reevaluate()` knows how to make
that decision, and these paths bypass it (or, for Defect 1, invoke it in a
way that never resolves because `lockComplete()` doesn't clear the
condition that triggered the call).
