# Môme DM — child-facing UX review (ages 6–14)

Date: 2026-08-25. Read-only review of the child device's three screens — `ChildLauncherScreen.kt`,
`BedtimeScreen.kt`, `ChildMenuScreen.kt` — plus their components (`AppTile.kt`, `PinDialog.kt`,
`NightSky.kt`), `ManagedHomeActivity.kt`/`ManagedViewModel.kt`, `RepairScanActivity.kt`, and both
`strings.xml` files. Evaluated against established children's-UX guidance: Nielsen Norman Group's
children's UX research (ages 3–12 studies and the 6–8 / 9–12 cohort findings), Google's Designed for
Families / Teacher Approved principles, WCAG 2.1/2.2, and Material Design accessibility. No code
changed. Companion to `2026-08-24-ux-review.md` and `2026-08-24-child-ui-design.md` — findings those
reports already raised are not repeated; where a prior recommendation was implemented, this review
judges the implementation instead.

## Verdict

The child side of this app is unusually well thought out for its genre: honest copy, real ownership
for the child (name and moon, kept off the wire), no gamification, no dark patterns, and visible
care at every point where a restriction could have read as a punishment. Most of the prior delight
pass landed well — the pause banner's alarm-maroon is genuinely fixed at the theme level, the moon
is astronomically honest, and the hidden-apps caption says exactly the right thing. What remains is
a second-pass problem set: the accessibility work done on the launcher header was not carried to the
bedtime screen (the one screen where it matters most), the launcher's primary action can fail with
no feedback at all, and a handful of copy and semantics details undercut otherwise excellent
emotional design. Three High, seven Medium, seven Low findings; all are small, bounded changes.

---

## High

### H1. The bedtime screen's long-press has no TalkBack action — the launcher's does

**What/where.** `ChildLauncherScreen.kt:174–198` attaches a `CustomAccessibilityAction`
("Open the parent menu") to the header alongside the raw `detectTapGestures` long-press — the fix
the previous UX review's Finding 3 asked for. `BedtimeScreen.kt:106–115` did not get the same
treatment: both branches (PIN set / no PIN) attach only `contentDescription = a11y` plus the raw
`pointerInput`, with no `customActions`. On a completely locked phone, the long-press on this
column is the *only* route to the parent PIN pad and menu; a TalkBack user has no announced,
triggerable way in. The bedtime screen is precisely the screen where a parent is most likely to
need that route (the phone is otherwise inert).

**Principle.** WCAG 2.1 SC 2.5.1 (Pointer Gestures — path-based/timed gestures need a single-pointer
alternative) and 4.1.2 (Name, Role, Value); Material accessibility ("all interactive elements must
be reachable by accessibility services"). Also an internal-consistency failure (Nielsen #4): the
sibling screen solved this exact problem.

**Fix.** Mirror the launcher's pattern: add
`customActions = listOf(CustomAccessibilityAction(parentMenuAction) { vm.pinDialogOpen.value = true; true })`
(or `vm.menuOpen` on the no-PIN branch) inside the existing `semantics {}` blocks at
`BedtimeScreen.kt:110–115`. The string (`launcher_parent_menu_action`) already exists in both
locales; this is a ~6-line change.

### H2. Tapping an app tile can silently do nothing

**What/where.** `ManagedViewModel.open` (`ManagedViewModel.kt:247–255`) calls
`policy.launchAllowed(pkg, locked)` and, on failure, only logs:
`if (!ok) Log.w(LOG_TAG, "Could not open $pkg")`. The tile's press-bounce plays
(`AppTile.kt:42–43`), then nothing happens. This is reachable in real states: an app uninstalled
since the grid was built (see M4 — the grid can be stale), a lock-task refusal, or a resolvable
activity that disappears. The child gets zero feedback on the launcher's single primary action.

**Principle.** Nielsen #1 (visibility of system status) and NN/g's children's research directly:
children interpret an unresponsive tap as "the thing is broken" and respond by tapping repeatedly or
abandoning — they do not form the adult hypothesis "this action is unavailable right now."
"Feedback on every action" is the baseline for this age band.

**Fix.** Have `open()` surface the failure — e.g. a `MutableStateFlow<String?>`/event the launcher
collects and shows as a small soft card or snackbar in the launcher's own visual register ("That
one can't open right now" / "Celle-là ne peut pas s'ouvrir pour l'instant"; new key pair in both
files, informal *tu* register). Also call `refreshApps()` on failure so a stale tile removes
itself. Keep the pinned-app bounce path silent (it is not a user action).

### H3. A midday manual lock says "Good night!"

**What/where.** `BedtimeScreen.kt:135` renders `bedtime_title` ("Good night!" / "Bonne nuit !")
unconditionally; only the `NightSky` is gated on `night` (`BedtimeScreen.kt:120–124` — the code
comment there even says a moon over a Tuesday-afternoon lock "would be nonsense", but the greeting
below it stays). A parent using "Lock now" at 2pm — statistically the moment of a live disagreement
about screen time — hands the child a screen that chirps "Good night!" over "A parent locked this
phone."

**Principle.** Emotional design / NN/g tone guidance for children: copy that is cheerful about a
restriction the child is unhappy about reads as sarcasm, exactly the "taunting line" the design
brief drew for animation and drew correctly. Also a mental-model error (Nielsen #2, match with the
real world): the screen asserts night when it is not night, which teaches a 6-year-old that the
phone's words don't mean anything.

**Fix.** Gate the title on `night` like the sky already is, and add a neutral pair for the manual
case — e.g. `bedtime_break_title`: "Screen break" / "Pause d'écran" — above the existing
`bedtime_manual` subtitle. Two new keys, both files, plus a one-line conditional at
`BedtimeScreen.kt:135`.

---

## Medium

### M1. Moon-picker selection is colour-only and invisible to TalkBack

**What/where.** In the "Make it yours" dialog, the three moon options
(`ChildLauncherScreen.kt:340–366`) mark the selected one solely by container colour
(`primaryContainer` vs `surfaceVariant`, lines 348–350). There is no `selectable`/`selected`
semantics, no `Role`, and no non-colour visual (border, check). A TalkBack user hears three names
("Full", "Outlined", "With craters") with no state; a colour-blind or low-vision child may not see
which is chosen, since with some parent-picked accents the two container tones sit close.

**Principle.** WCAG 1.4.1 (Use of Colour) and 4.1.2 (Name, Role, Value); Material selection
patterns (selected state must be announced and visually redundant).

**Fix.** Replace the plain `Surface(onClick=…)` with
`Modifier.selectable(selected = selected, role = Role.RadioButton) { draftMoon = option }` (or add
`semantics { this.selected = selected }`), and add a 2dp `primary` border on the selected card. The
existing `contentDescription = name` (line 351) stays.

### M2. The personalization entry point is an unlabeled, undersized, gesture-overlapped tap target

**What/where.** The only way into "Make it yours" is tapping the greeting text
(`ChildLauncherScreen.kt:210–217`): `Modifier.clickable { vm.namingOpen.value = true }` with no
`onClickLabel`, on a single `titleMedium` line (~24dp tall), nested inside the header `Row` that
carries the parent long-press (`ChildLauncherScreen.kt:162–200`). Three consequences: TalkBack
announces a generic "double-tap to activate" with no hint of what happens; the target is half the
48dp minimum; and a slow tap — common for younger children and motor-impaired users — crosses the
long-press timeout and opens the parent PIN pad instead of the child's own dialog, a genuinely
confusing swap of audiences.

**Principle.** Material accessibility 48×48dp minimum target; WCAG 2.5.8 (Target Size); NN/g:
children's touch targets should be *larger* than adult minimums, and hidden affordances are found
by "mine-sweeping" — which here collides with a deliberately hidden parent gesture.

**Fix.** Add `onClickLabel = stringResource(R.string.launcher_customise_title)` and
`Modifier.minimumInteractiveComponentSize()` (or vertical padding) to the greeting. Consider a
small trailing ✎-style icon (`Icons.Outlined.Edit`, core set) at 40% alpha so the affordance is
discoverable by sight without shouting; the child owns this feature — it should not be as hidden as
the parent's.

### M3. "Lock again" ends the pause with one child-reachable tap and no confirmation

**What/where.** The pause banner (`ChildLauncherScreen.kt:238–256`) shows two `TextButton`s to
whoever is holding the phone: "Menu" and "Lock again" (line 253, `vm.relock()` — immediate, no
dialog). The pause took a parent PIN to start; during its ten minutes the phone is typically handed
back and forth. A child tapping "Lock again" — and NN/g's research is unambiguous that children tap
whatever is visible — instantly re-locks the phone, and undoing that requires the parent to re-enter
the PIN. The failure direction is safe (more restriction, never less) but the cost asymmetry is
wrong: one stray child tap versus a full parent re-authentication.

**Principle.** Nielsen #5 (error prevention) and NN/g error tolerance for children: any
single-tap action a child can reach whose reversal requires an adult should be confirmed or moved.

**Fix.** Either a minimal confirm (`AlertDialog`, "Lock the phone again now?" — two new keys), or
move "Lock again" into the parent menu (already one tap away via "Menu" on the same banner), leaving
the banner's child-visible surface purely informational.

### M4. The app grid can go stale after a remote install — and nothing refreshes it

**What/where.** `refreshApps()` has exactly one trigger: the `kioskConfig` collect in `init`
(`ManagedViewModel.kt:182`). `kioskConfig` is a `StateFlow` (line 57), so equal configs don't
re-emit. A parent's `INSTALL` command does not touch `KioskConfig`; `onResumed()`
(`ManagedViewModel.kt:196–199`) bumps the lock evaluation but never the app list; there is no
`ACTION_PACKAGE_ADDED/REMOVED` listener anywhere in the app. So: parent installs a promised game
into an allowlist that already contains it → no tile appears until some unrelated config change or
a relaunch. The child was told the game is there, and their launcher says it isn't — from the
child's seat this is indistinguishable from "my phone is broken", the exact confusion the
hidden-apps caption was built to prevent. The mirror case (uninstall) leaves a dead tile, feeding H2.

**Principle.** Nielsen #1 (visibility of system status); NN/g: children cannot construct the
"eventually consistent" explanation an adult might.

**Fix.** Call `refreshApps()` from `onResumed()` (cheap — it already cancels prior runs and does
its work on `Dispatchers.IO`), and/or register a package-change `BroadcastReceiver` scoped to the
Activity's lifecycle. Verify on the two-emulator rig per `docs/testing.md`.

### M5. The big clock is hard-wired to 24-hour format — and disagrees with the subtitle

**What/where.** Both clocks format with `String.format("%02d:%02d", HOUR_OF_DAY, MINUTE)`
(`ChildLauncherScreen.kt:113`, `BedtimeScreen.kt:60–63`), ignoring the device's 12/24-hour
setting. The bedtime subtitle, meanwhile, uses `DateFormat.getTimeInstance(SHORT)`
(`BedtimeScreen.kt:79–80`), which respects it — so a US-configured phone can show a giant "21:30"
above "Your phone wakes up in 9 hours, at 7:00 AM". For 6–9-year-olds still learning to read
clocks, the format they're taught at home is the one they can parse; 24-hour time is a second
notation on top.

**Principle.** Nielsen #2 (match between system and the real world) and #4 (consistency); NN/g
reading-level guidance — reduce every decoding burden for young readers.

**Fix.** Use `android.text.format.DateFormat.getTimeFormat(context)` (or branch on
`DateFormat.is24HourFormat(context)`) for both big clocks, keeping the zero-padded look in 24h
locales. France defaults to 24h, so the FR experience is unchanged.

### M6. "in 1 hours", "in 1 minutes" — plural bugs on the most-read child screen

**What/where.** `bedtime_in_h` = "in %1$d hours" and `bedtime_in_m` = "in %1$d minutes"
(`values/strings.xml:79–80`; FR "dans 1 heures" / "dans 1 minutes", `values-fr/strings.xml:79–80`)
are plain strings, not `<plurals>`. "in 1 hours" is reachable whenever exactly one whole hour
remains (`BedtimeScreen.kt:72–77`), and "in 1 minutes" is *guaranteed* in the final minute via
`m.coerceAtLeast(1)` (line 77). This screen is read nightly by children who are practising reading;
the copy should not model broken grammar.

**Principle.** NN/g children's reading-level guidance; Google Teacher Approved ("high-quality,
age-appropriate" content includes correct language). Android's own i18n guidance mandates
`<plurals>` for quantity strings.

**Fix.** Convert both keys to `<plurals>` in both files (`StringsParityTest` parses key sets — add
identically-named plural resources to both in the same commit) and switch the call sites to
`pluralStringResource`. `bedtime_in_hm` ("in %1$dh %2$dmin") is abbreviation-style and fine as-is.

### M7. The FR empty state breaks the child voice: "Demandez à un parent"

**What/where.** `launcher_no_apps` (FR, `values-fr/strings.xml:72`): "Aucune app autorisée.
**Demandez** à un parent." — *vous* form, on a child-facing screen where every other child string
uses *tu* ("Ton téléphone se réveille…", "Fais-le à ton goût", "C'est juste pour toi…"). This is
also the exact screen shown in the worst edge state (child mode on, zero apps allowed, possibly
because the parent phone is out of range before the first allowlist arrived) — the moment the voice
matters most, it slips into addressing an adult.

**Principle.** Nielsen #4 (consistency); NN/g tone-of-voice for children — a stable, personal
register is part of what makes an interface feel trustworthy to a child.

**Fix.** "Aucune app autorisée pour l'instant. Demande à un parent." EN can stay ("No app allowed
yet. Ask a parent." already reads fine). One-word FR change plus optional "pour l'instant" to keep
the EN "yet"'s reassurance that this is a state, not a verdict.

---

## Low

### L1. Cold-start renders a raw empty Box with no background

`ChildLauncherScreen.kt:101`: while `kioskConfig` is null the screen is `Box(Modifier.fillMaxSize())`
— nothing painted, so the window background shows. The null-gate itself is correct and
security-motivated (the comment is right that flashing all tiles would be an escape hatch); but the
child sees a dead blank flash, and on a slow DataStore read, a dead blank *screen*. **Principle:**
Nielsen #1. **Fix:** paint the same gradient (theme roles are available before config is) so the
blank moment is "the app, loading" rather than "nothing"; optionally a progress indicator delayed
~400ms so it never shows on a normal start.

### L2. The connection dot bounces on every screen entry, not on change

`ChildLauncherScreen.kt:223–225`: `LaunchedEffect(connected)` runs on first composition, so the
"something changed" pulse fires every time the launcher composes, diluting the one-shot signal the
comment says it wants. **Principle:** feedback should mean something (Nielsen #1). **Fix:** skip
the first emission (e.g. `var first by remember`, or snapshot the initial value and compare).

### L3. Hidden-apps caption contrast is unverified at 70% alpha, small text

`ChildLauncherScreen.kt:290–292`: `bodySmall` at `onBackground.copy(alpha = 0.7f)` over the
gradient's tinted top. Likely passes in dark theme; in light theme over the mood-tinted
`primaryContainer` blend (lines 143–154) it may dip below 4.5:1 with some parent-picked accents.
**Principle:** WCAG 1.4.3 (small text needs 4.5:1); this caption is the launcher's most important
sentence for a restricted child. **Fix:** use `onSurfaceVariant` (a token already contrast-managed
per scheme) or raise alpha to 0.87; spot-check with `Palette.luminance` against the worst-case
accent.

### L4. PIN dialog errors are not announced to TalkBack

`PinDialog.kt:32–33`: the "Wrong PIN" / "Try again in N s" texts appear visually but carry no
`liveRegion` semantics, so a screen-reader user gets silence after a failed attempt (the field's
`isError` flag alone announces nothing meaningful). **Principle:** WCAG 4.1.3 (Status Messages).
**Fix:** `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on the error `Text`.

### L5. "Parent unlock" mislabels the no-PIN path, and sits on the bedtime clock

The shared `launcher_lock_cd` ("Parent unlock") labels the header even on the branches where the
long-press opens the menu directly with nothing to unlock (`ChildLauncherScreen.kt:188–191`,
`BedtimeScreen.kt:113–115`), and on the bedtime screen it labels the column containing the clock —
TalkBack focus on the time area leads with "Parent unlock". **Principle:** WCAG 2.4.6 (labels
describe purpose). **Fix:** a second string ("Parent menu" / "Menu parent") for the no-PIN
branches; on the bedtime screen consider putting the description on a smaller region than the whole
clock column.

### L6. Large-font behavior is untested where rows can't wrap

Two spots visibly assume default font scale: the pause banner packs a countdown text plus two
`TextButton`s into one non-wrapping `Row` (`ChildLauncherScreen.kt:246–255`) — at 1.5–2.0×
`fontScale` (large-print settings are common for younger kids and for low vision) the buttons will
compress or clip; and `AppTile.kt:63–66` caps labels at two ellipsized lines, which at 2.0× turns
"Khan Academy Kids" into noise. **Principle:** WCAG 1.4.4 (Resize Text — 200% without loss);
Material typography scaling. **Fix:** let the banner content wrap (`FlowRow` is off-limits per the
BOM gotcha in CLAUDE.md — use a `Column` fallback past a width threshold, or drop one button per
M3), and verify tile labels at 2.0× on the emulator rig.

### L7. The name field silently truncates at 20 characters

`ChildLauncherScreen.kt:331`: `onValueChange = { draft = it.take(20) }` — typing simply stops
registering at the limit, with no counter and no message. Twenty characters is generous for a first
name, but "the keyboard stopped working" is the child's read of it. **Principle:** NN/g feedback /
error tolerance. **Fix:** `supportingText = { Text("$draft.length/20") }` on the
`OutlinedTextField`, or simply raise the cap and let the greeting ellipsize.

---

## Larger UX / structural suggestions

### S1. Commit the bedtime screen to night, regardless of theme

`BedtimeScreen.kt:92–97` builds the "sky" from `surfaceVariant`→`surface`, and the moon from
`onSurface` (line 127). In light theme that is a pale grey sky with a near-black moon — legible,
but the metaphor inverts: the screen that *is* night renders as day, and the "stars" are dark
specks. The prior design brief (§1.4) flagged exactly this decision and recommended an always-dark
treatment built from `Palette.darken(seed, …)`; the implementation took the theme-literal path,
probably by default rather than by choice. Recommendation: take the brief's path — a fixed deep
navy/charcoal blended with the pushed accent, light moon and stars on top. The dawn blend
(`DawnWarm`, line 165) already assumes a dark ground to warm up. This is the one place in the child
app where honoring the parent's "Light" preference makes the child's experience worse; the parent's
accent still shows through the blend, so their customization is respected where it matters.

### S2. Give pre-readers a picture lane in the message states

Every explanatory state on the launcher is text-only: the empty-state card
(`ChildLauncherScreen.kt:259–275`), the hidden-apps caption (286–296), the pause banner (238–256).
NN/g's 6–8 cohort reads haltingly or not at all; for them these states communicate nothing. The
app already proved it can draw meaning without assets — `NightSky` is a Canvas moon. A small
matching Canvas glyph per state (an outlined app-tile shape for "no apps yet", a pause bar pair for
the banner) or a core Material icon at 48dp above the text would let a six-year-old distinguish
"empty but fine" from "broken" the way older kids now can. Keep the existing copy; add the picture.

### S3. Make the pause banner calmer: progress, not a stopwatch

The banner ticks a `MM:SS` countdown every second (`ChildLauncherScreen.kt:246–249`, fed by the
1s ticker in `ManagedViewModel.kt:237–242`). A per-second countdown is the visual grammar of exams
and bombs; the information ("roughly how much break is left") doesn't need that resolution for
either audience. Consider a slim `LinearProgressIndicator` (or the same text at minute
granularity) — same fact, exam-pressure removed — and drop the ticker to 15–30s, which also stops a
recomposition-per-second on the child's home screen for ten minutes. Pairs naturally with M3's
banner simplification.

### S4. Open the parent menu with one line a child can read

`ChildMenuScreen` is deliberately readable without the PIN (information for anyone, actions behind
proof — the right split), and its stated goal is answering "why is this phone behaving like this?".
But its answer is adult-shaped: "What is set on this phone", "Managed by Môme DM: Yes", model
numbers, an 8-hex parent id (`ChildMenuScreen.kt:78–151`). A child who long-presses their way here
— which the no-PIN path explicitly allows (`ChildLauncherScreen.kt:186–198`) — meets a settings
dump. One sentence in the child's own register at the top, above `menu_title`, would make the
screen serve both readers: "This phone follows rules your parent set. Everything below shows what
they are." / "Ce téléphone suit des règles choisies par tes parents. Tout ce qui suit les montre."
Two keys, both files; no structural change. The technical cards below stay exactly as they are —
they are the right content for the parent standing there with the PIN.

---

## Already done well — do not "fix" these away

- **The pause banner palette fix landed properly.** `Theme.kt:30–36` (light) and `:57–63` (dark)
  set tertiary explicitly to a warm cream/amber with a comment preserving *why* ("a parent gave you
  ten minutes… looked like a warning") — the prior brief's lead item 3, closed at the theme level so
  the banner code (`ChildLauncherScreen.kt:240`) didn't even need to change. Correct layer for the fix.
- **The moon is honest, and the code defends its honesty.** Real phase (`NightSky.kt:144–150`),
  hemisphere-correct waxing/waning with a comment explaining that a wrong-sided moon "would see the
  app is lying" (`NightSky.kt:119–129`), no motion on a screen that sits lit for hours, and a
  downgrade-tolerant `MoonStyle.from` (`NightSky.kt:30–32`).
- **The picker shows all three styles on a full disc** (`ChildLauncherScreen.kt:353–357`) so they
  are distinguishable even on a crescent night, and each has a screen-reader name
  (`moonName`, `ChildLauncherScreen.kt:386–394`).
- **Child ownership is real and stays local.** `childName`/`moonStyle` live in `ManagedPrefs`, never
  in `ChildPrefs`, never cross the link (`ManagedViewModel.kt:57–69`; CLAUDE.md invariant), and the
  dialog copy says so in the child's own register: "Just for you. Nobody else sees this, and it
  changes nothing about the rules" (`strings.xml:63`). That sentence is trust-building of a kind
  almost no parental-control app attempts.
- **The moon is chosen in the launcher, not on the bedtime screen — on purpose** (comment at
  `ChildLauncherScreen.kt:337–340`): nothing to fiddle with on the lock screen, so the lock never
  becomes negotiable. Exactly the right call.
- **The hidden-apps caption** (`ChildLauncherScreen.kt:283–296`, `launcher_some_hidden`) appears
  only when apps are genuinely hidden, and says "A parent chose which ones to show" — the
  broken-vs-curated distinction, stated without nagging.
- **The empty state is a soft card, not a crash screen** (`ChildLauncherScreen.kt:259–275`), with a
  comment showing that was the explicit intent.
- **The launcher header's long-press now has a TalkBack custom action**
  (`ChildLauncherScreen.kt:174–198`) — the prior review's Finding 3, properly closed *on this
  screen* (H1 is about carrying it to the sibling).
- **The emergency call is a visible button, placed so it can't collide with the hidden gesture**
  (`BedtimeScreen.kt:141–150`), added after real-device testing disproved the power-menu assumption.
- **Dawn arrives as light, not celebration** (`BedtimeScreen.kt:84–92`), the stars fade as it comes
  up (line 129), and the wake time leads with the relative framing a child actually wants
  (`bedtime_until_relative`, `BedtimeScreen.kt:66–81`).
- **Motion discipline held.** Tile stagger is one-shot and capped at six tiles' delay
  (`ChildLauncherScreen.kt:298–307`); the dot pulse is one-shot; no `infiniteRepeatable` anywhere on
  the child screens.
- **Touch targets are generous where it counts:** 150dp-minimum adaptive tiles, 64dp icons, big
  labels (`ChildLauncherScreen.kt:279`, `AppTile.kt:56–60`) — well above Material minimums, in line
  with children's-UX sizing guidance.
- **The pinned-app bounce waits for a finger on the header** (`ManagedHomeActivity.kt:93–113`,
  `ManagedViewModel.headerPressed`), keeping the parent menu reachable without ever letting the
  child out — careful conflict resolution between two gestures that could have fought.
- **CAMERA is self-granted by the device owner** (`PolicyManager.kt:68–84`), so the re-pair scanner
  can never open onto the permission-denied black screen a child device would otherwise be stuck at.
- **No dark patterns, no streaks, no scoreboards.** The design brief explicitly rejected
  behavior-linked rewards, and the implementation held that line everywhere — the mood tint is
  clock-driven only (`ChildLauncherScreen.kt:138–146`), and nothing on any child screen varies with
  compliance.
