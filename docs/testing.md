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
kiosk ON. Not testable on emulator: hotspot, SUW QR provisioning, real-radio
MTU/throughput.

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
