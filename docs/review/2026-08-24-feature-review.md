# Môme DM — feature review and what to build next

Date: 2026-08-24. Scope: `edu.fnosari.momedm`, read against `README.md`,
`docs/architecture.md`, `app/src/main/java/edu/fnosari/momedm/protocol/Messages.kt`,
and `app/src/main/res/values/strings.xml`, then cross-checked against the
persistence, managed, and controller packages. Read-only review, no code
changed.

## 1. Inventory — what the app actually does today

The command surface is small and closed: `CmdType` in
`protocol/Messages.kt:7` lists exactly thirteen things a parent can ask a
child to do — `KIOSK_ON`, `KIOSK_OFF`, `INSTALL`, `ADD_ACCOUNT`, `LIST_APPS`,
`GET_STATUS`, `SET_PREFS`, `SET_SCHEDULE`, `LOCK_NOW`, `UNLOCK`,
`SEARCH_APP`, `SET_SAFETY`, `GET_APP_SCHEMA`. `managed/CommandExecutor.kt`
handles every one of them and nothing else — the enum is a complete and
accurate inventory of the protocol, and every message type it defines is
both sent by the controller UI and consumed by the managed side. There is no
dead code and no command the README claims that the enum doesn't back up.

Confirmed against code:

- **App allowlist / kiosk.** `KIOSK_ON{apps, pinned}` drives Android lock
  task via `managed/PolicyManager.kt`; `pinned` must be a member of `apps`
  (`CommandExecutor.kt` strips it otherwise and says so in the result
  string). Matches the README's "choose which apps exist" and "pin to one
  app" claims exactly.
- **Content restrictions.** `SET_SAFETY` → `managed/SafetyManager.kt`. Two
  independent halves, as the README says: managed-configuration bundles
  built generically from whatever JSON an app declares
  (`SafetyManager.toBundle`, handling bool/int/string/string-array/nested
  object/list-of-objects), and a phone-wide private DNS resolver
  (`setGlobalPrivateDnsModeSpecifiedHost`). `GET_APP_SCHEMA` /
  `Message.Schema` is the generic-form half the README calls "a general
  escape hatch" — it is genuinely schema-driven, not a hardcoded Chrome
  integration; `managed/AppSchemaReader.kt` is where to look if extending it.
- **Night lock.** `SET_SCHEDULE` → `protocol/LockSchedule.kt`, a pure-Kotlin,
  DST-safe, midnight-wrapping weekday/weekend window. `LockState.evaluate`
  in the same file is a pure function of `(schedule, manualLock, pauseUntil,
  now)` — never persisted, recomputed on every trigger. This statelessness
  claim in the README is real and is exactly what `LockController.kt`
  implements; two regressions this design was built to prevent are cited in
  `LockControllerTest`.
- **`LOCK_NOW` / `UNLOCK`.** Manual lock, independent of the night schedule,
  survives reboot (`ManagedPrefs.KEY_LOCK_MANUAL`), and — confirmed — cannot
  be cleared remotely while a *night* window is what's actually active; the
  README's "known limitations" claim about this is accurate and matches
  `docs/architecture.md`'s note that the parent's Unlock button only shows
  for a manual lock.
- **PIN pause.** 10-minute pause, PBKDF2-HMAC-SHA256/20000/per-device-salt
  hash-only on the child (`protocol/PinHash.kt` pattern referenced from
  `ChildPrefs`), growing lockout persisted across process death
  (`ManagedPrefs.KEY_PIN_FAILURES`/`KEY_PIN_LOCK_UNTIL`). Real, not just UI.
- **Parent menu on the child device** (`menu_*` strings,
  `activities/managed/screens/ChildMenuScreen.kt`) reads current
  schedule/kiosk/connection state from local storage — works offline, as
  claimed.
- **Re-pairing.** `activities/managed/RepairScanActivity.kt` lets the child
  device overwrite its stored `controllerId`/`secret`
  (`ManagedPrefs.saveProvisioning`) by scanning a fresh QR. This is the
  README's "way back if the parent's phone is lost, replaced, or
  reinstalled" and it is real — but see §2.5, because it only recovers a
  *lost parent phone*, not a *lost PIN*.
- **Multiple children, one parent phone.** `persistence/DeviceRegistry.kt`
  keeps a list of `DeviceRecord`s; `controller/ControllerService.kt`'s BLE
  server accepts up to `clientLimit = 7` concurrent connections
  (`ControllerService.kt:140`). Per-child state (apps, schedule, safety) is
  correctly scoped by `deviceId` throughout.
- **Status / "current app".** `managed/StatusCollector.kt` reports a
  *point-in-time* foreground app (last `ACTIVITY_RESUMED` event in the past
  60 s, `FG_WINDOW_MS`) gated on optional usage access, pushed every 5
  minutes while connected (`ManagedLinkService.kt:51`,
  `STATUS_PERIOD_MS = 5 * 60_000L`) and on every reconnect. Nothing is
  accumulated or logged — `DeviceRegistry.updateStatus` simply overwrites
  `lastStatus`. There is no on-device or on-controller history of what was
  used, for how long, or what was attempted.
- **Connection diagnostics.** `settings_connection` screen and
  `conn_event_*` strings back a real event log in `ControllerService`/
  `SessionManager` (connected / recognised / rejected-wrong-secret /
  disconnected), matching the README's claim about the "connected but not
  recognised" case specifically.

Where the README slightly *overstates*: the "parent chooses how it looks"
section reads as if appearance were set once. In fact `ControllerPrefs`
stores exactly one `language`/`theme`/`accent`/PIN pair per controller
install, and `ControllerService.kt:138` pushes that same `SET_PREFS` to
*every* online child identically on any change. A family with two children
cannot give them different app colours, and — more materially — cannot give
them different parent PINs; there is exactly one PIN for the whole
household, sent to whichever children happen to be connected when it
changes (an offline child keeps the old hash until it reconnects, silently).
This isn't wrong, but it is undocumented and worth a line in the README.

Where the README is *accurate and appropriately modest*: "known limitations"
correctly calls out the single shared secret, no silent install, unencrypted
(but authenticated) BLE, and the night-lock-can't-be-ended-remotely case. All
four are verified against the code as described above.

## 2. Gaps that matter for real families

Framed around the actual job: agreeing rules with a child up to ~14, on two
phones, at home and out of the house.

### 2.1 Time is binary, not a budget

The only time primitive is `LockSchedule` — a nightly on/off window. There is
no concept of "two hours of screen time today," no per-app timer ("30 min of
YouTube, then it's gone"), and no weekday/weekend *daytime* budget distinct
from apps being allowed at all. A parent who wants "homework first, then an
hour of games" today has one lever: manually toggling `KIOSK_ON`/`KIOSK_OFF`
or editing the app list by hand, which is a phone-in-hand action, not
something the child's phone enforces on a schedule. This is the single
biggest gap for the stated audience — screen-time budgets are the most
common ask from parents of pre-teens, ahead of bedtime.

### 2.2 No channel for the child to ask for anything

`CmdType` is entirely parent→child; the only messages a child ever
originates are replies to a command (`RESULT`, `STATUS`, `APPS`, `SCHEMA`)
plus an unprompted `PING` keepalive
(`protocol/Messages.kt:66`). There is no "child requests 15 more minutes,"
no "child requests this app be added," nothing that surfaces on the parent's
device screen as a pending ask. The only child-initiated action that changes
anything is entering the parent's own PIN, which is a bypass, not a request
— and it requires the child to *know* the PIN, which the design otherwise
goes out of its way to keep hidden (no visible lock button; PIN pad reached
by long-pressing the header, `README.md`'s "A home screen built for a
child" section). Right now the only sanctioned way for a 10-year-old to ask
for more Minecraft time is to shout up the stairs.

### 2.3 Transparency is decent but PIN-gated where it matters most

To the design's credit, some transparency already exists without a PIN: the
bedtime screen shows `bedtime_until` ("Unlocks at %s"), and the launcher
shows `launcher_no_apps` ("No app allowed yet. Ask a parent.") — both are
genuinely helpful, unprompted explanations, and worth acknowledging as
already-solved. But the fuller answer to "why is this phone behaving like
this" — the parent menu's `menu_rules` screen — is reachable only through
the same PIN that also grants a 10-minute unrestricted pause
(`docs/architecture.md`, "A parent menu on the child's phone"). There is no
way to see *just* the rules without also being handed the keys. A child who
is simply curious, not trying to cheat, has no route to that information.

### 2.4 Households with more than one parent aren't supported

`ControllerPrefs` generates one `controllerId`/`secret` per app *install*
(`ControllerPrefs.kt:56`, `regenerateSecret`), and a managed device stores
exactly one controller identity at a time
(`ManagedPrefs.KEY_CONTROLLER_ID`/`KEY_SECRET`, single flows, overwritten
wholesale by `saveProvisioning`). There is no way for a second parent's
phone to also control the same child device — the only path is
`RepairScanActivity`, which *replaces* the pairing, it doesn't add one. Two
co-parents (a common real-world case, married or separated) cannot both have
a live controller on their own phone for the same child at the same time.
Whoever re-pairs last silently locks the other parent out with no
notification to either side.

### 2.5 Recovery has a hard floor: lose the PIN and the phone together, and you're done

Re-pairing (§1) genuinely solves "I got a new phone" or "I lost my old
phone" — as long as the parent still remembers the PIN and can reach the
child's parent menu to trigger it. If both are gone (phone lost *and* PIN
forgotten, or the PIN was never set at all and the only enrolled controller
identity is gone), there is no recorded recovery path, because the app is
also deliberately built so that "physical access wins" and a factory reset
is the only escape hatch. That's an honest design tradeoff (stated in the
README's "known limitations"), but it's currently not written down for the
PIN-specific version of it — "you must remember this PIN forever or you may
have to factory-reset your child's phone" is a stronger claim than what a
family will read before they set one.

### 2.6 One rule set can't distinguish a 7-year-old from a 13-year-old under one parent identity

As found in §1: appearance and the parent PIN are controller-wide, pushed
identically to every connected child (`ControllerService.kt:138`). Apps,
schedule, and content-safety level are correctly per-child. This is a minor
inconsistency rather than a functional gap (a shared parent PIN across
siblings is arguably fine, even desirable), but it means the one place a
family *might* reasonably want per-child difference — content-restriction
DNS provider display or theme — can't have it, and it's easy to trip over
when adding a second child.

### 2.7 No usage history, so "how much did they actually use it" has no answer

Related to 2.1 but distinct: even without budgets, a parent who just wants
to *see* "30 minutes of TikTok yesterday evening" has nothing — `lastStatus`
is overwritten every 5 minutes, there is no log. A parent who suspects a
rule is being circumvented (child mode toggled off and back on while out of
range, say) has no record to check against.

## 3. Proposed improvements

Each entry: what, why, rough build cost given the BLE-only/no-server/
child-enforces-locally architecture, and what it breaks or complicates.
Constraint-fighting proposals are flagged explicitly.

### P1 — Per-app or whole-device daily time budget (in addition to the night window)

**What.** A new schedule primitive alongside `LockSchedule`: "N minutes of
child-mode screen time per day" (whole-device) and, optionally, per-package
minute caps within the allowlist. Enforced by counting `UsageEvents` while
child mode is on and re-evaluating the same way `LockController` already
does for the night window.

**Why.** This is the #1 gap in §2.1 and the single most requested feature
from families with a child under ~14 — bedtime and screen-time-budget are
the two asks parents actually have, and the app currently has only one of
them.

**Cost.** Medium-large but architecturally native: the app already has the
right shape for this. Add `DailyBudget` to `protocol/` (pure Kotlin, same
treatment as `LockSchedule` — sanitized, unit-testable, no Android). On the
managed side, a new `UsageAccumulator` that polls `UsageStatsManager`
(already used read-only by `StatusCollector.foregroundApp()`) on an
`AlarmManager` cadence, decrementing a per-day counter that resets at local
midnight and persists in `ManagedPrefs` (a running total, not a "locked"
flag, so it survives reboot the same principled way `LockController` does —
recompute "minutes used today" from stored per-session deltas rather than
trusting a single mutable counter that a crash could double-count). New
`CmdType.SET_BUDGET`, a `budgetRemaining` field on `Status`, and a
budget-exhausted reason alongside `REASON_NIGHT`/`REASON_MANUAL` in
`LockState`. UI: a slider or number picker on the child's device page,
mirroring the existing night-window UI (`child_night_*` strings).

**What it breaks/complicates.** Needs a new locking *reason* that
`LockController` treats correctly relative to a PIN pause (pausing must not
reset the budget clock — currently `PIN pause` truly suspends all
enforcement, which is fine for "check something quickly" but would let a
child launder budget minutes through repeated pauses unless the accumulator
keeps running through a pause). Usage accounting is inherently approximate
without the child's phone staying awake and `UsageStatsManager` being
granted — it's optional today (`setup_usage_text` says "Optional"); a budget
feature would need to make usage access load-bearing rather than optional,
which is a real UX/consent change worth calling out to users. Does not fight
the no-cloud constraint at all — it's a pure child-side extension of
existing state machinery.

### P2 — A child-initiated "ask for more time / ask to add an app" request, surfaced on the parent's phone

**What.** A new lightweight message type, `Message.Ask{kind, detail}`
(e.g. `EXTEND_15`, `ADD_APP{pkg}`), sent from managed→controller without a
PIN. On the launcher, a plain button — "Ask for 15 more minutes" or a
long-press on a greyed-out install prompt — that fires it over the already-
open BLE link when connected, and queues it locally
(`ManagedPrefs`-backed pending list) to send on the next connection when
not. The parent's device page shows a badge and a notification (the
controller already runs a foreground service and posts notifications) with
Approve/Deny, which maps directly onto existing commands: approve-more-time
= `SET_SCHEDULE`/budget-pause command, approve-app = the existing `INSTALL`/
`KIOSK_ON` with the app added.

**Why.** Directly answers §2.2. It reframes the PIN from "the only way a
child can get anything" to "the emergency override," which is healthier for
the actual parent-child relationship this app is trying to support, and it
gives the parent something to say yes to instead of only no.

**Cost.** Small-to-medium. It's one new message type plus queuing, no new
enforcement logic — the actual grants route through commands that already
exist. The device-owner/foreground-service/notification machinery is all in
place.

**What it breaks/complicates.** If unanswered while the parent is out of
range (the core promise of this app), the ask just sits pending — needs
honest UI on the child's side ("Sent — not answered yet") rather than
implying an instant response, since there is no guarantee the parent's
phone is anywhere near. Doesn't fight no-cloud; it's BLE-only like
everything else, and degrades gracefully to "nothing happens until they're
back in range," which is consistent with the rest of the product's story.

### P3 — A visible, no-PIN "why" screen on the child's device

**What.** Promote a subset of `menu_rules` (the schedule in force, whether
budget/night lock is active and until when, which apps are currently
allowed) to a screen reachable without the PIN — e.g. a small "i" affordance
on the launcher header distinct from the long-press-for-PIN gesture, showing
read-only facts already computed for the launcher and bedtime screens today
(`bedtime_until` and `launcher_no_apps` already do a version of this).

**Why.** Directly answers §2.3. Every one of the facts this would show is
already assembled and rendered elsewhere in the app (`Status`, `LockState`,
`kioskConfig`) — it is not new data, only a new door to it that doesn't also
open the "unrestricted for 10 minutes" door.

**Cost.** Small. Pure UI: a new read-only composable over existing state
flows (`ManagedLinkState.lastStatus`, `ManagedPrefs.lockSchedule`,
`kioskConfig`), no protocol change, no persistence change.

**What it breaks/complicates.** Almost nothing, if scoped strictly to
read-only facts already surfaced elsewhere (schedule times, current
allowlist, lock reason). The one thing to get right: it must not leak
anything that would help circumvent the lock (e.g. must not show the PIN
lockout counter's exact reset time in a way that helps time an attack, and
must not show anything about *other* children's devices if a future
multi-child parent identity view leaks into it).

### P4 — Independent secrets per child, keyed off one parent identity (partially planned already)

**What.** `docs/architecture.md`'s own "Known limitations" says "v1 uses one
shared secret for all devices provisioned by a controller... Per-device
secrets are planned." This is already the project's own next step, not a
new idea — worth flagging here because it's real work already scoped by the
authors: each `DeviceRecord` gets its own derived or independently-generated
secret instead of sharing `ControllerPrefs.secretBase64`, so compromising
one child's phone doesn't expose siblings.

**Why.** Directly closes a stated, code-verified weakness (§1, "one shared
secret per parent"). Matters more as household size grows — it's the
"per-day rules, more than one child" axis of the brief, from the trust
angle rather than the feature angle.

**Cost.** Medium. `ControllerIdentity` becomes per-device rather than
per-controller; provisioning QR generation and the HELLO/CHALLENGE/AUTH
handshake (`docs/architecture.md`, "Security") need to carry/derive a
per-device secret, and `DeviceRegistry` needs to hold it. Contained to
`controller/provisioning/` and `protocol/`, but touches the handshake, so
it needs the full JVM-test treatment `SecureChannel`/`Framer` already have,
plus a two-emulator pass per `CONTRIBUTING.md`'s rule that anything touching
provisioning must be exercised on the rig.

**What it breaks/complicates.** Regenerating a secret today invalidates
every child at once with one clear confirmation
(`regenerate_key_warning`); per-device secrets make "regenerate" ambiguous
(one device? all of them? — needs its own UI decision) and the re-pairing
flow (`RepairScanActivity`) needs to carry the right per-device secret, not
the controller's.

### P5 — A second parent identity per child (multi-parent households)

**What.** Let a child device hold *two* controller identities
simultaneously (or N), each independently authenticated, so two parents'
phones can both connect and both issue commands to the same child.

**Why.** Directly answers §2.4, which is a real and common family shape.

**Cost.** Large, and this is the one proposal that most directly strains
the architecture as designed. `ManagedPrefs` currently models "the
controller" as a singleton (`KEY_CONTROLLER_ID`/`KEY_SECRET`, one value
each); `ManagedLinkService`/`BLEClient` scan for and connect to *a*
controller by service UUID with no notion of "which one." Making this work
needs: a list of trusted identities on the child, session/auth logic that
tries each in turn (or advertises differently per known controller — BLE
scanning-by-UUID makes "which parent is this" ambiguous until the HMAC
handshake succeeds), and — the actually hard part — a policy for *conflicting
commands from two parents* (parent A sets a night window, parent B disables
it five minutes later: last-write-wins is what the protocol does today for
everything, and with two parents that stops being a curiosity and becomes a
real source of confusion or a co-parenting conflict tool).

**What it breaks/complicates.** This is the one to be honest about: it
doesn't fight the no-cloud constraint (both parents' phones still only ever
talk BLE, still peer-to-peer, still no server), but it multiplies the
handshake/session code paths, doubles the "who can see what" surface
(should parent B see the log of parent A's commands? almost certainly yes,
for trust — but that means the *status/activity log* needs to become
multi-writer, and `DeviceRegistry`/`SessionManager` currently assume a
single owning controller for bookkeeping like `lastSeen`). Recommend
scoping v1 of this narrowly: two identities max, both full-privilege, last-
write-wins accepted as documented behavior (not silently), and the actual
conflict-resolution UI deferred.

### P6 — Persisted usage/event history, shown to the parent

**What.** Instead of `DeviceRegistry.lastStatus` being overwritten on every
push, append a bounded log (last N days, or last N events) of
kiosk-on/off, lock/unlock, safety-level changes, and (if P1 ships)
per-app minutes, stored on the controller.

**Why.** Answers §2.7, and is the natural companion to P1 (a budget without
any record of what it measured is hard to trust or discuss with a child).

**Cost.** Small-to-medium if scoped as "append what already crosses the
wire" — `Status` already carries everything needed; this is a controller-
side persistence change (`DeviceRegistry` gets a bounded ring buffer per
device) plus a screen, not a new protocol surface.

**What it breaks/complicates.** Storage growth is bounded by design choice
(cap at N entries), so this is low-risk. The one thing to get right:
per-app usage minutes are more sensitive than "was it locked" — this is
explicitly the kind of data a parent might over-rely on ("you used
Instagram 43 minutes yesterday" from a device that was disconnected for
part of that window is an estimate, not a fact) and the UI needs to be
honest about gaps when the child device was out of range.

### Explicitly constraint-fighting ideas, not recommended

- **Cloud sync / account so a parent can see status from work.** This is
  the no-cloud decision itself, stated as deliberate in the README's
  opening line. Do not build it; it is the product's identity, not a gap.
- **Silent app install / remote wipe.** `docs/architecture.md` already
  states these require a registered EMM, which a custom DPC cannot become
  without abandoning the "no account, no subscription" model (registered
  EMM status is an ongoing relationship with Google, not a one-time
  technical step). Correctly out of scope; don't revisit unless the whole
  business model changes.
- **Cross-device history synced between two parents' phones without either
  phone being near the child.** Falls out of P5/P6 naturally if the parent
  is near the child, but syncing parent-to-parent *without* going through
  the child device would need its own transport (cloud, or parent-to-parent
  BLE/Wi-Fi Direct) — resist the temptation; route everything through the
  child device, which is what keeps this app honest about where the source
  of truth lives.

## 4. Ranking — what to build next

**Build next, in this order: P1 (time budgets), P3 (no-PIN "why" screen), P2 (child-initiated requests).**

1. **P1 first.** It's the largest, most-requested capability gap (§2.1),
   and it's the most architecturally native of everything proposed — it
   extends a pattern (`LockSchedule`/`LockController`'s pure,
   never-persisted recomputation) the codebase already trusts and tests
   well, rather than introducing a new one.
2. **P3 second, deliberately before P2.** It's nearly free (read-only UI
   over data the app already has, §2.3), and it changes the emotional
   register of the product before the request feature (P2) lands — a child
   who can already see *why* a rule exists is a child who asks a more
   specific, more answerable question when P2 gives them a button to ask
   it with.
3. **P2 third.** It's the feature that most directly improves the
   parent-child relationship this app is implicitly designed around (§2.2),
   but it benefits from P1 existing first (there's more to meaningfully ask
   for — "more time" — once time is a budget rather than a fixed window)
   and from P3 existing first (a child who understands the rule asks a
   better question).

**P4 (per-device secrets) belongs on the roadmap regardless of ranking above**
— it's already the project's own stated next step in `docs/architecture.md`,
not a new suggestion, and should be done whenever provisioning code is next
touched for another reason, rather than as a dedicated release.

**P5 (multi-parent) and P6 (usage history) are real but should wait.** P5
specifically should not be attempted until the team is willing to answer the
conflicting-commands-from-two-parents question in the product, not just the
code — building the plumbing before deciding what "parent B overrides parent
A" should feel like will produce a feature that works and confuses families
anyway. P6 is low-cost but has more value once P1 gives it something
meaningful to record.

**Deliberately do not build:** cloud sync/remote status, silent install,
remote wipe, or any parent-to-parent channel that bypasses the child device.
All three are the no-cloud decision working as intended, not oversights —
building around them would be building a different, more conventional
product than the one `README.md` opens by describing.
