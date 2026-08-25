# Môme DM — parent-side UX review: setup and everyday comprehension

Date: 2026-08-25. Read-only review of the **parent-facing** UI (controller role) against the
persona of a parent who is not an Android specialist: `activities/main/` (DevicesScreen,
DeviceScreen, ProvisionScreen and their components), the shared layouts in `ui/`, both string
files, the provisioning controller, and the README/architecture flow descriptions. No code was
changed. Findings from the 2026-08-24 reviews that have since been fixed (offline banner +
disabled controls, Result codes instead of raw `msg`, stop-child-mode confirmation, drawer label
and description, password show/hide, OnlineIndicator shape + semantics) were verified as fixed
and are not repeated here.

## Summary verdict

Day-to-day management is in good shape: the device page's copy is honest and jargon-free, the
offline state is now explicit, risky actions confirm, and results arrive as translated sentences.
The weak half is the **first hour**: enrolment is the one journey a parent cannot retry casually
(each attempt means a factory reset), and the screen that drives it tells them the two most
important facts — reset the phone *first*, and the code only lives five minutes — only after it
is too late to act on them. Around the edges, a handful of raw developer strings (pairing
errors, the setup wizard's "controller", the foreground notification's "Advertising to managed
devices") still leak past the otherwise carefully enforced family vocabulary, and the device
page has grown into a single long scroll whose bottom third has lost the section grammar the top
two thirds established.

Findings: **3 High, 9 Medium, 8 Low**, then larger restructuring proposals.

---

# High

## H1 — The enrolment screen reveals the factory-reset prerequisite and the five-minute code life only after the code is generated

**What and where.** `ProvisionScreen.kt:87-165` lays the flow out as "1. Choose how the child
device gets online → 2. Create the pairing code → 3. On the child's reset device, tap the
welcome screen 6 times and scan this code". The only mention that the child's phone must be
factory-reset first is `pair_help` ("The child device must be factory-reset first.",
`strings.xml:243`), rendered at `ProvisionScreen.kt:173` — *below the QR code*, i.e. only after
step 2 has already been executed. The five-minute expiry (`ProvisioningController.kt:24`,
`EXPIRY_MS = 5 * 60_000L`) is never stated anywhere on screen; the parent learns it exists from
`pair_expired` (`ProvisionScreen.kt:162`) after the code has already died. There is no countdown
while the code is showing.

**Why it hurts.** A factory reset plus first-boot takes well over five minutes on most phones. A
parent who follows the numbered steps in the order shown — pick network, tap "Show the code",
*then* go reset the child's phone — is close to guaranteed to come back to an expired code and a
switched-off download, with no idea what they did wrong. This is the single most likely stall in
the whole app, it hits on the very first use, and each failed attempt costs another
half-hour-with-a-wiped-phone. The README explains the right order; the screen, which is what the
parent is actually looking at with two phones in hand, implies the wrong one.

**Fix.** Three small changes, no new architecture:
1. Add a "Before you start" line or card at the *top* of the screen (move/replace `pair_help`):
   "First, factory-reset the child's phone and get it to the welcome screen (Settings → System →
   Reset options → Erase all data). Come back here when you see 'Hi there'."
2. State the lifetime next to the "Show the code" button: "The code works for 5 minutes — get
   the child's phone to the welcome screen before you create it." (new key, both locales).
3. While `s.qrPayload != null`, show a live countdown ("Code valid for 4:37") derived from the
   same deadline `armExpiry()` uses — expose `expiresAt` in `ProvisioningController.State`.

## H2 — Pairing failures surface raw, untranslated developer strings

**What and where.** `ProvisionScreen.kt:160` renders `s.error` verbatim:
`s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }`. Every value that can land
there is hardcoded English developer shorthand, built nowhere near the resource system:
- `"HTTP server: ${e.message}"` — `ProvisioningController.kt:111`
- `"no IPv4 address"` — `ProvisioningController.kt:145`
- `"missing APK URL"` — `ProvisioningController.kt:155`
- `"checksum: ${e.message}"` — `ProvisioningController.kt:156`
- `"hotspot error 2"`, `"missing permission: …"`, `"hotspot unavailable"` — `HotspotManager.kt:29-31`

**Why it hurts.** These fire at the most fragile moment of the app for the least recoverable
audience: mid-enrolment, on a French or English parent who cannot act on "no IPv4 address" in
any language. It is the same class of leak the 2026-08-24 review's finding 1 fixed for command
results (`resultText` in `ControllerViewModel.kt:219` now maps codes to translated sentences) —
the pairing path just never got the same treatment. It also quietly violates the project's own
string-parity rule, since none of this text exists in either locale file.

**Fix.** Mirror the `Result.code` pattern: have `ProvisioningController` set an error *enum*
(e.g. `HOTSPOT_FAILED`, `NO_ADDRESS`, `SERVER_FAILED`, `CHECKSUM_FAILED`, `MISSING_URL`) instead
of a string, keep the raw text in `Log.e` only, and let `ProvisionScreen` map each code to a new
plain-language pair in both files — each with a next step, e.g. "This phone could not start a
hotspot. Try the Shared Wi-Fi option instead." The mapping is five strings and one `when`.

## H3 — First run opens on a wall of identical, unexplained permission buttons

**What and where.** `MainActivity.kt:81,93-96`: before anything else, a brand-new parent sees a
screen titled "My children" containing only a bare `Column` of `ButtonRequestPermission`s — one
per missing permission, requested one at a time. `friendlyPermissionName`
(`ButtonRequestPermission.kt:18-25`) maps `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and
`BLUETOOTH_ADVERTISE` all to the same word, so the screen shows **three buttons labelled "Allow
Bluetooth"**, plus "Allow Notifications" and "Allow Nearby devices" — with not one sentence of
copy explaining what the app is about to do or why any of these are needed.

**Why it hurts.** This is the app's actual first impression, before the nice empty state is ever
reached. Three identical buttons read as a bug ("I already allowed Bluetooth — why is it asking
again?"). With no rationale on screen, a privacy-conscious parent is primed to tap "Don't allow"
on the system dialog — and a denied runtime permission can escalate to "never ask again",
leaving the app stuck behind a gate it can no longer lift itself. Android's own guidance (and
every consumer app this parent has used) explains *before* asking.

**Fix.** Two steps: (1) request the whole set in one shot with
`ActivityResultContracts.RequestMultiplePermissions` behind a single button, so the three
Bluetooth grants collapse into the one system dialog Android already groups them into; (2) add
one short paragraph above it: "Môme DM talks to your child's phone over Bluetooth, with no
internet and no account. Allow these so the two phones can find each other." (new key, both
locales). If per-permission buttons stay, dedupe by friendly name so "Bluetooth" appears once.

---

# Medium

## M1 — Enrolment has no step 4: nothing tells the parent it worked, and leaving the screen silently kills it

**What and where.** After the QR is scanned there is no feedback of any kind on the parent's
phone: `ProvisionScreen.kt` has no "waiting for the child's phone" state, no signal when the APK
download starts or completes (`ApkHttpServer` serves it silently), and no notice when the child
first connects and authenticates. Success is only discoverable by navigating back and noticing a
new card on `DevicesScreen`. Meanwhile `ProvisionScreen.kt:67` (`DisposableEffect … pc.stop()`)
tears down the hotspot and the APK server the moment the screen is disposed, and nothing keeps
the screen awake, so a display timeout mid-scan leaves a dark, unscannable screen.

**Why it hurts.** For five-plus minutes the parent stares at a QR code with no way to tell
"working" from "stuck" — precisely the window in which the platform's known silent failure modes
(local-network drop, OEM no-internet prompt) occur. Worse, the natural anxious gesture — tap
back to check the children list — *breaks the download in progress*, invisibly. The README's
sequence diagram knows about all of these stages; the screen knows about none of them.

**Fix.** (1) Add `Modifier.keepScreenOn` (or the window flag) while a code is displayed. (2)
Surface the two events the code already observes: when `ApkHttpServer` serves `momedm.apk`,
flip a state to "The child's phone is downloading the app…"; when a new deviceId authenticates
(`ControllerLink` already exposes this — the registry write is what makes the card appear),
show "✓ {model} is paired" with a button to its device page, and cancel the expiry. (3) Until
then, at minimum add a line under the QR: "Keep this screen open. When the child's phone
finishes, it appears under My children."

## M2 — The setup wizard the parent reads on the child's phone speaks MDM ("controller"), and so does the parent's own notification

**What and where.** During enrolment the *parent* is the person holding the child's phone
through `PolicyComplianceActivity` (`PolicyComplianceActivity.kt:69-75`), which renders
`setup_account_text` — "Add a Google account so Play Store installs requested by **the
controller** can work" (`strings.xml:37`) — and `setup_usage_text` — "Allow usage access so
**the controller** can see the current app" (`strings.xml:40`); FR uses "le contrôleur"
identically. Separately, the permanent foreground notification on the parent's own phone says
"Môme DM controller — **Advertising to managed devices** (1 online)"
(`controller_notif_title`/`controller_notif_text`, `strings.xml:24-25`).

**Why it hurts.** These strings sit in the "legacy keys" block that predates the family-
vocabulary pass, and they are anything but legacy in exposure: the wizard text is read at the
single most stressful point of the journey, and the notification is on the parent's lock screen
every day. "Controller", "advertising" and "managed devices" are exactly the register the
project's own house rule bans, and "advertising" in a parental-control app can read as
*advertisements* to a lay reader.

**Fix.** Reword in place (keys can stay): setup steps → "…installs requested from the parent's
phone…", "…so the parent's phone can see which app is open…"; notification → title "Môme DM",
text "Visible to children — %1$d connected" (mirroring `children_visible`/
`children_online_count` vocabulary). Update both locales in the same commit.

## M3 — The status card shows raw package ids as "Current app" and "Single app"

**What and where.** `DeviceScreen.kt:102-103`: `Text(s?.kioskPkg ?: "—")` and
`Text(s?.currentApp ?: "—")`. Both values are package ids from `StatusCollector`, so the
most-read card in the app answers "what is my child doing right now?" with
`com.google.android.youtube`.

**Why it hurts.** The README explicitly promises the opposite register ("a parent never has to
know what `com.mojang.minecraftpe` is"), and the install field honours it — but the status card,
the very first thing on the page, does not. A parent cannot reliably decode
`com.zhiliaoapp.musically` as TikTok, which defeats the point of showing the current app at all.

**Fix.** Cheapest: prettify the last segment (`youtube` → "Youtube") as a fallback and hide the
"Single app" row when `kioskPkg == null` (it is "—" almost always). Proper: reuse labels the
child already sends — `Message.Apps` carries `AppInfo(label, pkg)`; either cache the last
`LIST_APPS` reply per device in `DeviceRegistry` and look labels up, or add an optional
`currentAppLabel` to `Status` (the child knows the label for free via `PackageManager`).

## M4 — "Start child mode" opens an unannounced app-picker dialog instead of starting anything

**What and where.** `DeviceScreen.kt:190-196`: when child mode is off, the filled button
labelled "Start child mode" calls `viewModel.requestApps(deviceId)` — which pops the "Allowed
apps" multi-select (`AppPickerDialog`) after a BLE round-trip. Nothing on the button or in the
dialog explains that choosing apps and confirming *is* how child mode starts.

**Why it hurts.** The label promises an action and delivers a form. A parent who expected a
switch flip may cancel the unexpected dialog ("I didn't ask to choose apps, I asked to start"),
concluding the button is broken — or confirm without understanding that the ticked set is now
the child's entire phone. The connection between "these checkboxes" and "everything else
disappears from the phone" is exactly the kind of consequence this app spells out elsewhere
(`child_stop_confirm`) but not here, in the mirror-image action.

**Fix.** Either rename the button when off — "Choose apps and start child mode…" — or keep the
label and give the picker a one-line preamble when it was opened by this button: "Tick the apps
your child may use. Everything else disappears from their phone until you stop child mode."
(new key, both locales; `AppPickerDialog` already accepts a `title`, add an optional
`supportingText`.)

## M5 — The night-lock card shows placeholder times as if they were real, and its time rows ignore the offline state

**What and where.** `DeviceScreen.kt:112`: `val schedule = s?.schedule ?: LockSchedule()` — with
no status yet (new device, or long offline), the card renders the *defaults* (21:00→07:00
weekday, 22:00→08:00 weekend, `LockSchedule.kt:24-27`) indistinguishably from a schedule the
child actually holds. And while the enable `Switch` is correctly gated (`enabled = isOnline`,
`DeviceScreen.kt:118`), the two `TimeRangeRow`s (`DeviceScreen.kt:120-125`) are not —
`TimeRangeRow.kt:35` has no `enabled` parameter — so an offline parent can open the clock
picker, pick a time, tap Save, and get "This device is offline" after the fact, the exact
snackbar-after-dead-tap pattern the offline banner work was done to eliminate.

**Why it hurts.** A parent glancing at the card while the child is offline reads "bedtime is
21:00" as a fact about their child's phone when it may be a rendering default; trust in every
other number on the page erodes the first time they catch it wrong. The half-disabled card also
teaches the wrong lesson: the switch greys out, the times don't, so the times look *more*
available offline than they are.

**Fix.** Render "—" for the times (and disable the rows) until `s?.schedule` exists; thread an
`enabled: Boolean = true` through `TimeRangeRow` (disable both `TextButton`s) and pass
`isOnline`. Both are small, local changes.

## M6 — "Done" fires when a command is *sent*, not when it is done

**What and where.** `ControllerViewModel.kt:202-209` (`announce`): the instant any command goes
out, the parent gets a snackbar with `child_sent` — whose text is **"Done"** / "C'est fait"
(`strings.xml:204`). The real outcome (`res_locked`, `res_failed`, …) arrives seconds later as a
second snackbar — or never, if the child drops mid-flight (`pendingIds` is explicitly trimmed
for exactly that case, `ControllerViewModel.kt:197-200`).

**Why it hurts.** For a trust-critical action — "Lock now" as the child walks out the door —
"Done" means *the phone is locked* to any ordinary reader. When the RESULT never arrives, the
parent's last signal was a false confirmation; when it does arrive, they get two near-identical
toasts ("Done" then "Phone locked") for one tap, which trains them to stop reading either. The
BLE reality (fire-and-forget, no queue) is well handled in the offline banner's copy but
contradicted by this word.

**Fix.** Change `child_sent` to "Sent — waiting for the phone…" / "Envoyé — en attente du
téléphone…". Better: skip the sent-toast entirely for commands that reliably produce a RESULT
within a second or two, and show only the outcome; keep a sent-indicator only if the RESULT is
late (>3 s), where it genuinely informs.

## M7 — The ten-minute Play Store window is never explained to the person who opened it

**What and where.** After "Find it on the Play Store" (`DeviceScreen.kt:198-199` →
`ControllerViewModel.install`), the success snackbar is `res_play_opened`: "The Play Store is
open on their phone" (`strings.xml:208`). Nothing tells the parent that (a) someone must tap
Install *on the child's phone*, and (b) the store vanishes again ten minutes later
(`PolicyManager.openPlayWindow`, per the kiosk-allowlist design).

**Why it hurts.** The likely real-world sequence: parent types "Minecraft" at 17:00, child is in
another room, nobody touches the child's phone, window closes, child later reports "the Play
Store disappeared", parent retries and mistrusts the feature. The window is a deliberate,
defensible safety design — but an invisible timer only reads as flakiness. This is the one
remaining place where a BLE/kiosk reality bites the parent with no on-screen explanation.

**Fix.** Extend the string: "The Play Store is open on their phone — someone needs to tap
Install there within 10 minutes." (both locales). If the window duration is ever made
configurable, format it in.

## M8 — Status freshness is illegible: no refresh on opening the page, and "Last seen" is an absolute timestamp

**What and where.** `DeviceScreen` has no `LaunchedEffect` sending `GET_STATUS` on entry — the
card renders whatever `lastStatus` DataStore holds, which between pushes is up to 5 minutes old
(status cadence: on auth, after commands, ≥5-point battery change, else every 5 min) and after
an app restart may be from yesterday while the "Connected" pill shows green. The only freshness
cue is `child_last_seen` rendered as a full `DateFormat.getDateTimeInstance()` date-time
(`DeviceScreen.kt:106`), and the only remedy is a "Refresh" button exiled to the bottom of the
scroll (`DeviceScreen.kt:201`) with no loading indication when tapped.

**Why it hurts.** "Is my kid on YouTube right now?" is answered by a card that may be describing
five minutes ago, with a timestamp ("Aug 25, 2026, 5:03:12 PM") the parent has to mentally
subtract from the current time to notice. The refresh affordance is furthest from the data it
refreshes, and tapping it changes nothing visibly until a snackbar appears.

**Fix.** (1) `LaunchedEffect(deviceId, isOnline) { if (isOnline) viewModel.refresh(deviceId) }`
— one line, makes the page self-freshening. (2) Render last seen relatively
(`DateUtils.getRelativeTimeSpanString`: "2 min ago" / "il y a 2 min"), keeping the absolute form
in a secondary line or on tap. (3) Move refresh to a small icon beside the "STATUS" section
label; the bottom button can go.

## M9 — The bottom third of the device page abandons the page's own section grammar

**What and where.** `DeviceScreen.kt:190-201`: after three labelled cards (Status / Night lock /
Content), the page trails off into loose controls with no card and no `SectionLabel` — the
Start/Stop button, an optional "Lock again", a bare `OutlinedTextField` whose only caption is
its hint "App name, or package id" (`child_install_hint`, `strings.xml:198`), an install button,
"Add a Google account", and "Refresh". The install field's hint is also the page's one remaining
jargon leak ("package id").

**Why it hurts.** The top of the page teaches the parent "a card = a topic"; the bottom then
presents six unrelated controls in an undifferentiated column, where the most consequential
button on the page (Start/Stop) is visually adjacent to a text field about installing apps. A
scanning parent has no anchor for "where do I add an app?" versus "where do I stop child mode?" —
and TalkBack users get the same flat list.

**Fix.** Wrap the tail in two labelled cards matching the existing pattern: "Apps" (install
field + button — hint reworded to "App name (e.g. Minecraft)") and "This phone" (add account;
refresh moves to the status header per M8). Start/Stop stays outside cards as the page-level
action but gains breathing room — or moves up beside the status pill (see proposal P2).

---

# Low

## L1 — "Custom app link" is an expert option dressed as a peer choice

`ProvisionScreen.kt:90-93` presents the three modes as equal radio rows; "Custom app link"
(`pair_wifi_custom`) then reveals a URL field hinting `https://…/momedm.apk`. No parent persona
can complete this path, and nothing marks it as the self-hosting escape hatch it is. The other
two rows also carry no differentiating help ("no home Wi-Fi needed" vs "child joins your
Wi-Fi"). **Fix:** one supporting line under each radio (three short keys, both locales), and
either list Custom last with "for advanced setups" in its line, or fold it behind an "Advanced"
toggle.

## L2 — Hotspot readiness is reported as a raw IP; Shared Wi-Fi readiness is not reported at all

`ProvisionScreen.kt:157-159`: hotspot mode shows `pair_serving` → "Ready — hotspot
192.168.43.1"; an IP address carries no meaning for the persona, and in `MODE_MANUAL` there is
no ready line whatsoever between tapping the button and the QR appearing. **Fix:** replace the
placeholder with a plain "Ready — show the child's phone this code" in both modes; log the IP.

## L3 — The known "this network has no internet" OEM prompt is not pre-empted on screen

`docs/architecture.md:298-302` documents that Samsung (among others) interrupts hotspot joins
with a no-internet warning the parent must accept during enrolment — a guaranteed stall for the
persona, currently explained only in docs. **Fix:** one hint line under the QR in hotspot mode:
"If the child's phone warns the network has no internet, choose Keep connection / Connect
anyway."

## L4 — A connected child with no status yet is labelled "Child mode off"

`DevicesScreen.kt:113-118` (`ChildStateChip`): the `else` branch renders `child_mode_off`
whenever `lastStatus` is null but the device is online — an unknown presented as a definite (and
alarming: "my child's phone is unrestricted?") state. **Fix:** add a `lastStatus == null` branch
rendering "—" or a "Checking…" chip in the neutral colour.

## L5 — The two BLE-backed loading dialogs can spin forever

`AppPickerDialog.kt:70-71` (`apps == null` → spinner) and `AppConfigLoadingDialog`
(`AppConfigDialog.kt:100-113`) have no timeout: if the child drops after `LIST_APPS`/
`GET_APP_SCHEMA` is sent, the spinner runs until the parent figures out Cancel is the exit.
**Fix:** after ~15 s of `null`, swap the spinner for "The phone did not answer. Move closer and
try again." with the existing dismiss; a `LaunchedEffect(delay)` in each dialog suffices.

## L6 — Section labels are not headings for TalkBack

`Pronote.kt:60-68` (`SectionLabel`) renders plain `Text` with no
`Modifier.semantics { heading() }`, so on the long DeviceScreen a TalkBack user cannot jump
between Status / Night lock / Content with heading navigation — they must swipe through every
row and button linearly. **Fix:** add `.semantics { heading() }` inside `SectionLabel` (one
line, benefits every screen using it).

## L7 — The rename dialog happily saves an empty name

`DeviceScreen.kt:259-266`: Save is always enabled and passes the raw string to
`viewModel.rename`, so a blank submission stores an empty nickname and the card title goes
blank-ish rather than falling back to the model name. **Fix:** treat blank as "clear nickname"
(`name.trim().ifEmpty { null }`) — which also gives parents a discoverable way to undo a rename.

## L8 — Tapping "Show the code" while a code is live is a silent no-op

`ProvisioningController.kt:93` correctly refuses re-entrant starts, but the button stays enabled
and gives no feedback, so a parent who taps again (e.g. wanting a fresh code) sees nothing
happen. **Fix:** disable the button while `serverRunning || hotspotSsid.isNotBlank()` (state is
already in the collected `State`), or relabel it "Code shown below" in that state.

---

# Larger proposals

## P1 — Turn ProvisionScreen into a true enrolment wizard (builds on H1, M1)

The current screen is a form with numbered labels; the journey it drives is a strict sequence
with a timer and an unattended success. Restructure into explicit stages, each owning the
screen: **(0)** "Before you start" — factory-reset instructions, what you need, how long it
takes; **(1)** network choice (with the per-mode help lines from L1); **(2)** the code, with
countdown, keep-screen-on, and the child-phone instructions *beside* it; **(3)** "Waiting for
the child's phone…" fed by the APK-served and first-auth events; **(4)** success, naming the new
device and linking to its page. The state machine mostly exists in
`ProvisioningController.State` already — this is primarily recomposition and ~10 new string
pairs, not new plumbing. *Effort: 2–4 days including both locales and emulator-rig verification
of the event hooks.*

## P2 — Restructure DeviceScreen around topics, with the mode toggle in the header

The page currently interleaves *observation* (status card) and *action* (buttons) with the
page-defining state change (Start/Stop) buried mid-scroll. Proposed order: a compact header
block (name, online pill, offline banner, **Start/Stop as the header's action**, last-seen +
refresh icon), then four cards — **Apps** (allowed apps + picker, install-by-name, advanced app
settings), **Bedtime** (night schedule + lock now/unlock), **Content** (level + DNS), **This
phone** (Google account, rename). This keeps one scroll (tabs are unnecessary at this content
volume and would hide the offline banner on inactive tabs) but gives every control a home and
puts the two most-used actions (toggle, lock) in the first viewport. *Effort: 1–2 days — it is
reordering and two new cards (M9), no new state.*

## P3 — Retire the navigation drawer

`MainActivity.kt:102-109` + `Layout.kt`: the drawer holds exactly two items — "My children"
(the screen you are already on) and "Pair a device" (the FAB one centimetre away). It costs a
top-bar slot, a gesture, and a TalkBack stop, and delivers nothing the surface UI doesn't. Drop
`BasicLayoutWithTopBarAndDrawer` from the controller activity in favour of the plain top bar;
if a menu is ever needed again, Settings already lives in the top-right. *Effort: half a day;
`Layout.kt` stays for any other consumer.*

## P4 — A visible command lifecycle instead of paired snackbars (builds on M6)

Replace the sent/result snackbar pair with a small inline status line under the header (or on
the acting card): "Locking… → Phone locked ✓" with failures persisting until dismissed. The
plumbing exists — `pendingIds` already tracks in-flight commands and `resultText` already
produces the sentence; this moves the output from a 4-second transient to a stateful one-liner,
which is what makes error recovery possible for a parent who looked away. *Effort: 1 day.*

## P5 — One-screen onboarding before the permission gate (builds on H3)

A single static first-run screen (three sentences: what the app is, that Bluetooth is the only
channel, what happens next) with one "Get started" button that fires the batched permission
request, shown only while permissions are missing. This is the cheapest possible fix for the
worst first impression in the app. *Effort: half a day plus strings.*

---

# What is already done well

- **The offline story on DeviceScreen is now exemplary.** The banner
  (`DeviceScreen.kt:85-97`) states the one non-obvious BLE fact — "changes… are not saved up to
  send later" — in plain words, and the controls beneath it are actually disabled rather than
  merely warned about. The code comment above it shows the reasoning was internalised, not
  patched.
- **Result codes, not wire text.** `ControllerViewModel.resultText` (`:219-233`) is exactly the
  right shape: stable codes mapped to translated sentences, unknown codes falling back to a
  plain outcome, raw `msg` confined to logcat. The 2026-08-24 review's worst finding is fully
  closed.
- **Stopping child mode now confirms with honest consequences** (`child_stop_confirm`,
  `DeviceScreen.kt:248-258`) — and the comment records why starting deliberately doesn't.
- **The pairing screen already sweats real details**: whole-row radio targets with proper
  `Role.RadioButton` semantics (`ProvisionScreen.kt:95-107`), SSID prefill that never overwrites
  user input, the masked Wi-Fi password with a reasoned show/hide, the in-context local-network
  permission card whose copy (`pair_local_network_why`) explains a genuinely obscure Android 16
  behaviour in one parent-readable sentence, and the expired state that says *why* the code went
  away instead of leaving a blank step 3.
- **The app picker is the strongest dialog in the app**: search, a live "%d allowed" pill,
  tap-anywhere rows, the pin-one-app option only where it means something, and one-tap selection
  in single-choice mode with the reasoning documented.
- **Honest capability copy remains the house voice** — `safety_explain` and `appcfg_none`
  undersell rather than oversell, and `conn_rejected_hint` turns the one BLE failure that
  actually happens into words and a fix.
- **The accessibility fixes landed properly**: the drawer button announces "Open the menu"
  (`Layout.kt:87`, `BasicLayoutWithTopBar.kt:61`), and the discoverability dot carries both a
  shape difference and a content description with its own vocabulary
  (`OnlineIndicator.kt:24-49`).
- **String parity discipline holds** — both files are 339 lines, key-for-key, and the French is
  idiomatic rather than translated ("C'est fait", "Demandez à un parent"), with the enforcement
  test still doing its job. The findings above (H2, M2) are leaks *around* the resource system,
  not within it.
