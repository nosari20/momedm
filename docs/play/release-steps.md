# Building and uploading a release — mechanics for this repo

Checked against the actual build files as they exist today:
`app/build.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`,
`settings.gradle.kts`, `.gitignore`. Facts below (AGP 9.3.1, compileSdk /
targetSdk 37, minSdk 34, applicationId `edu.fnosari.momedm`, versionCode 1 /
versionName "1.0", **no signing config wired up yet**) are read directly
from those files, not assumed.

## 1. Generate an upload keystore

Play App Signing (mandatory for all new apps — see
[Play Console Help](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en))
means there are two keys: the **upload key** you generate and keep, used to
sign the App Bundle you upload, and the **app signing key**, which Google
holds and uses to re-sign the final APKs served to users. Losing the upload
key is recoverable (Play support can reset it); losing the app signing key
is not — but with Play App Signing enabled, Google holds that one, so this
project only needs to generate and protect the upload key.

From the repo root:

```bash
keytool -genkeypair -v \
  -keystore momedm-upload.jks \
  -alias momedm-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` will ask for a keystore password, a key password, and the
certificate's distinguished name (organization = Florent NOSARI is fine for
a personal developer account). **Do not commit `momedm-upload.jks`.** Store
it outside the repo (or inside it but gitignored — see below), and back it
up somewhere durable; treat it like a password, not like a build artifact.

## 2. Keep the signing config out of git

Nothing in `app/build.gradle.kts` references a keystore today — the
`release` build type only sets `optimization { enable = false }`, no
`signingConfig`. Wire it up with a local, gitignored properties file rather
than hardcoding secrets in the build script:

**`keystore.properties`** (repo root, next to `settings.gradle.kts` —
**do not commit this file**):

```properties
storeFile=../momedm-upload.jks
storePassword=CHANGE_ME
keyAlias=momedm-upload
keyPassword=CHANGE_ME
```

(Path is relative to `app/`, hence `../` — adjust if the keystore lives
somewhere else, e.g. an absolute path outside the repo entirely, which is
the safer option.)

Add to `.gitignore` (the file already has `/local.properties` gitignored
following the same pattern — add these lines near it):

```gitignore
/keystore.properties
*.jks
*.keystore
```

**`app/build.gradle.kts`** — add a `signingConfigs` block and wire it into
`release`, reading the properties file only if it exists (so `assembleDebug`
and CI runs that don't have the keystore don't break):

```kotlin
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

android {
    // ...existing compileSdk/defaultConfig blocks unchanged...

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
```

This keeps `assembleDebug`/CI green with no keystore present, and only
signs `release` builds on a machine that has `keystore.properties`
populated. Confirm the exact `signingConfigs { }` block placement compiles
against AGP 9.3.1's DSL — the classic signing-config API used above has been
stable across AGP versions, but AGP 9's declarative-build-file work is
active development; if `android { signingConfigs { ... } }` inside the
existing `android { }` block errors, consult AGP 9.3's release notes for
whether it moved.

## 3. Consider CI implications

`docs/architecture.md`/`README.md` mention a CI badge
(`.github/workflows/ci.yml`). If a release build is ever produced in CI (as
opposed to only locally), the keystore and its passwords need to reach the
CI runner as secrets (e.g. GitHub Actions `secrets.*`, base64-decoded into
a file at build time) — never as committed files. Out of scope for a first
manual upload, but worth deciding before automating releases.

## 4. versionCode / versionName strategy

Current: `versionCode = 1`, `versionName = "1.0"` in `app/build.gradle.kts`.

- **`versionCode`**: must strictly increase on every upload to Play,
  including test tracks. Simplest workable scheme for a small, personally
  maintained app: bump it by 1 on every release you upload to any track
  (closed test, or production). Don't try to encode date/major.minor into
  it — Play only cares that each upload's `versionCode` is higher than the
  last one accepted on that app, across all tracks combined.
- **`versionName`**: the human-visible string. Semantic-ish is fine —
  `1.0`, `1.1`, `1.2`, bump the minor number for each round of changes,
  reserve `2.0` for a release that changes the protocol or provisioning
  flow in a way that isn't backward compatible between an old child build
  and a new parent build (or vice versa) — see the protocol version notes
  in `docs/architecture.md`'s "Known limitations" if any exist by the time
  of that release.
- Because this app has **two roles running the same APK on two different
  phones**, and the protocol between them is versioned by the app version
  itself (no separate protocol version field visible in `Messages.kt`),
  keep in mind that a parent and child running different app versions is a
  real scenario (a parent updates from Play before the child's phone does).
  Test that old-child/new-parent and new-child/old-parent combinations don't
  silently misbehave before bumping `versionName` past `1.x` in a way users
  would read as a significant update.

## 5. Build the signed release App Bundle

Play requires an **Android App Bundle** (`.aab`), not an APK, for new apps
(APKs are still fine for direct sideload/testing, e.g. the `assembleDebug`
flow described in the README, but Play's upload flow wants a bundle):

```bash
./gradlew clean :app:bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`. Confirm it's
signed correctly before uploading (also verifies the keystore wiring above
worked):

```bash
# List the app bundle's contents/signature via bundletool, or just check
# that the file exists and is a reasonable size (this repo's APK includes a
# camera dependency, CameraX, and ZXing, so expect several MB, not a few KB).
ls -lh app/build/outputs/bundle/release/app-release.aab
```

If Windows' known Gradle file-lock issue shows up (documented in
`CONTRIBUTING.md`: `Unable to delete directory …\app\build`), the same fix
applies:

```bash
./gradlew --stop
rm -rf app/build
./gradlew :app:bundleRelease
```

## 6. Upload

In Play Console, under the chosen testing track (see
`docs/play/README.md`'s publishing checklist for which track to start
with), **Create new release**, upload `app-release.aab`, fill in the
release notes, and confirm Play App Signing enrollment when prompted (first
upload only — Play will ask to opt in and may ask to upload the upload
key's certificate; follow the in-console prompts, since the exact wording
changes between Play Console UI revisions).

## 7. Pre-launch checks worth doing before every upload

- **Run the test suite.** `./gradlew :app:testDebugUnitTest` — this repo's
  JVM tests cover protocol framing, the handshake, the secure channel, and
  the lock schedule's date math; there's no reason to skip them before a
  release build.
- **Sanity-check the manifest hasn't drifted.** Compare
  `app/src/main/AndroidManifest.xml` against `docs/play/policy-forms.md`'s
  permission table before every release — a newly added permission changes
  what Play's Permissions Declaration Form and Data safety form require,
  and both docs in this directory were written against the manifest as of
  this writing.
- **Use Play Console's own Pre-launch report** after the first upload to a
  testing track — it runs the app on real/virtual devices in Google's lab
  and flags crashes, accessibility issues, and security warnings
  automatically. Given this app becomes device owner and a home-screen
  replacement, pay particular attention to whether the pre-launch report's
  automated crawler (which can't factory-reset a device or complete QR
  provisioning) produces confusing results — expect it to only meaningfully
  exercise the **controller** role's UI, not the managed/device-owner path,
  since that requires the real enrolment flow this repo's own
  `docs/testing.md` describes as needing either two physical devices or the
  two-emulator BLE rig.
- **Verify the signed bundle installs and runs** on a real device via
  `bundletool` (`build-apks` + `install-apks`) or by promoting to an
  internal test track and installing from there, before promoting further —
  a release-signed build can behave differently from a debug build (e.g. if
  ProGuard/R8 minification is ever turned on; currently
  `optimization { enable = false }` in the release build type means it
  isn't, so this particular risk doesn't apply yet, but re-check this file
  if that setting ever changes).
