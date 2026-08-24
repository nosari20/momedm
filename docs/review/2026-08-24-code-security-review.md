# Môme DM — correctness and security review

Date: 2026-08-24 · Scope: `edu.fnosari.momedm` @ `fa03d2d` · Read-only review, nothing was executed.

## Summary

The cryptographic core is the strongest part of this codebase, and it is strong on purpose rather
than by accident. The handshake's domain separation is not just present but *justified in a comment
and asserted by a test that constructs the exact oracle it defends against*
(`HandshakeTest.challengeProofIsNotSessionKeyForConcatenatedNonce`). Nonces are shape-checked before
they reach any HMAC, MACs are direction-bound so a captured envelope cannot be reflected, sequence
numbers advance only after the MAC verifies, and any protocol error resets the whole session so no
captured frame survives it. `LockSchedule` gets midnight wrap, weekend attribution and both DST
transitions right, with tests. The central design claim — that lock state is a pure function of
`(schedule, manualLock, pauseUntil, now)` and is never persisted — **holds**: I looked for a stored
verdict and there isn't one, and `lastApplied` is genuinely an apply-dedup cache with a long and
accurate comment explaining why it cannot become one.

What worries me most is not in `protocol/`. It is that **the entire enforcement model can be
dismantled from the child's own screen without any secret at all.** When no parent PIN is set — and
nothing requires one — a long-press on the launcher header or on the bedtime clock opens the parent
menu directly, and that menu contains a "Re-pair" button that lets anyone holding the phone point it
at a different controller. A child with a friend's phone running this same app as a controller can
walk out of a bedtime lock in about a minute. `RepairScanActivity`'s own KDoc says it is "reachable
only from the launcher's paused state, which requires the parent PIN"; that stopped being true.

Second: `LockController.reevaluate()` is called from at least seven triggers on three dispatchers
with no mutual exclusion, and the code itself documents a boot-time race between two of them. The
apply-dedup cache turns an unlucky interleaving into a *persistent* wrong state — precisely the class
of defect the existing regression tests were written for, reached by a door those tests don't cover.

Third: the secure channel authenticates but does not encrypt, and the parent PIN's salt and hash ride
`SET_PREFS` across it in cleartext — over a GATT characteristic that is also plainly readable by any
unauthenticated central. A 4-digit PIN behind 20 000 PBKDF2 iterations is minutes of laptop time.

Counts: **3 high, 4 medium, 8 low confirmed; 3 suspected.**

---

# Confirmed findings

## H1 — The parent menu, and with it re-pairing, is reachable with no authentication when no PIN is set

**Where**
- `app/src/main/java/edu/fnosari/momedm/activities/managed/screens/ChildLauncherScreen.kt:132-139`
- `app/src/main/java/edu/fnosari/momedm/activities/managed/screens/BedtimeScreen.kt:86-88`
- `app/src/main/java/edu/fnosari/momedm/activities/managed/screens/ChildMenuScreen.kt:150-153`
- `app/src/main/java/edu/fnosari/momedm/activities/managed/RepairScanActivity.kt:55-60` (stale KDoc)

**What is wrong**

Both child-facing screens branch on `pinSet`. With a PIN, the long-press opens `PinDialog`. Without
one, it goes straight to `vm.menuOpen.value = true` — no challenge of any kind. `ChildMenuScreen`
then offers, unconditionally and regardless of lock state:

```kotlin
OutlinedButton(
    onClick = { context.startActivity(Intent(context, RepairScanActivity::class.java)) },
) { Text(stringResource(R.string.repair_open)) }
```

`RepairScanActivity` accepts any QR that parses as a pairing payload and calls
`saveProvisioning(identity.controllerId, identity.secretBase64)` followed by
`ManagedLinkService.restart(...)`. The PIN is optional — `SettingsPinScreen` offers it, nothing
requires it, and no UI warns that leaving it unset makes every other control advisory.

The comment at `BedtimeScreen.kt:257-259` states the rationale ("a completely locked phone with no
way to reach the parent menu") and `ChildLauncherScreen.kt:122-127` states the other ("a family that
never set a PIN would have no way to re-pair"). Both motivations are real. The resulting security
property is not.

**Scenario**

Bedtime lock is in force, no PIN configured. The child long-presses the clock on the bedtime screen →
parent menu opens → *Re-pair* → `RepairScanActivity` starts (same package, so lock task permits it) →
the child scans a QR produced by Môme DM running as a controller on a friend's phone. The child
device now holds that controller's id and secret. From the friend's phone: `UNLOCK`,
`SET_SCHEDULE {enabled = false}`, `KIOSK_OFF`, `SET_SAFETY {OFF}` (which also releases private DNS via
`setGlobalPrivateDnsModeOpportunistic`), and `SET_PREFS` with a null PIN pair. Complete bypass, no
root, no adb, no knowledge of the original secret. The real parent's controller can no longer reach
the device at all and sees only "offline".

**Suggested fix**

Layered, in order of value:

1. Make a PIN a precondition for enforcement: refuse `KIOSK_ON`, `LOCK_NOW` and an enabled
   `SET_SCHEDULE` while `pinHash == null`, returning a `Result` the parent's UI surfaces
   ("set a PIN first"). Refuse a `SET_PREFS` that clears the PIN while child mode or a lock is active.
2. Gate the re-pair action specifically. Require the PIN when one exists; when none exists, require
   that the device be neither in child mode nor locked. That preserves the recovery path the comment
   is protecting (a family with no PIN and no restrictions can still re-pair) without leaving it open
   on a locked device.
3. Update `RepairScanActivity`'s KDoc, which now asserts a guarantee the code does not provide.

---

## H2 — `LockController.reevaluate()` has no mutual exclusion, and the apply cache can latch a wrong state

**Where** `app/src/main/java/edu/fnosari/momedm/managed/LockController.kt:71` (`lastApplied`),
`:100-206` (`reevaluate`), `:95-98` (`endPause`).

**What is wrong**

`reevaluate()` is a plain `suspend fun` with no lock. It is invoked from, at minimum:

| Trigger | File | Dispatcher |
|---|---|---|
| `LockAlarmReceiver` | `LockAlarms.kt` (`onReceive`) | `Dispatchers.Default` |
| `BootReceiver` | `BootReceiver.kt` (`onReceive`) | `Dispatchers.Default` |
| `TimeChangeReceiver` | `TimeChangeReceiver.kt` (`onReceive`) | `Dispatchers.Default` |
| `ManagedLinkService.onStartCommand(fromBoot)` | `ManagedLinkService.kt:154` | `Dispatchers.Main` |
| `ManagedLinkService.startPauseWatchdog` | `ManagedLinkService.kt:249` | `Dispatchers.Main` |
| `ManagedViewModel.onResumed` / `trackPause` / `relock` | `ManagedViewModel.kt:165,199,206,297` | `viewModelScope` |
| `PolicyManager.kioskOn/kioskOff/setSchedule/setManualLock/pause` | `PolicyManager.kt:117,123,259,346,353` | caller's |

Two of these fire together by construction on every boot, and the code says so —
`BootReceiver.kt`: *"This clears it here and also in the service's boot reevaluate() path to close the
race: whichever path runs first would otherwise observe a stale deadline."* That comment closes the
race on `pauseUntil` while leaving the surrounding function racy.

Three compounding problems:

1. The three inputs are read as three independent `.first()` calls (`:103-105`), so a concurrent
   write between them yields a decision made from a torn snapshot.
2. `lastApplied` is `@Volatile` but the read at `:191` and the write at `:198` are not atomic with
   respect to the apply in between.
3. `result.onSuccess { lastApplied = applied }` records "what we applied" from a coroutine that may
   have applied *before* another one applied something different.

**Scenario**

At boot, coroutine A (`BootReceiver`) reads `pauseUntil` before the service's `setPauseUntil(0L)`
lands and computes unlocked; coroutine B (the service) reads after and computes locked (night window
open). Interleaving: A computes `Applied(unlocked, kiosk)`, B computes `Applied(locked, kiosk)`, B
runs `lockComplete()` and writes `lastApplied = locked`, then A runs `restoreNormal()` — which reads
`kioskConfig` itself and reinstates the plain child-mode allowlist with `LOCK_TASK_FEATURE_HOME` and
`OVERVIEW`, dropping the complete lock — and A's `onSuccess` loses the write race, so `lastApplied`
still says `locked`. From then on every `reevaluate()` computes `locked`, finds it equal to
`lastApplied`, logs *"Lock state unchanged since last apply; skipping re-apply"* and does nothing.
The device sits on the child-mode allowlist all night while `BedtimeScreen` renders "locked". This is
the same silent downgrade the KDoc calls "regression 1", reached through concurrency instead of
through the cache key.

**Suggested fix**

```kotlin
companion object {
    private val gate = Mutex()          // process-wide, alongside lastApplied
}
suspend fun reevaluate() = gate.withLock { reevaluateLocked() }
suspend fun endPause() = gate.withLock { prefs.setPauseUntil(0L); reevaluateLocked() }
```

and read the inputs as one snapshot rather than three:

```kotlin
val (schedule, manual, kioskConfig) = combine(
    prefs.lockSchedule, prefs.manualLock, prefs.kioskConfig, ::Triple
).first()
```

`LockControllerTest` currently exercises `reevaluate()` sequentially; a test that launches two
`reevaluate()` calls concurrently against a prefs fake with an injectable delay would pin this.

---

## H3 — The parent PIN's salt and hash cross the BLE link in cleartext, on a world-readable characteristic

**Where**
- `app/src/main/java/edu/fnosari/momedm/protocol/Messages.kt:18-21` — `ChildPrefs.pinSalt` / `pinHash`
- `app/src/main/java/edu/fnosari/momedm/protocol/SecureChannel.kt:19-23` — `seal()` MACs the body; it never encrypts it
- `app/src/main/java/edu/fnosari/momedm/connectivity/ble/characteristics/BLECharacteristic.kt:64` — `Permission.NOTIFY → PERMISSION_READ`
- `app/src/main/java/edu/fnosari/momedm/connectivity/ble/BLEServer.kt:200-212` — the read handler answers with the characteristic's current `value`
- `app/src/main/java/edu/fnosari/momedm/protocol/PinHash.kt:8` — `ITERATIONS = 20_000`

**What is wrong**

Two independent weaknesses that combine badly.

*The channel is authenticated but not confidential.* `Envelope.body` is the plaintext JSON message;
`mac` only proves integrity and origin. `docs/architecture.md` says as much ("What is **not**
encrypted: BLE link-layer traffic itself"), so this is a known design choice — but the document
frames it as a link-layer sniffing exposure, and separately reassures that "The PIN itself is hashed
(PBKDF2) on the controller before it's sent". Read together, those two statements understate what is
actually exposed.

*The CMD characteristic is readable, not just notifiable.* `Permission.NOTIFY` maps to
`PERMISSION_READ` with no encryption or authentication requirement, and `BLEServer`'s
`onCharacteristicReadRequest` responds with `c.value.toByteArray()` — the last frame staged for
notification, whoever it was staged for. No handshake, no bonding, no session needed: connect and
read.

`ControllerService` pushes `SET_PREFS` after **every** successful auth and on every prefs change
(`ControllerService.kt:114`, `:138`), so the PIN pair is on the wire routinely.

**Scenario**

An attacker within BLE range (a neighbour, or the child with a laptop and a cheap dongle) either
sniffs the notifications or simply connects to the parent's advertised GATT server as an
unauthenticated central and reads `6d6f6d65-646d-4000-8000-000000000002`. They obtain
`pinSalt` (16 B) and `pinHash` (32 B). `PinHash` accepts 4–6 digits, so the search space is
10⁴–10⁶ candidates at 20 000 PBKDF2-HMAC-SHA256 iterations — seconds for a 4-digit PIN, minutes for
6, on one laptop core. With the PIN, physical possession of the child's phone yields the parent menu,
the pause, and (per H1) re-pairing. The same read also discloses every command and every status: the
installed app list, the foreground app, the schedule, the battery.

**Suggested fix**

1. Drop the read permission from the notify case — the framing layer never issues a GATT read, so
   `PERMISSION_READ` buys nothing and costs a free oracle. `Permission.NOTIFY` should map to
   `PROPERTY_NOTIFY` alone, with no permission bits.
2. Encrypt the envelope body. The handshake already produces shared entropy; derive a second key with
   the existing domain-separation pattern — `HMAC(secret, "momedm/enc|" + nonceC + "|" + nonceS)` —
   and use AES-GCM with the sequence number as the nonce, keeping the outer MAC as-is (encrypt-then-
   MAC). This is a contained change to `SecureChannel` and is unit-testable exactly like the current
   tests.
3. Raise `PinHash.ITERATIONS` substantially (≥200 000) regardless. Over a 4-digit space, 20 000
   iterations is not meaningful work — and the same hash also sits in the child's DataStore, where a
   child with adb or a recovery image can reach it.

---

## M1 — A silent rogue peripheral advertising the service UUID permanently severs the parent link

**Where**
- `app/src/main/java/edu/fnosari/momedm/connectivity/ble/BLEClient.kt:201-225` — connects to the first advertiser matching the service UUID (`serverName` is `null`, see `ManagedLinkService.kt:194`)
- `app/src/main/java/edu/fnosari/momedm/protocol/Endpoints.kt:36-101` — `ManagedEndpoint` has no auth timeout
- `app/src/main/java/edu/fnosari/momedm/managed/ManagedLinkService.kt:194-210` — no post-connect watchdog

**What is wrong**

The controller side gets this right: `SessionManager.AUTH_TIMEOUT_MS = 5_000` disconnects and forgets
a central that has not authenticated (`SessionManager.kt:259-271`, tested by
`unauthenticatedSessionIsDroppedAfterTimeout`). The managed side has no equivalent. `BLEClient` has a
30 s *scan* timeout and a 10 s *MTU* watchdog, but once `onConnected` fires it sends HELLO and waits
indefinitely. The service only rescans on `onDisconnected`, `onWriteFailed` or `onProtocolError` —
none of which a silent peer triggers.

`MdmGatt.SERVICE_UUID` is a fixed constant in a public repository, and `serverName = null` means any
advertiser carrying it is accepted.

**Scenario**

A cheap BLE peripheral (an ESP32, or a phone) left near the child's phone advertises
`6d6f6d65-646d-4000-8000-000000000001` and never answers the CHALLENGE. The child's client connects,
sends HELLO, and stops scanning. `ManagedLinkState` sits at `CONNECTED`, the foreground notification
says "connected", and the parent's device list shows the child offline forever. `LOCK_NOW`,
`UNLOCK`, schedule pushes and status all stop working. (The locally-stored night schedule keeps
working — that part of the design pays off here — but everything remote is dead.)

Answering the CHALLENGE with garbage is *worse* for the attacker: that raises `onProtocolError` and
triggers a rescan. Silence is the winning move, and it is the one the code doesn't handle.

**Suggested fix**

Mirror the controller. In `ManagedLinkService`, post a main-handler watchdog on `onConnected`; if
`endpoint?.authenticated != true` after ~8 s, `client?.disconnect()` and `scheduleRescan()`. Keep a
short in-memory set of BLE addresses that failed to authenticate and skip them in `_scanCallback` for
a few minutes, so the scanner moves on to the next advertiser instead of reconnecting to the same
one.

---

## M2 — The emergency dialer is resolved from an implicit action with no system-app check, then allowlisted under lock task

**Where** `app/src/main/java/edu/fnosari/momedm/managed/PolicyManager.kt:180-184`, used at `:138`
(child mode) and `:207` (complete lock); launched at `:192-198`.

**What is wrong**

```kotlin
fun emergencyDialerPackage(): String? = runCatching {
    context.packageManager
        .resolveActivity(Intent(EMERGENCY_DIAL_ACTION), PackageManager.ResolveInfoFlags.of(0))
        ?.activityInfo?.packageName
}.getOrNull()
```

`EMERGENCY_DIAL_ACTION` is `"com.android.phone.EmergencyDialer.DIAL"` — an ordinary implicit action
with no signature or system-app constraint. Whatever `resolveActivity` picks is appended to
`setLockTaskPackages`, including in `lockComplete()` where the whole point is that *only this app* is
launchable. The KDoc explains why the component is resolved rather than hardcoded (it is OEM-specific
— AOSP answers `com.android.phone`, Samsung `com.samsung.android.emergency`) and that reasoning is
sound; the missing half is verifying that what answered is a platform component.

This matters more than it looks because child mode's allowlist already includes the Play Store
(`PolicyManager.kt:138`, see L1), so a child in child mode can install apps.

**Scenario**

A child installs a Play app that declares an `<intent-filter>` for
`com.android.phone.EmergencyDialer.DIAL` with a higher priority than the OEM dialer's. On the next
`lockComplete()` (that night's window opening), its package is added to the lock-task allowlist and
the bedtime screen's "Emergency call" button launches it — inside lock task, on a device that is
supposed to run nothing but this app.

**Suggested fix**

```kotlin
fun emergencyDialerPackage(): String? = runCatching {
    context.packageManager
        .resolveActivity(
            Intent(EMERGENCY_DIAL_ACTION),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()),
        )
        ?.activityInfo
        ?.takeIf {
            it.applicationInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }
        ?.packageName
}.getOrNull()
```

Apply the same check in `launchEmergencyDialer()` before starting the intent.

---

## M3 — Peer-supplied `appConfigs` reach `setApplicationRestrictions` unvalidated and unbounded

**Where**
- `app/src/main/java/edu/fnosari/momedm/protocol/SafetyConfig.kt:131` — `sanitized()` touches only `dnsHost`
- `app/src/main/java/edu/fnosari/momedm/managed/SafetyManager.kt:64-81` — iterates `config.appConfigs` verbatim
- `app/src/main/java/edu/fnosari/momedm/managed/SafetyManager.kt:35-54` — `toBundle` recurses on `JsonObject` with no depth limit

**What is wrong**

`CommandExecutor` calls `cfg.sanitized()` (`CommandExecutor.kt:285`), which does exactly one thing:
drop a malformed `dnsHost`. Everything in `appConfigs` — the package names, the number of entries,
the keys, the value shapes, the nesting depth — passes straight through to
`dpm.setApplicationRestrictions(admin, pkg, toBundle(values))`. `SafetyConfig`'s KDoc says this is
deliberate ("applied verbatim … so adding a key later, or configuring a different app entirely, needs
no protocol change"), and as a *design* that is defensible; as an *input path from a peer* it wants a
bound.

The peer is authenticated, but the authentication is a single shared fleet secret
(`docs/architecture.md`, Known limitations) — so "authenticated" includes every sibling device and
anyone who photographed the QR during pairing.

**Scenario**

A hostile authenticated peer sends `SET_SAFETY` with `appConfigs` containing 10 000 entries, or with
managed configuration aimed at another MDM-aware app on the device, or with a `JsonObject` nested a
few thousand deep. The whole blob is also persisted verbatim by `prefs.setSafety(config)`
(`PolicyManager.kt:322`) *before* it is applied, and re-applied on every service start by
`restoreSafety()` — so a hostile push that fails on apply is replayed on every boot.

**Suggested fix**

In `SafetyConfig.sanitized()`, which is already the designated choke point:

```kotlin
private val PKG_RE = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$""")
private const val MAX_APPS = 32
private const val MAX_KEYS = 128

fun sanitized(): SafetyConfig = copy(
    dnsHost = dnsHost?.takeIf { isValidHostname(it) },
    appConfigs = appConfigs
        .filterKeys { PKG_RE.matches(it) }
        .filterValues { it.size <= MAX_KEYS }
        .entries.take(MAX_APPS).associate { it.toPair() },
)
```

and give `SafetyManager.toBundle` a `depth: Int = 0` parameter that refuses beyond ~8 levels.
`SafetyConfigTest` is the natural home for the new cases.

---

## M4 — Unauthenticated `REHELLO` can reset an established session at will

**Where** `app/src/main/java/edu/fnosari/momedm/protocol/Endpoints.kt:70-73`

```kotlin
if (env.seq == 0L && linkMtu > 0 && isPlainRehello(env)) { onConnected(linkMtu); return }
```

**What is wrong**

This check runs *before* the `channel == null` branch, so it applies even to a fully authenticated
session, and it requires no proof of anything — only that `onConnected` has been called at least
once. `onConnected` calls `reset()`, discarding the secure channel and both sequence counters, then
starts a fresh handshake. The comment ("the worst an impostor on the same link could do is force a
re-handshake") is accurate for a single REHELLO and understates a repeated one.

**Scenario**

Combined with M1, the rogue peripheral the child connects to can accept the handshake attempt and
then emit REHELLO in a loop, keeping the child in perpetual re-handshake. More narrowly, a REHELLO
injected between `LOCK_NOW`'s `RESULT` and its follow-up `Status` (`CommandExecutor.kt:294`) drops the
status the parent's UI is waiting on, so the parent sees a command that "went out" and a device whose
state never updates.

**Suggested fix**

Rate-limit it — at most one honoured REHELLO per ~10 s, tracked by the endpoint's clock — and ignore
it entirely once `confirmed` is true, since a confirmed session that has genuinely gone stale will
discover that through a failed write (`ManagedLinkService.onWriteFailed`, which already handles it).

---

## L1 — Child mode unconditionally allowlists the Play Store and GMS

**Where** `app/src/main/java/edu/fnosari/momedm/managed/PolicyManager.kt:138`

```kotlin
dpm.setLockTaskPackages(admin, (allowed + context.packageName + PLAY_PKG + GMS_PKG + listOfNotNull(emergencyDialerPackage())).toTypedArray())
```

`docs/architecture.md` describes child mode as showing "only the parent-chosen `apps`", and the
launcher does. But lock task also permits `com.android.vending` to be *launched* by anything on the
allowlist — a `market://` deep link, an in-app "rate us" button, a Play link in an allowed browser. A
child in child mode can reach the store and install apps (which is what makes M2 exploitable). Play
needs to be reachable for `INSTALL`/`SEARCH_APP` to work at all, so this is a real trade-off rather
than an oversight — but it is a standing hole, not a momentary one, and the documentation does not
mention it.

**Fix** Allowlist Play only while an `INSTALL`/`SEARCH_APP` is in flight: add it, launch, and remove
it on the next status push or after a timeout. Failing that, say so in `docs/architecture.md`.

## L2 — `deviceId` is self-asserted, and claiming a sibling's evicts it

**Where** `app/src/main/java/edu/fnosari/momedm/protocol/Handshake.kt:38` (deviceId is a plain HELLO
field, never bound into either proof or the session key),
`app/src/main/java/edu/fnosari/momedm/controller/SessionManager.kt:229-231`:

```kotlin
sessions.values.filter { it.key != key && it.endpoint.deviceId == hello.deviceId }
    .forEach { transport.disconnect(it.key) }
```

A device that authenticates while claiming another's `deviceId` *kicks the incumbent off*, then
receives every command the parent addresses to that child and reports status the parent's UI renders
under that child's name. `docs/architecture.md` lists fleet compromise as a known limitation but not
this consequence — that one compromised child can silently take over another's identity in the
parent's UI.

**Fix** Per-device secrets (already planned) resolves it. In the interim: bind `deviceId` into the
session-key derivation (`"momedm/session|$nonceC|$nonceS|$deviceId"`) so a claim is at least tied to
the session, and refuse a HELLO whose `deviceId` already belongs to a live authenticated session
rather than evicting the incumbent.

## L3 — A malformed stored secret crashes the link service into a restart loop

**Where** `app/src/main/java/edu/fnosari/momedm/managed/ManagedLinkService.kt:172-175`

```kotlin
private suspend fun startLink() {
    val secret = prefs.secretBytes()        // <- outside the try below
    if (secret == null) { ...; return }
    try { ... } catch (t: Throwable) { ... }
```

`secretBytes()` calls `Base64Std.decode`, which throws `IllegalArgumentException` on a malformed
value. It sits outside the `try` that exists precisely to keep startup failures from escaping. The
enclosing `scope` uses a `SupervisorJob` with no `CoroutineExceptionHandler`, so this is an uncaught
exception on the main dispatcher → process death → `START_STICKY` restart → same crash.

`ManagedSetup.persistExtras` validates only that the strings are non-empty, unlike
`PairingPayload.parse` which checks the exact shape (`SECRET_RE`). The debug provisioning receiver
writes whatever is on the adb command line.

**Fix** Move the read inside the `try`, and reuse `PairingPayload`'s `SECRET_RE` in `persistExtras`
so a bad secret is refused at the door rather than at every service start.

## L4 — The provisioning QR and the APK server have no expiry, and the listener binds all interfaces

**Where** `app/src/main/java/edu/fnosari/momedm/controller/provisioning/ApkHttpServer.kt:9`,
`app/src/main/java/edu/fnosari/momedm/controller/provisioning/ProvisioningController.kt:337,380-389`

`NanoHTTPD(port)` with a single argument binds `0.0.0.0`. In `MODE_MANUAL` that is the whole LAN —
which on a café or school network is everyone. What is served is only the app's own (open-source) APK,
so the confidentiality cost is near zero; the cost is an unnecessary open listener.

Lifetime is the sharper half. `ProvisionScreen` does tear down on dispose
(`ProvisionScreen.kt:67`), which is correct and worth noting — but as long as the screen stays
composed, the QR carrying the 32-byte shared secret *and* the Wi-Fi password stays on screen and the
server stays bound, with no timeout. `docs/architecture.md` already tells the parent to treat the QR
screen as sensitive, so this is hardening rather than a surprise.

**Fix** Bind to the chosen address (`NanoHTTPD(ip, port)`), and expire the payload plus tear down the
server and hotspot after ~5 minutes.

## L5 — `ProvisioningController.start()` is not re-entrant; a second press leaks the hotspot reservation

**Where** `ProvisioningController.kt:321-331`, `HotspotManager.kt:35-51`

Pressing "Generate QR" twice calls `hotspot.start()` twice. `HotspotManager.onStarted` assigns
`reservation = r`, overwriting the first reservation without calling `close()` on it. The orphaned
local-only hotspot stays up until the process dies.

**Fix** Guard `start()` on `_state.value.serverRunning || hotspotSsid.isNotBlank()`, and in
`HotspotManager.start()` return early (or `stop()` first) when `reservation != null`.

## L6 — The PIN lockout is not enforced during the ViewModel's first moments

**Where** `app/src/main/java/edu/fnosari/momedm/activities/managed/ManagedViewModel.kt:141-150`,
`:235-236`

`pinFailures` and `pinLockDeadline` are restored in a `viewModelScope.launch`; `tryPin` reads the
plain field, which is `0` until that coroutine completes. The KDoc's claim that "the backoff cannot be
reset by killing the launcher" is right about persistence and slightly optimistic about timing: the
launcher is recreated with `CLEAR_TASK` by every `LockController` apply, so fresh ViewModels are
routine. The window is milliseconds against hand-typed attempts, so the practical impact is small.

**Fix** Keep the restore `Job` in a field and `join()` it at the top of `tryPin`.

## L7 — `runBlocking` on the main thread during provisioning

**Where** `app/src/main/java/edu/fnosari/momedm/managed/ManagedSetup.kt` (`persistExtras`)

`persistExtras` wraps two DataStore writes in `runBlocking`, and is called from
`AdminReceiver.onProfileProvisioningComplete` (a broadcast on the main thread, subject to the ANR
timeout), `GetProvisioningModeActivity.onCreate` and `PolicyComplianceActivity.onCreate`. Confined to
the provisioning path, so low impact, but it is a main-thread disk write in a receiver.

**Fix** Make `persistExtras` a `suspend fun` and call it from `goAsync()` / `lifecycleScope`.

## L8 — `Reassembler` bounds the number of partial messages, not their size

**Where** `app/src/main/java/edu/fnosari/momedm/protocol/Framer.kt:43-68`, and the claim in
`docs/architecture.md` §Frames that this means "a flood of junk frames cannot exhaust memory before
auth".

`MAX_PARTIALS = 16` caps concurrency; nothing caps the size of any one partial. `Framer.MAX_COUNT` is
9999 and a chunk is up to `mtu - 18` bytes, so the theoretical ceiling is ~16 × 9999 × 499 ≈ 80 MB of
pre-auth buffer. In practice BLE throughput and the controller's 5 s auth timeout keep this far below
that, and the managed side only reads from a peer it chose to connect to — so the risk is small. The
gap is between the code and the documented guarantee.

**Fix** Track bytes buffered per partial and in total, and drop a partial (or the oldest) once a
budget of a few hundred KB is exceeded. `FramerTest.reassemblerCapsConcurrentPartials` is the right
place to extend.

---

# Suspected — needs checking

## S1 — `pointerInput(Unit)` may latch the no-PIN gesture handler permanently

**Where** `BedtimeScreen.kt:83-88`, `ChildLauncherScreen.kt:118-140`

Both screens choose a gesture handler by branching on `pinSet` / `canUnlock` and then calling
`Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { ... }) }`. In both branches the
modifier chain is structurally identical (`semantics` then `pointerInput`) and the `pointerInput`
key is `Unit` in both.

`ManagedViewModel.pinSet` is `stateIn(..., SharingStarted.Eagerly, false)` — its seed is `false`, and
the real value arrives only after DataStore's first emission. If the first composition of either
screen observes `pinSet == false`, the no-PIN handler (`menuOpen.value = true`) is installed. My
concern is that Compose's `SuspendPointerInputElement.equals` compares only the keys and deliberately
*not* the handler lambda, so when `pinSet` flips to `true` the element compares equal, `update()` is
not invoked, and the stale handler keeps running for the life of that Activity — meaning a family that
*did* set a PIN could still get the unauthenticated menu on a given launcher instance, and it would
not self-heal on recomposition.

Whether the first composition actually loses the race is not certain: `ManagedHomeActivity` only
shows `BedtimeScreen` once `lockState != null` and `ChildLauncherScreen` once `kioskConfig != null`,
both of which depend on the same DataStore, so the emissions may well be close together. And I did not
verify the `equals`/`update` behaviour against the Compose BOM actually in use (`2024.09.00`).

**How to check** Instrumented test: set a PIN, force `LockController` to relaunch the launcher, and
assert that a long-press on the bedtime clock opens `PinDialog` rather than the menu. Or read
`SuspendPointerInputElement.equals` in the resolved Compose UI source.

**Fix either way** — both changes are cheap and correct independently of the outcome:
1. Key the modifier on the state it depends on: `pointerInput(pinSet)` / `pointerInput(canUnlock)`.
2. Decide inside the handler rather than at composition time, reading the flow's current value:
   `onLongPress = { if (vm.pinSet.value) vm.pinDialogOpen.value = true else vm.menuOpen.value = true }`.
   That makes the outcome independent of when the modifier was installed.

## S2 — Deeply nested JSON from a peer may escape as `StackOverflowError` and kill the process

**Where** `SecureChannel.kt:30`, `Endpoints.kt:88`, `SafetyManager.kt:47`

`SecureChannel.open` wraps decoding in `catch (ex: Exception)`, and `ManagedEndpoint.onFrame` catches
only `ProtocolException`. A `StackOverflowError` is an `Error`, not an `Exception`, so it would pass
through both and surface on the GATT binder thread that called `onFrame` — taking the process down.
`kotlinx-serialization-json` 1.9.0's `StreamingJsonDecoder` recurses per nesting level and I am not
aware of a configurable depth guard, but I did not confirm that empirically. `SafetyManager.toBundle`
recurses too, though `runCatching` there does catch `Throwable`, so that half is covered.

**How to check** A JVM unit test in `MessagesTest`: build a `Cmd` whose `safety.appConfigs` value is a
`JsonObject` nested ~10 000 deep, encode it, and feed the frames to a `ManagedEndpoint` through the
existing `EndpointLoopbackTest` rig. Assert it produces an `onProtocolError`, not a crash.

**Fix either way** Broaden the catch in `SecureChannel.open` to `Throwable`, and add the depth cap
suggested in M3 so the depth is bounded before decoding rather than after.

## S3 — `Message.Status` from a peer is persisted into the controller's registry blob unbounded

**Where** `ControllerService.kt:118`, `DeviceRegistry.kt:293-296`, `ControllerPrefs.saveRegistry`

`Status` carries the whole `SafetyConfig` back (deliberately — see the comment at
`StatusCollector.kt:227-230`, and it is the right call for the merge problem it solves). It is stored
verbatim into a DataStore string, once per device, rewritten on every status push. A hostile child
could inflate its own `safety.appConfigs` up to the framing ceiling and have the parent write a
multi-megabyte preferences file every five minutes. I did not measure what DataStore does with a blob
that size, so the practical impact is unclear.

**How to check** Push a synthetic large `Status` through the loopback rig into a `DeviceRegistry`
backed by `InMemoryPreferencesProvider` and time the write.

**Fix** Apply M3's `sanitized()` to `Status.safety` on receipt at the controller as well as to
`Cmd.safety` on receipt at the child — the choke point is already there, it is just not used on the
inbound path.

---

# Verified sound

Recorded so this report reads as evidence and not only as a defect list. Each of these is something I
went looking for and found genuinely handled.

**Handshake and secure channel**
- Domain separation across all three HMACs (`Handshake.kt:13-15`) — and the specific attack it stops
  (a forged HELLO with `nonceC = realNonceC + nonceS` recovering the session key) is reconstructed and
  asserted in `HandshakeTest.challengeProofIsNotSessionKeyForConcatenatedNonce`, not merely described.
- Peer nonces are validated to exactly 32 lower-case hex chars *before* reaching any HMAC, on both
  sides (`Endpoints.kt:81`, `:154`), tested by `EndpointLoopbackTest.malformedNonceRejected`.
- MACs are direction-bound (`'C'`/`'M'`), so a captured envelope cannot be reflected at its sender
  (`SecureChannel.kt:22,28`), tested by `SecureChannelTest.reflectedMessageRejected`.
- `open()` rejects `seq <= lastInSeq` and advances `lastInSeq` **only after** the MAC verifies, so an
  injected high-sequence frame cannot desynchronise the counter (`SecureChannel.kt:27-29`).
- `Crypto.constantTimeEquals` is used for both handshake proofs and every envelope MAC; the early
  length return leaks only a length, which is public here.
- Any protocol error resets the whole session on both sides, and a replayed handshake afterwards is
  rejected as premature — `EndpointLoopbackTest.replayAfterProtocolErrorIsRejected` covers this.
- The managed side flips `authenticated` only on a *sealed* `AUTH_OK`, not on having sent its own
  AUTH (`Endpoints.kt:52`, `:90`) — a subtle distinction that is easy to get wrong.
- Peer-supplied MTU is clamped to `23..517` before it can drive chunk sizing (`Endpoints.kt:157`),
  tested.
- `ControllerEndpoint` drops unauthenticated centrals after 5 s and probes silent ones exactly once
  with REHELLO (`SessionManager.kt:259-271`), both tested.

**PIN handling**
- The clear PIN really is never persisted or logged. `ControllerPrefs.setPin` hashes before writing
  (`:205-211`); `DataStorePreferencesProvider.write(String)` logs a length and never a value, with a
  comment saying exactly why (`:377-381`); `PolicyManager.applyPrefs` logs `pin=<boolean>` (`:339`).
  I grepped the whole `main` source set for the leak and found none.
- `ChildPrefs.sanitized()` nulls *both* halves unless both are well-formed hex of the exact expected
  lengths, so a hostile or half-written `SET_PREFS` cannot reach `Hex.decode` (`Messages.kt:31-38`),
  tested by `MessagesTest.childPrefsSanitized`.
- `PolicyManager.verifyPin` cannot throw, rethrows `CancellationException` correctly, and runs the
  PBKDF2 work on `Dispatchers.Default` off the launcher's main-thread coroutine (`:367-376`).
- The lockout counter and deadline are persisted (`ManagedPrefs.setPinLock`), so force-stopping the
  launcher no longer resets the backoff — with the timing caveat in L6.

**Lock correctness — the central design claim**
- No code path persists a lock *verdict*. `manualLock`, `pauseUntil` and `schedule` are inputs;
  `lastApplied` is process-memory only, never consulted as the answer, cleared by process death and
  explicitly cleared while a pause is live. I went looking for a stored `locked` flag and there is
  none.
- `LockControllerTest` covers both historical regressions (KIOSK_ON/OFF becoming DPM no-ops; a lapsed
  pause being skipped as already-applied), and — importantly — that a *failed* apply is not cached and
  retries on the next trigger.
- `LockSchedule` handles midnight wrap, "a night belongs to the day it starts", `start == end`
  disabling a day type, and both DST transitions via `atZone` rather than minute arithmetic — all
  covered in `LockScheduleTest`, including the spring-forward gap and the fall-back overlap.
- Missed, stale or duplicated alarms are genuinely harmless: `LockAlarms` only wakes the device, and
  boot / time-change / launcher-resume / pause-watchdog are four independent compensating triggers.
- The pause short-circuit at `LockController.kt:118-128` is correct and the comment explaining why it
  must not be folded back into the general path is accurate — collapsing it would re-pin a device the
  parent just unlocked.
- Cancellation is rethrown rather than swallowed in every `PolicyManager` `Result` wrapper, with a
  comment explaining the hazard (`PolicyManager.kt:96-99`). This is done consistently.

**Provisioning**
- `ApkHttpServer` has no path traversal: the URI is compared for exact equality with
  `/momedm.apk`, so no normalisation bug is reachable. NanoHTTPD 2.3.1's known temp-file advisory
  (CVE-2022-21230) is in `parseBody`, which this code never calls — only GET-style handling of one
  fixed path.
- `PairingPayload.parse` validates that the scanned text is JSON, is an object, contains our extras
  bundle, has a non-blank controller id, and has a secret matching the exact shape we generate — with
  tests against a real code and several hostile ones. A camera pointed at a shop receipt cannot
  overwrite a working pairing.
- `ProvisionScreen`'s `DisposableEffect(Unit) { onDispose { pc.stop() } }` does tear down the server,
  hotspot and QR when the screen goes away; `invalidate()` correctly discards a code whose settings
  changed under it.
- `SettingsAdvancedScreen` displays only `HMAC(secret, "fingerprint")` truncated, never the secret.
- `SafetyConfig.isValidHostname` is applied before `setGlobalPrivateDnsModeSpecifiedHost`, so a
  malformed hostname never reaches the platform (`SafetyConfig.kt:131`), tested.
- `DeviceRegistry.mutate` re-reads the persisted blob under a mutex before applying its function —
  the right fix for two registry instances over one blob, and the KDoc explains the exact loss it
  prevents.
- `StatusCollector.MAX_APPS = 300` bounds the APPS reply so it stays inside the framing budget, and
  `ManagedLinkService.safeSend` catches the `IllegalArgumentException` `Framer.split` raises if
  something is oversized anyway.
- `MessageCodec.asciiEscape` makes byte-length equal char-length, which is what lets `Framer` chunk on
  characters safely — a real correctness dependency, and it is tested (`MessagesTest.nonAsciiIsEscaped`).

---

# What I did not examine

Stated plainly so the coverage of this report is not overestimated.

- **Nothing was run.** No build, no emulator, no device, and the unit test suite
  (`./gradlew :app:testDebugUnitTest`) was not executed. I read the tests to establish what is
  covered; I did not confirm they pass.
- `connectivity/ble/BLEClient.kt` (684 lines) and `BLEServer.kt` (576 lines) were read only where
  they bore on specific questions — characteristic permissions, connect/scan/MTU timeouts, frame
  value staging, and the write-rejection path. `BLEOperationQueue.kt`, the CCCD subscription flow,
  the notify queue's retry behaviour and the bonding/pairing surface were **not** reviewed. Given that
  M1 and H3 both live in this layer, it is the area I would look at next.
- Compose UI outside the managed launcher, bedtime screen, parent menu, PIN dialog and provisioning
  screen. Specifically **not** reviewed: `DeviceScreen`, `DevicesScreen`, `AppConfigDialog`,
  `AppPickerDialog`, `SafetyDialog`, `TimeRangeRow`, and the appearance/connection settings screens.
  `AppConfigDialog` in particular constructs the `JsonObject` that becomes managed configuration, so
  it deserves the same scrutiny M3 applies to the receiving end.
- Localisation and resources; `StringsParityTest` was not read beyond its name.
- `build.gradle.kts` beyond dependency versions and SDK levels — no review of signing config, R8/
  ProGuard rules, or whether `allowBackup="false"` is the only data-at-rest control in play.
- Dependency vulnerability scanning was limited to noting NanoHTTPD 2.3.1's unmaintained status and
  reasoning about whether its known advisory is reachable here. No SCA tool was run.
- Recently-fixed defects visible in `git log --oneline -30` were deliberately not re-reported.
