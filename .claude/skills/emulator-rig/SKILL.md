---
name: emulator-rig
description: Use when testing anything that touches BLE, lock task, alarms, provisioning, or the parent↔child link — brings up two Android emulators, pairs them, and drives a full session. Required before claiming device behaviour works.
---

# Two-emulator rig

Two Android emulators share an emulated Bluetooth stack (rootcanal), so the
whole parent↔child flow — advertising, scanning, GATT, MTU negotiation, the
HMAC handshake, commands, lock task, reconnection — runs between them. This is
the **only** place `DevicePolicyManager`, `AlarmManager`, and the broadcast
receivers are ever actually exercised: there is no JVM fake for them.

**If you changed lock task, alarms, provisioning, or the BLE link and you have
not run this, you have not tested your change.**

## What the rig cannot do

Say so rather than pretending otherwise:

- **QR / Setup Wizard provisioning** — needs real devices. The rig uses a
  debug-only broadcast instead (below).
- **The local hotspot** used by one provisioning mode.
- **Physical gestures** that the image maps elsewhere. On the API 35 image,
  long-press power opens Assistant rather than the classic power menu, so the
  "emergency call reachable under lock task" requirement **cannot be confirmed
  here** — it is still open and needs a real device.

## Setup

```bash
SDK="$HOME/AppData/Local/Android/Sdk"          # adjust for your OS
ADB="$SDK/platform-tools/adb.exe"
E1=emulator-5554   # parent (controller)
E2=emulator-5556   # child (managed, device owner)
PKG=edu.fnosari.momedm
```

Boot two API 34+ AVDs headless:

```bash
"$SDK/emulator/emulator.exe" -avd <AVD_1> -no-window -no-snapshot -gpu swiftshader_indirect -no-boot-anim -port 5554 &
"$SDK/emulator/emulator.exe" -avd <AVD_2> -no-window -no-snapshot -gpu swiftshader_indirect -no-boot-anim -port 5556 &
for p in 5554 5556; do "$ADB" -s emulator-$p wait-for-device; done
```

Wait for `getprop sys.boot_completed` to be `1` on both before continuing.

Build and install on both:

```bash
./gradlew :app:assembleDebug
for s in $E1 $E2; do "$ADB" -s $s install -r app/build/outputs/apk/debug/app-debug.apk; done
```

Kill animations so UI automation is deterministic:

```bash
for s in $E1 $E2; do for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$ADB" -s $s shell settings put global $k 0; done; done
```

## Pairing without the QR flow

Grant the parent its permissions and start it, so it generates its identity:

```bash
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE POST_NOTIFICATIONS NEARBY_WIFI_DEVICES; do
  "$ADB" -s $E1 shell pm grant $PKG android.permission.$p; done
"$ADB" -s $E1 shell am start -n $PKG/.activities.main.MainActivity
```

Read the parent's controller id and shared secret out of its DataStore:

```bash
"$ADB" -s $E1 shell run-as $PKG cat files/datastore/preferences.preferences_pb | tr -c '[:print:]' '\n' | grep -A1 ctrl_controller_id
"$ADB" -s $E1 shell run-as $PKG cat files/datastore/preferences.preferences_pb | tr -c '[:print:]' '\n' | grep -A1 ctrl_secret
```

Make the child a device owner (only works on a fresh profile with no accounts)
and hand it those values through the **debug-only** provisioning receiver:

```bash
"$ADB" -s $E2 shell dpm set-device-owner $PKG/.managed.AdminReceiver
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS; do
  "$ADB" -s $E2 shell pm grant $PKG android.permission.$p; done
"$ADB" -s $E2 shell am broadcast -a $PKG.DEBUG_PROVISION \
  -n $PKG/.managed.DebugProvisionReceiver --es controller_id "<ID>" --es secret "<SECRET>"
"$ADB" -s $E2 shell am start -n $PKG/.activities.main.MainActivity
```

Confirm the link came up:

```bash
"$ADB" -s $E2 logcat -d -s ManagedLinkService:V | grep Authenticated
```

## Driving the UI

```bash
# dump the view tree, find the node you want, tap its centre
"$ADB" -s $E1 shell uiautomator dump /sdcard/ui.xml && "$ADB" -s $E1 shell cat /sdcard/ui.xml
"$ADB" -s $E1 shell input tap X Y
"$ADB" -s $E2 shell input swipe X Y X Y 1200      # long-press (e.g. the child's hidden PIN gesture)
"$ADB" -s $E1 exec-out screencap -p > shot.png     # then LOOK at it
```

**Always look at the screenshot.** A blank frame, a half-drawn screen, or a
system dialog on top is a failed check, not a pass.

## Reading child state

```bash
"$ADB" -s $E2 shell dumpsys activity activities | grep -E "mLockTaskModeState|topResumedActivity"
"$ADB" -s $E2 shell dumpsys device_policy | grep -i -A3 "lock task"
"$ADB" -s $E2 logcat -d -v time -s ManagedLinkService:V PolicyManager:V LockController:V AndroidRuntime:E | tail -30
```

`mFlags` in the lock-task policy tells you *which* lock you are in:
`13` = `SYSTEM_INFO|HOME|OVERVIEW` (ordinary child mode — home and recents work,
and lead back to the allowed apps), `17` = `SYSTEM_INFO|GLOBAL_ACTIONS` (a
complete lock — no home, no recents, and `GLOBAL_ACTIONS` is what keeps the
emergency path reachable).

`mPackages` in the same dump is worth reading beside it: `com.android.vending`
and `com.google.android.gms` should be **absent** at rest and appear only for ten
minutes after the parent asks for an install.

## Proving a lock actually holds

The UI claiming "locked" is not evidence — a real defect once had the bedtime
screen showing while apps launched freely. Prove it from outside:

```bash
"$ADB" -s $E2 shell am start -n com.android.chrome/com.google.android.apps.chrome.Main
"$ADB" -s $E2 shell dumpsys activity activities | grep topResumedActivity
```

Under a complete lock this must be **rejected** (`error code 101`) and
`topResumedActivity` must stay on `ManagedHomeActivity`.

## Time-dependent checks without waiting

Never sit through a 10-minute pause or wait hours for a bedtime window. Either
set the schedule boundary a minute out, or move the device clock — which also
exercises the `TIME_SET` re-evaluation path:

```bash
"$ADB" -s $E2 shell settings put global auto_time 0
"$ADB" -s $E2 shell date MMDDhhmmYY          # then verify the lock followed
"$ADB" -s $E2 shell settings put global auto_time 1   # always restore
```

Forcing a re-evaluation without changing the clock:

```bash
"$ADB" -s $E2 shell am start -n $PKG/.activities.managed.ManagedHomeActivity
```

## Checking for a relaunch loop

A previous defect relaunched the launcher every ~350 ms at ~600% CPU until the
process died. After any change to lock application or re-evaluation:

```bash
"$ADB" -s $E2 shell top -m 10 -n 1 | head -15          # the app should be absent
"$ADB" -s $E2 logcat -d | grep -c "Displayed $PKG"      # should not keep climbing
```

## Leaving the rig clean

Turn off child mode and any lock, clear stray pauses, and restore
`auto_time` to `1`. The next person's run starts from whatever you left behind.

## Recording results

`docs/testing.md` is the durable record. Add what you ran and what you saw —
including failures and anything you could not verify. A stale PASS in that file
is worse than no entry: it tells the next reader something is proven when it is
not.
