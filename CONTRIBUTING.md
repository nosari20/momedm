# Contributing to Môme DM

Thanks for taking an interest. This is a parental-control app that takes real
control of a child's phone, so the bar for correctness is higher than for a
typical side project — a bug here can leave a device either bricked in a lock
screen or wide open when a parent believes it is locked.

## Ground rules

- **Be honest about what you tested.** "I ran the unit tests" and "I watched it
  work on a device" are different claims. Say which one you are making.
- **Report what failed.** A PR that says "the reboot case still misbehaves, here
  is why" is far more useful than one that quietly omits it.
- **No new dependencies** without a discussion first. The app deliberately has a
  small dependency surface.

## Development setup

You need Android Studio (or just the SDK + JDK 17) and an Android device or
emulator on **API 34+**.

```bash
./gradlew assembleDebug           # build the debug APK
./gradlew installDebug            # build + install on a connected device
./gradlew :app:testDebugUnitTest  # JVM unit tests
```

On Windows, Gradle sometimes fails with `Unable to delete directory
…\app\build`. That is a file-lock quirk, not your code:

```bash
./gradlew --stop && rm -rf app/build && ./gradlew :app:assembleDebug
```

## Testing

Two layers, and they cover different things:

1. **JVM unit tests** (`app/src/test/`) cover everything that can be pure: the
   BLE framing and handshake, the secure channel, the lock schedule's date
   maths, persistence codecs, the command executor, and `LockController`'s
   decisions. If your change *can* be tested here, it must be.
2. **The two-emulator rig** covers what has no JVM fake: `DevicePolicyManager`
   lock task, `AlarmManager`, the broadcast receivers, and the BLE link itself.
   Two API 34+ emulators share an emulated Bluetooth stack, so the whole
   parent↔child flow runs between them. See [`docs/testing.md`](docs/testing.md)
   for the exact setup and the checks that are expected to pass.

Anything touching lock task, alarms, or provisioning **must** be exercised on
the rig before you open a PR, and your PR description should say what you ran
and what you saw. Two real defects in this codebase — a busy loop and a reboot
race that silently downgraded a complete lock — were invisible to code review
and only showed up on the rig.

## Architectural rules

These are enforced by review, and some by tests:

- `app/src/main/java/edu/fnosari/momedm/protocol/` is **pure Kotlin** — no
  Android imports. It is the layer the JVM tests lean on. (`java.time` is fine.)
- `connectivity/ble/` stays app-agnostic: it knows about GATT, not about
  parents and children.
- **Lock state is never persisted.** Whether a child device is locked is always
  recomputed from its inputs (schedule, manual lock, pause deadline, now). A
  stored "locked" flag survives a missed alarm or a clock change and strands the
  device — that is the bug class the design exists to prevent.
- Every user-facing string lives in **both** `values/strings.xml` and
  `values-fr/strings.xml`, key for key. `StringsParityTest` fails the build
  otherwise.
- Copy uses family vocabulary — *parent*, *child*, *night*. Never "MDM",
  "kiosk", "lock task", or "device owner" in anything a parent or child reads.
- Never log a PIN, a shared secret, or a BLE payload in clear.
- Core Material icons only. `material-icons-extended` compiled fine and then
  crashed at runtime on this Compose BOM.

## Pull requests

1. Branch from `main`.
2. Keep the change focused; unrelated refactors make review harder.
3. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest` before pushing.
4. Fill in the PR template, including what you verified and what you did not.

## Security issues

Please do **not** open a public issue for a vulnerability. See
[SECURITY.md](SECURITY.md).

## Licence

By contributing you agree that your contributions are licensed under the
Apache License 2.0, the same licence as the project.
