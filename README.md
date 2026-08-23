<div align="center">

# Môme DM

**A parental-control app for families that runs entirely between two phones.**

No cloud. No account. No subscription. A parent's phone talks to a child's phone
over Bluetooth Low Energy, and nothing else is involved.

[![CI](https://github.com/nosari20/momedm/actions/workflows/ci.yml/badge.svg)](https://github.com/nosari20/momedm/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-14%2B%20(API%2034)-3ddc84.svg)](https://developer.android.com)
[![Languages](https://img.shields.io/badge/i18n-English%20%7C%20Fran%C3%A7ais-informational.svg)](app/src/main/res)

<img src="docs/images/child-launcher-childmode.png" width="220" alt="The child's home screen showing only the allowed apps">
<img src="docs/images/child-bedtime.png" width="220" alt="The bedtime screen during a night lock">
<img src="docs/images/parent-device.png" width="220" alt="The parent's view of one child device">

</div>

---

## What it is

Most parental-control tools route a child's activity through someone else's
servers. Môme DM doesn't. It is one Android app installed on two phones:

- On the **child's phone** it is the device owner — it becomes the home screen,
  decides which apps exist, and can lock the device completely.
- On the **parent's phone** it is a small control panel that talks to the child's
  phone directly over BLE when they are near each other.

Everything the child's phone enforces keeps working when the parent is out of
range, asleep, or has a flat battery. The parent's phone is a remote control, not
a dependency.

> **What this is not.** A parenting aid, not a security product. A factory reset
> removes it, and a determined teenager will eventually find the edges. It is
> built for the ordinary case: agreeing on rules and letting the phone hold them.

## Features

### Choose which apps exist

The parent picks the apps their child may use. Everything else is not on the home
screen and cannot be launched — this is Android's lock task mode, so Back and
Home cannot escape it. Optionally pin the child to **one single app**, useful
when handing a phone over for a specific purpose.

<div align="center">
<img src="docs/images/parent-apps-picker.png" width="245" alt="Choosing which apps the child may use">
<img src="docs/images/child-launcher-allapps.png" width="245" alt="The child's home screen with child mode off">
</div>

### A home screen built for a child

Not a cut-down admin console: a big clock, a greeting that follows the time of
day, large rounded app tiles that respond to a press, and a small dot showing
whether the parent's phone is nearby. Meant to feel normal for a child up to
about 14 — friendly without being babyish.

There is no visible lock button, deliberately. A parent unlocks by
**long-pressing the header**, which opens the PIN pad. A child looking at the
screen finds nothing to poke at.

### Night lock — a bedtime the phone keeps on its own

Set a bedtime window and the phone locks itself: no apps, just a quiet screen
with the time and when it opens again. Separate windows for school nights and for
Friday and Saturday, because those differ in most houses. The parent can also
lock the phone **right now** from their own device.

<div align="center">
<img src="docs/images/parent-time-picker.png" width="245" alt="Setting the bedtime window">
<img src="docs/images/child-pin-dialog.png" width="245" alt="The parent PIN dialog on the child's phone">
</div>

The lock is decided on the child's phone from the schedule it was given, so it
works with the parent nowhere nearby, survives a reboot, and follows the clock if
the timezone changes. Under the hood it is deliberately stateless: the phone
recomputes *am I locked?* from the schedule every time rather than remembering an
answer — so a missed alarm or a changed clock cannot strand it in the wrong state.

### A PIN that buys ten minutes

Type the parent PIN on the child's phone and the lock pauses for ten minutes —
enough to check something or deal with an exception — then it re-locks itself.
The parent can end the pause early.

<div align="center">
<img src="docs/images/child-paused.png" width="245" alt="The child's phone during a ten-minute pause">
<img src="docs/images/parent-pin.png" width="245" alt="Setting the parent PIN">
</div>

The PIN is hashed (PBKDF2-HMAC-SHA256, 20 000 iterations, per-device salt) on the
parent's phone before it is ever sent; the child's phone stores and compares only
the hash. Wrong guesses trigger a lockout that grows and survives killing the app.

### The parent's view

Which apps are allowed, what the child is using right now, battery, when the
phone was last seen, whether it is locked — with the controls to change any of it.

<div align="center">
<img src="docs/images/parent-children.png" width="245" alt="The list of children">
<img src="docs/images/parent-settings.png" width="245" alt="Settings">
</div>

### The parent chooses how it looks

Theme, accent colour and language are picked on the parent's phone and pushed to
the child's, so a child's phone shows the family's choices rather than its own —
including the language. Fully bilingual, **English and French**, with the two
string files kept key-for-key in sync by a test that fails the build otherwise.

<div align="center">
<img src="docs/images/parent-appearance.png" width="245" alt="Theme, accent colour and language">
</div>

### Setting up a child's phone

From a factory-reset phone: tap the welcome screen six times, scan the code the
parent's app shows, and the child's phone downloads the app and installs it as
device owner. The parent's phone can even provide the Wi-Fi itself through a
local hotspot, so no existing network is needed.

<div align="center">
<img src="docs/images/parent-provision.png" width="245" alt="The enrolment screen with the pairing code">
</div>

*The pairing code and Wi-Fi password are blurred in this screenshot on purpose —
that code carries the shared secret, so treat the real one as sensitive while it
is on screen.*

## Requirements

- Two Android phones on **Android 14 (API 34)** or newer.
- The child's phone must be **factory reset** to be enrolled — Android grants
  device-owner rights only during initial setup, before any account is added.
- Bluetooth on both. No internet, Google account or Play services are needed for
  day-to-day use.

## Installing

```bash
git clone https://github.com/nosari20/momedm.git
cd momedm
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Install that APK on the parent's phone and open it, then follow the **enrolment**
screen for the child's phone. [`docs/testing.md`](docs/testing.md) has the full
walkthrough, including the emulator route if you want to try it without two
handsets.

## How it works

```
Parent phone                                  Child phone
┌──────────────────────────┐                  ┌──────────────────────────┐
│ ControllerService         │   BLE GATT       │ ManagedLinkService        │
│  advertises, holds        │◄────────────────►│  scans, connects,         │
│  sessions, sends commands │   MdmService     │  applies policy           │
└──────────────────────────┘                  └──────────────────────────┘
                                                          │
                                                DevicePolicyManager
                                          (lock task, home, restrictions)
```

The parent's phone runs a BLE GATT server; the child's phone scans for it and
connects. On top sits a small protocol: messages are chunked to the negotiated
MTU, a mutual HMAC-SHA256 handshake derives a session key, and every message
afterwards carries a per-direction MAC and a monotonic sequence number, so a
captured frame cannot be replayed — in either direction.

The `protocol/` package is pure Kotlin with no Android imports, which is why the
framing, handshake, secure channel and lock schedule are all covered by ordinary
JVM tests.

For the deeper version — the exact handshake, the framing format, the commands,
and the threat model with its limits — see
[`docs/architecture.md`](docs/architecture.md).

## Testing

```bash
./gradlew :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:assembleDebug       # build the APK
```

Two layers, covering different things:

- **JVM tests** cover everything that can be pure — protocol framing, the
  handshake, the secure channel, the lock schedule's date maths (including DST),
  persistence, command dispatch, and the lock controller's decisions.
- **A two-emulator rig** covers what has no JVM fake: `DevicePolicyManager` lock
  task, `AlarmManager`, the broadcast receivers, and the BLE link itself. Two
  emulators share an emulated Bluetooth stack, so the whole parent↔child flow
  runs between them.

That second layer is not ceremony. Two real defects here — a busy loop, and a
reboot race that silently downgraded a complete lock so apps could launch while
the bedtime screen still claimed the phone was locked — were invisible to unit
tests and to code review, and only surfaced on devices. See
[`docs/testing.md`](docs/testing.md).

## Known limitations

Stated plainly, because a parental-control tool that oversells itself is worse
than useless:

- **Emergency calling under a night lock is unverified.** The app keeps Android's
  power menu (and its Emergency entry) enabled while locked, but this could not be
  confirmed on the emulator images used so far and still needs checking on a real
  handset.
- **A night lock cannot currently be ended remotely** — only by the parent PIN on
  the child's phone, or by waiting for the window to close.
- **One shared secret per parent**, not per child. Compromising one child's phone
  exposes the others until the parent regenerates the secret and re-enrols.
- **No silent app installation.** The parent can open a Play listing on the
  child's phone; someone still has to tap Install. Silent installation requires
  being a registered enterprise EMM.
- **The BLE link is authenticated, not encrypted.** Messages cannot be forged or
  replayed, but they are not confidential to someone sniffing the radio.
- **Physical access wins.** A factory reset removes the app entirely.

## Contributing

Contributions are welcome — especially real-device reports for the gaps above.
Read [CONTRIBUTING.md](CONTRIBUTING.md) first: it covers the architectural rules
(lock state is never persisted, `protocol/` stays Android-free, strings live in
both languages) and what "I tested it" has to mean here.

Security issues go through [SECURITY.md](SECURITY.md), not the public tracker.

This repository also ships [Claude Code](https://claude.com/claude-code)
configuration in [`.claude/`](.claude/) — skills describing the emulator rig, how
to add a management command end-to-end, and the string-parity rules. Useful
reading even if you never run Claude.

## Licence

[Apache License 2.0](LICENSE) — see [NOTICE](NOTICE).
