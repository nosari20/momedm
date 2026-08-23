# Architecture and protocol

Technical reference for Môme DM. For what the app does and what it looks like,
see the [README](../README.md); for contributor rules see
[CONTRIBUTING.md](../CONTRIBUTING.md).

## The two roles

Môme DM is a single Android app, package `edu.fnosari.momedm`, with two runtime
roles selected automatically at launch (`DevicePolicyManager.isDeviceOwnerApp`):

- **Managed** — the app is **device owner** on a fully managed device (work
  profile is refused). It connects over BLE to a controller and executes
  management commands.
- **Controller** — the same app on another phone. It provisions managed
  devices via QR code, advertises a BLE GATT server, and sends commands to
  connected managed devices.

There is no role picker and no cloud/Play EMM involved — the transport is BLE
only.

The app is fully localized in **French and English**; the parent picks
language, light/dark theme, and an accent colour under **Settings →
Appearance & language**, and every choice pushes to connected children live
(`SET_PREFS`) — a child device always shows its parent's chosen look, not its
own setting.

## Architecture

```
Controller phone                              Managed phone
┌─────────────────────────┐                   ┌─────────────────────────┐
│ ControllerService        │   BLE GATT        │ ManagedLinkService       │
│  BLEServer (advertiser,  │◄─────────────────►│  BLEClient (scan by      │
│  clientLimit 7)          │   MdmService       │  service UUID, connect) │
│  SessionManager          │   Cmd (NOTIFY)     │  ManagedEndpoint        │
│  ControllerEndpoint       │   Rsp (WRITE)      │  handshake, session key │
└─────────────────────────┘                   └─────────────────────────┘
```

Protocol layers, bottom to top:

1. **Frames** (`protocol/Framer.kt`) — each logical message is chunked to fit
   the negotiated ATT MTU: `"<msgId>:<idx>/<count>:<chunk>"`. `Reassembler`
   reassembles chunks per `msgId`, discards a partial after a 10 s idle
   timeout, and tracks at most 16 concurrent partial messages so a flood of
   junk frames cannot exhaust memory before auth.
2. **Envelope** (`protocol/SecureChannel.kt`) — once authenticated, every
   message is wrapped as `{seq, body, mac}` and sealed/opened by
   `SecureChannel`.
3. **Messages** (`protocol/Messages.kt`) — the typed sealed hierarchy (`Hello`,
   `Challenge`, `Auth`, `AuthOk`, `Status`, `Apps`, `Result`, `Cmd`, …) encoded
   as JSON by `MessageCodec`.

`connectivity/ble` (the GATT client/server framework) and `protocol` (framing,
handshake, secure channel, endpoints) are pure/app-agnostic layers; `managed/`
and `controller/` wire them to Android (DevicePolicyManager, foreground
services, DataStore).

## Provisioning walkthrough

1. On the **controller**, open the **Provision** screen and pick a Wi-Fi
   source: *Hotspot* (local-only hotspot, no internet needed), *Manual*
   (shared LAN), or *Custom URL* (self-hosted https APK, no local server).
2. Tap **Generate QR** — the controller starts an HTTP server (hotspot/manual
   modes) serving its own APK and encodes a JSON provisioning payload (device
   admin component, download URL, signing-certificate checksum, Wi-Fi
   credentials, controller id + shared secret) into a QR code.
3. On the **managed** device (factory reset), the Setup Wizard welcome screen
   is tapped 6× to enter QR provisioning, then the QR is scanned.
4. The device joins the Wi-Fi, downloads the APK, and Android sets Môme DM as
   device owner.
5. The app's provisioning wizard runs: **Add Google account** step, then
   **Grant usage access** step (both skippable), then the managed home screen
   is shown as device HOME.
6. Before finishing, the child **grants itself** the runtime permissions it needs
   to reach the parent (see below), and the link service starts scanning.

See [`testing.md`](testing.md) for the full manual walkthrough on two physical
devices.

### Two platform requirements that fail silently

Both of these produce a hang with no error on either screen, so they are worth
knowing before debugging a pairing that "just doesn't work".

**Local network access (parent, Android 16+).** Serving the APK to the child is
local-network traffic, which newer Android gates behind the *dangerous* runtime
permission `android.permission.ACCESS_LOCAL_NETWORK`. Without the grant the
platform completes the TCP handshake and then discards the traffic: the socket
accepts, the request never reaches `serve()`, and the download times out. The
pairing screen asks for it in context and refuses to generate a code until it is
granted. Note that a successful TCP connect proves nothing here — only that the
kernel accepted into the listen backlog.

**Runtime BLE permissions (child).** `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and
`POST_NOTIFICATIONS` are runtime permissions, and nobody will ever tap a
permission dialog on a child's phone: it is handed to a child, the link service
starts at boot with no one looking, and without `BLUETOOTH_SCAN` the client never
starts scanning at all (`ERROR_BLUETOOTH_SCAN_PERMISSION_NOT_GRANTED`). From the
parent that is indistinguishable from a failed pairing. Because the app is the
device owner it grants its own, via
`DevicePolicyManager.setPermissionGrantState` — at the end of provisioning and
again on every service start, so a device enrolled by an older build heals itself
rather than needing re-enrolment.

### Which address the QR advertises

In hotspot mode the phone has two `wlan*` interfaces — the network it is joined
to and the access point it is hosting — and nothing in their names says which is
which. Android's local-only hotspot also picks a *randomised* subnet and does not
necessarily take the `.1` of it, so a "gateway address" heuristic finds nothing
and plain interface-name ordering picks the client interface. The QR then tells
the child to fetch the APK from an address that does not exist on the network it
just joined.

`NetUtils` therefore excludes the client Wi-Fi address, found via
`ConnectivityManager` (which knows which network carries the Wi-Fi transport),
and skips 464XLAT clat addresses (`192.0.0.0/29`) that appear alongside mobile
data. Verified on a real pair: parent AP `10.47.248.185`, child joined at
`10.47.248.127`, 32 MB APK served over the hotspot.

Changing the mode, network or URL discards any code already on screen and tears
down the server, so a stale QR encoding the previous settings cannot be scanned.

## Commands

| Command | Effect on managed device |
|---|---|
| `KIOSK_ON {apps, pinned?}` | Child mode on: lock task with `apps` allowed; if `pinned` (must be in `apps`) is set, the launcher keeps bouncing back into that one app |
| `KIOSK_OFF` | Child mode off: exit lock task, launcher shows every installed app |
| `SET_PREFS {prefs}` | Push language/theme/accent and the parent-PIN hash+salt (or clear it); applied immediately and re-sent after every reconnect |
| `INSTALL {pkg}` | Open Google Play listing (`market://details?id=pkg`); user taps Install |
| `ADD_ACCOUNT` | Open system "Add Google account" flow |
| `LIST_APPS` | Reply with installed launchable apps |
| `GET_STATUS` | Reply with status |
| `SET_SCHEDULE {schedule}` | Push the nightly complete-lock window (weekday + weekend); sanitized on receipt, then the lock is re-evaluated immediately |
| `LOCK_NOW` | Lock the device completely, right now, until the parent unlocks |
| `UNLOCK` | Clear a manual lock (a *night* window keeps its own schedule — see below) |

`Status` carries `locked`, `lockReason` (`night` / `manual` / null), `lockUntil`
and the child's current `schedule`, so the parent can render real values after a
restart rather than guessing.

Out of scope: reboot/wipe, user restrictions, APK streaming over BLE, silent
Play install (requires a registered EMM — not available to a custom DPC).

### Child mode

`ManagedHomeActivity` is the managed device's launcher in both states: with
child mode off it lists every installed app; with child mode on ("Child
mode" header) it shows only the parent-chosen `apps`, running under Android
lock task so Back/Home can't escape it. If the parent picked a **pinned**
app, the launcher relaunches into it after a short (1.5 s) grace period — so the parent-PIN lock icon stays reachable — and keeps bouncing back
whenever the child returns to the launcher (e.g. via Home).

If the parent has set a PIN (Settings → Controller → *Set PIN*), a lock icon
in the header opens a PIN prompt; a correct PIN pauses child mode for 10
minutes (lock task is released, all apps become reachable, a countdown
banner shows) without turning it off — the parent can end the pause early
with *Lock again*, or a wrong PIN triggers a growing lockout. The pause does
**not** survive a reboot: the device re-locks with the same `apps`/`pinned`
on boot. The PIN itself is hashed (PBKDF2) on the controller before it's
sent — the managed device only ever stores/compares the hash, never the
plaintext PIN.

### Complete lock (night schedule and manual)

Separate from child mode and independent of it: a child device can be locked
**completely** on a nightly schedule, or on the parent's command. While locked it
shows a bedtime screen with no apps at all, and only this app is launchable.

The design property that matters: **lock state is never persisted.** It is a pure
function of `(schedule, manualLock, pauseUntil, now)`, recomputed by
`LockController.reevaluate()` at every trigger — an alarm, boot, a clock or
timezone change, the launcher resuming, a PIN pause ending, and each of the three
commands. Alarms only *wake* the device to re-evaluate; they never set state. A
missed, stale or duplicated alarm is therefore harmless, and no stored flag can
survive a reboot or a clock change to strand the device in the wrong state.

`LockSchedule` (in `protocol/`, pure Kotlin, `java.time` only) holds the window
arithmetic and is unit-tested without Android:

- a window **wraps midnight** when start > end (21:00 → 07:00); start < end is a
  legal same-day window;
- a night **belongs to the day it starts**, so windows starting Friday or Saturday
  use the weekend times and Sunday–Thursday use the weekday times;
- `start == end` disables that day type rather than locking for 24 hours.

`LockController` is the **single authority**: it is the only component that knows
a complete lock outranks ordinary child mode. Every path that restores or applies
lock-task policy goes through it — boot, the service's pause watchdog, the
launcher's pause tracking, and `KIOSK_ON`/`KIOSK_OFF`. Two production defects came
from paths that bypassed it and silently downgraded a complete lock to the
child-mode allowlist while the bedtime screen still claimed the device was locked;
both are covered by `LockControllerTest`.

A correct parent PIN pauses a complete lock exactly as it pauses child mode (10
minutes, then it re-locks if the window is still open). `lockComplete()` sets
`LOCK_TASK_FEATURE_GLOBAL_ACTIONS` alongside `SYSTEM_INFO`, unlike `kioskOn`,
so the power menu — and through it the system emergency dialer — stays reachable
on a locked device. That path is **unverified on real hardware**; see the README's
limitations.

## Security

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

What is **not** encrypted: BLE link-layer traffic itself (no LE Secure
Connections pairing is used — the app-layer HMAC handshake and per-message
MAC are the actual trust boundary), and the provisioning QR code — anyone who
photographs it during the provisioning window learns the shared secret, so
treat the QR screen as sensitive while it's on screen.

## Building

```bash
./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # build + install on a connected device
./gradlew :app:testDebugUnitTest # JVM unit tests (protocol, controller, persistence, managed)
```

BLE and device-owner behaviour can be exercised between two API 34+ emulators
(emulated Bluetooth) — see the "Emulator test rig" section of
[`testing.md`](testing.md). What the rig cannot cover, and must be checked on real
hardware: the QR/Setup-Wizard provisioning path, the hotspot, the Android 16+
local-network permission, and the power-menu emergency path under lock task (on
the API 35 image, long-press power opens Assistant rather than the classic power
menu, so the check is inconclusive there).

## Known limitations

- v1 uses one shared secret for all devices provisioned by a controller;
  compromise of one device's secret exposes the fleet until `Regenerate secret`
  + re-provisioning. Per-device secrets are planned.
- No silent Play install: `INSTALL` opens the Play listing for the user to
  tap Install — a custom (non-registered-EMM) DPC cannot install silently.
- Usage access (for `currentApp` in `STATUS`) is optional and skippable
  during provisioning; without it the managed device reports the kiosk
  package while kiosk is on, or nothing (shown as "—" in both UIs) otherwise.
- The provisioning download URL defaults to plain `http://` (the controller's
  self-hosted APK server); SUW's acceptance of `http://` and of a no-internet
  hotspot network varies by OEM/Android version — fall back to Manual (shared
  LAN) or Custom URL (self-hosted https) mode if the QR fails to provision.
- Hotspot mode has no internet — only useful for the APK download step; any
  step requiring internet (e.g. Play Store operations) needs the managed
  device back on real Wi-Fi/mobile data afterward. Some OEMs (Samsung among
  them) prompt "this network has no internet" when joining, and the parent has
  to accept it during enrolment.
- Emergency calling under a complete lock is **unverified**. `lockComplete()`
  keeps `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` enabled so the power menu — and the
  system emergency dialer through it — stays reachable, but this could not be
  confirmed on the emulator images used so far and still needs a real handset.
- A night lock cannot currently be ended remotely: only the parent PIN on the
  child's device, or waiting for the window to close. The parent's Unlock button
  is shown only for a manual lock, so it never silently does nothing.
- Some shell tooling is unavailable on OEM builds when testing: Samsung blocks
  `cmd wifi connect-network` for the shell user, so joining a test hotspot has to
  be driven through the Wi-Fi UI.
