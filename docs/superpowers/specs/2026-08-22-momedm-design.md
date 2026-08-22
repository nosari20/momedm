# Môme DM (BLE MDM) — design spec

Date: 2026-08-22
Status: approved in brainstorm, pending written review

## 1. Purpose

One Android app — **Môme DM**, package `edu.fnosari.momedm` (Android Studio project `momedm`) with two runtime roles:

- **Managed** — the app is **device owner** (fully managed device only; work
  profile is refused). It connects over BLE to a controller and executes
  management commands.
- **Controller** — the same app on another phone. It provisions managed devices
  via QR code, advertises a BLE GATT server, and sends commands to connected
  managed devices.

Role is picked at launch: `DevicePolicyManager.isDeviceOwnerApp(packageName)`
→ managed UI; otherwise controller UI. No role picker.

Transport is BLE only (no cloud, no Play EMM). BLE layer is the existing
`connectivity/ble` package from the BLEController project, copied and
re-namespaced, with four small extensions (§4.1).

### v1 command scope

| Command | Effect on managed device |
|---|---|
| `KIOSK_ON {pkg}` | Lock-task mode running `pkg` |
| `KIOSK_OFF` | Exit lock task, return to managed home |
| `INSTALL {pkg}` | Open Google Play listing (`market://details?id=pkg`); user taps Install |
| `ADD_ACCOUNT` | Open system "Add Google account" flow |
| `LIST_APPS` | Reply with installed launchable apps |
| `GET_STATUS` | Reply with status |

Out of scope v1: lock/reboot/wipe, user restrictions, APK streaming over BLE,
sideload URL, silent Play install (requires registered EMM — not available to a
custom DPC).

### Status reported by managed device

`kiosk` (bool), `kioskPkg`, `account` (Google account present), `battery` (%),
`currentApp` (foreground pkg; `unknown` if usage access not granted and kiosk
off), plus per-command `RESULT`.

## 2. Approach

Single Gradle module `app`. App scaffolding (activities, navigation, layouts,
theme, preferences, settings screens) is **reused from BLEController**
(`com.nosari20.blecontroller`) — copied and re-namespaced, same conventions:

- `activities/<name>/<Name>Activity.kt` = `ComponentActivity` + `setContent`,
  `enableEdgeToEdge`, theme wrapper, permission gate (`ButtonRequestPermission`
  list until all granted), then `Layout.BasicLayoutWithTopBarAndDrawer` +
  `NavHost` over an `activities/<name>/navigation/Routes` enum
  (`label: Int` string res, `icon: ImageVector`).
- `activities/<name>/screens/*Screen.kt` composables taking `navController`
  (+ view model); `activities/<name>/components/*` for banners/indicators.
- `activities/settings/SettingsActivity` with slide transitions,
  `SettingsMenu` categories, `SettingsScreen` driven by typed
  `Preference<T>` + `PreferencesProvider`, app-version row + easter egg.
- `ui/layouts/{Layout,BasicLayoutWithTopBar}.kt`, `ui/theme/*` (renamed
  `MomeDMTheme`), `persistence/preferences/*` (spelling fixed from
  `persistance`), `utils/AppVersion.kt`, private `LOG_TAG` companions,
  KDoc style, strings in `res/values/strings.xml`.

Packages:

```
edu.fnosari.momedm/
├── connectivity/ble/              # copied framework (+ extensions), app-agnostic
├── protocol/                      # pure Kotlin: framing, auth, messages (JVM-tested)
├── activities/
│   ├── main/MainActivity.kt       # launcher: DO? → ManagedHomeActivity, else controller UI
│   │   ├── ControllerViewModel.kt # link to ControllerService, device list/status StateFlows
│   │   ├── navigation/Routes.kt   # DEVICES, PROVISION
│   │   ├── screens/               # DevicesScreen, DeviceScreen, ProvisionScreen
│   │   └── components/            # ServiceBanner, OnlineIndicator, AppPickerDialog
│   ├── managed/                   # ManagedHomeActivity (HOME), ManagedViewModel,
│   │   │                          #   navigation/Routes (HOME), screens/HomeScreen,
│   │   │                          #   components/LinkBanner
│   │   └── provisioning/          # GetProvisioningModeActivity, PolicyComplianceActivity
│   └── settings/SettingsActivity.kt  # categories: Controller (id, regenerate secret),
│                                  #   Legal, Licenses, version/easter egg
├── managed/                       # non-UI: AdminReceiver, BootReceiver, ManagedLinkService,
│                                  #   PolicyManager, StatusCollector
├── controller/                    # non-UI: ControllerService, SessionManager, DeviceRegistry,
│                                  #   provisioning/{ProvisioningController, ApkHttpServer,
│                                  #   SignatureChecksum, QrPayloadBuilder}
├── persistence/preferences/       # Preference<T>, PreferencesProvider, DataStore impl
├── ui/{layouts,theme}/            # copied
└── utils/AppVersion.kt            # copied
```

Toolchain: AGP 9.3.1 (built-in Kotlin 2.2.x), Compose + Material3, minSdk 34,
targetSdk/compileSdk 37, Java 11, version catalog `gradle/libs.versions.toml`.
Project template is empty-activity (Views); Compose BOM, activity-compose,
navigation-compose, lifecycle-viewmodel-compose, datastore-preferences are
added. `NEARBY_WIFI_DEVICES` is the only Wi-Fi runtime permission needed
(minSdk 34 ≥ 33).
New deps: `com.google.zxing:core` (QR encode), `org.nanohttpd:nanohttpd`
(APK hosting), `kotlinx-serialization-json` (+ plugin). No camera.

Release/debug must produce a **single universal APK** (no splits) because the
controller serves its own `applicationInfo.sourceDir`.

## 3. Provisioning + QR

### 3.1 Controller "Provision" screen

1. **Wi-Fi source** (persisted choice):
   - *Hotspot*: `WifiManager.startLocalOnlyHotspot` → SSID/passphrase from
     `SoftApConfiguration`; controller IP = hotspot gateway. No internet.
     Needs `NEARBY_WIFI_DEVICES` runtime permission.
   - *Manual*: user enters SSID/pass of the LAN both phones share; controller IP
     = its current Wi-Fi address.
   - *Custom URL*: user-hosted https APK URL (fallback if SUW rejects local
     hosting); Wi-Fi fields still filled from manual entry.
2. **HTTP server** (NanoHTTPD, port 8080, bound while screen visible): `GET
   /momedm.apk` streams `applicationInfo.sourceDir` with `Content-Length`.
3. **Checksum**: base64url (no padding) of SHA-256 of the signing certificate
   (`PackageManager.getPackageInfo(GET_SIGNING_CERTIFICATES).signingInfo`).
4. **QR payload** (JSON, ZXing):

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "edu.fnosari.momedm/.managed.AdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "http://<ip>:8080/momedm.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "<base64url>",
  "android.app.extra.PROVISIONING_WIFI_SSID": "<ssid>",
  "android.app.extra.PROVISIONING_WIFI_PASSWORD": "<pass>",
  "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
  "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
    "controller_id": "<uuid>",
    "secret": "<base64 32 random bytes>"
  }
}
```

`QrPayloadBuilder` is pure Kotlin and unit-tested.

### 3.2 Managed device flow

Factory-reset device → Setup Wizard → tap welcome ×6 → scan QR → joins Wi-Fi →
downloads APK → app set as device owner. The app declares:

- `AdminReceiver : DeviceAdminReceiver` + `res/xml/device_admin.xml`.
- `GetProvisioningModeActivity` handling `ACTION_GET_PROVISIONING_MODE`:
  returns `PROVISIONING_MODE_FULLY_MANAGED_DEVICE` only. Work-profile requests
  finish with `RESULT_CANCELED`.
- `PolicyComplianceActivity` handling `ACTION_ADMIN_POLICY_COMPLIANCE`
  (setup wizard UI):
  1. read `PROVISIONING_ADMIN_EXTRAS_BUNDLE` → persist `controller_id`, `secret`;
     generate + persist `deviceId` (UUID).
  2. step **Add Google account** — `Settings.ACTION_ADD_ACCOUNT` with
     `EXTRA_ACCOUNT_TYPES=["com.google"]`; skippable.
  3. step **Grant usage access** — `Settings.ACTION_USAGE_ACCESS_SETTINGS`;
     skippable.
  4. finish `RESULT_OK`.
- `AdminReceiver.onProfileProvisioningComplete` as fallback persistence of
  extras (older flows).
- After provisioning: `addPersistentPreferredActivity` (HOME/MAIN) → self;
  start `ManagedLinkService`.

Account management is left enabled (no `DISALLOW_MODIFY_ACCOUNTS`, no
`setAccountManagementDisabled`) so Play works with a personal account.

### 3.3 Risks (on-device verification required)

- ManagedProvisioning on a Wi-Fi with no internet (local-only hotspot) may
  refuse to download. Fallback = manual LAN mode.
- `http://` download URL acceptance varies by OEM/Android version; HTTPS would
  require a cert the managed device trusts — not doable self-hosted. Fallback =
  Custom URL mode.
- Foreground-app detection requires usage access toggle; DO cannot self-grant
  reliably → skippable setup step + `unknown` fallback.

## 4. BLE + protocol

### 4.1 Framework extensions (`connectivity/ble`)

Copied verbatim from BLEController, re-namespaced, plus:

1. `BLECharacteristic.Permission.NOTIFY` → `PROPERTY_NOTIFY` (+ CCCD
   descriptor added server-side for notify characteristics).
2. `BLEClient.requestMtu(517)` after connect; `onMtuChanged` exposes the
   negotiated MTU via callback `onMtuChanged(mtu: Int)`.
3. `BLEServer.notifyDevice(device, service, characteristic)` — per-device
   notify (current `updateCharacteristic` notifies all).
4. Advertise by **service UUID** (`AdvertiseData.addServiceUuid`,
   `setIncludeDeviceName(false)`); `BLEClient` gets a constructor variant that
   scan-filters on service UUID instead of name.

Values remain `String`; payloads are ASCII JSON so char-based chunking is
byte-safe. Framework stays app-agnostic (no MDM terms).

### 4.2 GATT layout

| Item | UUID (fixed, generated once in code) | Role |
|---|---|---|
| `MdmService` | custom 128-bit | primary |
| `CmdCharacteristic` | custom | NOTIFY, controller → managed |
| `RspCharacteristic` | custom | WRITE, managed → controller |

### 4.3 Framing

Each logical message is ASCII JSON, split into chunks:
`"<msgId>:<idx>/<count>:<chunk>"`, `msgId` = 4 hex chars per direction,
chunk size = `mtu − 3 − headerLen` (fallback MTU 23 → ~10 payload chars).
Client sends its negotiated MTU in `HELLO`; server sizes chunks per device.
`Framer`/`Reassembler` are pure Kotlin; incomplete messages time out after 10 s.

### 4.4 Auth + integrity

Shared `secret` (32 B) from QR. HMAC-SHA256 throughout.

```
C→S  HELLO     {deviceId, model, nonceC, mtu}
S→C  CHALLENGE {nonceS, proof = HMAC(secret, "momedm/challenge|" + nonceC)}
C→S  AUTH      {proof = HMAC(secret, "momedm/auth|" + nonceS)}
sessionKey = HMAC(secret, "momedm/session|" + nonceC + "|" + nonceS)
```

Every HMAC is **domain-separated** by a distinct constant prefix. Without it the
three computations share one key and one input space, and the CHALLENGE proof is
an oracle: whoever sends the HELLO picks `nonceC` freely, so a forged HELLO with
`nonceC = realNonceC + nonceS` would come back with exactly the value the old
`HMAC(secret, nonceC || nonceS)` session key had — disclosing the session key
without ever knowing the secret. The tags make the three input spaces disjoint.

Both endpoints also **validate the peer's nonce before any HMAC is computed**: a
`nonceC` (controller side, on HELLO) or `nonceS` (managed side, on CHALLENGE)
that is not exactly 32 lower-case hex chars — the shape `Crypto.randomHex(16)`
produces — is a protocol error and resets the session.

After auth, every message is `{seq, body, mac}` with
`mac = HMAC(sessionKey, dir|seq|body)` with `dir = 'C'` for controller→managed
and `'M'` for managed→controller; `seq` monotonic per direction starting at 1.
Binding the mac to a direction tag stops a captured sealed message from being
replayed back at its own sender and accepted as if it came from the peer.
Bad MAC / non-increasing seq / unknown msg before auth → disconnect and reset
the whole session (handshake + channel), so no captured handshake or sealed
frame from before the error can be replayed to re-derive or reuse it.
Managed device on auth failure keeps scanning (another controller may be
nearby). Controller drops unauthenticated connections after 5 s.

### 4.5 Messages

Managed → controller: `HELLO`, `AUTH`, `STATUS{kiosk,kioskPkg,account,battery,
currentApp}`, `APPS[{pkg,label}]`, `RESULT{cmdId,ok,msg}`.
Controller → managed: `CHALLENGE`, `CMD{id,type,args}` with types
`KIOSK_ON{pkg}`, `KIOSK_OFF`, `INSTALL{pkg}`, `ADD_ACCOUNT`, `LIST_APPS`,
`GET_STATUS`.

`STATUS` is pushed on auth complete, on any change (kiosk, account, battery
±5 %, foreground app), and at least every 5 min.

`protocol/` has no Android imports (kotlinx-serialization + `javax.crypto`).

## 5. Managed side

- **`ManagedLinkService`** (foreground, persistent notification): owns
  `BLEClient`; scan by service UUID (30 s timeout → rescan), connect,
  `requestMtu`, handshake, dispatch `CMD` → `PolicyManager`, send `RESULT`,
  push `STATUS`. Reconnect on drop with backoff 2 s → 30 s. Started by
  `BootReceiver` (`BOOT_COMPLETED`) and after provisioning.
- **`PolicyManager`** (DPM wrapper):
  - kiosk on: `setLockTaskPackages([pkg, self, "com.android.vending"])`,
    `setLockTaskFeatures(HOME|NOTIFICATIONS|SYSTEM_INFO)` as needed, launch
    `pkg` with `ActivityOptions.setLockTaskEnabled(true)`; persist.
  - kiosk off: `setLockTaskPackages([])` (forces exit), launch self; persist.
  - install: `ACTION_VIEW market://details?id=pkg` (`com.android.vending`
    allowlisted so it opens inside kiosk).
  - add account: `Settings.ACTION_ADD_ACCOUNT` (`com.google`).
  - boot: if `kioskOn` persisted → re-enter kiosk.
- **`StatusCollector`**: `BatteryManager`, `AccountManager.getAccountsByType
  ("com.google")` (DO has account visibility), `UsageStatsManager` events
  (if `PACKAGE_USAGE_STATS` op granted) else `kioskPkg`/`unknown`, launchable
  apps via `queryIntentActivities(MAIN/LAUNCHER)`.
- **`ManagedHomeActivity`** (`activities/managed`, manifest `HOME` + `DEFAULT`
  intent filter, no LAUNCHER): same pattern as BLEController `MainActivity` —
  permission gate (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`), then
  `Layout.BasicLayoutWithTopBarAndDrawer` + `NavHost` over `Routes { HOME }`;
  `LinkBanner` (copy of `ConnectionBanner`) shows scanning/disconnected;
  `HomeScreen` shows status card + buttons "Add Google account", "Grant usage
  access". `ManagedViewModel` exposes link state + status as `StateFlow`
  (bound to `ManagedLinkService`).
- **Persistence** (`DataStore` prefs): `controllerId`, `secret`, `deviceId`,
  `kioskPkg`, `kioskOn`.

## 6. Controller side

- **`ControllerService`** (foreground): `BLEServer` (`clientLimit` 7) +
  `SessionManager` (`BluetoothDevice` → session: auth state, nonces, seqs,
  `deviceId`, MTU). Advertising runs while service runs; user toggles from
  Devices screen.
- **Registry** (`DataStore`, JSON): `devices[{deviceId, model, lastSeen,
  lastStatus}]`. Online = session authenticated.
- **Provisioning** (`ProvisioningController`): hotspot / manual / custom-URL
  mode, `ApkHttpServer`, `SignatureChecksum`, `QrPayloadBuilder`, QR bitmap.
- **`MainActivity`** (`activities/main`, LAUNCHER): in `onCreate`, if
  `isDeviceOwnerApp` → `startActivity(ManagedHomeActivity)` + `finish()`.
  Otherwise BLEController `MainActivity` pattern: permission gate
  (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`,
  `POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES`), then
  `Layout.BasicLayoutWithTopBarAndDrawer` (right action: `OnlineIndicator`
  (n online) + Settings icon → `SettingsActivity`) + `ServiceBanner`
  (advertising stopped) + `NavHost` over `Routes { DEVICES, PROVISION }`;
  `DeviceScreen` reached via `"device/{deviceId}"` route. `ControllerViewModel`
  binds `ControllerService`, exposes registry + sessions as `StateFlow`.
- **Screens** (`activities/main/screens`):
  - *DevicesScreen*: list with online dot, advertising toggle, FAB → Provision.
  - *DeviceScreen*: status card; Kiosk ON → sends `LIST_APPS`, `AppPickerDialog`
    → `KIOSK_ON`; Kiosk OFF; Install → pkg text field → `INSTALL`; Add account;
    Refresh (`GET_STATUS`); `RESULT` shown as snackbar.
  - *ProvisionScreen*: Wi-Fi mode, server state/IP, QR.
- **`SettingsActivity`** (`activities/settings`, copied): `SettingsMenu`
  categories → *Controller* (`SettingsScreen` with controller id read-only +
  "Regenerate secret" action w/ confirm dialog: disconnects all provisioned
  devices until re-provisioned), *Legal*, *Licenses*; version row + easter egg
  kept.
- **Identity**: `controllerId` UUID + `secret` generated on first launch,
  persisted.

## 7. Error handling

- BLE exceptions (`BLEException`) surface as UI state, never crash services.
- Unauthenticated / bad-MAC sessions closed; managed keeps scanning.
- Command failures → `RESULT{ok=false,msg}` (e.g. pkg not installed, lock task
  refused, no Play Store).
- Provisioning hosting errors (hotspot denied, port busy) shown on Provision
  screen with retry.
- Reassembler drops stale partial messages (10 s).

## 8. Testing

- **JVM unit tests** (`./gradlew :app:testDebugUnitTest`): `Framer`/
  `Reassembler` (chunk sizes incl. MTU 23, ordering, timeouts), handshake state
  machines both sides (good/bad secret, replay, out-of-order seq), message codec
  round-trips, `QrPayloadBuilder`, checksum base64url, `SessionManager` logic
  (via interface, no Bluetooth types).
- **On-device manual checklist** (`docs/testing.md`): provisioning via hotspot
  and via LAN; handshake; each command; reconnect after BT off/on and reboot;
  kiosk survives reboot; two managed devices concurrently; wrong-secret
  controller ignored.
- BLE/DPM cannot run on emulator/CI — call out in every relevant PR.

## 9. Open questions (none blocking)

- Whether SUW accepts no-internet hotspot / `http://` (see §3.3) — decides
  default Provision mode after first device test.
