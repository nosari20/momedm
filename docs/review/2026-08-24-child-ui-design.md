# Môme DM — child-side warmth & character design brief

Design-only pass over the three child-facing screens (`BedtimeScreen.kt`, `ChildLauncherScreen.kt`,
`ChildMenuScreen.kt`). No code changed. Companion to `2026-08-24-ux-review.md`, which already covers
accessibility, copy correctness, confirmations and information architecture — none of that is repeated
here. This is about warmth, character, illustration, motion and delight, inside four hard constraints:
Compose M3 only (no new deps, no icon packs, no Lottie), core Material icons only, EN/FR string parity,
and no continuous animation that would drain a battery on the device that *is* the child's home screen.

---

## Build these three first

1. **A Canvas-drawn night sky on the bedtime screen** — a moon whose phase matches the real date, a
   handful of stars, one calm entrance fade, and the unlock time reframed as "wakes up in…" instead of a
   bare timestamp. This is the single highest-leverage change: it's the screen a child sees most often
   while being told no, and today it is a clock on a gradient.
2. **A "personalise my screen" link the child owns outright** — a nickname for the greeting and a choice
   of moon style, stored locally, invisible to the parent app, incapable of touching any rule. This is
   the most promising direction in the whole brief: real ownership that can never be mistaken for
   negotiating the rules themselves.
3. **Recolour the pause banner off the alarm palette.** `ChildLauncherScreen.kt:163` renders the "child
   mode paused" banner in `MaterialTheme.colorScheme.tertiaryContainer`, which — because `Theme.kt`'s
   `withSeed()` only recolours `primary`/`secondary`, never `tertiary` — stays Material3's untouched
   default tertiary container: a dusty maroon/rose. Confirmed in `child-paused.png`: a parent-granted
   ten-minute break currently reads as a warning. A break is good news; it shouldn't look like one.

Everything below expands these and covers the rest of the brief.

---

## 1. The bedtime screen

### 1.1 Illustration: a Canvas-drawn moon and sky

**What it looks like.** A softly glowing moon disc sits above the clock, with a small scatter of stars
around it. The moon's phase tracks the real calendar — full, crescent, gibbous — the same moon the child
could in principle look up at through the window. No cartoon face, no sleeping cap, no "Zzz" — it is
scenery, not a character.

**Where it goes.** Behind/above the existing `Column` of clock + "Bonne nuit !" + subtitle in
`BedtimeScreen.kt`, as a new sibling layer inside the outer `Box` (insert before the `Column` at
`BedtimeScreen.kt:76`, i.e. drawn first so the text sits on top). Roughly the upper third of the screen,
above the clock, so the existing text layout doesn't need to move.

**Why a child would like it.** It's the owner's own instinct, and it's right: a bedroom-at-night visual
on a screen that says goodnight is legible to any age without being childish — moons and stars read as
"night", not "toddler". A 13-year-old glancing at their phone in front of friends sees ambient scenery,
the same register as a phone's own default lock-screen wallpaper, not a mascot.

**Why it stays on the right side of the taunting line.** It does not move on its own, does not react to
the child, does not celebrate anything. A moon doesn't know or care that the child wanted to stay up —
it's just there, the way the actual sky is just there. Compare to a bouncing sleepy-cartoon character
that "cheers" for bedtime, which would read as mocking a disappointed kid; static scenery never does that
because it has no implied attitude. The one entrance animation (§3) is a fade-in, not a performance.

**How to implement it here.**
- New `Canvas` composable, e.g. `NightSky(modifier, phase: Float, starSeed: Long)` — draw with
  `drawCircle`, no new deps.
- **Moon phase**, pure Kotlin, unit-testable, no network:
  ```kotlin
  private const val SYNODIC_MONTH_DAYS = 29.53058867
  private const val KNOWN_NEW_MOON_EPOCH_MS = 947_182_440_000L // 2000-01-06 18:14 UTC

  fun moonPhase(nowMs: Long): Float {
      val days = (nowMs - KNOWN_NEW_MOON_EPOCH_MS) / 86_400_000.0
      val phase = (days % SYNODIC_MONTH_DAYS) / SYNODIC_MONTH_DAYS
      return ((phase + 1.0) % 1.0).toFloat() // 0 = new, 0.5 = full, 1 = new again
  }
  ```
- **Drawing the phase** (the standard "two overlapping discs" trick — no path math beyond circles):
  1. Draw a filled circle in a light "moon" tone (`Palette.lighten(seed, 0.9f)` or similar, computed
     from the pushed accent so it still reads as "this app's colour" — see §1.3 on light/dark).
  2. Compute `shadowOffsetX = radius * cos(phase * 2π)`.
  3. Draw a second circle, same radius, in the sky's own background colour, centered at
     `(moonCenter.x + shadowOffsetX, moonCenter.y)`, clipped to the moon's circular bounds (`clipPath`
     or draw with `BlendMode.DstOut`). At `phase = 0` this shadow disc sits exactly over the moon (new
     moon, fully dark); at `phase = 0.5` it sits exactly off it (full moon); in between it carves the
     correct crescent/gibbous edge, and the sign of `cos` naturally flips which side is lit between
     waxing and waning.
- **Stars**: 6–10 small circles (1.5–2.5dp radius) at positions seeded once per day
  (`Random(dayOfYear)`), so they hold still for the whole night rather than reshuffling on every screen
  wake — a static sky, not noise. Vary base alpha 0.3–0.8 per star for a natural, non-uniform field.
- No new drawable/vector asset is *required* — Canvas is enough and keeps this screen theme-reactive at
  draw time (a vector drawable would need per-theme tinting anyway). If an engineer prefers a vector for
  easier art-directed tweaking, a single `moon_phase.xml` vector could be built as 8 stacked masked
  layers (one per major phase, cross-faded by nearest match) — more asset work for less flexibility;
  Canvas is the recommended path.

### 1.2 Seasons and the passage of the night

**What it looks like.** Two independent, cheap variations:
- **Real moon phase** (above) already gives the sky a reason to look different night to night — a child
  who glances at it regularly will notice it's "really" waxing and waning, which is a nicer hook than any
  invented seasonal reskin.
- **A slow dawn.** As the unlock time approaches, blend a low-alpha warm tint (soft peach/gold, alpha
  ≤0.12) into the sky gradient, mixed via `Palette.blend`, recomputed on the clock's existing 30-second
  tick (`BedtimeScreen.kt:53-54`) — reusing a timer that's already running rather than adding one. By the
  last 30–45 minutes before `until`, the sky has visibly started to lighten at the horizon. This makes
  the ending feel like something that arrives on its own, the way real mornings do, rather than a
  countdown a parent imposed.

**Why it doesn't read as celebration.** Dawn approaching is *true* — it's a literal readout of "how much
night is left" restated as light instead of digits — not an invented reward for waiting quietly. It
changes at the same rate regardless of anything the child does.

**Seasonal palette (optional, lower priority).** A very subtle month-based hue drift (cooler
blue-violet in December, warmer indigo in July) in the base sky gradient, same low-alpha-blend technique.
This is the one genuinely subjective piece of this section — it adds almost nothing most nights and is
easy to cut for scope. **Alternative:** skip seasons entirely and let the real moon phase carry all of
"the sky changes over time"; it's free (already being computed) and true, where invented seasons are
decorative-only.

### 1.3 Showing the unlock time kindly

**Today:** `BedtimeScreen.kt:62-64` shows "Déverrouillage à 07:00" — a raw timestamp, the single most
"admin console" string on this screen.

**Proposal:** lead with a relative, human framing; keep the exact time as a small secondary line for
kids (and parents glancing over a shoulder) who think in clock time:

```
        (moon + stars canvas)

           23:20  ← current time, unchanged, big

          Bonne nuit !

      Tu te réveilles dans 3h40
              (à 07:00)
```

New strings (only for the scheduled/`REASON_NIGHT` case — a manual lock has no known end, so it keeps
today's `bedtime_manual` unchanged):

| key | EN | FR |
|---|---|---|
| `bedtime_wakes_in_hours` | `Wakes up in %1$dh %2$02dm` | `Tu te réveilles dans %1$dh%2$02d` |
| `bedtime_wakes_in_minutes` | `Wakes up in %1$d min` | `Tu te réveilles dans %1$d min` |
| `bedtime_wakes_at` | `at %1$s` (small caption under the above) | `à %1$s` |

Use `bedtime_wakes_in_minutes` under 60 minutes remaining so it never reads as the slightly odd "0h 45m".
`bedtime_until` stays in the strings table for the manual-lock path or as a fallback if remaining time
can't be computed.

**Progress arc (optional, second-priority).** A thin arc traced around the moon, sweep = elapsed
fraction of the lock window, recomputed on the same 30s tick — "how much of the night is done" as a
shape instead of a number. Only render it when `reason == REASON_NIGHT` and the schedule is enabled,
since that's the only case with a known start time (`ManagedViewModel.lockSchedule`'s weekday/weekend
start for "today"); skip it for a manual lock, where there is no fixed duration to show progress against
— an arc that never fills would be worse than no arc. **This is the subjective one to flag explicitly:**
it's a nice touch but adds a second time-telling element next to the relative text above; an alternative
is to ship only the relative/absolute text pairing and hold the arc for a follow-up once it's been seen
on a device.

### 1.4 Light/dark and accent — a design decision to flag

The bedtime screen currently just uses `colorScheme.surfaceVariant`→`surface` (`BedtimeScreen.kt:70`),
so if a parent has picked the *light* theme, "bedtime" renders as a pale gradient — which undercuts the
whole point of a night sky. **Proposal (subjective, flagging the alternative):** have `BedtimeScreen`
always render its sky in a dark "night" treatment — a deep navy/charcoal blended with the pushed accent
via `Palette.darken(seed, …)` — regardless of the parent's light/dark preference, because this screen
represents actual night to the child, not "the app's current theme". The moon and stars are drawn in
light tones against it either way, so contrast holds. **Alternative:** respect the parent's theme choice
literally and adapt the moon/star rendering for a light sky (e.g. a pale sun-adjacent motif) — more
correct to the letter of "must survive light and dark", less correct to the metaphor. Recommend the
first: a bedtime screen that goes light because someone picked "Light" in Settings is a worse experience
than one that always looks like night.

### 1.5 Personalisation entry point on this screen — "tap the moon"

Covered in full in §4, but the interaction detail belongs here: the moon canvas from §1.1 is added as a
sibling layer *outside* the existing long-press `Column` (`BedtimeScreen.kt:76-101`), not nested inside
it, specifically so a plain tap on the moon's circular bounds can open the style picker without any risk
of colliding with the header's hidden long-press-for-parent-PIN gesture. Keep the moon's tappable area
generous (44dp minimum, centered on the drawn circle) even though the circle itself may be drawn smaller.

---

## 2. The launcher

### 2.1 Greeting

Already good: three tiers (`launcher_greeting_morning/afternoon/evening`, `ChildLauncherScreen.kt:93-97`)
and an informal register in French ("Bonjour !", not "Bonjour Monsieur"). Two additions:

- **Nickname** (§4): "Salut Max !" instead of "Bonsoir !" once a child has set one, falling back to the
  existing time-of-day text when empty. Implemented as a new format string
  `launcher_greeting_named` (`EN: "Hi %1$s!"` / `FR: "Salut %1$s !"`) picked instead of the plain
  `launcher_greeting_*` keys when a nickname is stored, keeping the same time-of-day logic underneath (a
  named "Hi Max!" at 11pm reads oddly — better to still vary the greeting word by hour and just splice
  the name in: `launcher_greeting_morning_named "Good morning, %1$s!"` etc., 3 EN + 3 FR pairs).
- **No separate "night" tier is needed** — the bedtime screen already owns actual night; the launcher's
  own "evening" tier only shows during the pre-bedtime window when the phone isn't locked yet.

### 2.2 Time-of-day character for the background

**What it looks like.** The existing accent gradient (`ChildLauncherScreen.kt:101-106`, a
`primaryContainer`→`background` vertical blend) gets one more very-low-alpha layer blended in by hour
bucket: a warm peach undertone in the morning, neutral through the day, a cool lavender undertone in the
evening. `t ≈ 0.08–0.12` via `Palette.blend`, so the parent's chosen accent colour still visibly dominates
— this is a mood layered on top of their choice, never a replacement for it.

**Why a child would like it.** It's the kind of thing that's felt more than seen — the screen matches the
time of day the way a room's light does, without becoming a wallpaper the child has to look at and judge.

**Why it's not gamification.** It carries zero information about behaviour, restriction level, or app
usage — it changes with the clock, identically whether the child has been perfectly cooperative or is
mid-argument about screen time. A cue that isn't tied to conduct can't function as a reward.

**How to implement.** A small `timeOfDayTint(hour: Int): Color` returning the warm/neutral/cool colour,
blended into the existing `gradient` `Brush.verticalGradient` call at `ChildLauncherScreen.kt:101-106`.
Recomputed on the existing `tick` (already ticking every 30s at line 84-89) — no new timer.

### 2.3 Tiles

`AppTile.kt` already has the right instinct (rounded pastel card, press-bounce via
`animateFloatAsState` at line 42). Nothing to change structurally; §3 covers the one motion addition
(staggered entrance) that belongs here.

### 2.4 The connection dot

Out of scope for accessibility (already covered by the UX review's Finding 6, which is about the
*parent-side* dot specifically — this one at `ChildLauncherScreen.kt:150-158` already has a
`contentDescription`). The one warmth addition: a one-shot scale pulse (1 → 1.15 → 1, ~300ms) the moment
`connected` flips, via `LaunchedEffect(connected)` driving an `animateFloatAsState` — not a continuous
pulse, just a brief acknowledgement that something changed, the same register as a chat app's "online"
dot animating in. See §3 for why this must stay one-shot.

### 2.5 Empty states

`launcher_no_apps` ("No app allowed yet. Ask a parent.") and `launcher_no_apps_installed` ("No app
found") currently render as plain centred text (`ChildLauncherScreen.kt:181-185`). Proposal: keep the
copy (it's honest and already brief — not this brief's job to rewrite it further), but give it the same
gentle visual weight as the rest of the screen instead of looking like an error state: wrap it in a
softly-rounded `Surface` using `primaryContainer.copy(alpha = 0.3f)` (matching the tile treatment) with
generous padding, so an empty grid still looks like "this app", not like a crash screen. No new icon
needed — the previous review's Finding 5 (a persistent "5 allowed" caption once *some* apps are allowed)
is the substantive fix for the restricted case; this is only about not letting the *fully* empty case
look broken.

### 2.6 Should time of day change the screen's character?

Yes, but only at the "mood tint" level in §2.2 — not structurally. A launcher that rearranges its layout,
switches typography, or changes which affordances are visible by hour would cost more (recomposition,
QA surface, a child having to relearn where things are at different times) than it buys. Keep the
structural layout constant all day; let only colour breathe with time. This is the same principle as the
bedtime screen's dawn-blend in §1.2 — light, not layout, carries the passage of time.

---

## 3. Motion

Ledger of where motion is worth its cost and where it isn't, given the hard constraint that this is a
home screen that may be on a cheap, low-battery device for hours at a stretch.

### Already in the app (keep)
- `AppTile.kt:42` press-bounce (`animateFloatAsState`, triggered only on touch) — cheap, bounded, already
  battery-safe because it only runs while a finger is on the tile.

### Worth adding — all bounded, all one-shot or timer-piggybacked
| Where | What | Cost |
|---|---|---|
| Bedtime moon/stars (§1.1) | Fade-in 0→1 alpha, ~800ms, on first composition only | One-shot, negligible |
| Bedtime dawn tint (§1.2) | Recomputed on the existing 30s clock tick | No new timer |
| Launcher tile grid | Staggered fade+scale-in on first load, capped at ~6 tiles' worth of stagger delay so a 10-app grid doesn't take a full second to finish appearing | One-shot on screen entry |
| Launcher greeting text | `Crossfade` when the greeting string changes (hour-boundary or nickname save) | Fires a few times a day at most |
| Connection dot (§2.4) | One-shot scale pulse on state flip | Fires only on actual connect/disconnect |
| Pause banner (§Lead item 3) | `AnimatedVisibility` enter/exit instead of popping | One-shot per pause/resume |

### Explicitly not worth it — would be noise or a battery cost
- **Any `infiniteRepeatable` or `rememberInfiniteTransition`** anywhere on these screens — a shimmering
  sky, a breathing gradient, a twinkling star loop — keeps Compose recomposing every frame for as long as
  the screen is on, which on a locked phone that's supposed to sit untouched for eight hours is a real,
  measurable battery cost for a decorative effect nobody is actively watching.
- **Star "twinkle"**: instead of a loop, piggyback on the clock's existing 30s tick to nudge one or two
  stars' alpha per tick (§1.1). Looks alive over the course of a minute without ever running its own
  animation clock.
- **A bouncing/animated mascot on the bedtime screen** — ruled out entirely per the brief's own tension:
  motion that performs (cheering, waving, celebrating) reads as mocking a child who's disappointed about
  bedtime, no matter how "cute" the character. Static scenery has no implied attitude; an animated
  character always does.
- **Parallax on tilt/gyroscope** — sensor polling for a cosmetic effect is the wrong trade on a
  battery-constrained always-on device.

---

## 4. Personalisation a child can own

This is the highest-value direction in the brief, so here's the full shape of it, not just a mention.

### The principle
Everything here is stored **locally on the child's device only**, in `ManagedPrefs` (see
`app/src/main/java/edu/fnosari/momedm/persistence/ManagedPrefs.kt`) as new keys that are **not** part of
`ChildPrefs` (the struct the parent pushes over BLE via `SET_PREFS`, per `ManagedPrefs.kt:51-54,93-96`)
and are never read by, or exposed to, the parent app. That asymmetry is the whole point: the parent
controls rules (child mode on/off, allowed apps, lock schedule, theme/accent/language); the child controls
*only* decoration that sits on top of those rules and cannot be mistaken for negotiating them. A parent
opening `ChildMenuScreen` or the controller app should see nothing about nickname/moon style/mood tint —
there's genuinely nothing there for them to see or approve, which is what makes it the child's.

### 4.1 A nickname for the greeting
- **What:** a short name (≤14 chars, simple validation) used to personalise the launcher greeting
  (§2.1) — "Salut Max !" instead of "Bonsoir !".
- **Where:** a small, low-emphasis text link — `TextButton`, `bodySmall`, `onSurfaceVariant` — placed as
  a **sibling composable directly below** the existing header `Row` (insert after
  `ChildLauncherScreen.kt:159`, before the `if (paused)` block at line 161), *not* nested inside that
  `Row`. That placement is deliberate: the `Row` already carries the header's hidden long-press-for-PIN
  gesture across its full bounds (`ChildLauncherScreen.kt:113-141`); a tap target added as a sibling
  underneath can never collide with it, where nesting a `clickable` inside the same `Row` risks the two
  gesture detectors fighting over the same touch (flag this for the implementing engineer to verify
  either way — Compose's nested-pointer-input behaviour here is exactly the kind of thing worth a quick
  manual test before shipping).
- **Copy:**

  | key | EN | FR |
  |---|---|---|
  | `personalize_link` | `Personalize` | `Personnaliser` |
  | `personalize_title` | `Make this screen yours` | `Fais de cet écran le tien` |
  | `personalize_nickname_label` | `What should we call you?` | `Comment veux-tu qu'on t'appelle ?` |
  | `personalize_nickname_hint` | `Your name` | `Ton prénom` |
  | `personalize_save` | `Save` | `Enregistrer` |
  | `personalize_cancel` | `Cancel` | `Annuler` |

- **Why a child would like it:** it's their name, in their voice, on the one screen that's theirs all
  day — small, but it's the difference between "a device configured for a child" and "my phone".
- **Why it can't weaken any rule:** it only ever feeds a `String` into a greeting template; there's no
  code path from this value to kiosk state, lock schedule, or safety config.
- **Implementation:** new `ManagedPrefs` key `KEY_CHILD_NICKNAME`, `readString`/`write`, exposed as a
  `Flow<String>` alongside the existing ones; a `PersonalizeDialog` composable (same shape as the
  existing `PinDialog`) reachable from the new `TextButton`; greeting selection logic in
  `ChildLauncherScreen.kt:93-97` extended to pick the `_named` variants from §2.1 when non-blank.

### 4.2 A moon style for the bedtime screen
- **What:** 3–4 Canvas-drawn variations on the §1.1 illustration — e.g. *Classic* (the real-phase
  crescent/gibbous moon), *Full always* (always drawn full, for a child who just prefers how it looks),
  *Ringed* (a simple Saturn-like ring drawn as two overlapping ellipses around the disc), *Cluster* (no
  moon, just a denser, larger star field). All are pure `Canvas` draws — no new assets — so this is a
  handful of `when` branches inside the `NightSky` composable from §1.1, not new drawables.
- **Where:** tap the moon itself on the bedtime screen (§1.5) — discoverable by being visibly the most
  interesting thing on the screen, unlike the hidden header long-press, and structurally isolated from it
  so the two gestures can't collide (see §1.5).
- **Why a child would like it:** picking "their" sky is a genuinely personal choice with no functional
  weight — closer to picking a wallpaper than to anything the parent app touches.
- **Why it doesn't undercut the "company, not celebration" rule from the brief:** the picker itself opens
  on a deliberate tap, is available equally every night regardless of behaviour, and none of the four
  options is more "rewarding" than another — there's no unlockable/best option, just taste. Compare to a
  star-collection mechanic (tempting, and explicitly the wrong move): accumulating stars for
  cooperative nights would gamify bedtime exactly as the brief warns against, so this is a flat, always-
  fully-available menu, never a progression.
- **Copy:** `personalize_moon_title` (`EN: "Pick your sky"` / `FR: "Choisis ton ciel"`),
  `moon_style_classic` / `_full` / `_ringed` / `_cluster` (`EN: "Real moon"`/`"Full moon"`/`"Rings"`/
  `"Stars only"`; `FR: "Vraie lune"`/`"Pleine lune"`/`"Anneaux"`/`"Juste des étoiles"`).
- **Implementation:** `KEY_MOON_STYLE` in `ManagedPrefs`, a small enum, read by `BedtimeScreen` to pick
  the `NightSky` branch.

### 4.3 A mood tint for the launcher
- **What:** 3 subtle presets for the §2.2 time-of-day tint layer — *Calm* (cool), *Sunny* (warm),
  *Neutral* (off) — letting a child nudge the mood without touching the parent's actual accent colour
  underneath.
- **Where:** third option inside the same `personalize_title` dialog as §4.1, so there's one entry
  point, not three scattered ones.
- **Why it's safe:** it only adjusts the low-alpha overlay from §2.2, mathematically incapable of
  overriding the pushed accent, since it's blended at a fixed low `t` on top of, never instead of, the
  parent's `MaterialTheme.colorScheme.primaryContainer`.
- **Copy:** `personalize_mood_label` (`EN: "Screen mood"` / `FR: "Ambiance de l'écran"`),
  `mood_calm`/`mood_sunny`/`mood_neutral` (`EN: "Calm"`/`"Sunny"`/`"As-is"`; `FR: "Calme"`/`"Ensoleillé"`/
  `"Normal"`).

### What was deliberately left out
- **Tile reordering / custom tile colours per app** — plausible, but scope-creep for this pass; flagging
  as a natural follow-on once the above ships, not a rejection.
- **Any streak, badge, or "days without a mis-tap" counter** — explicitly rejected. The brief is clear
  that the launcher must not reward or gamify screen time, and a counter of *anything* tied to behaviour
  turns "your phone" into a scoreboard, which is precisely the opposite of what §4 is trying to buy.

---

## 5. Making a locked or restricted moment feel like an agreed rule, not a punishment

- **Fix the pause banner's colour** (lead item 3, §above) — concretely, swap
  `MaterialTheme.colorScheme.tertiaryContainer` at `ChildLauncherScreen.kt:163` for
  `MaterialTheme.colorScheme.primaryContainer` (or a dedicated blend off the pushed accent), since a
  parent-granted ten-minute pause is neutral-to-good news and should never inherit Material3's default
  tertiary, which happens to land on a dusty rose that reads as an alert.
- **Let the ending arrive on its own, visually** (§1.2, §1.3) — a sky that lightens toward the real
  unlock time and a "wakes up in…" framing both restate the exact same fact the countdown already
  states, just as something that's *happening* rather than a deadline being enforced. That reframing does
  real work without adding a single new claim to the copy.
- **Deliberately not proposing:** on-screen copy asserting the rule was "agreed together" or similar
  (e.g. "Tes parents et toi avez décidé ensemble de ce couvre-feu"). It's tempting — it's exactly the
  sentiment this section is aiming for — but the app has no way to know whether that's true for a given
  family, and the previous UX review's whole throughline is that this app *undersells* rather than
  oversells (`safety_explain`, `appcfg_none`). Inventing an agreement that may not have happened would
  break that pattern for a small emotional payoff. Let warmth carry this implicitly (moon, colour, the
  dawn-not-deadline framing) rather than asserting it in words.
- **The restricted-apps grid** (`child-launcher-childmode.png`): the previous review's Finding 5 already
  proposes the substantive fix (a persistent "N allowed" caption). The warmth-layer note to add on top of
  that fix: phrase it informally rather than as a count of a restriction — reusing `child_allowed_count`
  ("5 allowed" / "5 autorisée(s)") as-is already reads neutrally enough; resist the temptation to
  editorialize further ("only 5 apps 😢" or similar) — plain and calm beats cute here, for the same reason
  the bedtime screen shouldn't cheer.

---

## Summary of new strings proposed (EN + FR pairs, both files)

`bedtime_wakes_in_hours`, `bedtime_wakes_in_minutes`, `bedtime_wakes_at`, `personalize_link`,
`personalize_title`, `personalize_nickname_label`, `personalize_nickname_hint`, `personalize_save`,
`personalize_cancel`, `launcher_greeting_morning_named`, `launcher_greeting_afternoon_named`,
`launcher_greeting_evening_named`, `personalize_moon_title`, `moon_style_classic`, `moon_style_full`,
`moon_style_ringed`, `moon_style_cluster`, `personalize_mood_label`, `mood_calm`, `mood_sunny`,
`mood_neutral`. All are plain, jargon-free, and match the informal "tu" register already used throughout
`values-fr/strings.xml`.

## Summary of new code surfaces proposed

- `NightSky` Canvas composable (new file, e.g. `activities/managed/components/NightSky.kt`) with a
  `moonPhase(nowMs: Long): Float` pure function alongside it (unit-testable).
- `timeOfDayTint(hour: Int): Color` helper for the launcher gradient.
- `PersonalizeDialog` composable (new file, shaped like the existing `PinDialog`).
- Three new `ManagedPrefs` keys (`KEY_CHILD_NICKNAME`, `KEY_MOON_STYLE`, `KEY_MOOD_TINT`) — local-only,
  no changes to `ChildPrefs` or the BLE protocol.
- Small edits to `BedtimeScreen.kt` (sky layer, relative-time strings, dawn tint) and
  `ChildLauncherScreen.kt` (mood tint in the gradient, personalize link, pause banner colour, tile
  stagger-in, connection-dot pulse).

No new Gradle dependencies, no `material-icons-extended`, no image/animation libraries — every idea above
is buildable with `Canvas`, `Brush`, and the animation APIs already in `androidx.compose.animation`/
`animation-core` that the project already depends on via the Compose BOM.
