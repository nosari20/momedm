# Screenshot index

Captured on the two-emulator BLE test rig (`emulator-5554` = parent, `emulator-5556` = child,
device owner already provisioned and paired). Parent UI is English, child UI is French — the app
is fully bilingual and that contrast is intentional.

| File | Shows |
|---|---|
| `parent-children.png` | "My children" list: the connected child card, green presence dot, "Child mode off" badge, "+" add-device button. |
| `parent-device.png` | Child detail page: status card and the full Night lock card with a realistic schedule — toggle ON, School nights 21:00→07:00, Friday/Saturday 22:00→08:00, state line "Locked until 7:00 AM". |
| `parent-apps-picker.png` | "Allowed apps" dialog with 5 apps ticked (Agenda, Appareil photo, Chrome, Gmail, Horloge) and the "Keep to a single app" toggle visible above the list. |
| `parent-time-picker.png` | The Material clock dialog opened from the School-nights start-time row (24h dial, Cancel/Save). |
| `parent-settings.png` | Settings list: Appearance & language, Parent PIN, Advanced, plus Legal/Licenses/version footer. |
| `parent-appearance.png` | Appearance & language screen with the "App colour" picker dialog open (8 accent swatches, one checked), Appearance: Dark visible behind it. |
| `parent-pin.png` | Parent PIN screen in the "PIN already set" state (Change PIN / Remove PIN) — no digits shown. |
| `parent-provision.png` | Provision screen after tapping "Show the code": steps 1–3 of the enrolment flow. **The QR code and Wi-Fi credentials are deliberately blurred** — that code carries the shared pairing secret. Keep them redacted in any re-capture. |
| `child-launcher-allapps.png` | Kid launcher with child mode OFF: full app grid, big clock, "Bon après-midi !" greeting, connection dot. |
| `child-launcher-childmode.png` | Kid launcher with child mode ON: grid reduced to only the 5 parent-allowed apps. |
| `child-bedtime.png` | Bedtime/complete-lock screen: clock at 21:30, "Bonne nuit !", "Déverrouillage à 07:00", no app tiles. Captured with the child clock moved into a 21:00→07:00 window. |
| `child-pin-dialog.png` | "Code PIN parental" dialog opened by long-pressing the header clock/greeting area — empty input field. |
| `child-paused.png` | Launcher showing the "Mode enfant en pause · MM:SS" banner with "Reverrouiller", after entering the correct parent PIN — apps unlocked again. |

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
  dialog instead of just the keyboard.

## Re-capturing

Use the rig described in [`../testing.md`](../testing.md) and the
`emulator-rig` skill in [`../../.claude/skills/`](../../.claude/skills/). Two things
to preserve:

- **Realistic values.** A "night lock" running 12:50→13:50, or a bedtime screen
  saying "Bonne nuit !" at 15:39, reads as a bug to anyone browsing the README.
  Move the child's clock into the window instead of shrinking the window to now.
- **Redact the pairing code.** `parent-provision.png` must never ship an
  unblurred QR or Wi-Fi password.
