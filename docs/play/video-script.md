# Demo videos — justifying device-owner (DPC) use and the child audience

Play’s review of this app hinges on two sensitive positions: it is a
**Device Policy Controller** (device-owner app), and it is a **monitoring
tool used openly inside a family** (`isMonitoringTool = child_monitoring`,
target audience **adults** — the Play customer is the parent; see
`policy-forms.md` §2, and keep that story consistent everywhere). Reviewers
ask for a video demonstrating the functionality that justifies each
capability. These recordings, captured on the two-emulator BLE rig, are that
demonstration: legitimate device-owner use, and the anti-stalkerware posture
— visible, disclosed, child-readable — shown on screen.
The child UI is **in English** — reviewers read English; the same flows in
French are covered by the fr screenshot set.

## Files

| File | Length | Shows |
|---|---|---|
| `assets/video/demo-child.mp4` | ~75 s | The child’s phone: kiosk launcher → parent-triggered complete lock (“Screen break”, visible **Emergency call** button) → unlock → the **bedtime lock** engaging (“Good night!”, the real moon) → unlock |
| `assets/video/demo-parent.mp4` | ~33 s | The parent’s phone driving a lock/unlock round of the same session |

(The parent capture is 720×1280 — the emulator’s encoder rejects its
native resolution.)

Two evidence gaps a reviewer may still probe, and why they are not in
these clips:

- **The notification shade** cannot be pulled down under lock task (the
  kiosk deliberately omits `LOCK_TASK_FEATURE_NOTIFICATIONS`), so the
  child-side persistent notification cannot be shown from inside child
  mode. It is verifiable on the parent recording’s status bar and in any
  screenshot taken during a PIN pause, and the policy story (“always a
  visible foreground-service notification on both phones”) is stated in
  the privacy policy the reviewer reads anyway.
- **The provisioning consent flow** (`GetProvisioningModeActivity` /
  `PolicyComplianceActivity`) only runs inside a real factory-reset
  enrolment — Android refuses to launch those activities outside it
  (`BIND_DEVICE_ADMIN` permission denial), so the rig cannot record them.
  Record this once on a physical device during the Play-Protect
  provisioning test that `policy-forms.md` §1 already requires, and blur
  the QR while it is on screen.

## What the child recording demonstrates, in order

1. **The kiosk launcher** (device-owner lock task): a home screen holding
   only the parent-allowed apps — visibly a child’s screen, with the
   greeting and the app grid. This is the DPC capability in use: without
   device-owner, no allowlist and no lock task.
2. **A visible “parent locked this phone” screen** appearing the moment the
   parent taps “Lock now”: the complete lock (“Screen break” / “Pause
   d’écran”), with the always-visible **Emergency call** button.
3. **The unlock round-trip**: the parent unlocks, the child’s launcher
   returns.

## What it demonstrates for the child-audience declaration

- The child-facing UI is designed for children (calm launcher, large tiles,
  child-register copy), not an admin console handed to a child.
- **Nothing is hidden**: the child screen announces its state at all times
  (“Child mode” banner during pauses, an explicit lock screen, a parent
  menu the child can open and read). This is the anti-stalkerware posture
  `policy-forms.md` §5 describes — the video *shows* it.
- No ads, no purchases, no external links reachable from the child screens.

## How to use them in Play Console

Play’s declaration forms ask for a **YouTube URL**, not a file upload:

1. Upload `demo-child.mp4` to YouTube as **Unlisted** (account:
   nosari20@gmail.com or the developer account’s channel). Title it
   “Môme DM — device-owner functionality demo”.
2. Paste the URL wherever a declaration form asks for a demo video — the
   device-admin/DPC questions, and any reviewer follow-up questioning the
   target-audience declaration (the answer stays: the product is a parent’s
   utility; the child-styled screens are what the managed device displays).
3. Keep `demo-parent.mp4` in reserve; attach it (second unlisted video) if
   a reviewer asks how the parent side initiates the actions seen on the
   child screen.

## Re-recording

The rig procedure is `.claude/skills/emulator-rig/SKILL.md`; record with
`adb shell screenrecord --time-limit 50 /sdcard/demo.mp4` on each emulator
while driving: open the child device’s page on the parent → Lock now →
wait → Unlock. Compress with
`ffmpeg -i in.mp4 -c:v libx264 -crf 30 -movflags +faststart out.mp4`.
Never show a real pairing QR or a real PIN on a recording that leaves the
rig — the rig PIN (1234) is a throwaway, but a real family’s is not.
