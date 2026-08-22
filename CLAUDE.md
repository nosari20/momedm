# CLAUDE.md

Guidance for working in this repository.

## What this is

**Môme DM** is a single Android app (Kotlin + Jetpack Compose), package
`edu.fnosari.momedm`, with two runtime roles chosen at launch by
`DevicePolicyManager.isDeviceOwnerApp(packageName)` — no role picker:

- **Managed** — the app is **device owner** on a fully managed device (work
  profile is refused). It connects over BLE to a controller and executes
  management commands (kiosk, install, add account, status).
- **Controller** — the same app on another phone. It provisions managed
  devices via QR code (embedding a shared HMAC secret + a self-hosted APK
  download), advertises a BLE GATT server, and drives connected managed
  devices.

Transport is BLE only — no cloud, no Play EMM. Design spec:
`docs/superpowers/specs/2026-08-22-momedm-design.md`. Implementation plan:
`docs/superpowers/plans/2026-08-22-momedm-implementation.md`.

## Build & run

```bash
./gradlew assembleDebug              # build debug APK
./gradlew installDebug               # build + install on a connected device
./gradlew :app:testDebugUnitTest     # JVM unit tests (protocol, controller, persistence, managed)
./gradlew clean :app:assembleDebug :app:testDebugUnitTest  # full verification
```

On Windows, `./gradlew` runs through Git Bash; `gradlew.bat` is the native
equivalent. `local.properties` must point `sdk.dir` at the Android SDK. BLE
and device-owner behavior CAN be exercised between two API 33+ emulators
(the emulator's rootcanal Bluetooth stack links all emulators on one host) —
see "Emulator test rig" in `docs/testing.md`; real hardware still needed for
the QR/Setup-Wizard provisioning path and hotspot.

**Windows Gradle quirk:** a stray Gradle daemon can hold a file lock on
`app/build` between runs. If a build fails with a lock/access error: `./gradlew
--stop`, delete `app/build`, then retry.

**Toolchain:** AGP 9.3.1 with built-in Kotlin 2.2.10, Gradle 9.5, Compose BOM
2024.09.00 + Material3, navigation-compose, lifecycle-viewmodel-compose,
DataStore preferences, kotlinx-serialization-json 1.9.0, zxing-core 3.5.3
(QR), nanohttpd 2.3.1 (APK hosting), JUnit 4. `minSdk 34` / `compileSdk 37` /
`targetSdk 37`, Java 11. Dependencies are managed through the version catalog
at `gradle/libs.versions.toml` — add libraries there, never inline in
`app/build.gradle.kts`. Build must stay a single universal APK (no
`splits {}`) because the controller serves its own
`applicationInfo.sourceDir`.

## Architecture

```
app/src/main/java/edu/fnosari/momedm/
├── connectivity/ble/              # Reusable, app-agnostic BLE framework (copied from BLEController + extended)
│   ├── BLEClient.kt               #   Scan-by-name/UUID, connect, discover, notify, read/write
│   ├── BLEServer.kt               #   GATT server + advertising, per-device notify queue
│   ├── BLEOperationQueue.kt       #   Serializes GATT ops (one in flight at a time)
│   ├── BLEOperation.kt            #   Sealed Read/Write/WriteDescriptor op types
│   ├── BLEException.kt, BLEDevice.kt
│   ├── characteristics/BLECharacteristic.kt  #   Abstract base + Permission enum (READ/WRITE/READ_WRITE/NOTIFY)
│   ├── services/BLEService.kt     #   Abstract base
│   └── README.md                  #   Framework-level docs; keep in sync with extensions
│
├── protocol/                      # Pure Kotlin — no Android imports; JVM-tested
│   ├── Encoding.kt                #   Hex, Base64Url, Base64Std
│   ├── Crypto.kt                  #   HMAC-SHA256, nonces, constant-time compare
│   ├── Framer.kt                  #   Framer (chunking) + Reassembler (10 s idle timeout, 16 concurrent partials)
│   ├── Messages.kt                #   Message sealed hierarchy, Envelope, MessageCodec, CmdType, AppInfo
│   ├── Handshake.kt                #   ManagedHandshake, ControllerHandshake
│   ├── SecureChannel.kt           #   SecureChannel (seal/open), ProtocolException
│   ├── Endpoints.kt               #   FrameSink, ManagedEndpoint, ControllerEndpoint (session state machines)
│   ├── PinHash.kt                 #   Parent-PIN hashing: PBKDF2-HMAC-SHA256, 20k iters, 32-byte out, 16-byte salt
│   └── ProvisioningExtras.kt      #   Admin-extras bundle keys (controller_id, secret)
│
├── link/MdmGatt.kt                # Service/characteristic UUIDs (permanent) + MdmService/Cmd/RspCharacteristic
│
├── persistence/
│   ├── preferences/               # Preference<T>, PreferencesProvider, DataStore impl (copied from BLEController)
│   ├── ManagedPrefs.kt            #   controllerId, secret, deviceId, kioskConfig, childPrefs
│   ├── KioskConfig.kt             #   on/apps/pinned/pauseUntil + isPaused/isLocked, PAUSE_MS (pure Kotlin)
│   ├── ControllerPrefs.kt         #   controllerId, secret (identity), parent PIN (hash+salt), pinSet
│   └── DeviceRegistry.kt          #   DeviceRecord, codec, registry (DataStore JSON), nickname
│
├── managed/                       # Non-UI, device-owner side
│   ├── AdminReceiver.kt           #   DeviceAdminReceiver
│   ├── BootReceiver.kt            #   Restarts ManagedLinkService on BOOT_COMPLETED
│   ├── ManagedSetup.kt            #   Post-provisioning setup (preferred HOME, start link)
│   ├── PolicyManager.kt           #   DevicePolicyManager wrapper: multi-app kiosk on/off/pause/resume,
│   │                              #     restoreKiosk (reboot), verifyPin, install, add account
│   ├── StatusCollector.kt         #   Battery, account, foreground app, launchable apps
│   ├── CommandExecutor.kt         #   Dispatches Cmd -> PolicyManager/StatusCollector, builds Result
│   ├── ManagedLinkState.kt        #   Link state enum/data exposed to UI
│   └── ManagedLinkService.kt      #   Foreground service: owns BLEClient + ManagedEndpoint, reconnect w/ backoff
│
├── controller/                    # Non-UI, controller side
│   ├── ControllerLink.kt          #   Advertising state bus consumed by UI/settings
│   ├── ControllerService.kt       #   Foreground service: owns BLEServer + SessionManager
│   ├── SessionManager.kt          #   BluetoothDevice -> ControllerEndpoint session map
│   └── provisioning/              #   ControllerIdentity, QrPayloadBuilder, SignatureChecksum, ApkHttpServer,
│                                  #     HotspotManager, NetUtils, QrBitmap, ProvisioningController
│
├── activities/
│   ├── main/                      # Controller UI: MainActivity (LAUNCHER; redirects to ManagedHomeActivity if
│   │   │                          #   isDeviceOwnerApp), ControllerViewModel, navigation/Routes {DEVICES, PROVISION},
│   │   │                          #   screens/{DevicesScreen,DeviceScreen,ProvisionScreen},
│   │   └── components/{ServiceBanner,OnlineIndicator,AppPickerDialog}
│   ├── managed/                   # Managed UI: ManagedHomeActivity (HOME/DEFAULT; itself a launcher screen — all
│   │   │                          #   apps when child mode is off), ManagedViewModel, screens/ChildLauncherScreen
│   │   │                          #   (app grid, pinned-app bounce, PIN pause banner), components/PinDialog
│   │   │                          #   (masked numeric PIN, lockout countdown)
│   │   └── provisioning/          #   GetProvisioningModeActivity, PolicyComplianceActivity (setup wizard steps)
│   └── settings/                  # SettingsActivity (copied), navigation/Routes, screens/{SettingsCategories,
│                                  #   SettingsScreen, SettingsEasterEgg, SettingsControllerScreen}, components/
│
├── ui/{layouts,theme}/            # Layout.BasicLayoutWithTopBarAndDrawer, BasicLayoutWithTopBar, MomeDMTheme (copied)
├── ui/components/ButtonRequestPermission.kt  # Permission-gate button used by activity onCreate flows
├── ui/AppLocale.kt                # Applies a ChildPrefs language override (per-app locale) at runtime
└── utils/AppVersion.kt            # Copied

app/src/test/java/edu/fnosari/momedm/
├── protocol/                      # Framer/Reassembler, Crypto, Encoding, Handshake, Messages, SecureChannel,
│                                  #   EndpointLoopbackTest (full controller<->managed session over an in-memory sink)
├── controller/                    # SessionManagerTest, provisioning/{ControllerIdentityTest,NetUtilsTest,QrPayloadBuilderTest}
├── persistence/                   # ManagedPrefsTest, ControllerPrefsTest, DeviceRegistryCodecTest, InMemoryPreferencesProvider
└── managed/                       # CommandExecutorTest
```

Data flow: **Controller BLEServer (GATT peripheral) ⇄ BLE link ⇄ Managed
BLEClient (GATT central)**, both sides driving a `ControllerEndpoint` /
`ManagedEndpoint` state machine over `protocol/` (frames → envelope →
messages) before reaching `managed/CommandExecutor` or the controller
screens.

### Key conventions

- **`connectivity/ble` is app-agnostic** — no MDM terms, no app-specific
  UUIDs or classes live there; it is the same reusable framework as
  BLEController's, copied and extended (see its own
  `connectivity/ble/README.md`).
- **`protocol/` is pure Kotlin** — no Android imports, only Kotlin stdlib,
  kotlinx-serialization and `javax.crypto`; it is fully covered by JVM unit
  tests and must stay that way for anything added to it.
- **Role switch, not a role picker.** `MainActivity.onCreate` checks
  `isDeviceOwnerApp` and redirects to `ManagedHomeActivity` when true; there
  is no user-facing toggle.
- **Routes enum / Layout pattern**, copied from BLEController: each
  activity's `navigation/Routes` enum (`label: Int` string res, `icon:
  ImageVector`) drives a `NavHost` inside
  `Layout.BasicLayoutWithTopBarAndDrawer`.
- **Version catalog only.** All dependencies go through
  `gradle/libs.versions.toml`; never add inline coordinates in
  `app/build.gradle.kts`.
- **Private `LOG_TAG` companion + verbose `android.util.Log`** on every
  class, KDoc on public API — matches BLEController's style.
- BLE system callbacks run on binder threads: they log-and-return on a
  missing permission, never throw.

## Gotchas

- **BLE works between two emulators** (API 33+ images expose emulated
  Bluetooth via rootcanal; advertising, scanning, GATT, MTU 517 all work).
  `docs/testing.md` has the exact rig (two AVDs, `dpm set-device-owner`,
  the debug-only `DebugProvisionReceiver` to inject the controller secret).
  CI can still only compile-check and run the JVM unit tests.
- **The DPC (device-owner) path is only testable two ways:** a factory-reset
  device run through Setup Wizard QR provisioning, or on a device with **no
  accounts added**, via
  `adb shell dpm set-device-owner edu.fnosari.momedm/.managed.AdminReceiver`.
  Device owner cannot be set on a device that already has a user account.
- **ATT MTU falls back to 23** if `requestMtu(517)` fails or the peer
  negotiates lower — `Framer.maxChunk` and the protocol's chunking must
  tolerate the resulting ~5-byte-per-frame payload; `Endpoints.kt` clamps a
  peer-reported `Hello.mtu` to `23..517` before trusting it.
- **`allowBackup="false"` is intentional** — the shared secret (`controllerId`
  + `secret` in `ManagedPrefs`/`ControllerPrefs`) must never be restored onto
  a different device via Auto Backup.
- **GATT UUIDs in `link/MdmGatt.kt` are permanent** — `SERVICE_UUID`,
  `CMD_UUID`, `RSP_UUID` are fixed forever once generated; do not regenerate
  them, or already-provisioned devices lose discovery.
- **Every handshake HMAC is domain-separated** — challenge proof =
  `HMAC(secret, "momedm/challenge|$nonceC")`, auth proof =
  `HMAC(secret, "momedm/auth|$nonceS")`, session key =
  `HMAC(secret, "momedm/session|$nonceC|$nonceS")`. Never collapse these onto a
  shared input space: the CHALLENGE proof is an oracle over an attacker-chosen
  `nonceC`, so without the tags a forged HELLO with `nonceC = realNonceC+nonceS`
  gets the session key handed straight back. Both endpoints also reject any
  peer nonce that isn't exactly 32 lower-case hex chars, *before* hashing it.
- **Never log string preference values or BLE payloads** —
  `DataStorePreferencesProvider` logs string writes as key + length only (the
  shared secret and the Wi-Fi passphrase go through it), and `BLEClient`/
  `BLEServer` log characteristic traffic as UUID + byte length only (payloads
  are protocol frames).
- **Protocol errors reset the whole session** (handshake + channel), on
  either side — a bad MAC, non-increasing seq, or unexpected message before
  auth never leaves a half-valid state that a replayed frame could exploit.
- **`ManagedEndpoint.authenticated` is only `true` after `AUTH_OK`** lands
  from the controller, not merely after the managed side sends `AUTH` —
  `AUTH_OK` is an addition beyond the spec so the managed side has a
  positive signal that the session is actually live.
- **Reassembler** discards a partial message after 10 s of inactivity and
  tracks at most 16 concurrent partials (oldest evicted first) to bound
  pre-auth memory use against junk frames.
- **Kiosk allowlist** is `[...apps, self, "com.android.vending",
  "com.google.android.gms"]` (GMS is needed for Play's UI inside lock task,
  beyond what the spec listed) and `setLockTaskFeatures` only enables
  `SYSTEM_INFO` — `NOTIFICATIONS` requires `HOME` to also be enabled or AOSP
  throws `IllegalArgumentException`, so it's left off.
- **`KIOSK_ON` carries `apps` (non-empty allowlist) + optional `pinned`**
  (single app kept in front, must be `in apps`) — v1's single-`pkg` kiosk is
  gone. `PolicyManager.kioskOn` persists both into `KioskConfig` and always
  re-derives the lock-task allowlist as `apps + self + Play + GMS`; a
  `pinned` app that isn't installed/allowed is silently dropped (logged),
  never rejected outright.
- **Parent PIN is PBKDF2, never plaintext, and pushed as prefs, not a
  command.** `PinHash` (PBKDF2-HMAC-SHA256, 20k iterations, 32-byte output,
  16-byte random salt) hashes the PIN on the **controller**; only the salt +
  hash travel inside `ChildPrefs` on `SET_PREFS`. A correct PIN starts a
  10-minute pause (`KioskConfig.PAUSE_MS`) that the launcher `Activity`
  itself releases by calling `stopLockTask()` — `PolicyManager.pause()` only
  persists the deadline, it does not touch lock-task state. The pause is
  **not honoured across reboot**: `restoreKiosk()` unconditionally re-locks
  with the stored `apps`/`pinned` on `BOOT_COMPLETED`, ignoring any prior
  `pauseUntil`.
- **`SET_PREFS` is pushed after every successful auth and after every prefs
  change** (PIN set/changed/cleared, language/theme/accent), so a managed
  device that reconnects always gets the parent's current PIN hash — never
  rely on it being pushed only once.
- **`ManagedHomeActivity` is a launcher for both modes**: with child mode
  off it shows every launchable app (`ChildLauncherScreen`, header "All
  apps"); with child mode on it shows only the allowed apps (header "Child
  mode", lock icon when a PIN is set) — there is no separate "off" screen to
  maintain.
- **`ADD_ACCOUNT` is refused while kiosk is on** — Settings would be
  escapable from inside lock task, so the controller must send `KIOSK_OFF`
  first; this is on purpose, not a bug.
- **`STATUS` push cadence** is: on auth, after every command, on a battery
  change of ≥5 percentage points, and at least every 5 minutes — *not* on
  foreground-app or account change (no cheap signal exists for those; the
  5-minute timer covers them). A sealed `PING` goes out every minute in
  between. Acceptable for v1.
- **Session-loss recovery is write-driven.** `BluetoothGattServer.cancelConnection`
  does not reliably drop a central-initiated link and notifications do not
  reach a client after the server re-registers (verified on the emulator's
  rootcanal stack). So `SessionManager.onFrame` returns `false` for a key it
  has no session for, `ControllerService` answers that write with
  `GATT_FAILURE`, `BLEClient` surfaces it as `onWriteFailed`, and
  `ManagedLinkService` disconnects + rescans. `REHELLO` (plain) is the
  notify-side complement (sealed frame before auth / silent session probe)
  and `PING` bounds detection latency to ~60 s.
- **`BLEServerCallBack.onCharacteristicWriteRequest` takes a 4th `value:
  String` parameter** — use it, not `characteristic.value`, since the latter
  is a single field shared across all connected centrals and can be
  overwritten by a second concurrent write before the first write's callback
  runs.
- **`BLEClient.stopScan()` is public**, idempotent, and never throws — safe
  to call defensively from service lifecycle code.
- **Controller secret rotation** goes through
  `ControllerService.reloadIdentity(context)` (via a
  `startForegroundService` extra), which reloads identity **in place**
  without tearing down and rebuilding the running service.
- **Hotspot IP selection** prefers an interface address ending in `.1` (the
  local-only-hotspot gateway convention), polling briefly for it to appear
  rather than failing immediately if the interface isn't up yet.

## House rules for changes

- Keep `connectivity/ble` free of MDM-specific concepts — it's meant to stay
  reusable and is documented independently in
  `connectivity/ble/README.md`.
- Keep `protocol/` free of Android imports.
- Add dependencies via `gradle/libs.versions.toml`.
- Match the existing doc-comment style (KDoc on public classes/members) and
  logging style (`LOG_TAG` + `android.util.Log`).
- Don't reformat or churn unrelated files.
- BLE/DPM behavior can't be verified in CI; use the two-emulator rig in
  `docs/testing.md` (or real devices) and say which you used. Update
  `docs/testing.md` if a change alters the manual checklist.
