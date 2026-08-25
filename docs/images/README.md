# Screenshot index

Captured on the two-emulator BLE test rig (`emulator-5554` = parent, `emulator-5556` = child,
device owner already provisioned and paired). Parent UI is English, child UI is French — the app
is fully bilingual and that contrast is intentional.

| File | Shows |
|---|---|
| `parent-children.png` | "My children" list: the connected child card, green presence dot, "Child mode off" badge, "+" add-device button. |
| `parent-device.png` | Child detail page: status card and the full Night lock card — toggle ON, School nights 21:00→07:00, Friday/Saturday 22:00→08:00, state line "Locked until 7:00 AM", and the top of the Content card. |
| `parent-content.png` | The Content card on a child's page: level, then Change / Choose allowed apps / Advanced app settings. |
| `parent-content-dialog.png` | "Content restrictions" dialog: the three levels, the phone-wide filtering resolvers, and the note about what browser settings do and do not cover. |
| `parent-app-picker.png` | "Which app?" — the single-choice list behind Advanced app settings. One tap opens that app's form; no tick boxes, no confirm. |
| `parent-app-config.png` | A settings form built from Chrome's *own* declared managed configuration — nothing about these fields is hardcoded in this project. |
| `parent-apps-picker.png` | "Allowed apps" dialog with 5 apps ticked (Agenda, Appareil photo, Chrome, Gmail, Horloge) and the "Keep to a single app" toggle visible above the list. |
| `parent-time-picker.png` | The Material clock dialog opened from the School-nights start-time row (24h dial, Cancel/Save). |
| `parent-settings.png` | Settings list: Appearance & language, Parent PIN, Advanced, plus Legal/Licenses/version footer. |
| `parent-appearance.png` | Appearance & language screen with the "App colour" picker dialog open (8 accent swatches, one checked), Appearance: Dark visible behind it. |
| `parent-pin.png` | Parent PIN screen in the "PIN already set" state (Change PIN / Remove PIN) — no digits shown. |
| `parent-provision.png` | Provision screen after tapping "Show the code": steps 1–3 of the enrolment flow. **The QR code and Wi-Fi credentials are deliberately blurred** — that code carries the shared pairing secret. Keep them redacted in any re-capture. |
| `child-launcher-allapps.png` | Kid launcher with child mode OFF: full app grid, big clock, time-of-day greeting, connection dot. |
| `child-launcher-childmode.png` | Kid launcher with child mode ON: grid reduced to the parent-allowed apps, a chosen name in the greeting, and the line saying how many apps are hidden. |
| `child-bedtime.png` | Bedtime/complete-lock screen: tonight's real moon phase, "Bonne nuit !", how long is left before the time it unlocks, and the explicit "Appel d'urgence" button. Captured inside a real night window. |
| `child-pin-dialog.png` | "Code PIN parental" dialog opened by long-pressing the header clock/greeting area — empty input field. |
| `child-paused.png` | Launcher showing the "Mode enfant en pause" banner with "Menu" and "Reverrouiller" — during a pause every installed app is listed, not just the allowed ones. |
| `child-parent-menu.png` | The parent menu on the child's phone: rules in force, the parent link, device facts, then Pause / Re-pair / Close. Reached by long-pressing the header and entering the PIN. |
| `enrolment.svg` | Hand-drawn six-step enrolment walkthrough, written for a parent rather than an engineer — deliberately not a UML diagram. Step 2 opens out into the three ways onto a network (home Wi-Fi, the parent’s hotspot, a self-hosted link), each with its own doodled icon. Hand-authored SVG: edit it directly, and keep the sketchy stroke and the handwriting font stack (it falls back to `cursive`, so the wobble in the geometry is what carries the look). |
| `connectivity.svg` | Hand-drawn explanation of what Wi-Fi is for (once, to install) versus what Bluetooth is for (everything after), and that out of range changes nothing. Same style and same caveats as `enrolment.svg`. |
| `enrolment-sequence.svg` | The same enrolment, drawn as a sequence: who says what to whom, in order, split into the Wi-Fi phase and the Bluetooth phase. Hand-drawn on purpose — it is a sequence diagram in shape only, not in notation. |

## Notes for re-capture

- `child-bedtime.png` is the **scheduled** lock (has the unlock-time line). A manual
  "Lock now" tap produces a slightly different message ("Un parent a verrouillé ce
  téléphone", no unlock time) — use the schedule path if you want the unlock-time line back.
- `parent-provision.png`'s QR code encodes a hotspot SSID/password that the emulator
  generates fresh each time "Show the code" is tapped — it is not a project secret, just
  regenerate it if you need a new shot.
- To reach `child-paused.png`, long-press the child's header to open the PIN dialog, type
  the parent PIN, dismiss the on-screen keyboard state carefully — tapping "Déverrouiller"
  while the keyboard is still up works; using the back button first can dismiss the whole
  dialog instead of just the keyboard. A correct PIN lands on the parent menu
  (`child-parent-menu.png`); the pause itself is the button inside it.
- Driving the child's long-press over adb needs a settle delay, or the swipe arrives before
  the launcher has resumed and is swallowed:
  `adb shell "input keyevent 3; sleep 0.6; input swipe 300 150 302 152 2500"`. The on-screen
  keyboard also moves the PIN dialog's buttons — re-read their bounds after typing.

## Re-capturing

Use the rig described in [`../testing.md`](../testing.md) and the
`emulator-rig` skill in [`../../.claude/skills/`](../../.claude/skills/). Two things
to preserve:

- **Realistic values.** A "night lock" running 12:50→13:50, or a bedtime screen
  saying "Bonne nuit !" at 15:39, reads as a bug to anyone browsing the README.
  Move the child's clock into the window instead of shrinking the window to now.
- **Redact the pairing code.** `parent-provision.png` must never ship an
  unblurred QR or Wi-Fi password.
