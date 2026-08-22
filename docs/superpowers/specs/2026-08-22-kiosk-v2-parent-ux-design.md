# Môme DM v2 — multi-app child mode, parent PIN, pushed preferences, parent-friendly UI (FR/EN)

Date: 2026-08-22
Status: approved in brainstorm, pending written review
Builds on: `2026-08-22-momedm-design.md` (v1, implemented and emulator-tested).

## 1. Goals

1. **Child mode (kiosk v2)**: a parent allows *several* apps; the child device shows a
   child-friendly launcher with only those apps. Optionally *one* of them is pinned
   (auto-launched and re-launched whenever the child leaves it). A single parent **PIN**
   (same for all children) temporarily unlocks the child device locally.
2. **Preferences pushed from the parent**: language (system / fr / en), theme
   (system / light / dark), accent colour, and the PIN are chosen on the parent phone and
   synced to every child over BLE. The child has no settings UI.
3. **Parent-friendly UI**: same language/theme/accent system and visual language as
   MaClasse (project `ClassManager`, package `edu.fnosari.classmanager`): Pronote-inspired
   palette and shapes, seed accent colour with presets + custom colour dialog, per-app
   language via `AppLocale`. Vocabulary for parents, not MDM experts: *Parent / Enfant*,
   *Mode enfant*, *Apps autorisées*, *Associer un appareil*. No "kiosk", "provision",
   "lock task", "GATT" in the UI.
4. **French and English** everywhere (`values` = EN default, `values-fr`), key-for-key.

Out of scope: per-child PIN, child-side settings UI, web/time limits, app usage
reports, anything needing a Google account beyond v1's Play deep link.

## 2. Delivery split

One spec, two implementation plans executed in order (each leaves `main` green and
emulator-testable):

- **Plan 1 — Kiosk v2 + PIN + prefs sync**: protocol, managed launcher/PIN/pinned
  app, `SET_PREFS`, controller-side logic and the minimum UI hooks (multi-select apps,
  PIN setting) — still in today's visual style.
- **Plan 2 — Parent-friendly UI + i18n + theme**: MaClasse theme/accent/language
  system in both roles, FR/EN strings, new vocabulary, redesigned parent screens and
  the child launcher look.

## 3. Protocol changes (`protocol/`)

### 3.1 Messages
```kotlin
@Serializable data class ChildPrefs(
    val language: String = "system",   // "system" | "fr" | "en"
    val theme: String = "system",      // "system" | "light" | "dark"
    val accent: Int = Palette.DEFAULT, // ARGB seed colour
    val pinSalt: String? = null,       // hex, 16 bytes; null = no PIN set
    val pinHash: String? = null,       // hex, PinHash.hash(pin, salt)
)
Cmd(id, type, pkg: String? = null, apps: List<String> = emptyList(),
    pinned: String? = null, prefs: ChildPrefs? = null)
enum CmdType { KIOSK_ON, KIOSK_OFF, INSTALL, ADD_ACCOUNT, LIST_APPS, GET_STATUS, SET_PREFS }
Status(kiosk, kioskPkg /* = pinned app or null */, account, battery, currentApp,
       kioskApps: List<String> = emptyList(), kioskPaused: Boolean = false,
       pauseEndsAt: Long? = null)
```
- `KIOSK_ON{apps, pinned?}`: `apps` non-empty, `pinned ∈ apps` or null. Re-sending it
  while a PIN pause is active ends the pause (re-lock). `pkg` is ignored for KIOSK_ON.
- `SET_PREFS{prefs}`: full replace of the child's pushed prefs (idempotent).
- `RESULT` semantics unchanged. `APPS` unchanged (label + pkg).

### 3.2 PIN hashing (`protocol/PinHash.kt`, pure Kotlin)
- `PinHash.newSalt(): String` (16 random bytes, hex).
- `PinHash.hash(pin: String, saltHex: String): String` = hex of
  PBKDF2-WithHmacSHA256(pin, salt, 20 000 iterations, 32 bytes) via `javax.crypto.SecretKeyFactory`.
- `PinHash.verify(pin, saltHex, hashHex): Boolean` (constant-time compare).
- PIN = 4–6 ASCII digits, validated on the parent before hashing.
- The PIN in clear never leaves the parent phone and is not stored there either (only
  salt + hash + "isSet").

### 3.3 Sync rules (controller)
- After every successful authentication of a child, the controller sends `SET_PREFS`
  with the current prefs (cheap; guarantees offline children catch up).
- When the parent changes language/theme/accent or the PIN, `SET_PREFS` is sent to every
  online child.

## 4. Child device (managed role)

### 4.1 Persisted state (`ManagedPrefs`)
- `KioskConfig`: `on: Boolean`, `apps: List<String>` (JSON), `pinned: String?`,
  `pauseUntil: Long` (epoch ms, 0 = none).
- `ChildPrefs` fields: `language`, `theme`, `accent`, `pinSalt`, `pinHash`.

### 4.2 `PolicyManager`
- `kioskOn(apps, pinned)`: validate each pkg launchable (drop unknown ones, fail if none
  left), `setLockTaskPackages(admin, apps + self + com.android.vending + com.google.android.gms)`,
  `setLockTaskFeatures(SYSTEM_INFO)`, persist config (`on=true`, `pauseUntil=0`),
  launch `ManagedHomeActivity` with `ActivityOptions.setLockTaskEnabled(true)`
  (`CLEAR_TASK|NEW_TASK`); if `pinned != null` the launcher immediately starts it.
- `kioskOff()`: `setLockTaskPackages(admin, [])`, persist `on=false`, launch home.
- `pause(minutes = 10)`: persist `pauseUntil`; the launcher calls `stopLockTask()`.
  `resume()`: persist `pauseUntil=0`, re-run `kioskOn(config)`.
- `restoreKiosk()` on boot: `pauseUntil` ignored (always re-lock when `on`).
- `applyPrefs(prefs)`: persist; `AppLocale.apply(context, language)` (API 33+
  `LocaleManager` → framework recreates activities); theme/accent observed by the UI.

### 4.3 `ManagedHomeActivity` = child launcher
- Shows `ChildLauncherScreen`: grid of large tiles (app icon from `PackageManager`,
  label below), 3 columns portrait; **allowed apps** when child mode is on, **all
  launchable apps** when off (today a kiosk-off child sees only a status card and has no
  way to open anything). Slim header: link dot (online/offline with the parent), battery,
  and — when child mode is on — a lock icon.
- Tap tile → `startActivity(launchIntent)`; inside lock task the target must be in the
  allowlist (it is) and inherits the locked task.
- **Pinned app**: when `pinned != null` and child mode is on and not paused, every
  `onResume` of the launcher immediately relaunches the pinned app (child bounces back
  at most one frame).
- **PIN**: lock icon → numeric pad dialog (4–6 digits, masked). Correct → `pause(10)`:
  `stopLockTask()`, banner "Mode enfant en pause · 09:58 · Reverrouiller" on the
  launcher, all apps visible, countdown; auto `resume()` at `pauseUntil`, on reboot, or
  on `KIOSK_ON` from the parent. Wrong PIN → error text, lockout 3 s doubling per
  failure (reset on success), max 60 s. No PIN set → lock icon hidden.
- Status pushed on pause start/end (and as before).
- Theme: `MomeDMTheme(darkTheme = isDarkTheme(theme, systemDark), seed = accent)` from
  the pushed prefs; locale from `AppLocale.wrap` in `attachBaseContext` (below API 33)
  or `LocaleManager` (33+). minSdk is 34 → `LocaleManager` path only; `wrap` kept for
  parity with MaClasse code.

### 4.4 `StatusCollector`
Adds `kioskApps`, `kioskPaused`, `pauseEndsAt` from `KioskConfig`.

### 4.5 `CommandExecutor`
- `KIOSK_ON`: requires `apps` non-empty → `policy.kioskOn(apps, pinned)`; Result then Status.
- `SET_PREFS`: requires `prefs` → `policy.applyPrefs(prefs)`; Result `"prefs applied"`.
- Others unchanged.

## 5. Parent device (controller role)

### 5.1 Persisted state
- `ControllerPrefs`: `language`, `theme`, `accent`, `pinSalt`, `pinHash` (→ `pinSet`).
- `DeviceRecord` gains `nickname: String? = null` (local only); `lastStatus` carries the
  new fields.

### 5.2 Logic
- `ControllerService`: on `onAuthenticated` → `SET_PREFS` with current prefs.
  `ControllerLink.sendCommand` gains `apps/pinned/prefs` parameters (or a `sendCmd(Cmd)`).
- `ControllerViewModel`: `kioskOn(deviceId, apps, pinned)`, `relock(deviceId)` (= KIOSK_ON
  with the last known config), `setPrefs(...)` → persist + broadcast `SET_PREFS` to
  online children, `setPin(pin)` → `PinHash.newSalt()` + hash → persist → broadcast,
  `renameDevice(deviceId, nickname)`.
- Per-child "desired config" is the child's own persisted config; the parent shows the
  last `Status` (apps, pinned, paused). When the parent picks apps/pin and presses
  "Activer", that becomes the new config.

### 5.3 Screens & wording (FR / EN)
Drawer: *Mes enfants / My children*, *Associer un appareil / Pair a device*,
*Réglages / Settings*.

- **Mes enfants**: one card per child — nickname (or model), model as subtitle, online dot,
  chip: *Mode enfant actif* / *En pause* / *Désactivé*; empty state text with the FAB
  *Associer un appareil*.
- **Enfant** (child page): title = nickname with edit (rename dialog); status card
  (*Mode enfant*, *Apps autorisées* count, *Application en cours*, *Batterie*, *Compte
  Google*); section *Apps autorisées* → button *Choisir les apps* → multi-select dialog
  (checkboxes, search field, labels from `LIST_APPS`; shows a spinner while fetching);
  switch *N'autoriser qu'une seule app* + chooser among the selected; primary button
  *Activer le mode enfant* / *Désactiver le mode enfant*; *Reverrouiller* when paused;
  *Installer une app (Play Store)* (package name field, explained hint); *Ajouter un compte
  Google*; *Actualiser*. Every `RESULT` → snackbar in plain words (*C'est fait* / *Échec : …*).
- **Associer un appareil**: three numbered steps in plain language (1 choose Wi-Fi source,
  2 generate the code, 3 on the child's new/reset device tap the welcome screen 6 times and
  scan), QR, troubleshooting hint (Manual / Custom URL).
- **Réglages**: *Langue* (Système/Français/English), *Thème* (Système/Clair/Sombre),
  *Couleur* (MaClasse presets row + custom colour dialog: hue/sat/value sliders + hex),
  *Code PIN parental* (*Définir* / *Modifier*: dialog with PIN + confirm, 4–6 digits,
  explains it unlocks the child device temporarily), *Avancé* (identifiant du parent,
  *Régénérer la clé* with warning) and *À propos* (version).

### 5.4 Visual system (copied from MaClasse, adapted names)
- `ui/theme/Color.kt`, `Palette.kt`, `Theme.kt` (`MomeDMTheme(darkTheme, seed)`,
  `withSeed`, `PronoteShapes`, `isDarkTheme`), `Type.kt`; `ui/AppLocale.kt`.
- `MainActivity` / `ManagedHomeActivity`: `attachBaseContext` locale wrap; system bar
  style follows the accent as in MaClasse `MainActivity`.
- Components: cards with 16 dp corners, primary = seed, pastel chips for states
  (green = actif, amber = en pause, grey = désactivé).

## 6. i18n

- `res/values/strings.xml` (English) and `res/values-fr/strings.xml`, identical key sets;
  a JVM test parses both files and fails on any key difference. No hard-coded UI text in
  Kotlin (notifications, dialogs, snackbars, launcher included).
- `AppLocale.TAGS = [system, fr, en]`; default `system`; English resources are the
  fallback for other system languages.
- Child device: language comes only from `SET_PREFS` (defaults to `system` = the child
  device's system language).

## 7. Error handling
- `KIOSK_ON` with no launchable app left → `RESULT ok=false "no allowed app installed"`.
- `pinned ∉ apps` → treated as null (Result notes it).
- `SET_PREFS` with invalid language/theme → defaults substituted, Result ok with note.
- PIN pad offline from the parent still works (verification is local).
- Parent without PIN set → child launcher shows no lock icon; *Réglages* shows a hint.

## 8. Testing
- JVM: codec round-trips for `Cmd(apps/pinned/prefs)`, `ChildPrefs`, new `Status` fields;
  `PinHash` (deterministic vector, verify, wrong pin, salt length); `CommandExecutor`
  (KIOSK_ON apps/pinned validation, SET_PREFS, relock); `KioskConfig` pause expiry; copied
  `Palette`/`isDarkTheme` tests; strings key-parity test; `AppLocale` tag validation.
- Emulator rig (`docs/testing.md`): multi-app launcher shows only allowed apps; pinned app
  relaunch; PIN wrong/right, pause banner, relock by timer/parent/reboot; `SET_PREFS`
  switches child to FR + dark + accent live; parent screens FR and EN; all v1 scenarios.
