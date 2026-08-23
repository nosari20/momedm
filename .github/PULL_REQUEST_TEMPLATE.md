# What this changes

<!-- One or two sentences. Link the issue if there is one. -->

## Why

<!-- What problem it solves, from a parent's or child's point of view where that applies. -->

## How it was verified

<!--
Be specific and be honest. "Ran the unit tests" and "watched it work on a
device" are different claims — make the one that is true.
-->

- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes
- [ ] New behaviour is covered by JVM tests where it can be
- [ ] Exercised on the two-emulator rig (required for anything touching lock
      task, alarms, provisioning, or the BLE link) — say what you ran and saw:

<!-- e.g. "Enabled a night window starting in 1 min; child locked into the
bedtime screen; `am start` on Chrome rejected; PIN paused it; it re-locked
after the pause lapsed." -->

**Anything you could not verify, or that still fails:**

<!-- Say so here. An honest gap is worth more than a silent one. -->

## Checklist

- [ ] New user-facing strings are in **both** `values/strings.xml` and
      `values-fr/strings.xml`, key for key
- [ ] Copy uses family vocabulary (no "MDM", "kiosk", "lock task", "device owner")
- [ ] No PIN, secret, or BLE payload is logged in clear
- [ ] No new dependencies (or discussed in an issue first)
- [ ] `protocol/` still has no Android imports
- [ ] No lock decision is persisted — lock state is still recomputed from its inputs
