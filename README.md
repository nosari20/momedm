# Môme DM

## What it is

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

See `docs/testing.md` for the full manual walkthrough on two physical
devices.

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

Out of scope v1: lock/reboot/wipe, user restrictions, APK streaming over BLE,
sideload URL, silent Play install (requires registered EMM — not available to
a custom DPC).

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

BLE and device-owner behavior can be exercised between two API 33+ emulators
(emulated Bluetooth) — see the "Emulator test rig" section of
`docs/testing.md`; the QR/Setup-Wizard provisioning path and the hotspot need
real devices.

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
  device back on real Wi-Fi/mobile data afterward.
