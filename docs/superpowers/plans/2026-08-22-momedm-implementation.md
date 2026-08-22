# Môme DM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Môme DM — one Android app that is either a device-owner DPC (managed role) or a BLE controller (controller role) that provisions managed devices via QR and drives them (kiosk, Play install, add account, status) over an HMAC-authenticated, chunked JSON protocol on BLE GATT.

**Architecture:** Single Gradle module `app`, package `edu.fnosari.momedm`. BLE layer = BLEController's `connectivity/ble` package copied + extended (notify char, CCCD via op queue, MTU, per-device notify queue, scan/advertise by service UUID). `protocol/` is pure Kotlin (framing, HMAC handshake, secure channel, endpoints) and JVM-tested with a loopback test. App scaffolding (activities / Routes enum / Layout drawer / SettingsActivity / typed Preference + DataStore provider / theme) copied from BLEController. Role chosen at launch by `isDeviceOwnerApp`.

**Tech Stack:** AGP 9.3.1 (built-in Kotlin 2.2.10), Gradle 9.5, Compose BOM 2024.09.00 + Material3, navigation-compose, lifecycle-viewmodel-compose, DataStore preferences, kotlinx-serialization-json 1.9.0, zxing-core 3.5.3, nanohttpd 2.3.1, JUnit 4. minSdk 34, compileSdk/targetSdk 37.

**Spec:** `docs/superpowers/specs/2026-08-22-momedm-design.md`

## Global Constraints

- Package `edu.fnosari.momedm`; `applicationId` unchanged; namespace unchanged.
- minSdk 34, compileSdk 37, targetSdk 37, Java 11, AGP 9.3.1, Kotlin 2.2.10 (built-in). Compose + serialization plugins must be version `2.2.10`.
- All dependencies go through `gradle/libs.versions.toml` (never inline coordinates).
- Build must produce a single universal APK (no `splits {}`, no ABI/density splits).
- `connectivity/ble` package stays app-agnostic: no MDM terms, no app UUIDs, no app classes.
- `protocol/` package has **no Android imports** (only Kotlin stdlib, kotlinx-serialization, `java.*`/`javax.crypto`).
- Every class: private `LOG_TAG` companion constant, `android.util.Log` verbose logging, KDoc on public API.
- BLE system callbacks run on binder threads: they log-and-return on missing permission, never throw.
- Strings shown to users live in `res/values/strings.xml`.
- Commit after every task with a conventional-commit message. Run commands from the project root `C:/Users/ACH02/Documents/Projects/Android/momedm` in Git Bash; Gradle = `./gradlew` (Windows `gradlew.bat` equivalent). First Gradle run downloads JDK 25 via foojay — allow 10+ minutes.
- BLE/DPM behaviour cannot be verified on emulator; tasks say "compile check" where on-device is needed, and `docs/testing.md` (Task 16) lists the manual checklist.
- Source root: `app/src/main/java/edu/fnosari/momedm/` (abbreviated `SRC/` below). Test root: `app/src/test/java/edu/fnosari/momedm/` (`TEST/`). BLEController source: `C:/Users/ACH02/Documents/Projects/Android/BLEController/app/src/main/java/com/nosari20/blecontroller/` (`BLEC/`).

---

## File Structure (final)

```
app/src/main/java/edu/fnosari/momedm/
├── connectivity/ble/                    # Task 3 copy, Task 4 extensions
│   ├── BLEClient.kt  BLEServer.kt  BLEOperationQueue.kt  BLEOperation.kt  BLEException.kt  BLEDevice.kt  README.md
│   ├── characteristics/BLECharacteristic.kt
│   └── services/BLEService.kt
├── protocol/                            # Task 5 (pure Kotlin)
│   ├── Encoding.kt        # Hex, Base64Url
│   ├── Crypto.kt          # HMAC-SHA256, nonces, constant-time compare
│   ├── Framer.kt          # Framer (split/parse/maxChunk), Reassembler
│   ├── Messages.kt        # Message sealed hierarchy, Envelope, MessageCodec, CmdType, AppInfo
│   ├── Handshake.kt       # ManagedHandshake, ControllerHandshake
│   ├── SecureChannel.kt   # SecureChannel, ProtocolException
│   ├── Endpoints.kt       # FrameSink, ManagedEndpoint, ControllerEndpoint
│   └── ProvisioningExtras.kt  # admin-extras bundle keys
├── link/MdmGatt.kt                      # Task 5: service/char UUIDs + BLEService/BLECharacteristic subclasses
├── persistence/
│   ├── preferences/{Preference,PreferencesProvider,DataStorePreferencesProvider,DefaultPreferencesProvider}.kt  # Task 2 copy
│   ├── ManagedPrefs.kt  ControllerPrefs.kt   # Task 7
│   └── DeviceRegistry.kt                     # Task 7 (DeviceRecord, DeviceRegistryCodec, DeviceRegistry)
├── managed/                             # Task 8-9
│   ├── AdminReceiver.kt  BootReceiver.kt  ManagedSetup.kt  PolicyManager.kt  StatusCollector.kt
│   ├── CommandExecutor.kt  ManagedLinkState.kt  ManagedLinkService.kt
├── controller/                          # Task 12-13
│   ├── ControllerLink.kt  ControllerService.kt  SessionManager.kt
│   └── provisioning/{ControllerIdentity,QrPayloadBuilder,SignatureChecksum,ApkHttpServer,HotspotManager,NetUtils,QrBitmap,ProvisioningController}.kt
├── activities/
│   ├── main/{MainActivity,ControllerViewModel}.kt, navigation/Routes.kt,
│   │   screens/{DevicesScreen,DeviceScreen,ProvisionScreen}.kt, components/{ServiceBanner,OnlineIndicator,AppPickerDialog}.kt   # Task 14
│   ├── managed/{ManagedHomeActivity,ManagedViewModel}.kt, navigation/Routes.kt, screens/HomeScreen.kt, components/LinkBanner.kt  # Task 11
│   ├── managed/provisioning/{GetProvisioningModeActivity,PolicyComplianceActivity}.kt   # Task 10
│   └── settings/SettingsActivity.kt, navigation/Routes.kt, screens/{SettingsCategories,SettingsScreen,SettingsEasterEgg,SettingsControllerScreen}.kt, components/SettingsComponents.kt  # Task 2 + 15
├── ui/layouts/{Layout,BasicLayoutWithTopBar}.kt  ui/theme/{Color,Theme,Type}.kt   # Task 2
└── utils/AppVersion.kt                  # Task 2
app/src/main/res/xml/device_admin.xml    # Task 8
app/src/test/java/edu/fnosari/momedm/protocol/*Test.kt, controller/provisioning/*Test.kt, persistence/*Test.kt
docs/testing.md, README.md, CLAUDE.md    # Task 16
```

---

### Task 1: Gradle + manifest baseline (Compose, serialization, deps, permissions, theme)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (root)
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/themes.xml`, `app/src/main/res/values-night/themes.xml`
- Create: `TEST/SerializationSmokeTest.kt`
- Delete: `app/src/test/java/edu/fnosari/momedm/ExampleUnitTest.kt`, `app/src/androidTest/java/edu/fnosari/momedm/ExampleInstrumentedTest.kt`

**Interfaces:**
- Produces: version-catalog aliases used by every later task: `libs.androidx.compose.bom`, `libs.androidx.compose.ui`, `libs.androidx.compose.ui.graphics`, `libs.androidx.compose.ui.tooling`, `libs.androidx.compose.ui.tooling.preview`, `libs.androidx.compose.material3`, `libs.androidx.activity.compose`, `libs.androidx.lifecycle.runtime.ktx`, `libs.androidx.lifecycle.viewmodel.compose`, `libs.androidx.navigation.compose`, `libs.androidx.datastore.preferences`, `libs.kotlinx.serialization.json`, `libs.kotlinx.coroutines.android`, `libs.zxing.core`, `libs.nanohttpd`, `libs.junit`, `libs.kotlinx.coroutines.test`.

- [ ] **Step 1: Replace `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.3.1"
kotlin = "2.2.10"
coreKtx = "1.19.0"
junit = "4.13.2"
lifecycle = "2.10.0"
activityCompose = "1.13.0"
composeBom = "2024.09.00"
navigationCompose = "2.9.7"
datastore = "1.2.1"
kotlinxSerialization = "1.9.0"
kotlinxCoroutines = "1.10.2"
zxing = "3.5.3"
nanohttpd = "2.3.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }
nanohttpd = { group = "org.nanohttpd", name = "nanohttpd", version.ref = "nanohttpd" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

- [ ] **Step 3: `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "edu.fnosari.momedm"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "edu.fnosari.momedm"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.nanohttpd)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 4: Theme resources → Compose-only**

`app/src/main/res/values/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.MomeDM" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```
`app/src/main/res/values-night/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.MomeDM" parent="android:Theme.Material.NoActionBar" />
</resources>
```
Delete `ExampleUnitTest.kt` and `ExampleInstrumentedTest.kt` (they reference removed deps). Remove the `app/src/androidTest` tree entirely.

- [ ] **Step 5: `AndroidManifest.xml` baseline (permissions + queries; activities added by later tasks)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- BLE: both roles -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
    <!-- Provisioning hotspot + APK hosting (controller) -->
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Foreground services (both roles) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <!-- Managed role -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />

    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
        <package android:name="com.android.vending" />
    </queries>

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.MomeDM">
    </application>

</manifest>
```
(`allowBackup=false`: the secret must not be restored onto another device. Delete `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`.)

- [ ] **Step 6: Serialization smoke test** — `TEST/SerializationSmokeTest.kt`

```kotlin
package edu.fnosari.momedm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationSmokeTest {
    @Serializable
    data class Probe(val a: Int, val b: String)

    @Test
    fun roundTrip() {
        val json = Json.encodeToString(Probe.serializer(), Probe(1, "x"))
        assertEquals("""{"a":1,"b":"x"}""", json)
        assertEquals(Probe(1, "x"), Json.decodeFromString(Probe.serializer(), json))
    }
}
```

- [ ] **Step 7: Build + test**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 1 test passed. If the serialization plugin is rejected by built-in Kotlin (error mentions "KotlinCompilerPluginSupportPlugin" or the `@Serializable` class lacks a `serializer()`), fall back: add `android.builtInKotlin=false` to `gradle.properties`, add `kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }` to `[plugins]`, apply it in both build files (root `apply false`, app normally) and re-run.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "build: compose, serialization, BLE/DPC deps and manifest baseline"
```

---

### Task 2: Copy app scaffolding from BLEController (layouts, theme, prefs, settings activity, AppVersion)

**Files:**
- Create (copied): `SRC/ui/layouts/Layout.kt`, `SRC/ui/layouts/BasicLayoutWithTopBar.kt`, `SRC/ui/theme/{Color,Theme,Type}.kt`, `SRC/persistence/preferences/{Preference,PreferencesProvider,DataStorePreferencesProvider,DefaultPreferencesProvider}.kt`, `SRC/utils/AppVersion.kt`, `SRC/activities/settings/SettingsActivity.kt`, `SRC/activities/settings/navigation/Routes.kt`, `SRC/activities/settings/screens/{SettingsCategories,SettingsScreen,SettingsEasterEgg}.kt`, `SRC/activities/settings/components/SettingsComponents.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `MomeDMTheme { content }`, `BasicLayoutWithTopBar(title, leftActionIcon, leftAction, rightActions, content)`, `Layout.BasicLayoutWithTopBarAndDrawer(title, rightActions, drawerItems, drawerName, content)`, `Layout.DrawerItem(label, icon, onClick)`, `PreferencesProvider` (Flow reads / suspend writes), `DataStorePreferencesProvider(context)`, `Preference.{String,Int,Boolean,Double}Preference`, `getAppVersion(context)`, `SettingsActivity`, `SettingsMenu(navController)`, `SettingsScreen(navController, provider, prefs)`, `SettingsCategoryItem(title, icon, onClick)`, `SettingsCategoryDivider()`, `SettingsAppVersion(...)`.

- [ ] **Step 1: Copy + re-namespace**

```bash
cd C:/Users/ACH02/Documents/Projects/Android/momedm
BLEC=C:/Users/ACH02/Documents/Projects/Android/BLEController/app/src/main/java/com/nosari20/blecontroller
SRC=app/src/main/java/edu/fnosari/momedm
mkdir -p $SRC/ui/layouts $SRC/ui/theme $SRC/persistence/preferences $SRC/utils $SRC/activities/settings/navigation $SRC/activities/settings/screens $SRC/activities/settings/components
cp $BLEC/ui/layouts/*.kt $SRC/ui/layouts/
cp $BLEC/ui/theme/*.kt $SRC/ui/theme/
cp $BLEC/persistance/preferences/*.kt $SRC/persistence/preferences/
cp $BLEC/utils/AppVersion.kt $SRC/utils/
cp $BLEC/activities/settings/SettingsActivity.kt $SRC/activities/settings/
cp $BLEC/activities/settings/navigation/Routes.kt $SRC/activities/settings/navigation/
cp $BLEC/activities/settings/screens/*.kt $SRC/activities/settings/screens/
cp $BLEC/activities/settings/components/*.kt $SRC/activities/settings/components/
find $SRC -name '*.kt' -exec sed -i \
  -e 's/com\.nosari20\.blecontroller\.persistance/edu.fnosari.momedm.persistence/g' \
  -e 's/com\.nosari20\.blecontroller/edu.fnosari.momedm/g' \
  -e 's/BLEControllerTheme/MomeDMTheme/g' {} +
grep -rn "nosari20\|persistance\|BLEController" $SRC || echo CLEAN
```

- [ ] **Step 2: Trim `SettingsMenu`** in `SRC/activities/settings/screens/SettingsCategories.kt` — keep as copied (Legal, Licenses, divider, version/easter egg). No change needed beyond namespace. In `SettingsActivity.kt` keep the copied NavHost as is.

- [ ] **Step 3: Strings** — replace `app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Môme DM</string>

    <!-- Settings -->
    <string name="settings_activity_name">Settings</string>
    <string name="settings_screen_title">Settings</string>
    <string name="settings_screen_category_controller">Controller</string>
    <string name="settings_screen_category_legal">Legal</string>
    <string name="settings_screen_category_licenses">Licenses</string>
    <string name="settings_screen_category_easteregg">Easter Egg</string>
    <string name="settings_screen_category_easteregg_title">🎉</string>
    <string name="settings_screen_category_easteregg_text">You found the hidden chalkboard. Back to class!</string>
    <string name="settings_screen_version">Version %1$s (%2$s)</string>
    <string name="settings_screen_copyrights">© 2026 fnosari</string>
    <string name="settings_dialog_title_edit">Edit Value</string>
    <string name="settings_dialog_confirm">Save</string>
    <string name="settings_dialog_dismiss">Cancel</string>
</resources>
```

- [ ] **Step 4: Manifest** — inside `<application>` add:

```xml
        <activity
            android:name=".activities.settings.SettingsActivity"
            android:exported="false"
            android:label="@string/settings_activity_name"
            android:theme="@style/Theme.MomeDM" />
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (unused composables are fine).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: copy BLEController UI scaffolding, theme, preferences and settings activity"
```

---

### Task 3: Copy BLE framework verbatim

**Files:**
- Create (copied): `SRC/connectivity/ble/{BLEClient,BLEServer,BLEOperationQueue,BLEOperation,BLEException,BLEDevice}.kt`, `SRC/connectivity/ble/README.md`, `SRC/connectivity/ble/characteristics/BLECharacteristic.kt`, `SRC/connectivity/ble/services/BLEService.kt`

**Interfaces:**
- Produces (unchanged from BLEController): `BLEClient(context, serverName, servicesToListen, callBack)` with `startScan(onTimeout)`, `disconnect()`, `writeCharacteristic(service, char)`, `readCharacteristic(...)`, `BLEClient.BLEClientCallBack { onCharacteristicChanged(char, service); onConnected(); onDisconnected() }`; `BLEServer(context, clientLimit, callBack)` with `addService`, `startServer`, `stopServer`, `updateCharacteristic(service, char)`, `connectedDevices`, `BLEServer.BLEServerCallBack { onCharacteristicWriteRequest(char, service, device); onDeviceConnected(device); onDeviceDisconnected(device) }`; `BLEService(uuid, name, type)` + `addCharacteristic`; `BLECharacteristic(uuid, name, value, permission)`; `BLEException`.

- [ ] **Step 1: Copy**

```bash
cd C:/Users/ACH02/Documents/Projects/Android/momedm
BLEC=C:/Users/ACH02/Documents/Projects/Android/BLEController/app/src/main/java/com/nosari20/blecontroller
SRC=app/src/main/java/edu/fnosari/momedm
mkdir -p $SRC/connectivity/ble/characteristics $SRC/connectivity/ble/services
cp $BLEC/connectivity/ble/*.kt $BLEC/connectivity/ble/README.md $SRC/connectivity/ble/
cp $BLEC/connectivity/ble/characteristics/BLECharacteristic.kt $SRC/connectivity/ble/characteristics/
cp $BLEC/connectivity/ble/services/BLEService.kt $SRC/connectivity/ble/services/
# sample services are app-specific: not copied (ClassInfoService, ClientCommunicationService)
find $SRC/connectivity -type f -exec sed -i 's/com\.nosari20\.blecontroller/edu.fnosari.momedm/g' {} +
sed -i 's/^package edu.fnosari.momedm.connectivity.ble;$/package edu.fnosari.momedm.connectivity.ble/' $SRC/connectivity/ble/BLEClient.kt
grep -rn "nosari20" $SRC/connectivity || echo CLEAN
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`BLEServer` imports `androidx.compose.runtime.mutableStateListOf` — Compose is present.) Remove the two unused imports in `BLEServer.kt` if the compiler warns: `import edu.fnosari.momedm.connectivity.ble.BLEClient.BLEClientExitCode` and `...BLEClient.Companion`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(ble): import connectivity/ble framework from BLEController"
```

---
### Task 4: BLE framework extensions (NOTIFY, CCCD via queue, MTU, per-device notify queue, UUID scan/advertise, disconnect)

**Files:**
- Modify: `SRC/connectivity/ble/characteristics/BLECharacteristic.kt`
- Modify: `SRC/connectivity/ble/BLEOperation.kt`
- Modify: `SRC/connectivity/ble/BLEOperationQueue.kt`
- Modify: `SRC/connectivity/ble/BLEClient.kt`
- Modify: `SRC/connectivity/ble/BLEServer.kt`
- Modify: `SRC/connectivity/ble/README.md`

**Interfaces:**
- Produces:
  - `BLECharacteristic.Permission.NOTIFY` (properties = `PROPERTY_NOTIFY`, permissions = `PERMISSION_READ`).
  - `BLEClient(context, serverName: String?, servicesToListen, callBack, serviceUuid: UUID? = null)`; callback gains `fun onMtuChanged(mtu: Int) {}` (default no-op). Connection sequence: connect → discover → enqueue CCCD writes for NOTIFY/READ characteristics (through the op queue) → `requestMtu(517)` → `onMtuChanged(mtu)` → `onConnected()`. If `requestMtu` fails, `onMtuChanged(23)` then `onConnected()`.
  - `BLEServer(context, clientLimit = 5, callBack, includeDeviceName: Boolean = true)`; new `fun notifyDevice(device, service, characteristic)` (per-device, serialized via `onNotificationSent`), `fun disconnectDevice(device)`, `stopServer()` now also stops advertising; callback gains `fun onMtuChanged(device: BluetoothDevice, mtu: Int) {}` (default no-op). NOTIFY characteristics get a CCCD descriptor; `onDescriptorWriteRequest` is answered.
  - `BLEOperation.WriteDescriptor(descriptor, value)`.

- [ ] **Step 1: `BLECharacteristic.kt` — add NOTIFY**

Replace the `Permission` enum and both `when` blocks:

```kotlin
    enum class Permission {
        READ,
        WRITE,
        READ_WRITE,
        /** Server pushes values; clients subscribe via CCCD. Readable so the last value can be fetched. */
        NOTIFY
    }

    val properties: Int
        get() = when (permission) {
            Permission.READ -> BluetoothGattCharacteristic.PROPERTY_READ
            Permission.WRITE -> BluetoothGattCharacteristic.PROPERTY_WRITE
            Permission.READ_WRITE -> BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE
            Permission.NOTIFY -> BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        }

    val permissions: Int
        get() = when (permission) {
            Permission.READ -> BluetoothGattCharacteristic.PERMISSION_READ
            Permission.WRITE -> BluetoothGattCharacteristic.PERMISSION_WRITE
            Permission.READ_WRITE -> BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            Permission.NOTIFY -> BluetoothGattCharacteristic.PERMISSION_READ
        }

    /** True when a central should subscribe (write the CCCD) for this characteristic. */
    val notifies: Boolean get() = permission == Permission.NOTIFY
```

- [ ] **Step 2: `BLEOperation.kt` — descriptor write op**

```kotlin
package edu.fnosari.momedm.connectivity.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

sealed class BLEOperation {
    data class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic) : BLEOperation()
    data class WriteCharacteristic(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : BLEOperation()
    /** CCCD (or any descriptor) write; completes on [android.bluetooth.BluetoothGattCallback.onDescriptorWrite]. */
    data class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : BLEOperation()
}
```

- [ ] **Step 3: `BLEOperationQueue.kt` — handle `WriteDescriptor`**

In `processNext()` extend the `when`:
```kotlin
        val started: Boolean = when (op) {
            is BLEOperation.ReadCharacteristic -> gatt.readCharacteristic(op.characteristic)
            is BLEOperation.WriteCharacteristic -> startWrite(op)
            is BLEOperation.WriteDescriptor -> startDescriptorWrite(op)
        }
```
Add:
```kotlin
    @Suppress("DEPRECATION") // pre-Tiramisu descriptor API
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startDescriptorWrite(op: BLEOperation.WriteDescriptor): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(op.descriptor, op.value) == BluetoothStatusCodes.SUCCESS
        } else {
            op.descriptor.value = op.value
            gatt.writeDescriptor(op.descriptor)
        }
    }
```

- [ ] **Step 4: `BLEClient.kt` — constructor, UUID scan filter, CCCD via queue, MTU**

4a. Constructor + KDoc:
```kotlin
/**
 * ...
 * @param serverName Advertised device name to match, or null to accept any device passing [serviceUuid].
 * @param serviceUuid When non-null, the scan is filtered on this advertised service UUID.
 */
class BLEClient(
    private val context: Context,
    private val serverName: String?,
    private val servicesToListen: List<BLEService>,
    val callBack: BLEClientCallBack,
    private val serviceUuid: UUID? = null,
) {
```
Add to companion: `private const val REQUESTED_MTU = 517` and `private const val DEFAULT_MTU = 23`.

4b. Callback interface:
```kotlin
    interface BLEClientCallBack {
        fun onCharacteristicChanged(characteristic: BLECharacteristic, service: BLEService)
        fun onConnected()
        fun onDisconnected()
        /** Negotiated ATT MTU (payload = mtu - 3). Called once per connection, before [onConnected]. */
        fun onMtuChanged(mtu: Int) {}
    }
```

4c. Scan match in `_scanCallback` — replace `if(device.name == serverName && _server == null){` with:
```kotlin
                val nameMatches = serverName == null || device.name == serverName || deviceName == serverName
                if (nameMatches && _server == null) {
```

4d. `startScan` — build filters:
```kotlin
        val filters: List<ScanFilter>? = serviceUuid?.let { listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
        _bluetoothLeScanner.startScan(filters, scanSettings, _scanCallback)
```
Add imports `android.bluetooth.le.ScanFilter`, `android.os.ParcelUuid`. Update the log line: `"Starting device scan, looking for name=$serverName service=$serviceUuid"`.

4e. `onServicesDiscovered` — subscribe only for `notifies` characteristics, then request MTU; move `callBack.onConnected()` out:
```kotlin
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                Log.w(LOG_TAG, "Service discovery failed: $status")
                return
            }
            gatt.services?.forEach { service ->
                Log.d(LOG_TAG, "Service discovered: ${service.uuid}")
                val serviceToListen = servicesToListen.firstOrNull { it.uuid == service.uuid } ?: return@forEach
                Log.d(LOG_TAG, "Service ${service.uuid} in services to listen")
                _listeningServices.add(service)
                service.characteristics.forEach { characteristic ->
                    val sCharacteristic = serviceToListen.characteristics.firstOrNull { it.uuid == characteristic.uuid }
                    if (sCharacteristic != null && sCharacteristic.notifies) {
                        Log.d(LOG_TAG, "Subscribing to ${sCharacteristic.name} (${sCharacteristic.uuid})")
                        enableNotifications(gatt, characteristic)
                    }
                }
            }
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(LOG_TAG, "BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
                return
            }
            // MTU negotiation is not a queued GATT op; its callback fires onConnected.
            if (!gatt.requestMtu(REQUESTED_MTU)) {
                Log.w(LOG_TAG, "requestMtu failed; assuming default MTU")
                callBack.onMtuChanged(DEFAULT_MTU)
                callBack.onConnected()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val effective = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            Log.d(LOG_TAG, "MTU changed: $mtu (status $status) -> using $effective")
            callBack.onMtuChanged(effective)
            callBack.onConnected()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(LOG_TAG, "Descriptor ${descriptor.uuid} write status: $status")
            _operationQueue?.signalOperationComplete()
        }
```

4f. `enableNotifications` — enqueue instead of direct write. Replace the body after the CCCD null-check with:
```kotlin
        _operationQueue?.enqueue(BLEOperation.WriteDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
        Log.d(LOG_TAG, "Queued CCCD write for ${characteristic.uuid}")
```
(Remove the direct `gatt.writeDescriptor` branches and the `Build` usage in that function.)

- [ ] **Step 5: `BLEServer.kt` — constructor flag, CCCD descriptor, notify queue, disconnect, stop advertising, MTU**

5a. Constructor:
```kotlin
class BLEServer(
    private val context: Context,
    private val clientLimit: Int = 5,
    private val callBack: BLEServerCallBack,
    private val includeDeviceName: Boolean = true,
) {
```
Companion additions:
```kotlin
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val NOTIFY_TIMEOUT_MS = 5000L
```
Imports: `android.bluetooth.BluetoothGattDescriptor`, `java.util.UUID`.

5b. Callback:
```kotlin
    interface BLEServerCallBack {
        fun onCharacteristicWriteRequest(characteristic: BLECharacteristic, service: BLEService, device: BluetoothDevice)
        fun onDeviceConnected(device: BluetoothDevice)
        fun onDeviceDisconnected(device: BluetoothDevice)
        /** ATT MTU negotiated by [device]; payload per notification = mtu - 3. */
        fun onMtuChanged(device: BluetoothDevice, mtu: Int) {}
    }
```

5c. Notify queue state (fields):
```kotlin
    private data class PendingNotify(val device: BluetoothDevice, val characteristic: BluetoothGattCharacteristic, val value: ByteArray)
    private val _notifyLock = Any()
    private val _notifyQueue: ArrayDeque<PendingNotify> = ArrayDeque()
    private var _notifyInFlight = false
    private val _notifyHandler = Handler(Looper.getMainLooper())
    private val _notifyTimeout = Runnable {
        Log.w(LOG_TAG, "Notification send timed out; advancing notify queue")
        onNotifyDone()
    }
    private var _advertiseCallback: AdvertiseCallback? = null
```

5d. In `gattServerCallback` add:
```kotlin
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            Log.d(LOG_TAG, "${device.address} writes descriptor ${descriptor.uuid} on ${descriptor.characteristic.uuid}")
            if (!responseNeeded) return
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(LOG_TAG, "BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
                return
            }
            _gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            Log.d(LOG_TAG, "Notification sent to ${device.address}: status $status")
            onNotifyDone()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(LOG_TAG, "MTU for ${device.address}: $mtu")
            callBack.onMtuChanged(device, mtu)
        }
```

5e. `startServer()` — after `val c = BluetoothGattCharacteristic(...)` add CCCD for notify chars:
```kotlin
                if (characteristic.notifies) {
                    c.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                }
```
Replace `val data = AdvertiseData.Builder().setIncludeDeviceName(true)` with `.setIncludeDeviceName(includeDeviceName)`. Store the advertise callback: `_advertiseCallback = object : AdvertiseCallback() { ... }` then `advertiser.startAdvertising(settings, data.build(), _advertiseCallback)`.

5f. `stopServer()` — before cancelling connections:
```kotlin
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
            _advertiseCallback?.let { _bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
            _advertiseCallback = null
        }
```

5g. New public methods + queue internals:
```kotlin
    /** Pushes [characteristic]'s current value to one connected central, serialized with other notifications. */
    @Throws(BLEException::class)
    fun notifyDevice(device: BluetoothDevice, service: BLEService, characteristic: BLECharacteristic) {
        val c = _gattServer.getService(service.uuid)?.getCharacteristic(characteristic.uuid)
            ?: throw BLEException("Service ${service.uuid} or characteristic ${characteristic.uuid} not found: ${BLEServerExitCode.ERROR_BLUETOOTH_WRITE_SERVICE_OR_CHARACTERISTIC_NOT_FOUND}")
        synchronized(_notifyLock) {
            _notifyQueue.add(PendingNotify(device, c, characteristic.value.toByteArray(Charsets.UTF_8)))
        }
        processNextNotify()
    }

    /** Drops the link to [device]. */
    fun disconnectDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(LOG_TAG, "BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
            return
        }
        Log.d(LOG_TAG, "Disconnecting ${device.address}")
        _gattServer.cancelConnection(device)
    }

    private fun processNextNotify() {
        val next: PendingNotify
        synchronized(_notifyLock) {
            if (_notifyInFlight || _notifyQueue.isEmpty()) return
            _notifyInFlight = true
            next = _notifyQueue.removeFirst()
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(LOG_TAG, "BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
            onNotifyDone()
            return
        }
        Log.d(LOG_TAG, "Notify ${next.device.address} on ${next.characteristic.uuid} (${next.value.size} bytes)")
        val code = _gattServer.notifyCharacteristicChanged(next.device, next.characteristic, false, next.value)
        if (code != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
            Log.w(LOG_TAG, "notifyCharacteristicChanged failed: $code")
            onNotifyDone()
        } else {
            _notifyHandler.postDelayed(_notifyTimeout, NOTIFY_TIMEOUT_MS)
        }
    }

    private fun onNotifyDone() {
        _notifyHandler.removeCallbacks(_notifyTimeout)
        synchronized(_notifyLock) { _notifyInFlight = false }
        processNextNotify()
    }
```
Rewrite `updateCharacteristic` to `for (device in _connectedDevices) notifyDevice(device, service, characteristic)`.

- [ ] **Step 6: README** — under "What it handles for you" add bullets: NOTIFY permission + CCCD descriptor on server; CCCD writes queued; MTU negotiation (`onMtuChanged`); per-device serialized notifications (`notifyDevice`); scan/advertise by service UUID (`serviceUuid`, `includeDeviceName`); `disconnectDevice`. Update the client callback snippet with `onMtuChanged`.

- [ ] **Step 7: Compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. On-device behaviour verified later via the loopback of the two roles (Task 16 checklist).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(ble): notify chars, queued CCCD, MTU, per-device notify queue, UUID scan/advertise"
```

---
### Task 5: Protocol package (pure Kotlin, TDD) + GATT constants

**Files:**
- Create: `SRC/protocol/Encoding.kt`, `SRC/protocol/Crypto.kt`, `SRC/protocol/Framer.kt`, `SRC/protocol/Messages.kt`, `SRC/protocol/Handshake.kt`, `SRC/protocol/SecureChannel.kt`, `SRC/protocol/Endpoints.kt`, `SRC/protocol/ProvisioningExtras.kt`, `SRC/link/MdmGatt.kt`
- Test: `TEST/protocol/EncodingTest.kt`, `TEST/protocol/CryptoTest.kt`, `TEST/protocol/FramerTest.kt`, `TEST/protocol/MessagesTest.kt`, `TEST/protocol/HandshakeTest.kt`, `TEST/protocol/SecureChannelTest.kt`, `TEST/protocol/EndpointLoopbackTest.kt`

**Interfaces:**
- Produces (all in `edu.fnosari.momedm.protocol` unless noted):
  - `object Hex { encode(ByteArray): String; decode(String): ByteArray }`, `object Base64Url { encodeNoPad(ByteArray): String }`, `object Base64Std { encode(ByteArray): String; decode(String): ByteArray }`
  - `object Crypto { hmacSha256(key, data): ByteArray; hmacHex(key: ByteArray, data: String): String; randomBytes(n): ByteArray; randomHex(nBytes): String; constantTimeEquals(a: String, b: String): Boolean }`
  - `object Framer { HEADER_MAX=15; maxChunk(mtu): Int; split(msgId: Int, payload: String, chunkSize: Int): List<String>; parse(frame): Frame? }`, `data class Framer.Frame(msgId, index, count, chunk)`, `class Reassembler(timeoutMs=10_000) { feed(frame: String, nowMs: Long): String? }`
  - `sealed class Message` with `Hello(deviceId, model, nonceC, mtu)`, `Challenge(nonceS, proof)`, `Auth(proof)`, `AuthOk`, `Status(kiosk, kioskPkg, account, battery, currentApp)`, `Apps(apps: List<AppInfo>)`, `Result(cmdId, ok, msg)`, `Cmd(id, type: CmdType, pkg: String?)`; `data class AppInfo(pkg, label)`; `enum CmdType { KIOSK_ON, KIOSK_OFF, INSTALL, ADD_ACCOUNT, LIST_APPS, GET_STATUS }`; `data class Envelope(seq: Long, body: String, mac: String) { companion plain(m: Message) }`; `object MessageCodec { encodeMessage/decodeMessage, encodeEnvelope/decodeEnvelope, asciiEscape }`
  - `class ManagedHandshake(secret: ByteArray, deviceId, model, mtu, nonceC = Crypto.randomHex(16)) { hello(): Message.Hello; onChallenge(c): Message.Auth?; sessionKey }`, `class ControllerHandshake(secret, nonceS = Crypto.randomHex(16)) { onHello(h): Message.Challenge; onAuth(a): Boolean; hello; sessionKey }`
  - `class SecureChannel(sessionKey) { seal(m): Envelope; open(e): Message }`, `class ProtocolException(msg)`
  - `fun interface FrameSink { send(frame: String) }`; `class ManagedEndpoint(secret, deviceId, model, sink, listener, clock)` with `Listener { onAuthenticated(); onCommand(cmd: Message.Cmd); onProtocolError(reason) }`, `onConnected(mtu)`, `onFrame(frame)`, `send(m)`, `authenticated`, `reset()`; `class ControllerEndpoint(secret, sink, listener, clock)` with `Listener { onAuthenticated(hello); onMessage(m); onProtocolError(reason) }`, `onFrame`, `send`, `authenticated`, `deviceId`, `mtu`, `reset()`
  - `object ProvisioningExtras { KEY_CONTROLLER_ID = "controller_id"; KEY_SECRET = "secret" }`
  - `edu.fnosari.momedm.link.MdmGatt { SERVICE_UUID, CMD_UUID, RSP_UUID }`, `class MdmService : BLEService` (holds `cmd: CmdCharacteristic`, `rsp: RspCharacteristic`), `class CmdCharacteristic : BLECharacteristic(NOTIFY)`, `class RspCharacteristic : BLECharacteristic(WRITE)`

- [ ] **Step 1: Encoding + Crypto tests**

`TEST/protocol/EncodingTest.kt`:
```kotlin
package edu.fnosari.momedm.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EncodingTest {
    @Test fun hexRoundTrip() {
        val b = byteArrayOf(0, 1, 0x7f, -1, 0x10)
        assertEquals("00017fff10", Hex.encode(b))
        assertArrayEquals(b, Hex.decode("00017fff10"))
    }
    @Test fun base64UrlNoPadding() {
        // "any carnal pleas" -> standard "YW55IGNhcm5hbCBwbGVhcw==" ; url-safe no pad drops '=='
        assertEquals("YW55IGNhcm5hbCBwbGVhcw", Base64Url.encodeNoPad("any carnal pleas".toByteArray()))
        assertEquals("-_8", Base64Url.encodeNoPad(byteArrayOf(-5, -1)))
    }
    @Test fun base64StdRoundTrip() {
        val b = ByteArray(32) { it.toByte() }
        assertArrayEquals(b, Base64Std.decode(Base64Std.encode(b)))
    }
}
```
`TEST/protocol/CryptoTest.kt`:
```kotlin
package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    @Test fun hmacKnownVector() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        val mac = Crypto.hmacHex("Jefe".toByteArray(), "what do ya want for nothing?")
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac)
    }
    @Test fun randomHexLengthAndUniqueness() {
        val a = Crypto.randomHex(16); val b = Crypto.randomHex(16)
        assertEquals(32, a.length); assertNotEquals(a, b)
    }
    @Test fun constantTimeEquals() {
        assertTrue(Crypto.constantTimeEquals("abc", "abc"))
        assertFalse(Crypto.constantTimeEquals("abc", "abd"))
        assertFalse(Crypto.constantTimeEquals("abc", "abcd"))
    }
}
```

- [ ] **Step 2: Run → fail** — `./gradlew :app:testDebugUnitTest --tests "edu.fnosari.momedm.protocol.*"` → compile errors (classes missing).

- [ ] **Step 3: Implement `Encoding.kt` + `Crypto.kt`**

```kotlin
package edu.fnosari.momedm.protocol

import java.util.Base64

/** Lower-case hex codec. */
object Hex {
    private const val DIGITS = "0123456789abcdef"
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xff; sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f]) }
        return sb.toString()
    }
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i -> ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte() }
    }
}

/** URL-safe base64 without padding (format required by PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM). */
object Base64Url {
    fun encodeNoPad(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Standard base64 (secret transport inside the QR admin-extras bundle). */
object Base64Std {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
}
```
```kotlin
package edu.fnosari.momedm.protocol

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 helpers and nonces. Pure JVM — no Android. */
object Crypto {
    private const val ALGO = "HmacSHA256"
    private val random = SecureRandom()

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGO)
        mac.init(SecretKeySpec(key, ALGO))
        return mac.doFinal(data)
    }
    fun hmacHex(key: ByteArray, data: String): String = Hex.encode(hmacSha256(key, data.toByteArray(Charsets.UTF_8)))
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
    fun randomHex(nBytes: Int): String = Hex.encode(randomBytes(nBytes))
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
```

- [ ] **Step 4: Run → pass** — same command; EncodingTest + CryptoTest green.

- [ ] **Step 5: Framer tests** — `TEST/protocol/FramerTest.kt`

```kotlin
package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramerTest {
    @Test fun maxChunkFromMtu() {
        assertEquals(5, Framer.maxChunk(23))      // 23-3-15
        assertEquals(499, Framer.maxChunk(517))
        assertEquals(1, Framer.maxChunk(10))      // never below 1
    }
    @Test fun splitAndParse() {
        val frames = Framer.split(0x1a2b, "abcdefghij", 4)
        assertEquals(listOf("1a2b:0/3:abcd", "1a2b:1/3:efgh", "1a2b:2/3:ij"), frames)
        val f = Framer.parse("1a2b:1/3:efgh")!!
        assertEquals(0x1a2b, f.msgId); assertEquals(1, f.index); assertEquals(3, f.count); assertEquals("efgh", f.chunk)
    }
    @Test fun emptyPayloadIsOneFrame() {
        assertEquals(listOf("0001:0/1:"), Framer.split(1, "", 5))
    }
    @Test fun parseRejectsGarbage() {
        assertNull(Framer.parse("nope")); assertNull(Framer.parse("zzzz:0/1:x")); assertNull(Framer.parse("0001:2/1:x"))
    }
    @Test fun reassembleInOrder() {
        val r = Reassembler()
        assertNull(r.feed("0001:0/2:hel", 0L))
        assertEquals("hello", r.feed("0001:1/2:lo", 1L))
    }
    @Test fun reassembleDropsStaleMessage() {
        val r = Reassembler(timeoutMs = 10_000)
        assertNull(r.feed("0001:0/2:hel", 0L))
        assertNull(r.feed("0002:0/1:x", 20_000L).let { assertEquals("x", it); null })
        assertNull(r.feed("0001:1/2:lo", 20_001L)) // first message expired → restarted, still incomplete
    }
    @Test fun interleavedMessagesAreIndependent() {
        val r = Reassembler()
        assertNull(r.feed("000a:0/2:A1", 0L)); assertNull(r.feed("000b:0/2:B1", 0L))
        assertEquals("B1B2", r.feed("000b:1/2:B2", 0L)); assertEquals("A1A2", r.feed("000a:1/2:A2", 0L))
    }
}
```

- [ ] **Step 6: Run → fail**, then implement `Framer.kt`:

```kotlin
package edu.fnosari.momedm.protocol

/**
 * Splits an ASCII payload into BLE-sized frames `"<msgId>:<idx>/<count>:<chunk>"` and reassembles them.
 * msgId = 4 lower-case hex chars; idx/count up to 4 digits; header worst case = 4+1+4+1+4+1 = 15 chars.
 */
object Framer {
    const val HEADER_MAX = 15
    const val MAX_COUNT = 9999
    private val FRAME_RE = Regex("^([0-9a-f]{4}):(\\d{1,4})/(\\d{1,4}):(.*)$", RegexOption.DOT_MATCHES_ALL)

    data class Frame(val msgId: Int, val index: Int, val count: Int, val chunk: String)

    /** Largest chunk that fits an ATT payload of `mtu - 3` bytes after the header. Never < 1. */
    fun maxChunk(mtu: Int): Int = (mtu - 3 - HEADER_MAX).coerceAtLeast(1)

    fun split(msgId: Int, payload: String, chunkSize: Int): List<String> {
        require(chunkSize >= 1) { "chunkSize must be >= 1" }
        val id = String.format("%04x", msgId and 0xffff)
        if (payload.isEmpty()) return listOf("$id:0/1:")
        val chunks = payload.chunked(chunkSize)
        require(chunks.size <= MAX_COUNT) { "payload too large: ${chunks.size} frames" }
        return chunks.mapIndexed { i, c -> "$id:$i/${chunks.size}:$c" }
    }

    fun parse(frame: String): Frame? {
        val m = FRAME_RE.matchEntire(frame) ?: return null
        val (id, idx, cnt, chunk) = m.destructured
        val index = idx.toInt(); val count = cnt.toInt()
        if (count < 1 || index >= count) return null
        return Frame(id.toInt(16), index, count, chunk)
    }
}

/** Collects frames per msgId; returns the payload when the last chunk lands. Partial messages expire after [timeoutMs]. */
class Reassembler(private val timeoutMs: Long = 10_000) {
    private class Partial(val count: Int, var startedAt: Long) { val chunks = arrayOfNulls<String>(count); var received = 0 }
    private val partials = HashMap<Int, Partial>()

    fun feed(frame: String, nowMs: Long): String? {
        val f = Framer.parse(frame) ?: return null
        partials.entries.removeIf { nowMs - it.value.startedAt > timeoutMs }
        var p = partials[f.msgId]
        if (p == null || p.count != f.count) { p = Partial(f.count, nowMs); partials[f.msgId] = p }
        if (p.chunks[f.index] == null) p.received++
        p.chunks[f.index] = f.chunk
        if (p.received < p.count) return null
        partials.remove(f.msgId)
        return p.chunks.joinToString("") { it ?: "" }
    }
}
```
Run → pass.

- [ ] **Step 7: Messages + codec tests** — `TEST/protocol/MessagesTest.kt`

```kotlin
package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    @Test fun roundTripAllTypes() {
        val msgs = listOf(
            Message.Hello("d1", "Pixel", "aa", 517), Message.Challenge("bb", "cc"), Message.Auth("dd"), Message.AuthOk,
            Message.Status(true, "com.x", false, 77, "com.x"), Message.Apps(listOf(AppInfo("com.a", "A"))),
            Message.Result("c1", true, "ok"), Message.Cmd("c1", CmdType.KIOSK_ON, "com.a"), Message.Cmd("c2", CmdType.GET_STATUS, null),
        )
        for (m in msgs) assertEquals(m, MessageCodec.decodeMessage(MessageCodec.encodeMessage(m)))
    }
    @Test fun typeDiscriminatorIsT() {
        assertTrue(MessageCodec.encodeMessage(Message.AuthOk).contains("\"t\":\"AUTH_OK\""))
    }
    @Test fun nonAsciiIsEscaped() {
        val enc = MessageCodec.encodeMessage(Message.Apps(listOf(AppInfo("p", "Paramètres 日本"))))
        assertTrue(enc.all { it.code in 0x20..0x7e })
        assertEquals("Paramètres 日本", (MessageCodec.decodeMessage(enc) as Message.Apps).apps[0].label)
    }
    @Test fun envelopeRoundTrip() {
        val e = Envelope(7, MessageCodec.encodeMessage(Message.AuthOk), "ab")
        assertEquals(e, MessageCodec.decodeEnvelope(MessageCodec.encodeEnvelope(e)))
        assertEquals(0L, Envelope.plain(Message.AuthOk).seq)
    }
}
```

- [ ] **Step 8: Run → fail**, implement `Messages.kt`:

```kotlin
package edu.fnosari.momedm.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class CmdType { KIOSK_ON, KIOSK_OFF, INSTALL, ADD_ACCOUNT, LIST_APPS, GET_STATUS }

@Serializable
data class AppInfo(val pkg: String, val label: String)

/** All wire messages. Serialized with discriminator key "t". */
@Serializable
sealed class Message {
    @Serializable @SerialName("HELLO")     data class Hello(val deviceId: String, val model: String, val nonceC: String, val mtu: Int) : Message()
    @Serializable @SerialName("CHALLENGE") data class Challenge(val nonceS: String, val proof: String) : Message()
    @Serializable @SerialName("AUTH")      data class Auth(val proof: String) : Message()
    @Serializable @SerialName("AUTH_OK")   data object AuthOk : Message()
    @Serializable @SerialName("STATUS")    data class Status(val kiosk: Boolean, val kioskPkg: String?, val account: Boolean, val battery: Int, val currentApp: String?) : Message()
    @Serializable @SerialName("APPS")      data class Apps(val apps: List<AppInfo>) : Message()
    @Serializable @SerialName("RESULT")    data class Result(val cmdId: String, val ok: Boolean, val msg: String) : Message()
    @Serializable @SerialName("CMD")       data class Cmd(val id: String, val type: CmdType, val pkg: String? = null) : Message()
}

/** Outer frame payload: handshake messages use seq=0/mac="", everything else is sealed by [SecureChannel]. */
@Serializable
data class Envelope(val seq: Long, val body: String, val mac: String) {
    companion object {
        fun plain(m: Message): Envelope = Envelope(0, MessageCodec.encodeMessage(m), "")
    }
}

object MessageCodec {
    val json: Json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    fun encodeMessage(m: Message): String = asciiEscape(json.encodeToString(Message.serializer(), m))
    fun decodeMessage(s: String): Message = json.decodeFromString(Message.serializer(), s)
    fun encodeEnvelope(e: Envelope): String = asciiEscape(json.encodeToString(Envelope.serializer(), e))
    fun decodeEnvelope(s: String): Envelope = json.decodeFromString(Envelope.serializer(), s)

    /** Escapes every char outside printable ASCII as a JSON `\uXXXX` so frames are byte == char safe. */
    fun asciiEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) if (ch.code in 0x20..0x7e) sb.append(ch) else sb.append(String.format("\\u%04x", ch.code))
        return sb.toString()
    }
}
```
Run → pass.

- [ ] **Step 9: Handshake + SecureChannel tests**

`TEST/protocol/HandshakeTest.kt`:
```kotlin
package edu.fnosari.momedm.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {
    private val secret = ByteArray(32) { 7 }

    @Test fun successfulMutualAuth() {
        val m = ManagedHandshake(secret, "dev", "Pixel", 517, nonceC = "01".repeat(16))
        val c = ControllerHandshake(secret, nonceS = "02".repeat(16))
        val challenge = c.onHello(m.hello())
        val auth = m.onChallenge(challenge); assertNotNull(auth)
        assertTrue(c.onAuth(auth!!))
        assertArrayEquals(m.sessionKey, c.sessionKey)
    }
    @Test fun managedRejectsWrongControllerSecret() {
        val m = ManagedHandshake(secret, "dev", "Pixel", 517)
        val c = ControllerHandshake(ByteArray(32) { 9 })
        assertNull(m.onChallenge(c.onHello(m.hello())))
    }
    @Test fun controllerRejectsWrongManagedSecret() {
        val m = ManagedHandshake(ByteArray(32) { 9 }, "dev", "Pixel", 517)
        val c = ControllerHandshake(secret)
        val ch = c.onHello(m.hello())
        // forge an Auth with the wrong key
        assertFalse(c.onAuth(Message.Auth(Crypto.hmacHex(ByteArray(32) { 9 }, ch.nonceS))))
    }
}
```
`TEST/protocol/SecureChannelTest.kt`:
```kotlin
package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureChannelTest {
    private val key = ByteArray(32) { 3 }

    @Test fun sealOpenIncrementsSeq() {
        val a = SecureChannel(key); val b = SecureChannel(key)
        val e1 = a.seal(Message.AuthOk); val e2 = a.seal(Message.Result("c", true, "ok"))
        assertEquals(1L, e1.seq); assertEquals(2L, e2.seq)
        assertEquals(Message.AuthOk, b.open(e1)); assertEquals(Message.Result("c", true, "ok"), b.open(e2))
    }
    @Test fun replayRejected() {
        val a = SecureChannel(key); val b = SecureChannel(key)
        val e = a.seal(Message.AuthOk); b.open(e)
        assertThrows(ProtocolException::class.java) { b.open(e) }
    }
    @Test fun badMacRejected() {
        val a = SecureChannel(key); val b = SecureChannel(ByteArray(32) { 4 })
        assertThrows(ProtocolException::class.java) { b.open(a.seal(Message.AuthOk)) }
    }
    @Test fun tamperedBodyRejected() {
        val a = SecureChannel(key); val b = SecureChannel(key)
        val e = a.seal(Message.Cmd("1", CmdType.KIOSK_OFF))
        assertThrows(ProtocolException::class.java) { b.open(e.copy(body = e.body.replace("KIOSK_OFF", "KIOSK_ON"))) }
    }
}
```

- [ ] **Step 10: Run → fail**, implement `Handshake.kt` + `SecureChannel.kt`:

```kotlin
package edu.fnosari.momedm.protocol

/** Managed-device side of the mutual HMAC handshake: HELLO → (CHALLENGE) → AUTH. */
class ManagedHandshake(
    private val secret: ByteArray,
    private val deviceId: String,
    private val model: String,
    private val mtu: Int,
    private val nonceC: String = Crypto.randomHex(16),
) {
    private var _sessionKey: ByteArray? = null
    val sessionKey: ByteArray get() = _sessionKey ?: error("handshake not complete")

    fun hello(): Message.Hello = Message.Hello(deviceId, model, nonceC, mtu)

    /** Verifies the controller's proof of [nonceC]; returns our AUTH or null when the controller is an impostor. */
    fun onChallenge(c: Message.Challenge): Message.Auth? {
        if (!Crypto.constantTimeEquals(c.proof, Crypto.hmacHex(secret, nonceC))) return null
        _sessionKey = Crypto.hmacSha256(secret, (nonceC + c.nonceS).toByteArray(Charsets.UTF_8))
        return Message.Auth(Crypto.hmacHex(secret, c.nonceS))
    }
}

/** Controller side: (HELLO) → CHALLENGE → (AUTH) → verified. */
class ControllerHandshake(
    private val secret: ByteArray,
    private val nonceS: String = Crypto.randomHex(16),
) {
    var hello: Message.Hello? = null
        private set
    private var _sessionKey: ByteArray? = null
    val sessionKey: ByteArray get() = _sessionKey ?: error("handshake not complete")

    fun onHello(h: Message.Hello): Message.Challenge {
        hello = h
        return Message.Challenge(nonceS, Crypto.hmacHex(secret, h.nonceC))
    }

    fun onAuth(a: Message.Auth): Boolean {
        val h = hello ?: return false
        if (!Crypto.constantTimeEquals(a.proof, Crypto.hmacHex(secret, nonceS))) return false
        _sessionKey = Crypto.hmacSha256(secret, (h.nonceC + nonceS).toByteArray(Charsets.UTF_8))
        return true
    }
}
```
```kotlin
package edu.fnosari.momedm.protocol

class ProtocolException(message: String) : Exception(message)

/** Per-session integrity: `mac = HMAC(sessionKey, "$seq|$body")`, seq strictly increasing per direction. */
class SecureChannel(private val sessionKey: ByteArray) {
    private var outSeq = 0L
    private var lastInSeq = 0L

    fun seal(m: Message): Envelope {
        val seq = ++outSeq
        val body = MessageCodec.encodeMessage(m)
        return Envelope(seq, body, Crypto.hmacHex(sessionKey, "$seq|$body"))
    }

    @Throws(ProtocolException::class)
    fun open(e: Envelope): Message {
        if (e.seq <= lastInSeq) throw ProtocolException("replay or out-of-order seq ${e.seq} (last ${lastInSeq})")
        if (!Crypto.constantTimeEquals(e.mac, Crypto.hmacHex(sessionKey, "${e.seq}|${e.body}"))) throw ProtocolException("bad mac")
        lastInSeq = e.seq
        return try { MessageCodec.decodeMessage(e.body) } catch (ex: Exception) { throw ProtocolException("undecodable body: ${ex.message}") }
    }
}
```
Run → pass.

- [ ] **Step 11: Endpoint loopback test** — `TEST/protocol/EndpointLoopbackTest.kt`

```kotlin
package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointLoopbackTest {
    private val secret = ByteArray(32) { 5 }

    /** Wires two endpoints through in-memory frame lists, delivering frames in order. */
    private class Wire {
        val toController = ArrayDeque<String>(); val toManaged = ArrayDeque<String>()
        lateinit var managed: ManagedEndpoint; lateinit var controller: ControllerEndpoint
        fun pump() { while (toController.isNotEmpty() || toManaged.isNotEmpty()) {
            toController.removeFirstOrNull()?.let { controller.onFrame(it) }
            toManaged.removeFirstOrNull()?.let { managed.onFrame(it) } } }
    }

    private fun build(mtu: Int, managedSecret: ByteArray = secret): Triple<Wire, MutableList<Message.Cmd>, MutableList<Message>> {
        val w = Wire(); val cmds = mutableListOf<Message.Cmd>(); val ctrlMsgs = mutableListOf<Message>()
        w.managed = ManagedEndpoint(managedSecret, "dev-1", "Pixel", { w.toController.add(it) }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {}
            override fun onCommand(cmd: Message.Cmd) { cmds.add(cmd) }
            override fun onProtocolError(reason: String) {}
        })
        w.controller = ControllerEndpoint(secret, { w.toManaged.add(it) }, object : ControllerEndpoint.Listener {
            override fun onAuthenticated(hello: Message.Hello) {}
            override fun onMessage(m: Message) { ctrlMsgs.add(m) }
            override fun onProtocolError(reason: String) {}
        })
        w.managed.onConnected(mtu); w.pump()
        return Triple(w, cmds, ctrlMsgs)
    }

    @Test fun handshakeAndCommandAtMtu517() {
        val (w, cmds, ctrlMsgs) = build(517)
        assertTrue(w.managed.authenticated); assertTrue(w.controller.authenticated)
        assertEquals("dev-1", w.controller.deviceId); assertEquals(517, w.controller.mtu)
        w.controller.send(Message.Cmd("c1", CmdType.KIOSK_ON, "com.example")); w.pump()
        assertEquals(listOf(Message.Cmd("c1", CmdType.KIOSK_ON, "com.example")), cmds)
        w.managed.send(Message.Result("c1", true, "ok")); w.pump()
        assertEquals(Message.Result("c1", true, "ok"), ctrlMsgs.last())
    }

    @Test fun bigMessageAtMtu23IsChunked() {
        val (w, _, ctrlMsgs) = build(23)
        val apps = Message.Apps((1..40).map { AppInfo("com.pkg.number$it", "Application numéro $it") })
        w.managed.send(apps)
        assertTrue(w.toController.size > 100)   // many 5-char chunks
        w.pump()
        assertEquals(apps, ctrlMsgs.last())
    }

    @Test fun wrongSecretNeverAuthenticates() {
        val (w, _, _) = build(517, managedSecret = ByteArray(32) { 6 })
        assertFalse(w.managed.authenticated); assertFalse(w.controller.authenticated)
    }
}
```

- [ ] **Step 12: Run → fail**, implement `Endpoints.kt`:

```kotlin
package edu.fnosari.momedm.protocol

/** Sends one BLE-sized frame string over whatever transport the host provides. */
fun interface FrameSink { fun send(frame: String) }

/** Shared chunking/reassembly for both endpoints. */
internal class FrameLayer(private val sink: FrameSink, private val clock: () -> Long) {
    private val reassembler = Reassembler()
    private var nextMsgId = 0
    var mtu: Int = 23
    fun sendEnvelope(e: Envelope) {
        val payload = MessageCodec.encodeEnvelope(e)
        nextMsgId = (nextMsgId + 1) and 0xffff
        for (f in Framer.split(nextMsgId, payload, Framer.maxChunk(mtu))) sink.send(f)
    }
    /** Returns a full envelope when [frame] completes one. */
    fun receive(frame: String): Envelope? = reassembler.feed(frame, clock())?.let { MessageCodec.decodeEnvelope(it) }
}

/** Managed-device protocol endpoint: drives the handshake then delivers [Message.Cmd]s. Transport-agnostic. */
class ManagedEndpoint(
    private val secret: ByteArray,
    private val deviceId: String,
    private val model: String,
    sink: FrameSink,
    private val listener: Listener,
    clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onAuthenticated()
        fun onCommand(cmd: Message.Cmd)
        fun onProtocolError(reason: String)
    }
    private val frames = FrameLayer(sink, clock)
    private var handshake: ManagedHandshake? = null
    private var channel: SecureChannel? = null
    val authenticated: Boolean get() = channel != null

    /** Call once the link is up and MTU known: resets state and sends HELLO. */
    fun onConnected(mtu: Int) {
        reset(); frames.mtu = mtu
        handshake = ManagedHandshake(secret, deviceId, model, mtu).also { frames.sendEnvelope(Envelope.plain(it.hello())) }
    }
    fun reset() { handshake = null; channel = null }

    fun onFrame(frame: String) {
        val env = try { frames.receive(frame) } catch (e: Exception) { listener.onProtocolError("bad frame: ${e.message}"); return } ?: return
        val ch = channel
        if (ch == null) {
            val hs = handshake ?: run { listener.onProtocolError("message before HELLO"); return }
            val m = try { MessageCodec.decodeMessage(env.body) } catch (e: Exception) { listener.onProtocolError("bad handshake body"); return }
            val c = m as? Message.Challenge ?: run { listener.onProtocolError("expected CHALLENGE, got ${m::class.simpleName}"); return }
            val auth = hs.onChallenge(c) ?: run { listener.onProtocolError("controller proof invalid"); return }
            channel = SecureChannel(hs.sessionKey)
            frames.sendEnvelope(Envelope.plain(auth))
            // Controller confirms with a sealed AUTH_OK; until then we are optimistic (seq starts at 1 both ways).
            return
        }
        val m = try { ch.open(env) } catch (e: ProtocolException) { listener.onProtocolError(e.message ?: "protocol error"); channel = null; return }
        when (m) {
            is Message.AuthOk -> listener.onAuthenticated()
            is Message.Cmd -> listener.onCommand(m)
            else -> listener.onProtocolError("unexpected ${m::class.simpleName}")
        }
    }

    /** Sends a sealed message; only valid after authentication. */
    fun send(m: Message) {
        val ch = channel ?: throw IllegalStateException("not authenticated")
        frames.sendEnvelope(ch.seal(m))
    }
}

/** Controller-side endpoint for ONE connected managed device. */
class ControllerEndpoint(
    private val secret: ByteArray,
    sink: FrameSink,
    private val listener: Listener,
    clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onAuthenticated(hello: Message.Hello)
        fun onMessage(m: Message)
        fun onProtocolError(reason: String)
    }
    private val frames = FrameLayer(sink, clock)
    private var handshake: ControllerHandshake? = null
    private var channel: SecureChannel? = null
    val authenticated: Boolean get() = channel != null
    val deviceId: String? get() = handshake?.hello?.deviceId
    val mtu: Int get() = frames.mtu

    fun reset() { handshake = null; channel = null; frames.mtu = 23 }

    fun onFrame(frame: String) {
        val env = try { frames.receive(frame) } catch (e: Exception) { listener.onProtocolError("bad frame: ${e.message}"); return } ?: return
        val ch = channel
        if (ch == null) {
            val m = try { MessageCodec.decodeMessage(env.body) } catch (e: Exception) { listener.onProtocolError("bad handshake body"); return }
            when (m) {
                is Message.Hello -> {
                    frames.mtu = m.mtu
                    handshake = ControllerHandshake(secret).also { frames.sendEnvelope(Envelope.plain(it.onHello(m))) }
                }
                is Message.Auth -> {
                    val hs = handshake ?: run { listener.onProtocolError("AUTH before HELLO"); return }
                    if (!hs.onAuth(m)) { listener.onProtocolError("managed proof invalid"); return }
                    val c = SecureChannel(hs.sessionKey); channel = c
                    frames.sendEnvelope(c.seal(Message.AuthOk))
                    listener.onAuthenticated(hs.hello!!)
                }
                else -> listener.onProtocolError("unexpected ${m::class.simpleName} before auth")
            }
            return
        }
        val m = try { ch.open(env) } catch (e: ProtocolException) { listener.onProtocolError(e.message ?: "protocol error"); channel = null; return }
        listener.onMessage(m)
    }

    fun send(m: Message) {
        val ch = channel ?: throw IllegalStateException("not authenticated")
        frames.sendEnvelope(ch.seal(m))
    }
}
```
Run → pass (all protocol tests).

- [ ] **Step 13: `ProvisioningExtras.kt` + `link/MdmGatt.kt`**

```kotlin
package edu.fnosari.momedm.protocol

/** Keys inside `PROVISIONING_ADMIN_EXTRAS_BUNDLE` (QR) consumed by the managed device. */
object ProvisioningExtras {
    const val KEY_CONTROLLER_ID = "controller_id"
    /** Standard base64 of 32 random bytes. */
    const val KEY_SECRET = "secret"
}
```
```kotlin
package edu.fnosari.momedm.link

import android.bluetooth.BluetoothGattService
import edu.fnosari.momedm.connectivity.ble.characteristics.BLECharacteristic
import edu.fnosari.momedm.connectivity.ble.services.BLEService
import java.util.UUID

/** GATT layout shared by both roles. UUIDs are fixed forever; do not regenerate. */
object MdmGatt {
    val SERVICE_UUID: UUID = UUID.fromString("6d6f6d65-646d-4000-8000-000000000001")
    val CMD_UUID: UUID = UUID.fromString("6d6f6d65-646d-4000-8000-000000000002")
    val RSP_UUID: UUID = UUID.fromString("6d6f6d65-646d-4000-8000-000000000003")
}

/** Controller → managed frames (server notifies). */
class CmdCharacteristic : BLECharacteristic(MdmGatt.CMD_UUID, "mdm-cmd", "", Permission.NOTIFY)

/** Managed → controller frames (client writes). */
class RspCharacteristic : BLECharacteristic(MdmGatt.RSP_UUID, "mdm-rsp", "", Permission.WRITE)

class MdmService : BLEService(MdmGatt.SERVICE_UUID, "mdm", BluetoothGattService.SERVICE_TYPE_PRIMARY) {
    val cmd = CmdCharacteristic()
    val rsp = RspCharacteristic()
    init { addCharacteristic(cmd); addCharacteristic(rsp) }
}
```

- [ ] **Step 14: Full build + tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all protocol tests pass.

- [ ] **Step 15: Commit**

```bash
git add -A
git commit -m "feat(protocol): framing, HMAC handshake, secure channel, endpoints with loopback tests; GATT layout"
```

---
### Task 6: Controller identity + QR payload builder (pure Kotlin, TDD)

**Files:**
- Create: `SRC/controller/provisioning/ControllerIdentity.kt`, `SRC/controller/provisioning/QrPayloadBuilder.kt`
- Test: `TEST/controller/provisioning/QrPayloadBuilderTest.kt`, `TEST/controller/provisioning/ControllerIdentityTest.kt`

**Interfaces:**
- Produces: `data class ControllerIdentity(controllerId: String, secretBase64: String) { secretBytes; companion generate() }`; `data class ProvisioningParams(apkUrl, signatureChecksum, wifiSsid: String?, wifiPassword: String?, controllerId, secretBase64)`; `object QrPayloadBuilder { ADMIN_COMPONENT; APK_FILE_NAME = "momedm.apk"; HTTP_PORT = 8080; fun build(p: ProvisioningParams): String; fun apkUrl(ip: String): String }`.

- [ ] **Step 1: Tests**

`TEST/controller/provisioning/ControllerIdentityTest.kt`:
```kotlin
package edu.fnosari.momedm.controller.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ControllerIdentityTest {
    @Test fun generateIsRandomAnd32Bytes() {
        val a = ControllerIdentity.generate(); val b = ControllerIdentity.generate()
        assertEquals(32, a.secretBytes.size)
        assertNotEquals(a.secretBase64, b.secretBase64); assertNotEquals(a.controllerId, b.controllerId)
        assertEquals(36, a.controllerId.length) // UUID
    }
}
```
`TEST/controller/provisioning/QrPayloadBuilderTest.kt`:
```kotlin
package edu.fnosari.momedm.controller.provisioning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QrPayloadBuilderTest {
    private val base = ProvisioningParams("http://192.168.1.5:8080/momedm.apk", "abc_-", "MyNet", "pw123", "cid", "c2VjcmV0")

    @Test fun containsAllProvisioningKeys() {
        val o = Json.parseToJsonElement(QrPayloadBuilder.build(base)).jsonObject
        assertEquals("edu.fnosari.momedm/.managed.AdminReceiver", o["android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"]!!.jsonPrimitive.content)
        assertEquals(base.apkUrl, o["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"]!!.jsonPrimitive.content)
        assertEquals("abc_-", o["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"]!!.jsonPrimitive.content)
        assertEquals("MyNet", o["android.app.extra.PROVISIONING_WIFI_SSID"]!!.jsonPrimitive.content)
        assertEquals("pw123", o["android.app.extra.PROVISIONING_WIFI_PASSWORD"]!!.jsonPrimitive.content)
        assertEquals("WPA", o["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"]!!.jsonPrimitive.content)
        assertEquals(true, o["android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, o["android.app.extra.PROVISIONING_SKIP_ENCRYPTION"]!!.jsonPrimitive.content.toBoolean())
        val extras = o["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!.jsonObject
        assertEquals("cid", extras["controller_id"]!!.jsonPrimitive.content)
        assertEquals("c2VjcmV0", extras["secret"]!!.jsonPrimitive.content)
    }
    @Test fun omitsWifiWhenNull() {
        val o = Json.parseToJsonElement(QrPayloadBuilder.build(base.copy(wifiSsid = null, wifiPassword = null))).jsonObject
        assertFalse(o.containsKey("android.app.extra.PROVISIONING_WIFI_SSID"))
        assertFalse(o.containsKey("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"))
    }
    @Test fun apkUrl() { assertEquals("http://10.0.0.2:8080/momedm.apk", QrPayloadBuilder.apkUrl("10.0.0.2")) }
}
```

- [ ] **Step 2: Run → fail** (`./gradlew :app:testDebugUnitTest --tests "edu.fnosari.momedm.controller.*"`).

- [ ] **Step 3: Implement**

`ControllerIdentity.kt`:
```kotlin
package edu.fnosari.momedm.controller.provisioning

import edu.fnosari.momedm.protocol.Base64Std
import edu.fnosari.momedm.protocol.Crypto
import java.util.UUID

/** Controller identity shared with every device it provisions (via QR admin extras). */
data class ControllerIdentity(val controllerId: String, val secretBase64: String) {
    val secretBytes: ByteArray get() = Base64Std.decode(secretBase64)
    companion object {
        fun generate(): ControllerIdentity = ControllerIdentity(UUID.randomUUID().toString(), Base64Std.encode(Crypto.randomBytes(32)))
    }
}
```
`QrPayloadBuilder.kt`:
```kotlin
package edu.fnosari.momedm.controller.provisioning

import edu.fnosari.momedm.protocol.ProvisioningExtras
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ProvisioningParams(
    val apkUrl: String,
    val signatureChecksum: String,
    val wifiSsid: String?,
    val wifiPassword: String?,
    val controllerId: String,
    val secretBase64: String,
)

/** Builds the Android Enterprise QR provisioning JSON (scanned from the Setup Wizard). */
object QrPayloadBuilder {
    const val ADMIN_COMPONENT = "edu.fnosari.momedm/.managed.AdminReceiver"
    const val APK_FILE_NAME = "momedm.apk"
    const val HTTP_PORT = 8080
    private const val P = "android.app.extra."

    fun apkUrl(ip: String): String = "http://$ip:$HTTP_PORT/$APK_FILE_NAME"

    fun build(p: ProvisioningParams): String {
        val obj: JsonObject = buildJsonObject {
            put(P + "PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME", ADMIN_COMPONENT)
            put(P + "PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION", p.apkUrl)
            put(P + "PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM", p.signatureChecksum)
            if (!p.wifiSsid.isNullOrBlank()) {
                put(P + "PROVISIONING_WIFI_SSID", p.wifiSsid)
                put(P + "PROVISIONING_WIFI_PASSWORD", p.wifiPassword ?: "")
                put(P + "PROVISIONING_WIFI_SECURITY_TYPE", "WPA")
            }
            put(P + "PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", true)
            put(P + "PROVISIONING_SKIP_ENCRYPTION", true)
            put(P + "PROVISIONING_ADMIN_EXTRAS_BUNDLE", buildJsonObject {
                put(ProvisioningExtras.KEY_CONTROLLER_ID, p.controllerId)
                put(ProvisioningExtras.KEY_SECRET, p.secretBase64)
            })
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }
}
```

- [ ] **Step 4: Run → pass.**

- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(controller): identity generation and QR provisioning payload builder"`

---

### Task 7: Typed prefs for both roles + device registry

**Files:**
- Create: `SRC/persistence/ManagedPrefs.kt`, `SRC/persistence/ControllerPrefs.kt`, `SRC/persistence/DeviceRegistry.kt`
- Test: `TEST/persistence/DeviceRegistryCodecTest.kt`, `TEST/persistence/ManagedPrefsTest.kt` (uses `DefaultPreferencesProvider`-like in-memory fake)

**Interfaces:**
- Produces:
  - `class ManagedPrefs(p: PreferencesProvider)`: `controllerId: Flow<String>`, `secretBase64: Flow<String>`, `deviceId: Flow<String>`, `kioskPkg: Flow<String>`, `kioskOn: Flow<Boolean>`; `suspend saveProvisioning(controllerId, secretBase64)`, `suspend ensureDeviceId(): String`, `suspend setKiosk(on: Boolean, pkg: String?)`, `suspend isProvisioned(): Boolean`, `suspend secretBytes(): ByteArray?`.
  - `class ControllerPrefs(p)`: `controllerId`, `secretBase64`, `wifiMode: Flow<String>` (`"HOTSPOT"|"MANUAL"|"CUSTOM_URL"`), `manualSsid`, `manualPassword`, `customUrl`, `registryJson`, `advertiseOnLaunch: Flow<Boolean>`; `suspend ensureIdentity(): ControllerIdentity`, `suspend regenerateSecret(): ControllerIdentity`, `suspend setWifi(mode, ssid, pass, url)`, `suspend saveRegistry(json)`, `suspend identity(): ControllerIdentity?`.
  - `@Serializable data class DeviceRecord(deviceId, model, lastSeen: Long, lastStatus: Message.Status? = null)`; `object DeviceRegistryCodec { encode(List<DeviceRecord>): String; decode(String): List<DeviceRecord> }`; `class DeviceRegistry(prefs: ControllerPrefs, scope: CoroutineScope)`: `devices: StateFlow<List<DeviceRecord>>`, `suspend upsertSeen(deviceId, model, nowMs)`, `suspend updateStatus(deviceId, status, nowMs)`, `fun get(deviceId): DeviceRecord?`.
  - `class InMemoryPreferencesProvider : PreferencesProvider` (in **test** sources) for unit tests.

- [ ] **Step 1: Test fake + tests**

`TEST/persistence/InMemoryPreferencesProvider.kt`:
```kotlin
package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.persistence.preferences.PreferencesProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryPreferencesProvider : PreferencesProvider {
    private val store = MutableStateFlow<Map<String, Any>>(emptyMap())
    @Suppress("UNCHECKED_CAST") private fun <T> read(key: String, default: T): Flow<T> = store.map { (it[key] as? T) ?: default }
    private fun put(key: String, v: Any) { store.value = store.value + (key to v) }
    override fun readString(key: String, default: String) = read(key, default)
    override fun readInt(key: String, default: Int) = read(key, default)
    override fun readBoolean(key: String, default: Boolean) = read(key, default)
    override fun readDouble(key: String, default: Double) = read(key, default)
    override suspend fun write(key: String, value: String) = put(key, value)
    override suspend fun write(key: String, value: Int) = put(key, value)
    override suspend fun write(key: String, value: Boolean) = put(key, value)
    override suspend fun write(key: String, value: Double) = put(key, value)
}
```
`TEST/persistence/ManagedPrefsTest.kt`:
```kotlin
package edu.fnosari.momedm.persistence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedPrefsTest {
    @Test fun provisioningAndDeviceId() = runTest {
        val p = ManagedPrefs(InMemoryPreferencesProvider())
        assertFalse(p.isProvisioned())
        p.saveProvisioning("cid", "AAAA")
        assertTrue(p.isProvisioned()); assertEquals("cid", p.controllerId.first())
        val id1 = p.ensureDeviceId(); val id2 = p.ensureDeviceId()
        assertEquals(id1, id2); assertEquals(36, id1.length)
        p.setKiosk(true, "com.k"); assertTrue(p.kioskOn.first()); assertEquals("com.k", p.kioskPkg.first())
        p.setKiosk(false, null); assertFalse(p.kioskOn.first()); assertEquals("", p.kioskPkg.first())
    }
}
```
`TEST/persistence/DeviceRegistryCodecTest.kt`:
```kotlin
package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceRegistryCodecTest {
    @Test fun roundTrip() {
        val list = listOf(DeviceRecord("d1", "Pixel", 10L, Message.Status(false, null, true, 50, "x")), DeviceRecord("d2", "Nokia", 20L))
        assertEquals(list, DeviceRegistryCodec.decode(DeviceRegistryCodec.encode(list)))
        assertEquals(emptyList<DeviceRecord>(), DeviceRegistryCodec.decode("")); assertEquals(emptyList<DeviceRecord>(), DeviceRegistryCodec.decode("garbage"))
    }
    @Test fun upsertKeepsStatusAndUpdatesSeen() = runTest {
        val prefs = ControllerPrefs(InMemoryPreferencesProvider())
        val reg = DeviceRegistry(prefs, this)
        reg.upsertSeen("d1", "Pixel", 1L)
        reg.updateStatus("d1", Message.Status(true, "k", false, 9, "k"), 2L)
        reg.upsertSeen("d1", "Pixel", 3L)
        val r = reg.get("d1")!!
        assertEquals(3L, r.lastSeen); assertEquals("k", r.lastStatus?.kioskPkg)
        assertNull(reg.get("nope"))
        assertEquals(DeviceRegistryCodec.encode(reg.devices.value), DeviceRegistryCodec.decode(DeviceRegistryCodec.encode(reg.devices.value)).let { DeviceRegistryCodec.encode(it) })
    }
}
```

- [ ] **Step 2: Run → fail.**

- [ ] **Step 3: Implement**

`ManagedPrefs.kt`:
```kotlin
package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.persistence.preferences.PreferencesProvider
import edu.fnosari.momedm.protocol.Base64Std
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/** Managed-role settings: controller identity from the QR, our device id, kiosk state. */
class ManagedPrefs(private val p: PreferencesProvider) {
    companion object {
        const val KEY_CONTROLLER_ID = "managed_controller_id"
        const val KEY_SECRET = "managed_secret"
        const val KEY_DEVICE_ID = "managed_device_id"
        const val KEY_KIOSK_PKG = "managed_kiosk_pkg"
        const val KEY_KIOSK_ON = "managed_kiosk_on"
    }
    val controllerId: Flow<String> = p.readString(KEY_CONTROLLER_ID, "")
    val secretBase64: Flow<String> = p.readString(KEY_SECRET, "")
    val deviceId: Flow<String> = p.readString(KEY_DEVICE_ID, "")
    val kioskPkg: Flow<String> = p.readString(KEY_KIOSK_PKG, "")
    val kioskOn: Flow<Boolean> = p.readBoolean(KEY_KIOSK_ON, false)

    suspend fun saveProvisioning(controllerId: String, secretBase64: String) { p.write(KEY_CONTROLLER_ID, controllerId); p.write(KEY_SECRET, secretBase64) }
    suspend fun isProvisioned(): Boolean = secretBase64.first().isNotEmpty()
    suspend fun secretBytes(): ByteArray? = secretBase64.first().takeIf { it.isNotEmpty() }?.let { Base64Std.decode(it) }
    suspend fun ensureDeviceId(): String {
        val existing = deviceId.first()
        if (existing.isNotEmpty()) return existing
        return UUID.randomUUID().toString().also { p.write(KEY_DEVICE_ID, it) }
    }
    suspend fun setKiosk(on: Boolean, pkg: String?) { p.write(KEY_KIOSK_ON, on); p.write(KEY_KIOSK_PKG, pkg ?: "") }
}
```
`ControllerPrefs.kt`:
```kotlin
package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.controller.provisioning.ControllerIdentity
import edu.fnosari.momedm.persistence.preferences.PreferencesProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Controller-role settings: identity/secret, provisioning Wi-Fi choices, device registry blob. */
class ControllerPrefs(private val p: PreferencesProvider) {
    companion object {
        const val KEY_CONTROLLER_ID = "ctrl_controller_id"
        const val KEY_SECRET = "ctrl_secret"
        const val KEY_WIFI_MODE = "ctrl_wifi_mode"
        const val KEY_MANUAL_SSID = "ctrl_manual_ssid"
        const val KEY_MANUAL_PASS = "ctrl_manual_pass"
        const val KEY_CUSTOM_URL = "ctrl_custom_url"
        const val KEY_REGISTRY = "ctrl_registry_json"
        const val KEY_ADVERTISE_ON_LAUNCH = "ctrl_advertise_on_launch"
        const val MODE_HOTSPOT = "HOTSPOT"; const val MODE_MANUAL = "MANUAL"; const val MODE_CUSTOM_URL = "CUSTOM_URL"
    }
    val controllerId: Flow<String> = p.readString(KEY_CONTROLLER_ID, "")
    val secretBase64: Flow<String> = p.readString(KEY_SECRET, "")
    val wifiMode: Flow<String> = p.readString(KEY_WIFI_MODE, MODE_HOTSPOT)
    val manualSsid: Flow<String> = p.readString(KEY_MANUAL_SSID, "")
    val manualPassword: Flow<String> = p.readString(KEY_MANUAL_PASS, "")
    val customUrl: Flow<String> = p.readString(KEY_CUSTOM_URL, "")
    val registryJson: Flow<String> = p.readString(KEY_REGISTRY, "")
    val advertiseOnLaunch: Flow<Boolean> = p.readBoolean(KEY_ADVERTISE_ON_LAUNCH, true)

    suspend fun identity(): ControllerIdentity? {
        val id = controllerId.first(); val s = secretBase64.first()
        return if (id.isEmpty() || s.isEmpty()) null else ControllerIdentity(id, s)
    }
    suspend fun ensureIdentity(): ControllerIdentity = identity() ?: regenerateSecret()
    suspend fun regenerateSecret(): ControllerIdentity {
        val existingId = controllerId.first().ifEmpty { ControllerIdentity.generate().controllerId }
        val fresh = ControllerIdentity(existingId, ControllerIdentity.generate().secretBase64)
        p.write(KEY_CONTROLLER_ID, fresh.controllerId); p.write(KEY_SECRET, fresh.secretBase64)
        return fresh
    }
    suspend fun setWifi(mode: String, ssid: String, pass: String, url: String) {
        p.write(KEY_WIFI_MODE, mode); p.write(KEY_MANUAL_SSID, ssid); p.write(KEY_MANUAL_PASS, pass); p.write(KEY_CUSTOM_URL, url)
    }
    suspend fun saveRegistry(json: String) = p.write(KEY_REGISTRY, json)
    suspend fun setAdvertiseOnLaunch(v: Boolean) = p.write(KEY_ADVERTISE_ON_LAUNCH, v)
}
```
`DeviceRegistry.kt`:
```kotlin
package edu.fnosari.momedm.persistence

import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class DeviceRecord(val deviceId: String, val model: String, val lastSeen: Long, val lastStatus: Message.Status? = null)

object DeviceRegistryCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(DeviceRecord.serializer())
    fun encode(list: List<DeviceRecord>): String = json.encodeToString(ser, list)
    fun decode(s: String): List<DeviceRecord> = if (s.isBlank()) emptyList() else try { json.decodeFromString(ser, s) } catch (e: Exception) { emptyList() }
}

/** Known managed devices, persisted as JSON in [ControllerPrefs]. Loaded once on construction. */
class DeviceRegistry(private val prefs: ControllerPrefs, scope: CoroutineScope) {
    private val mutex = Mutex()
    private val _devices = MutableStateFlow<List<DeviceRecord>>(emptyList())
    val devices: StateFlow<List<DeviceRecord>> = _devices.asStateFlow()
    private val loaded = scope.launch { _devices.value = DeviceRegistryCodec.decode(prefs.registryJson.first()) }

    fun get(deviceId: String): DeviceRecord? = _devices.value.firstOrNull { it.deviceId == deviceId }

    suspend fun upsertSeen(deviceId: String, model: String, nowMs: Long) = mutate { list ->
        val old = list.firstOrNull { it.deviceId == deviceId }
        list.filter { it.deviceId != deviceId } + DeviceRecord(deviceId, model, nowMs, old?.lastStatus)
    }
    suspend fun updateStatus(deviceId: String, status: Message.Status, nowMs: Long) = mutate { list ->
        val old = list.firstOrNull { it.deviceId == deviceId } ?: DeviceRecord(deviceId, "?", nowMs)
        list.filter { it.deviceId != deviceId } + old.copy(lastSeen = nowMs, lastStatus = status)
    }
    private suspend fun mutate(f: (List<DeviceRecord>) -> List<DeviceRecord>) {
        loaded.join()
        mutex.withLock {
            _devices.value = f(_devices.value).sortedByDescending { it.lastSeen }
            prefs.saveRegistry(DeviceRegistryCodec.encode(_devices.value))
        }
    }
}
```

- [ ] **Step 4: Run → pass** (`./gradlew :app:testDebugUnitTest`).

- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(persistence): managed/controller typed prefs and device registry"`

---
### Task 8: Managed core — AdminReceiver, BootReceiver, PolicyManager, StatusCollector, CommandExecutor

**Files:**
- Create: `SRC/managed/AdminReceiver.kt`, `SRC/managed/ManagedSetup.kt`, `SRC/managed/BootReceiver.kt`, `SRC/managed/PolicyManager.kt`, `SRC/managed/StatusCollector.kt`, `SRC/managed/CommandExecutor.kt`, `SRC/managed/ManagedLinkState.kt`, `app/src/main/res/xml/device_admin.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `TEST/managed/CommandExecutorTest.kt`

**Interfaces:**
- Produces:
  - `class AdminReceiver : DeviceAdminReceiver` (component `edu.fnosari.momedm/.managed.AdminReceiver`).
  - `object ManagedSetup { fun persistExtras(context, bundle: PersistableBundle?): Boolean; fun prefs(context): ManagedPrefs }`.
  - `interface PolicyActions { suspend kioskOn(pkg): Result<Unit>; suspend kioskOff(): Result<Unit>; fun openPlay(pkg): Result<Unit>; fun openAddAccount(): Result<Unit> }`; `class PolicyManager(context, prefs) : PolicyActions` + `isDeviceOwner`, `setAsDefaultHome()`, `suspend restoreKiosk()`, `PLAY_PKG`, `GMS_PKG`.
  - `interface StatusSource { suspend fun collect(): Message.Status; fun launchableApps(): List<AppInfo> }`; `class StatusCollector(context, prefs) : StatusSource` + `hasUsageAccess()`, `hasGoogleAccount()`, `foregroundApp()`.
  - `class CommandExecutor(policy: PolicyActions, status: StatusSource) { suspend fun execute(cmd: Message.Cmd): List<Message> }` — always returns a `Message.Result` first, followed by `Apps`/`Status` when relevant.
  - `object ManagedLinkState { enum LinkState { IDLE, SCANNING, CONNECTED, AUTHENTICATED }; state: MutableStateFlow<LinkState>; lastStatus: MutableStateFlow<Message.Status?>; lastError: MutableStateFlow<String?> }`.

- [ ] **Step 1: CommandExecutor test (fakes for policy + status)** — `TEST/managed/CommandExecutorTest.kt`

```kotlin
package edu.fnosari.momedm.managed

import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExecutorTest {
    private class FakePolicy : PolicyActions {
        var kiosk: String? = null; var played: String? = null; var accountOpened = false
        override suspend fun kioskOn(pkg: String) = if (pkg == "bad") Result.failure(IllegalArgumentException("not installed")) else { kiosk = pkg; Result.success(Unit) }
        override suspend fun kioskOff() = run { kiosk = null; Result.success(Unit) }
        override fun openPlay(pkg: String) = run { played = pkg; Result.success(Unit) }
        override fun openAddAccount() = run { accountOpened = true; Result.success(Unit) }
    }
    private class FakeStatus : StatusSource {
        override suspend fun collect() = Message.Status(false, null, true, 42, "x")
        override fun launchableApps() = listOf(AppInfo("a", "A"))
    }

    @Test fun kioskOnReturnsResultThenStatus() = runTest {
        val p = FakePolicy(); val out = CommandExecutor(p, FakeStatus()).execute(Message.Cmd("1", CmdType.KIOSK_ON, "com.k"))
        assertEquals(Message.Result("1", true, "kiosk on com.k"), out[0]); assertTrue(out[1] is Message.Status); assertEquals("com.k", p.kiosk)
    }
    @Test fun kioskOnFailure() = runTest {
        val out = CommandExecutor(FakePolicy(), FakeStatus()).execute(Message.Cmd("2", CmdType.KIOSK_ON, "bad"))
        assertFalse((out[0] as Message.Result).ok); assertEquals(1, out.size)
    }
    @Test fun kioskOnWithoutPkgFails() = runTest {
        val out = CommandExecutor(FakePolicy(), FakeStatus()).execute(Message.Cmd("3", CmdType.KIOSK_ON, null))
        assertFalse((out[0] as Message.Result).ok)
    }
    @Test fun listAppsAndStatus() = runTest {
        val ex = CommandExecutor(FakePolicy(), FakeStatus())
        val apps = ex.execute(Message.Cmd("4", CmdType.LIST_APPS)); assertEquals(Message.Apps(listOf(AppInfo("a", "A"))), apps[1])
        val st = ex.execute(Message.Cmd("5", CmdType.GET_STATUS)); assertEquals(42, (st[1] as Message.Status).battery)
    }
    @Test fun installAndAccount() = runTest {
        val p = FakePolicy(); val ex = CommandExecutor(p, FakeStatus())
        assertTrue((ex.execute(Message.Cmd("6", CmdType.INSTALL, "com.p"))[0] as Message.Result).ok); assertEquals("com.p", p.played)
        assertTrue((ex.execute(Message.Cmd("7", CmdType.ADD_ACCOUNT))[0] as Message.Result).ok); assertTrue(p.accountOpened)
    }
}
```

- [ ] **Step 2: Run → fail**, then implement.

`ManagedLinkState.kt`:
```kotlin
package edu.fnosari.momedm.managed

import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide observable link state, written by [ManagedLinkService], read by the managed UI. */
object ManagedLinkState {
    enum class LinkState { IDLE, SCANNING, CONNECTED, AUTHENTICATED }
    val state = MutableStateFlow(LinkState.IDLE)
    val lastStatus = MutableStateFlow<Message.Status?>(null)
    val lastError = MutableStateFlow<String?>(null)
}
```
`CommandExecutor.kt`:
```kotlin
package edu.fnosari.momedm.managed

import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.Message

interface PolicyActions {
    suspend fun kioskOn(pkg: String): Result<Unit>
    suspend fun kioskOff(): Result<Unit>
    fun openPlay(pkg: String): Result<Unit>
    fun openAddAccount(): Result<Unit>
}

interface StatusSource {
    suspend fun collect(): Message.Status
    fun launchableApps(): List<AppInfo>
}

/** Maps a [Message.Cmd] to policy actions; returns the messages to send back (RESULT first). Pure Kotlin. */
class CommandExecutor(private val policy: PolicyActions, private val status: StatusSource) {
    suspend fun execute(cmd: Message.Cmd): List<Message> {
        fun res(r: Result<Unit>, okMsg: String) = Message.Result(cmd.id, r.isSuccess, if (r.isSuccess) okMsg else (r.exceptionOrNull()?.message ?: "failed"))
        return when (cmd.type) {
            CmdType.KIOSK_ON -> {
                val pkg = cmd.pkg ?: return listOf(Message.Result(cmd.id, false, "missing pkg"))
                val r = policy.kioskOn(pkg)
                if (r.isSuccess) listOf(res(r, "kiosk on $pkg"), status.collect()) else listOf(res(r, ""))
            }
            CmdType.KIOSK_OFF -> { val r = policy.kioskOff(); if (r.isSuccess) listOf(res(r, "kiosk off"), status.collect()) else listOf(res(r, "")) }
            CmdType.INSTALL -> { val pkg = cmd.pkg ?: return listOf(Message.Result(cmd.id, false, "missing pkg")); listOf(res(policy.openPlay(pkg), "play opened for $pkg")) }
            CmdType.ADD_ACCOUNT -> listOf(res(policy.openAddAccount(), "account flow opened"))
            CmdType.LIST_APPS -> listOf(Message.Result(cmd.id, true, "apps"), Message.Apps(status.launchableApps()))
            CmdType.GET_STATUS -> listOf(Message.Result(cmd.id, true, "status"), status.collect())
        }
    }
}
```
Run → CommandExecutorTest passes.

- [ ] **Step 3: `device_admin.xml`, `AdminReceiver`, `ManagedSetup`, `BootReceiver`**

`app/src/main/res/xml/device_admin.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
    </uses-policies>
</device-admin>
```
`AdminReceiver.kt`:
```kotlin
package edu.fnosari.momedm.managed

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log

/** Device-owner admin component. Also catches provisioning extras on older flows. */
class AdminReceiver : DeviceAdminReceiver() {
    companion object { private const val LOG_TAG = "AdminReceiver" }

    override fun onEnabled(context: Context, intent: Intent) { Log.d(LOG_TAG, "Admin enabled") }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.d(LOG_TAG, "Provisioning complete")
        val bundle = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        ManagedSetup.persistExtras(context, bundle)
    }
}
```
`ManagedSetup.kt`:
```kotlin
package edu.fnosari.momedm.managed

import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.protocol.ProvisioningExtras
import kotlinx.coroutines.runBlocking

/** One-shot helpers used by provisioning components (receiver + activities). */
object ManagedSetup {
    private const val LOG_TAG = "ManagedSetup"

    fun prefs(context: Context): ManagedPrefs = ManagedPrefs(DataStorePreferencesProvider(context))

    /** Stores controller id + secret from the QR admin-extras bundle. Returns true when both were present. */
    fun persistExtras(context: Context, bundle: PersistableBundle?): Boolean {
        val controllerId = bundle?.getString(ProvisioningExtras.KEY_CONTROLLER_ID)
        val secret = bundle?.getString(ProvisioningExtras.KEY_SECRET)
        if (controllerId.isNullOrEmpty() || secret.isNullOrEmpty()) { Log.w(LOG_TAG, "Admin extras missing controller_id/secret"); return false }
        runBlocking { prefs(context).saveProvisioning(controllerId, secret) }
        Log.d(LOG_TAG, "Provisioning extras persisted for controller $controllerId")
        return true
    }
}
```
`BootReceiver.kt`:
```kotlin
package edu.fnosari.momedm.managed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** On boot of a managed device: restart the BLE link service (which also restores kiosk). */
class BootReceiver : BroadcastReceiver() {
    companion object { private const val LOG_TAG = "BootReceiver" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PolicyManager(context, ManagedSetup.prefs(context)).isDeviceOwner) { Log.d(LOG_TAG, "Not device owner; ignoring boot"); return }
        Log.d(LOG_TAG, "Boot completed; starting link service")
        ManagedLinkService.start(context, fromBoot = true)
    }
}
```

- [ ] **Step 4: `PolicyManager.kt`**

```kotlin
package edu.fnosari.momedm.managed

import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import android.util.Log
import edu.fnosari.momedm.activities.managed.ManagedHomeActivity
import edu.fnosari.momedm.persistence.ManagedPrefs
import kotlinx.coroutines.flow.first

/** Thin, logged wrapper around [DevicePolicyManager] for the managed role. */
class PolicyManager(private val context: Context, private val prefs: ManagedPrefs) : PolicyActions {
    companion object {
        private const val LOG_TAG = "PolicyManager"
        const val PLAY_PKG = "com.android.vending"
        const val GMS_PKG = "com.google.android.gms"
    }
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, AdminReceiver::class.java)
    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /** Makes [ManagedHomeActivity] the persistent HOME so the device boots into us. */
    fun setAsDefaultHome() {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
        dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, ManagedHomeActivity::class.java))
        Log.d(LOG_TAG, "Persistent HOME set")
    }

    override suspend fun kioskOn(pkg: String): Result<Unit> = runCatching {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalArgumentException("$pkg not installed or not launchable")
        dpm.setLockTaskPackages(admin, arrayOf(pkg, context.packageName, PLAY_PKG, GMS_PKG))
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
        prefs.setKiosk(true, pkg)
        Log.d(LOG_TAG, "Kiosk on: $pkg")
    }

    override suspend fun kioskOff(): Result<Unit> = runCatching {
        dpm.setLockTaskPackages(admin, emptyArray())   // removing the allowlist forces lock task to end
        val home = Intent(context, ManagedHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(home)
        prefs.setKiosk(false, null)
        Log.d(LOG_TAG, "Kiosk off")
    }

    /** Re-enters kiosk after reboot when it was on. */
    suspend fun restoreKiosk() {
        if (prefs.kioskOn.first()) { val pkg = prefs.kioskPkg.first(); if (pkg.isNotEmpty()) kioskOn(pkg).onFailure { Log.w(LOG_TAG, "Kiosk restore failed", it) } }
    }

    override fun openPlay(pkg: String): Result<Unit> = runCatching {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage(PLAY_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) == null) throw IllegalStateException("Play Store not available")
        val opts = ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle()
        context.startActivity(i, opts)
        Log.d(LOG_TAG, "Opened Play for $pkg")
    }

    override fun openAddAccount(): Result<Unit> = runCatching {
        val i = Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        Log.d(LOG_TAG, "Opened add-account")
    }
}
```

- [ ] **Step 5: `StatusCollector.kt`**

```kotlin
package edu.fnosari.momedm.managed

import android.accounts.AccountManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Process
import android.util.Log
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.flow.first

/** Gathers the fields of [Message.Status] and the launchable app list. */
class StatusCollector(private val context: Context, private val prefs: ManagedPrefs) : StatusSource {
    companion object { private const val LOG_TAG = "StatusCollector"; private const val FG_WINDOW_MS = 60_000L }

    override suspend fun collect(): Message.Status {
        val kioskOn = prefs.kioskOn.first(); val kioskPkg = prefs.kioskPkg.first().ifEmpty { null }
        val s = Message.Status(kiosk = kioskOn, kioskPkg = kioskPkg, account = hasGoogleAccount(), battery = batteryPercent(),
            currentApp = foregroundApp() ?: if (kioskOn) kioskPkg else null)
        Log.d(LOG_TAG, "Status: $s"); return s
    }

    fun batteryPercent(): Int = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun hasGoogleAccount(): Boolean = try { AccountManager.get(context).getAccountsByType("com.google").isNotEmpty() } catch (e: SecurityException) { Log.w(LOG_TAG, "No account visibility", e); false }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Last ACTIVITY_RESUMED package in the past minute, or null when usage access is missing. */
    fun foregroundApp(): String? {
        if (!hasUsageAccess()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis(); val events = usm.queryEvents(now - FG_WINDOW_MS, now)
        var last: String? = null; val e = UsageEvents.Event()
        while (events.hasNextEvent()) { events.getNextEvent(e); if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName }
        return last
    }

    override fun launchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }
}
```

- [ ] **Step 6: Manifest** — inside `<application>`:

```xml
        <receiver
            android:name=".managed.AdminReceiver"
            android:exported="true"
            android:permission="android.permission.BIND_DEVICE_ADMIN">
            <meta-data android:name="android.app.device_admin" android:resource="@xml/device_admin" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
                <action android:name="android.app.action.PROFILE_PROVISIONING_COMPLETE" />
            </intent-filter>
        </receiver>

        <receiver
            android:name=".managed.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
```
`PolicyManager` references `ManagedHomeActivity` (Task 11) and `BootReceiver` references `ManagedLinkService` (Task 9). To keep this task compiling, create minimal stubs now that later tasks replace:
- `SRC/activities/managed/ManagedHomeActivity.kt`: `package edu.fnosari.momedm.activities.managed; import androidx.activity.ComponentActivity; class ManagedHomeActivity : ComponentActivity()`
- `SRC/managed/ManagedLinkService.kt`: `package edu.fnosari.momedm.managed; import android.app.Service; import android.content.Context; import android.content.Intent; import android.os.IBinder; class ManagedLinkService : Service() { companion object { fun start(context: Context, fromBoot: Boolean = false) {} } override fun onBind(intent: Intent?): IBinder? = null }`

- [ ] **Step 7: Build + tests** — `./gradlew :app:assembleDebug :app:testDebugUnitTest` → SUCCESS.

- [ ] **Step 8: Commit** — `git add -A && git commit -m "feat(managed): admin receiver, policy manager, status collector, command executor"`

---

### Task 9: ManagedLinkService (foreground BLE client + endpoint + status push)

**Files:**
- Replace stub: `SRC/managed/ManagedLinkService.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `BLEClient` (Task 4), `MdmService`/`MdmGatt` (Task 5), `ManagedEndpoint` (Task 5), `ManagedPrefs`, `PolicyManager`, `StatusCollector`, `CommandExecutor`, `ManagedLinkState`.
- Produces: `ManagedLinkService.start(context, fromBoot)`, `ManagedLinkService.stop(context)`; constants `CHANNEL_ID = "managed_link"`, `NOTIFICATION_ID = 1`, `EXTRA_FROM_BOOT`; it publishes to `ManagedLinkState`.

- [ ] **Step 1: Strings** — add:
```xml
    <string name="managed_notif_channel">Controller link</string>
    <string name="managed_notif_title">Môme DM</string>
    <string name="managed_notif_scanning">Looking for controller…</string>
    <string name="managed_notif_connected">Connected to controller</string>
    <string name="managed_notif_authenticated">Managed by controller</string>
```

- [ ] **Step 2: Implement service**

```kotlin
package edu.fnosari.momedm.managed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import edu.fnosari.momedm.R
import edu.fnosari.momedm.connectivity.ble.BLEClient
import edu.fnosari.momedm.connectivity.ble.BLEException
import edu.fnosari.momedm.connectivity.ble.characteristics.BLECharacteristic
import edu.fnosari.momedm.connectivity.ble.services.BLEService
import edu.fnosari.momedm.link.MdmGatt
import edu.fnosari.momedm.link.MdmService
import edu.fnosari.momedm.managed.ManagedLinkState.LinkState
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.ManagedEndpoint
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service owning the BLE link to the controller: scan → connect → handshake → commands.
 * Reconnects with backoff (2 s → 30 s). Pushes STATUS on auth, on change, and every 5 minutes.
 */
class ManagedLinkService : Service() {
    companion object {
        private const val LOG_TAG = "ManagedLinkService"
        const val CHANNEL_ID = "managed_link"
        const val NOTIFICATION_ID = 1
        const val EXTRA_FROM_BOOT = "from_boot"
        private const val STATUS_PERIOD_MS = 5 * 60_000L
        private const val BACKOFF_MIN_MS = 2_000L
        private const val BACKOFF_MAX_MS = 30_000L

        fun start(context: Context, fromBoot: Boolean = false) {
            context.startForegroundService(Intent(context, ManagedLinkService::class.java).putExtra(EXTRA_FROM_BOOT, fromBoot))
        }
        fun stop(context: Context) { context.stopService(Intent(context, ManagedLinkService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: ManagedPrefs
    private lateinit var policy: PolicyManager
    private lateinit var status: StatusCollector
    private lateinit var executor: CommandExecutor
    private val gatt = MdmService()
    private var client: BLEClient? = null
    private var endpoint: ManagedEndpoint? = null
    private var mtu = 23
    private var backoffMs = BACKOFF_MIN_MS
    private var statusJob: Job? = null
    private var lastBattery = -1
    private var started = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pct = status.batteryPercent()
            if (lastBattery < 0 || abs(pct - lastBattery) >= 5) { lastBattery = pct; pushStatus() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = ManagedSetup.prefs(this)
        policy = PolicyManager(this, prefs)
        status = StatusCollector(this, prefs)
        executor = CommandExecutor(policy, status)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.managed_notif_scanning)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        if (!started) { started = true; scope.launch { if (fromBoot) policy.restoreKiosk(); startLink() } }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(batteryReceiver)
        statusJob?.cancel(); client?.disconnect(); scope.cancel()
        ManagedLinkState.state.value = LinkState.IDLE
        super.onDestroy()
    }

    private suspend fun startLink() {
        val secret = prefs.secretBytes()
        if (secret == null) { Log.w(LOG_TAG, "Not provisioned; no secret"); ManagedLinkState.lastError.value = "Not provisioned"; return }
        val deviceId = prefs.ensureDeviceId()
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        endpoint = ManagedEndpoint(secret, deviceId, model, { frame -> sendFrame(frame) }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {
                Log.d(LOG_TAG, "Authenticated")
                backoffMs = BACKOFF_MIN_MS
                setState(LinkState.AUTHENTICATED)
                pushStatus(); startPeriodicStatus()
            }
            override fun onCommand(cmd: Message.Cmd) { scope.launch { handleCommand(cmd) } }
            override fun onProtocolError(reason: String) {
                Log.w(LOG_TAG, "Protocol error: $reason"); ManagedLinkState.lastError.value = reason
                client?.disconnect(); scheduleRescan()
            }
        })
        client = BLEClient(this, serverName = null, servicesToListen = listOf(gatt), callBack = object : BLEClient.BLEClientCallBack {
            override fun onMtuChanged(mtu: Int) { this@ManagedLinkService.mtu = mtu }
            override fun onConnected() { Log.d(LOG_TAG, "Connected, mtu=$mtu"); setState(LinkState.CONNECTED); endpoint?.onConnected(mtu) }
            override fun onDisconnected() { Log.d(LOG_TAG, "Disconnected"); statusJob?.cancel(); endpoint?.reset(); scheduleRescan() }
            override fun onCharacteristicChanged(characteristic: BLECharacteristic, service: BLEService) {
                if (characteristic.uuid == MdmGatt.CMD_UUID) endpoint?.onFrame(characteristic.value)
            }
        }, serviceUuid = MdmGatt.SERVICE_UUID)
        scan()
    }

    private fun scan() {
        setState(LinkState.SCANNING)
        try { client?.startScan(onTimeout = { Log.d(LOG_TAG, "Scan timeout"); scheduleRescan() }) }
        catch (e: BLEException) { Log.w(LOG_TAG, "Scan failed: ${e.message}"); ManagedLinkState.lastError.value = e.message; scheduleRescan() }
    }

    private fun scheduleRescan() {
        setState(LinkState.SCANNING)
        val delayMs = backoffMs; backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ scan() }, delayMs)
    }

    private fun sendFrame(frame: String) {
        gatt.rsp.value = frame
        try { client?.writeCharacteristic(gatt, gatt.rsp) } catch (e: BLEException) { Log.w(LOG_TAG, "Write failed: ${e.message}") }
    }

    private suspend fun handleCommand(cmd: Message.Cmd) {
        Log.d(LOG_TAG, "Command ${cmd.type} ${cmd.pkg ?: ""}")
        val replies = executor.execute(cmd)
        for (m in replies) safeSend(m)
        replies.filterIsInstance<Message.Status>().lastOrNull()?.let { ManagedLinkState.lastStatus.value = it }
    }

    private fun safeSend(m: Message) { try { endpoint?.send(m) } catch (e: IllegalStateException) { Log.w(LOG_TAG, "Not authenticated; dropping ${m::class.simpleName}") } }

    private fun pushStatus() { scope.launch { val s = status.collect(); ManagedLinkState.lastStatus.value = s; safeSend(s) } }

    private fun startPeriodicStatus() {
        statusJob?.cancel()
        statusJob = scope.launch { while (isActive) { delay(STATUS_PERIOD_MS); pushStatus() } }
    }

    private fun setState(s: LinkState) {
        ManagedLinkState.state.value = s
        val text = when (s) { LinkState.AUTHENTICATED -> getString(R.string.managed_notif_authenticated); LinkState.CONNECTED -> getString(R.string.managed_notif_connected); else -> getString(R.string.managed_notif_scanning) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.managed_notif_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle(getString(R.string.managed_notif_title)).setContentText(text).setOngoing(true).build()
}
```

- [ ] **Step 3: Manifest** — inside `<application>`:
```xml
        <service
            android:name=".managed.ManagedLinkService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
```

- [ ] **Step 4: Build** — `./gradlew :app:assembleDebug` → SUCCESS. (Runtime verified on device in Task 16 checklist.)

- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(managed): foreground BLE link service with handshake, commands, status push and reconnect"`

---
### Task 10: Provisioning activities (GET_PROVISIONING_MODE, ADMIN_POLICY_COMPLIANCE setup wizard)

**Files:**
- Create: `SRC/activities/managed/provisioning/GetProvisioningModeActivity.kt`, `SRC/activities/managed/provisioning/PolicyComplianceActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ManagedSetup.persistExtras`, `PolicyManager.setAsDefaultHome`, `ManagedLinkService.start`, `StatusCollector.hasGoogleAccount/hasUsageAccess`, `MomeDMTheme`, `BasicLayoutWithTopBar`.
- Produces: two exported activities guarded by `android.permission.BIND_DEVICE_ADMIN`.

- [ ] **Step 1: Strings**
```xml
    <string name="setup_title">Set up Môme DM</string>
    <string name="setup_account_title">Google account</string>
    <string name="setup_account_text">Add a Google account so Play Store installs requested by the controller can work. You can skip and do it later.</string>
    <string name="setup_account_button">Add Google account</string>
    <string name="setup_usage_title">Usage access</string>
    <string name="setup_usage_text">Allow usage access so the controller can see the current app. Optional.</string>
    <string name="setup_usage_button">Open usage access settings</string>
    <string name="setup_skip">Skip</string>
    <string name="setup_next">Next</string>
    <string name="setup_done">Finish</string>
    <string name="setup_status_done">Done</string>
    <string name="setup_status_missing">Not yet</string>
```

- [ ] **Step 2: `GetProvisioningModeActivity.kt`**
```kotlin
package edu.fnosari.momedm.activities.managed.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import edu.fnosari.momedm.managed.ManagedSetup

/** Answers the Setup Wizard: we only support fully managed device. Also persists admin extras early. */
class GetProvisioningModeActivity : Activity() {
    companion object { private const val LOG_TAG = "GetProvisioningMode" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowed = intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES) ?: arrayListOf()
        Log.d(LOG_TAG, "Allowed modes: $allowed")
        val extras = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        ManagedSetup.persistExtras(this, extras)
        if (allowed.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)) {
            setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
        } else {
            Log.w(LOG_TAG, "Fully managed mode not offered; refusing (work profile unsupported)")
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
```

- [ ] **Step 3: `PolicyComplianceActivity.kt`** (Compose wizard: account → usage → finish)
```kotlin
package edu.fnosari.momedm.activities.managed.provisioning

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import edu.fnosari.momedm.R
import edu.fnosari.momedm.managed.ManagedLinkService
import edu.fnosari.momedm.managed.ManagedSetup
import edu.fnosari.momedm.managed.PolicyManager
import edu.fnosari.momedm.managed.StatusCollector
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import edu.fnosari.momedm.ui.theme.MomeDMTheme

/** Shown by the Setup Wizard right after we become device owner. Two optional steps, then HOME + link service. */
class PolicyComplianceActivity : ComponentActivity() {
    companion object { private const val LOG_TAG = "PolicyCompliance" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val extras = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        ManagedSetup.persistExtras(this, extras)
        val prefs = ManagedSetup.prefs(this)
        val policy = PolicyManager(this, prefs)
        val status = StatusCollector(this, prefs)

        setContent {
            MomeDMTheme {
                var step by remember { mutableIntStateOf(0) }
                var accountOk by remember { mutableStateOf(status.hasGoogleAccount()) }
                var usageOk by remember { mutableStateOf(status.hasUsageAccess()) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { accountOk = status.hasGoogleAccount(); usageOk = status.hasUsageAccess() } }
                    owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
                }
                BasicLayoutWithTopBar(title = getString(R.string.setup_title)) {
                    when (step) {
                        0 -> StepCard(getString(R.string.setup_account_title), getString(R.string.setup_account_text), getString(R.string.setup_account_button), accountOk,
                            onAction = { policy.openAddAccount() }, onNext = { step = 1 })
                        else -> StepCard(getString(R.string.setup_usage_title), getString(R.string.setup_usage_text), getString(R.string.setup_usage_button), usageOk,
                            onAction = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }, onNext = { finishSetup(policy) }, last = true)
                    }
                }
            }
        }
    }

    private fun finishSetup(policy: PolicyManager) {
        Log.d(LOG_TAG, "Setup finished; setting HOME and starting link")
        runCatching { policy.setAsDefaultHome() }.onFailure { Log.w(LOG_TAG, "setAsDefaultHome failed", it) }
        ManagedLinkService.start(this)
        setResult(RESULT_OK); finish()
    }
}

@Composable
private fun StepCard(title: String, text: String, actionLabel: String, done: Boolean, onAction: () -> Unit, onNext: () -> Unit, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Text(if (done) androidx.compose.ui.res.stringResource(R.string.setup_status_done) else androidx.compose.ui.res.stringResource(R.string.setup_status_missing), style = MaterialTheme.typography.labelLarge)
        Button(onClick = onAction) { Text(actionLabel) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onNext) { Text(androidx.compose.ui.res.stringResource(if (last) R.string.setup_done else R.string.setup_skip)) }
            if (done && !last) Button(onClick = onNext) { Text(androidx.compose.ui.res.stringResource(R.string.setup_next)) }
        }
    }
}
```

- [ ] **Step 4: Manifest** — inside `<application>`:
```xml
        <activity
            android:name=".activities.managed.provisioning.GetProvisioningModeActivity"
            android:exported="true"
            android:permission="android.permission.BIND_DEVICE_ADMIN">
            <intent-filter>
                <action android:name="android.app.action.GET_PROVISIONING_MODE" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
        <activity
            android:name=".activities.managed.provisioning.PolicyComplianceActivity"
            android:exported="true"
            android:permission="android.permission.BIND_DEVICE_ADMIN"
            android:theme="@style/Theme.MomeDM">
            <intent-filter>
                <action android:name="android.app.action.ADMIN_POLICY_COMPLIANCE" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
```

- [ ] **Step 5: Build** — `./gradlew :app:assembleDebug` → SUCCESS.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat(managed): provisioning mode + policy compliance setup wizard"`

---

### Task 11: Managed home UI (ManagedHomeActivity, ManagedViewModel, Routes, HomeScreen, LinkBanner)

**Files:**
- Replace stub: `SRC/activities/managed/ManagedHomeActivity.kt`
- Create: `SRC/activities/managed/ManagedViewModel.kt`, `SRC/activities/managed/navigation/Routes.kt`, `SRC/activities/managed/screens/HomeScreen.kt`, `SRC/activities/managed/components/LinkBanner.kt`, `SRC/ui/components/ButtonRequestPermission.kt`
- Modify: manifest, strings

**Interfaces:**
- Produces: `ButtonRequestPermission(context, permission, description, granted, denied)` (shared by both role activities, same as BLEController's), `ManagedViewModel` (`linkState`, `lastStatus`, `lastError` StateFlows; `addAccount()`, `openUsageAccess()`, `restartLink()`), `Routes { HOME }`, `HomeScreen(navController, viewModel)`, `LinkBanner(state)`.

- [ ] **Step 1: Strings**
```xml
    <string name="managed_activity_title">Môme DM</string>
    <string name="managed_drawer_name">Managed device</string>
    <string name="managed_route_home">Home</string>
    <string name="managed_allow">Allow</string>
    <string name="managed_link_scanning">Looking for controller…</string>
    <string name="managed_link_connected">Connected, authenticating…</string>
    <string name="managed_link_idle">Link service stopped</string>
    <string name="managed_status_title">Status</string>
    <string name="managed_status_kiosk">Kiosk</string>
    <string name="managed_status_account">Google account</string>
    <string name="managed_status_battery">Battery</string>
    <string name="managed_status_current">Current app</string>
    <string name="managed_action_account">Add Google account</string>
    <string name="managed_action_usage">Grant usage access</string>
    <string name="managed_action_restart">Restart link</string>
    <string name="managed_yes">Yes</string>
    <string name="managed_no">No</string>
```

- [ ] **Step 2: Shared permission button** — `SRC/ui/components/ButtonRequestPermission.kt` (copy of the composable at the bottom of BLEController `MainActivity.kt`, package `edu.fnosari.momedm.ui.components`, label text from `R.string.managed_allow`):
```kotlin
package edu.fnosari.momedm.ui.components

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import edu.fnosari.momedm.R

@Composable
fun ButtonRequestPermission(context: Context, permission: String, description: String, granted: () -> Unit, denied: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) granted() else denied() }
    Button(onClick = { launcher.launch(permission) }) { Text("${context.getString(R.string.managed_allow)} $description") }
}
```

- [ ] **Step 3: Routes, ViewModel, LinkBanner, HomeScreen**

`navigation/Routes.kt`:
```kotlin
package edu.fnosari.momedm.activities.managed.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import edu.fnosari.momedm.R

enum class Routes(val label: Int, val icon: ImageVector) {
    HOME(R.string.managed_route_home, Icons.Default.Home),
}
```
`ManagedViewModel.kt`:
```kotlin
package edu.fnosari.momedm.activities.managed

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import edu.fnosari.momedm.managed.ManagedLinkService
import edu.fnosari.momedm.managed.ManagedLinkState
import edu.fnosari.momedm.managed.ManagedSetup
import edu.fnosari.momedm.managed.PolicyManager
import kotlinx.coroutines.flow.StateFlow

/** Exposes the managed link state to the home UI and the few local actions. */
class ManagedViewModel(application: Application) : AndroidViewModel(application) {
    private val policy = PolicyManager(application, ManagedSetup.prefs(application))
    val linkState: StateFlow<ManagedLinkState.LinkState> = ManagedLinkState.state
    val lastStatus = ManagedLinkState.lastStatus
    val lastError = ManagedLinkState.lastError

    fun addAccount() = policy.openAddAccount()
    fun openUsageAccess() = getApplication<Application>().startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun restartLink() { val app = getApplication<Application>(); ManagedLinkService.stop(app); ManagedLinkService.start(app) }
    fun ensureLink() { if (ManagedLinkState.state.value == ManagedLinkState.LinkState.IDLE) ManagedLinkService.start(getApplication()) }
}
```
`components/LinkBanner.kt` — copy of BLEController `ConnectionBanner` adapted:
```kotlin
package edu.fnosari.momedm.activities.managed.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.managed.ManagedLinkState.LinkState

/** Full-width banner visible whenever the link is not authenticated. */
@Composable
fun LinkBanner(state: LinkState, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = state != LinkState.AUTHENTICATED, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically(), modifier = modifier) {
        val busy = state == LinkState.SCANNING || state == LinkState.CONNECTED
        val bg = if (busy) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
        val fg = if (busy) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
        Surface(color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg); Spacer(Modifier.width(12.dp)) }
                Text(stringResource(when (state) { LinkState.SCANNING -> R.string.managed_link_scanning; LinkState.CONNECTED -> R.string.managed_link_connected; else -> R.string.managed_link_idle }), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
```
`screens/HomeScreen.kt`:
```kotlin
package edu.fnosari.momedm.activities.managed.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.ManagedViewModel

@Composable
fun HomeScreen(navController: NavHostController, viewModel: ManagedViewModel) {
    val status by viewModel.lastStatus.collectAsState()
    val error by viewModel.lastError.collectAsState()
    val yes = stringResource(R.string.managed_yes); val no = stringResource(R.string.managed_no)
    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.managed_status_title), style = MaterialTheme.typography.titleMedium)
                StatusRow(stringResource(R.string.managed_status_kiosk), status?.let { if (it.kiosk) it.kioskPkg ?: yes else no } ?: "—")
                StatusRow(stringResource(R.string.managed_status_account), status?.let { if (it.account) yes else no } ?: "—")
                StatusRow(stringResource(R.string.managed_status_battery), status?.let { "${it.battery}%" } ?: "—")
                StatusRow(stringResource(R.string.managed_status_current), status?.currentApp ?: "—")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Button(onClick = { viewModel.addAccount() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.managed_action_account)) }
        Button(onClick = { viewModel.openUsageAccess() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.managed_action_usage)) }
        OutlinedButton(onClick = { viewModel.restartLink() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.managed_action_restart)) }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
```

- [ ] **Step 4: `ManagedHomeActivity.kt`** (BLEController MainActivity pattern)
```kotlin
package edu.fnosari.momedm.activities.managed

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.components.LinkBanner
import edu.fnosari.momedm.activities.managed.navigation.Routes
import edu.fnosari.momedm.activities.managed.screens.HomeScreen
import edu.fnosari.momedm.ui.components.ButtonRequestPermission
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import edu.fnosari.momedm.ui.layouts.Layout
import edu.fnosari.momedm.ui.theme.MomeDMTheme

/** HOME activity of a managed device: permission gate, drawer + NavHost, link banner. */
class ManagedHomeActivity : ComponentActivity() {
    companion object { private const val LOG_TAG = "ManagedHomeActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            val vm: ManagedViewModel = viewModel()
            MomeDMTheme {
                val required = remember { mutableStateListOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, POST_NOTIFICATIONS) }
                val missing = required.filter { ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                Log.d(LOG_TAG, "Missing permissions: $missing")
                if (missing.isNotEmpty()) {
                    BasicLayoutWithTopBar(title = context.getString(R.string.managed_activity_title)) {
                        Column { for (p in missing) ButtonRequestPermission(context, p, p, granted = { required.remove(p) }, denied = { Log.d(LOG_TAG, "$p denied") }) }
                    }
                } else {
                    LaunchedEffect(Unit) { vm.ensureLink() }
                    val link by vm.linkState.collectAsState()
                    Layout.BasicLayoutWithTopBarAndDrawer(
                        title = context.getString(R.string.managed_activity_title),
                        drawerItems = listOf(Layout.DrawerItem(context.getString(Routes.HOME.label), Routes.HOME.icon) { navController.navigate(Routes.HOME.name) }),
                        drawerName = context.getString(R.string.managed_drawer_name),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            LinkBanner(link)
                            NavHost(navController, startDestination = Routes.HOME.name) {
                                composable(Routes.HOME.name) { HomeScreen(navController, vm) }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Manifest** — inside `<application>`:
```xml
        <activity
            android:name=".activities.managed.ManagedHomeActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:excludeFromRecents="true"
            android:theme="@style/Theme.MomeDM">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
```

- [ ] **Step 6: Build** — `./gradlew :app:assembleDebug` → SUCCESS.
- [ ] **Step 7: Commit** — `git add -A && git commit -m "feat(managed): home activity with link banner and status screen"`

---
### Task 12: Controller core — ControllerLink bus, SessionManager, ControllerService

**Files:**
- Create: `SRC/controller/ControllerLink.kt`, `SRC/controller/SessionManager.kt`, `SRC/controller/ControllerService.kt`
- Modify: manifest, strings
- Test: `TEST/controller/SessionManagerTest.kt`

**Interfaces:**
- Consumes: `BLEServer` (Task 4), `MdmService` (Task 5), `ControllerEndpoint` (Task 5), `ControllerPrefs`, `DeviceRegistry`, `ControllerIdentity`.
- Produces:
  - `object ControllerLink { advertising: MutableStateFlow<Boolean>; online: MutableStateFlow<Set<String>>; results: MutableSharedFlow<Pair<String, Message.Result>>; apps: MutableSharedFlow<Pair<String, Message.Apps>>; errors: MutableSharedFlow<String>; var commander: ((deviceId: String, cmd: Message.Cmd) -> Boolean)?; fun sendCommand(deviceId, type, pkg): String? }` (returns cmdId or null when offline).
  - `class SessionManager(secret: ByteArray, transport: Transport, events: Events, clock)` with `interface Transport { fun sendFrame(key: String, frame: String); fun disconnect(key: String) }`, `interface Events { fun onAuthenticated(key, hello); fun onMessage(key, deviceId, m); fun onDropped(key, deviceId?) }`; methods `onConnected(key)`, `onDisconnected(key)`, `onFrame(key, frame)`, `send(deviceId, m): Boolean`, `onlineDeviceIds(): Set<String>`, `tick(nowMs)` (drops sessions unauthenticated > 5 s). Keys are BLE addresses.
  - `class ControllerService : Service` with `start(context)`, `stop(context)`, `CHANNEL_ID = "controller"`, `NOTIFICATION_ID = 2`.

- [ ] **Step 1: SessionManager test (pure; transport/events fakes)** — `TEST/controller/SessionManagerTest.kt`
```kotlin
package edu.fnosari.momedm.controller

import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.ManagedEndpoint
import edu.fnosari.momedm.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private val secret = ByteArray(32) { 1 }

    /** One fake managed device wired to the session manager through in-memory frames. */
    private class Harness(secret: ByteArray, managedSecret: ByteArray = secret) {
        val toManaged = ArrayDeque<String>(); val toCtrl = ArrayDeque<String>()
        val disconnected = mutableListOf<String>(); val msgs = mutableListOf<Pair<String, Message>>(); val cmds = mutableListOf<Message.Cmd>()
        var now = 0L
        val sm = SessionManager(secret, object : SessionManager.Transport {
            override fun sendFrame(key: String, frame: String) { toManaged.add(frame) }
            override fun disconnect(key: String) { disconnected.add(key) }
        }, object : SessionManager.Events {
            override fun onAuthenticated(key: String, hello: Message.Hello) {}
            override fun onMessage(key: String, deviceId: String, m: Message) { msgs.add(deviceId to m) }
            override fun onDropped(key: String, deviceId: String?) {}
        }, clock = { now })
        val managed = ManagedEndpoint(managedSecret, "dev-A", "Pixel", { toCtrl.add(it) }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {}
            override fun onCommand(cmd: Message.Cmd) { cmds.add(cmd) }
            override fun onProtocolError(reason: String) {}
        })
        fun pump() { while (toCtrl.isNotEmpty() || toManaged.isNotEmpty()) { toCtrl.removeFirstOrNull()?.let { sm.onFrame("AA", it) }; toManaged.removeFirstOrNull()?.let { managed.onFrame(it) } } }
        fun connect() { sm.onConnected("AA"); managed.onConnected(517); pump() }
    }

    @Test fun authenticatesAndRoutesByDeviceId() {
        val h = Harness(secret); h.connect()
        assertEquals(setOf("dev-A"), h.sm.onlineDeviceIds())
        assertTrue(h.sm.send("dev-A", Message.Cmd("1", CmdType.GET_STATUS))); h.pump()
        assertEquals(CmdType.GET_STATUS, h.cmds.single().type)
        h.managed.send(Message.Result("1", true, "ok")); h.pump()
        assertEquals("dev-A" to Message.Result("1", true, "ok"), h.msgs.single())
    }
    @Test fun sendToUnknownDeviceFails() { val h = Harness(secret); h.connect(); assertFalse(h.sm.send("nope", Message.Cmd("1", CmdType.GET_STATUS))) }
    @Test fun disconnectRemovesSession() { val h = Harness(secret); h.connect(); h.sm.onDisconnected("AA"); assertTrue(h.sm.onlineDeviceIds().isEmpty()) }
    @Test fun unauthenticatedSessionIsDroppedAfterTimeout() {
        val h = Harness(secret, managedSecret = ByteArray(32) { 2 }); h.connect()
        assertTrue(h.sm.onlineDeviceIds().isEmpty())
        h.now = 6_000; h.sm.tick(h.now)
        assertEquals(listOf("AA"), h.disconnected)
    }
}
```

- [ ] **Step 2: Run → fail; implement `SessionManager.kt`**
```kotlin
package edu.fnosari.momedm.controller

import edu.fnosari.momedm.protocol.ControllerEndpoint
import edu.fnosari.momedm.protocol.Message

/** Tracks one [ControllerEndpoint] per connected central (keyed by BLE address). Pure Kotlin. */
class SessionManager(
    private val secret: ByteArray,
    private val transport: Transport,
    private val events: Events,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object { const val AUTH_TIMEOUT_MS = 5_000L }
    interface Transport { fun sendFrame(key: String, frame: String); fun disconnect(key: String) }
    interface Events { fun onAuthenticated(key: String, hello: Message.Hello); fun onMessage(key: String, deviceId: String, m: Message); fun onDropped(key: String, deviceId: String?) }

    private class Session(val key: String, val endpoint: ControllerEndpoint, val connectedAt: Long)
    private val sessions = LinkedHashMap<String, Session>()

    @Synchronized fun onConnected(key: String) {
        val ep = ControllerEndpoint(secret, { f -> transport.sendFrame(key, f) }, object : ControllerEndpoint.Listener {
            override fun onAuthenticated(hello: Message.Hello) {
                // one session per deviceId: drop an older link of the same device
                sessions.values.filter { it.key != key && it.endpoint.deviceId == hello.deviceId }.forEach { transport.disconnect(it.key) }
                events.onAuthenticated(key, hello)
            }
            override fun onMessage(m: Message) { sessions[key]?.endpoint?.deviceId?.let { events.onMessage(key, it, m) } }
            override fun onProtocolError(reason: String) { transport.disconnect(key) }
        }, clock)
        sessions[key] = Session(key, ep, clock())
    }
    @Synchronized fun onDisconnected(key: String) { sessions.remove(key)?.let { events.onDropped(key, it.endpoint.deviceId) } }
    @Synchronized fun onFrame(key: String, frame: String) { sessions[key]?.endpoint?.onFrame(frame) }
    @Synchronized fun send(deviceId: String, m: Message): Boolean {
        val s = sessions.values.firstOrNull { it.endpoint.authenticated && it.endpoint.deviceId == deviceId } ?: return false
        return try { s.endpoint.send(m); true } catch (e: IllegalStateException) { false }
    }
    @Synchronized fun onlineDeviceIds(): Set<String> = sessions.values.filter { it.endpoint.authenticated }.mapNotNull { it.endpoint.deviceId }.toSet()
    /** Disconnects centrals that have not authenticated within [AUTH_TIMEOUT_MS]. Call periodically. */
    @Synchronized fun tick(nowMs: Long) {
        sessions.values.filter { !it.endpoint.authenticated && nowMs - it.connectedAt > AUTH_TIMEOUT_MS }.forEach { transport.disconnect(it.key) }
    }
}
```
Run → pass.

- [ ] **Step 3: `ControllerLink.kt`**
```kotlin
package edu.fnosari.momedm.controller

import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Process-wide bridge between [ControllerService] and the UI. */
object ControllerLink {
    val advertising = MutableStateFlow(false)
    val online = MutableStateFlow<Set<String>>(emptySet())
    val results = MutableSharedFlow<Pair<String, Message.Result>>(extraBufferCapacity = 16)
    val apps = MutableSharedFlow<Pair<String, Message.Apps>>(extraBufferCapacity = 4)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Installed by the running service. */
    @Volatile var commander: ((deviceId: String, cmd: Message.Cmd) -> Boolean)? = null

    /** Returns the command id, or null if no authenticated session for [deviceId]. */
    fun sendCommand(deviceId: String, type: CmdType, pkg: String? = null): String? {
        val cmd = Message.Cmd(UUID.randomUUID().toString().substring(0, 8), type, pkg)
        return if (commander?.invoke(deviceId, cmd) == true) cmd.id else null
    }
}
```

- [ ] **Step 4: Strings**
```xml
    <string name="controller_notif_channel">Controller</string>
    <string name="controller_notif_title">Môme DM controller</string>
    <string name="controller_notif_text">Advertising to managed devices (%1$d online)</string>
```

- [ ] **Step 5: `ControllerService.kt`**
```kotlin
package edu.fnosari.momedm.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import edu.fnosari.momedm.R
import edu.fnosari.momedm.connectivity.ble.BLEException
import edu.fnosari.momedm.connectivity.ble.BLEServer
import edu.fnosari.momedm.connectivity.ble.characteristics.BLECharacteristic
import edu.fnosari.momedm.connectivity.ble.services.BLEService
import edu.fnosari.momedm.link.MdmGatt
import edu.fnosari.momedm.link.MdmService
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.DeviceRegistry
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground service hosting the BLE GATT server and all managed-device sessions. */
class ControllerService : Service() {
    companion object {
        private const val LOG_TAG = "ControllerService"
        const val CHANNEL_ID = "controller"
        const val NOTIFICATION_ID = 2
        fun start(context: Context) = context.startForegroundService(Intent(context, ControllerService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, ControllerService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gatt = MdmService()
    private var server: BLEServer? = null
    private var sessions: SessionManager? = null
    private val devicesByKey = HashMap<String, BluetoothDevice>()
    private lateinit var prefs: ControllerPrefs
    private lateinit var registry: DeviceRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = ControllerPrefs(DataStorePreferencesProvider(this))
        registry = DeviceRegistry(prefs, scope)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.controller_notif_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        scope.launch { startServer() }
    }

    private suspend fun startServer() {
        val identity = prefs.ensureIdentity()
        val sm = SessionManager(identity.secretBytes, object : SessionManager.Transport {
            override fun sendFrame(key: String, frame: String) {
                val device = devicesByKey[key] ?: return
                gatt.cmd.value = frame
                try { server?.notifyDevice(device, gatt, gatt.cmd) } catch (e: BLEException) { Log.w(LOG_TAG, "notify failed: ${e.message}") }
            }
            override fun disconnect(key: String) { devicesByKey[key]?.let { server?.disconnectDevice(it) } }
        }, object : SessionManager.Events {
            override fun onAuthenticated(key: String, hello: Message.Hello) {
                Log.d(LOG_TAG, "Authenticated ${hello.deviceId} (${hello.model})")
                scope.launch { registry.upsertSeen(hello.deviceId, hello.model, System.currentTimeMillis()); refreshOnline() }
            }
            override fun onMessage(key: String, deviceId: String, m: Message) {
                when (m) {
                    is Message.Status -> scope.launch { registry.updateStatus(deviceId, m, System.currentTimeMillis()) }
                    is Message.Result -> ControllerLink.results.tryEmit(deviceId to m)
                    is Message.Apps -> ControllerLink.apps.tryEmit(deviceId to m)
                    else -> Log.w(LOG_TAG, "Unexpected ${m::class.simpleName} from $deviceId")
                }
            }
            override fun onDropped(key: String, deviceId: String?) { refreshOnline() }
        })
        sessions = sm
        ControllerLink.commander = { deviceId, cmd -> sm.send(deviceId, cmd) }
        try {
            val s = BLEServer(this, clientLimit = 7, callBack = object : BLEServer.BLEServerCallBack {
                override fun onDeviceConnected(device: BluetoothDevice) { devicesByKey[device.address] = device; sm.onConnected(device.address) }
                override fun onDeviceDisconnected(device: BluetoothDevice) { devicesByKey.remove(device.address); sm.onDisconnected(device.address) }
                override fun onCharacteristicWriteRequest(characteristic: BLECharacteristic, service: BLEService, device: BluetoothDevice) {
                    if (characteristic.uuid == MdmGatt.RSP_UUID) sm.onFrame(device.address, characteristic.value)
                }
            }, includeDeviceName = false)
            s.addService(gatt); s.startServer(); server = s
            ControllerLink.advertising.value = true
            Log.d(LOG_TAG, "GATT server started")
        } catch (e: BLEException) {
            Log.e(LOG_TAG, "Server start failed: ${e.message}"); ControllerLink.errors.tryEmit(e.message ?: "BLE error"); stopSelf(); return
        }
        scope.launch { while (isActive) { delay(1_000); sm.tick(System.currentTimeMillis()) } }
    }

    private fun refreshOnline() {
        val online = sessions?.onlineDeviceIds() ?: emptySet()
        ControllerLink.online.value = online
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(online.size))
    }

    private fun notification(online: Int): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle(getString(R.string.controller_notif_title))
        .setContentText(getString(R.string.controller_notif_text, online)).setOngoing(true).build()

    override fun onDestroy() {
        ControllerLink.commander = null
        ControllerLink.advertising.value = false; ControllerLink.online.value = emptySet()
        try { server?.stopServer() } catch (e: BLEException) { Log.w(LOG_TAG, "stop failed: ${e.message}") }
        scope.cancel()
        super.onDestroy()
    }
}
```

- [ ] **Step 6: Manifest** — inside `<application>`:
```xml
        <service
            android:name=".controller.ControllerService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
```

- [ ] **Step 7: Build + tests** — `./gradlew :app:assembleDebug :app:testDebugUnitTest` → SUCCESS.
- [ ] **Step 8: Commit** — `git add -A && git commit -m "feat(controller): session manager, link bus and GATT server foreground service"`

---

### Task 13: Controller provisioning runtime — signature checksum, APK HTTP server, hotspot, QR bitmap, ProvisioningController

**Files:**
- Create: `SRC/controller/provisioning/SignatureChecksum.kt`, `SRC/controller/provisioning/ApkHttpServer.kt`, `SRC/controller/provisioning/NetUtils.kt`, `SRC/controller/provisioning/HotspotManager.kt`, `SRC/controller/provisioning/QrBitmap.kt`, `SRC/controller/provisioning/ProvisioningController.kt`

**Interfaces:**
- Produces: `object SignatureChecksum { fun compute(context): String }`; `class ApkHttpServer(apkPath: String, port = QrPayloadBuilder.HTTP_PORT) : NanoHTTPD` (`start()`/`stop()` inherited); `object NetUtils { fun localIpv4(preferPrefixes = listOf("ap", "swlan", "wlan")): String? }`; `class HotspotManager(context) { fun start(onReady: (ssid: String, pass: String) -> Unit, onFailed: (reason: String) -> Unit); fun stop() }`; `object QrBitmap { fun render(text: String, sizePx: Int): Bitmap }`; `class ProvisioningController(context, prefs: ControllerPrefs, scope)` with `data class State(mode: String, ssid: String, password: String, customUrl: String, ip: String?, serverRunning: Boolean, qrPayload: String?, error: String?)`, `state: StateFlow<State>`, `fun setMode(mode)`, `fun setManual(ssid, pass)`, `fun setCustomUrl(url)`, `fun start()`, `fun stop()`.

- [ ] **Step 1: `SignatureChecksum.kt`**
```kotlin
package edu.fnosari.momedm.controller.provisioning

import android.content.Context
import android.content.pm.PackageManager
import edu.fnosari.momedm.protocol.Base64Url
import java.security.MessageDigest

/** base64url(SHA-256(signing certificate)) — the value ManagedProvisioning expects in the QR. */
object SignatureChecksum {
    fun compute(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val signers = info.signingInfo?.apkContentsSigners ?: error("no signing info")
        val cert = signers.first().toByteArray()
        return Base64Url.encodeNoPad(MessageDigest.getInstance("SHA-256").digest(cert))
    }
}
```

- [ ] **Step 2: `ApkHttpServer.kt`**
```kotlin
package edu.fnosari.momedm.controller.provisioning

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/** Serves our own APK at `/momedm.apk` for the managed device's Setup Wizard download. */
class ApkHttpServer(private val apkPath: String, port: Int = QrPayloadBuilder.HTTP_PORT) : NanoHTTPD(port) {
    companion object { private const val LOG_TAG = "ApkHttpServer" }

    override fun serve(session: IHTTPSession): Response {
        Log.d(LOG_TAG, "${session.method} ${session.uri} from ${session.remoteIpAddress}")
        if (session.uri != "/${QrPayloadBuilder.APK_FILE_NAME}") return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        val f = File(apkPath)
        val r = newFixedLengthResponse(Response.Status.OK, "application/vnd.android.package-archive", FileInputStream(f), f.length())
        r.addHeader("Content-Disposition", "attachment; filename=\"${QrPayloadBuilder.APK_FILE_NAME}\"")
        return r
    }
}
```

- [ ] **Step 3: `NetUtils.kt` + `HotspotManager.kt`**
```kotlin
package edu.fnosari.momedm.controller.provisioning

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** First non-loopback IPv4, preferring interfaces whose name starts with one of [preferPrefixes] (hotspot first). */
    fun localIpv4(preferPrefixes: List<String> = listOf("ap", "swlan", "wlan")): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback } ?: return null
        val ordered = ifaces.sortedBy { i -> preferPrefixes.indexOfFirst { i.name.startsWith(it) }.let { if (it < 0) 99 else it } }
        for (i in ordered) for (a in i.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
        return null
    }
}
```
```kotlin
package edu.fnosari.momedm.controller.provisioning

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Wraps [WifiManager.startLocalOnlyHotspot]; SSID/passphrase come from the system. Needs NEARBY_WIFI_DEVICES. */
class HotspotManager(context: Context) {
    companion object { private const val LOG_TAG = "HotspotManager" }
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun start(onReady: (ssid: String, pass: String) -> Unit, onFailed: (reason: String) -> Unit) {
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val cfg = r.softApConfiguration
                    val ssid = cfg.wifiSsid?.toString()?.trim('"') ?: @Suppress("DEPRECATION") cfg.ssid ?: ""
                    val pass = cfg.passphrase ?: ""
                    Log.d(LOG_TAG, "Hotspot started: $ssid"); onReady(ssid, pass)
                }
                override fun onStopped() { Log.d(LOG_TAG, "Hotspot stopped"); reservation = null }
                override fun onFailed(reason: Int) { Log.w(LOG_TAG, "Hotspot failed: $reason"); onFailed("hotspot error $reason") }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) { onFailed("missing permission: ${e.message}") }
        catch (e: IllegalStateException) { onFailed(e.message ?: "hotspot unavailable") }
    }
    fun stop() { reservation?.close(); reservation = null }
}
```

- [ ] **Step 4: `QrBitmap.kt`**
```kotlin
package edu.fnosari.momedm.controller.provisioning

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrBitmap {
    fun render(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(sizePx * sizePx) { i -> if (matrix.get(i % sizePx, i / sizePx)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.RGB_565)
    }
}
```

- [ ] **Step 5: `ProvisioningController.kt`**
```kotlin
package edu.fnosari.momedm.controller.provisioning

import android.content.Context
import android.util.Log
import edu.fnosari.momedm.persistence.ControllerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Drives the Provision screen: Wi-Fi source, APK hosting, QR payload. */
class ProvisioningController(private val context: Context, private val prefs: ControllerPrefs, private val scope: CoroutineScope) {
    companion object { private const val LOG_TAG = "ProvisioningController" }

    data class State(
        val mode: String = ControllerPrefs.MODE_HOTSPOT, val ssid: String = "", val password: String = "", val customUrl: String = "",
        val ip: String? = null, val serverRunning: Boolean = false, val qrPayload: String? = null, val error: String? = null,
    )
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val hotspot = HotspotManager(context)
    private var http: ApkHttpServer? = null

    init { scope.launch { _state.value = State(prefs.wifiMode.first(), prefs.manualSsid.first(), prefs.manualPassword.first(), prefs.customUrl.first()) } }

    fun setMode(mode: String) { update { copy(mode = mode) }; persist() }
    fun setManual(ssid: String, pass: String) { update { copy(ssid = ssid, password = pass) }; persist() }
    fun setCustomUrl(url: String) { update { copy(customUrl = url) }; persist() }
    private fun persist() = scope.launch { val s = _state.value; prefs.setWifi(s.mode, s.ssid, s.password, s.customUrl) }
    private fun update(f: State.() -> State) { _state.value = _state.value.f() }

    /** Starts hotspot (if mode HOTSPOT) and the APK server (unless CUSTOM_URL), then builds the QR. */
    fun start() {
        update { copy(error = null, qrPayload = null) }
        when (_state.value.mode) {
            ControllerPrefs.MODE_HOTSPOT -> hotspot.start(onReady = { ssid, pass -> update { copy(ssid = ssid, password = pass) }; serveAndBuild() }, onFailed = { update { copy(error = it) } })
            ControllerPrefs.MODE_MANUAL -> serveAndBuild()
            else -> buildQr()
        }
    }

    private fun serveAndBuild() {
        try {
            if (http == null) { http = ApkHttpServer(context.applicationInfo.sourceDir).also { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) } }
            val ip = NetUtils.localIpv4() ?: run { update { copy(error = "no IPv4 address") }; return }
            update { copy(serverRunning = true, ip = ip) }
            buildQr()
        } catch (e: Exception) { Log.e(LOG_TAG, "HTTP server failed", e); update { copy(error = "HTTP server: ${e.message}") } }
    }

    private fun buildQr() = scope.launch {
        val id = prefs.ensureIdentity(); val s = _state.value
        val url = if (s.mode == ControllerPrefs.MODE_CUSTOM_URL) s.customUrl else QrPayloadBuilder.apkUrl(s.ip ?: return@launch)
        if (url.isBlank()) { update { copy(error = "missing APK URL") }; return@launch }
        val checksum = try { SignatureChecksum.compute(context) } catch (e: Exception) { update { copy(error = "checksum: ${e.message}") }; return@launch }
        val payload = QrPayloadBuilder.build(ProvisioningParams(url, checksum, s.ssid.ifBlank { null }, s.password, id.controllerId, id.secretBase64))
        update { copy(qrPayload = payload) }
        Log.d(LOG_TAG, "QR payload ready (${payload.length} chars)")
    }

    fun stop() { http?.stop(); http = null; hotspot.stop(); update { copy(serverRunning = false, qrPayload = null) } }
}
```

- [ ] **Step 6: Build** — `./gradlew :app:assembleDebug` → SUCCESS.
- [ ] **Step 7: Commit** — `git add -A && git commit -m "feat(controller): provisioning runtime (checksum, APK server, hotspot, QR)"`

---
### Task 14: Controller UI — MainActivity (role switch), ControllerViewModel, Routes, Devices/Device/Provision screens, components

**Files:**
- Create: `SRC/activities/main/MainActivity.kt`, `SRC/activities/main/ControllerViewModel.kt`, `SRC/activities/main/navigation/Routes.kt`, `SRC/activities/main/screens/DevicesScreen.kt`, `SRC/activities/main/screens/DeviceScreen.kt`, `SRC/activities/main/screens/ProvisionScreen.kt`, `SRC/activities/main/components/ServiceBanner.kt`, `SRC/activities/main/components/OnlineIndicator.kt`, `SRC/activities/main/components/AppPickerDialog.kt`
- Modify: manifest (LAUNCHER), strings

**Interfaces:**
- Consumes: `ControllerLink`, `ControllerService`, `DeviceRegistry`, `ControllerPrefs`, `ProvisioningController`, `QrBitmap`, `Layout`, `BasicLayoutWithTopBar`, `ButtonRequestPermission`, `SettingsActivity`, `PolicyManager.isDeviceOwner` (via `DevicePolicyManager` directly), `ManagedHomeActivity`.
- Produces: `Routes { DEVICES, PROVISION }` + `ROUTE_DEVICE = "device/{deviceId}"`; `ControllerViewModel` (`devices: StateFlow<List<DeviceRecord>>`, `online: StateFlow<Set<String>>`, `advertising: StateFlow<Boolean>`, `events: SharedFlow<String>` (snackbar text), `appsFor: StateFlow<Pair<String, List<AppInfo>>?>`, `provisioning: ProvisioningController`; `setAdvertising(on)`, `kioskOn(deviceId, pkg)`, `kioskOff(deviceId)`, `install(deviceId, pkg)`, `addAccount(deviceId)`, `refresh(deviceId)`, `requestApps(deviceId)`, `clearApps()`).

- [ ] **Step 1: Strings**
```xml
    <string name="main_activity_title">Môme DM</string>
    <string name="main_drawer_name">Controller</string>
    <string name="main_route_devices">Devices</string>
    <string name="main_route_provision">Provision</string>
    <string name="main_settings_button">Settings</string>
    <string name="main_banner_stopped">Advertising stopped — managed devices cannot connect</string>
    <string name="main_advertising">Advertising</string>
    <string name="main_online_count">%1$d online</string>
    <string name="devices_empty">No device yet. Tap + to provision one.</string>
    <string name="devices_online">Online</string>
    <string name="devices_offline">Offline</string>
    <string name="device_title">Device</string>
    <string name="device_status">Status</string>
    <string name="device_kiosk">Kiosk</string>
    <string name="device_account">Google account</string>
    <string name="device_battery">Battery</string>
    <string name="device_current">Current app</string>
    <string name="device_last_seen">Last seen</string>
    <string name="device_kiosk_on">Kiosk ON…</string>
    <string name="device_kiosk_off">Kiosk OFF</string>
    <string name="device_install">Install from Play</string>
    <string name="device_install_hint">Package name (e.g. org.mozilla.firefox)</string>
    <string name="device_add_account">Add Google account</string>
    <string name="device_refresh">Refresh status</string>
    <string name="device_offline_msg">Device is offline</string>
    <string name="device_sent">Command sent</string>
    <string name="device_result">%1$s: %2$s</string>
    <string name="apps_title">Choose kiosk app</string>
    <string name="apps_loading">Fetching apps…</string>
    <string name="apps_cancel">Cancel</string>
    <string name="provision_title">Provision a device</string>
    <string name="provision_mode">Wi-Fi source</string>
    <string name="provision_mode_hotspot">Hotspot (auto)</string>
    <string name="provision_mode_manual">Shared Wi-Fi (manual)</string>
    <string name="provision_mode_custom">Custom APK URL</string>
    <string name="provision_ssid">SSID</string>
    <string name="provision_password">Password</string>
    <string name="provision_url">https://…/momedm.apk</string>
    <string name="provision_start">Generate QR</string>
    <string name="provision_stop">Stop</string>
    <string name="provision_serving">Serving APK at %1$s</string>
    <string name="provision_help">Factory-reset the device, tap the welcome screen 6 times and scan this QR.</string>
    <string name="yes">Yes</string>
    <string name="no">No</string>
```

- [ ] **Step 2: Routes + components**

`navigation/Routes.kt`:
```kotlin
package edu.fnosari.momedm.activities.main.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.graphics.vector.ImageVector
import edu.fnosari.momedm.R

enum class Routes(val label: Int, val icon: ImageVector) {
    DEVICES(R.string.main_route_devices, Icons.Default.List),
    PROVISION(R.string.main_route_provision, Icons.Default.Add);
    companion object { const val ROUTE_DEVICE = "device/{deviceId}"; fun device(id: String) = "device/$id" }
}
```
`components/OnlineIndicator.kt`:
```kotlin
package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R

/** Green/red dot + "n online" label for the top bar. */
@Composable
fun OnlineIndicator(advertising: Boolean, online: Int, modifier: Modifier = Modifier) {
    val color = if (advertising) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(horizontal = 8.dp)) {
        Spacer(Modifier.size(12.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.main_online_count, online), style = MaterialTheme.typography.labelLarge)
    }
}
```
`components/ServiceBanner.kt`:
```kotlin
package edu.fnosari.momedm.activities.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R

@Composable
fun ServiceBanner(advertising: Boolean) {
    AnimatedVisibility(visible = !advertising) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.main_banner_stopped), Modifier.padding(16.dp, 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```
`components/AppPickerDialog.kt`:
```kotlin
package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.protocol.AppInfo

/** Lists apps reported by the managed device; null = still loading. */
@Composable
fun AppPickerDialog(apps: List<AppInfo>?, onPick: (AppInfo) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.apps_cancel)) } },
        title = { Text(stringResource(R.string.apps_title)) },
        text = {
            if (apps == null) { Column { CircularProgressIndicator(); Text(stringResource(R.string.apps_loading)) } }
            else LazyColumn { items(apps) { a ->
                Column(Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 10.dp)) {
                    Text(a.label, style = MaterialTheme.typography.bodyLarge); Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } } }
        })
}
```

- [ ] **Step 3: `ControllerViewModel.kt`**
```kotlin
package edu.fnosari.momedm.activities.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.fnosari.momedm.R
import edu.fnosari.momedm.controller.ControllerLink
import edu.fnosari.momedm.controller.ControllerService
import edu.fnosari.momedm.controller.provisioning.ProvisioningController
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.DeviceRecord
import edu.fnosari.momedm.persistence.DeviceRegistry
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.CmdType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Controller UI state: registry, online set, advertising flag, command results; owns the provisioning controller. */
class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = ControllerPrefs(DataStorePreferencesProvider(application))
    private val registry = DeviceRegistry(prefs, viewModelScope)
    val provisioning = ProvisioningController(application, prefs, viewModelScope)

    val devices: StateFlow<List<DeviceRecord>> = registry.devices
    val online: StateFlow<Set<String>> = ControllerLink.online
    val advertising: StateFlow<Boolean> = ControllerLink.advertising
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events
    private val _appsFor = MutableStateFlow<Pair<String, List<AppInfo>?>?>(null)
    /** (deviceId, apps) while the picker is open; apps == null while loading. */
    val appsFor: StateFlow<Pair<String, List<AppInfo>?>?> = _appsFor

    init {
        val app = application
        viewModelScope.launch { ControllerLink.results.collect { (id, r) -> _events.emit(app.getString(R.string.device_result, if (r.ok) "OK" else "ERR", r.msg)) } }
        viewModelScope.launch { ControllerLink.apps.collect { (id, a) -> if (_appsFor.value?.first == id) _appsFor.value = id to a.apps } }
        viewModelScope.launch { ControllerLink.errors.collect { _events.emit(it) } }
        viewModelScope.launch { if (prefs.advertiseOnLaunch.first() && !ControllerLink.advertising.value) ControllerService.start(app) }
        // Service-side registry writes land in DataStore; re-read the blob so the UI list refreshes.
        viewModelScope.launch { prefs.registryJson.collect { registry.reload() } }
    }

    fun setAdvertising(on: Boolean) { val app = getApplication<Application>(); viewModelScope.launch { prefs.setAdvertiseOnLaunch(on) }; if (on) ControllerService.start(app) else ControllerService.stop(app) }

    private fun send(deviceId: String, type: CmdType, pkg: String? = null) {
        val app = getApplication<Application>()
        val id = ControllerLink.sendCommand(deviceId, type, pkg)
        viewModelScope.launch { _events.emit(if (id == null) app.getString(R.string.device_offline_msg) else app.getString(R.string.device_sent)) }
    }
    fun kioskOn(deviceId: String, pkg: String) { _appsFor.value = null; send(deviceId, CmdType.KIOSK_ON, pkg) }
    fun kioskOff(deviceId: String) = send(deviceId, CmdType.KIOSK_OFF)
    fun install(deviceId: String, pkg: String) = send(deviceId, CmdType.INSTALL, pkg)
    fun addAccount(deviceId: String) = send(deviceId, CmdType.ADD_ACCOUNT)
    fun refresh(deviceId: String) = send(deviceId, CmdType.GET_STATUS)
    fun requestApps(deviceId: String) { _appsFor.value = deviceId to null; send(deviceId, CmdType.LIST_APPS) }
    fun clearApps() { _appsFor.value = null }
}
```
This needs `DeviceRegistry.reload()`; add to `SRC/persistence/DeviceRegistry.kt`:
```kotlin
    /** Re-reads the persisted blob (another process/service wrote it). */
    suspend fun reload() { _devices.value = DeviceRegistryCodec.decode(prefs.registryJson.first()) }
```
(Service and UI run in the same process, but each has its own `DeviceRegistry` instance; the DataStore flow is the shared truth.)

- [ ] **Step 4: Screens**

`screens/DevicesScreen.kt`:
```kotlin
package edu.fnosari.momedm.activities.main.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.activities.main.navigation.Routes

@Composable
fun DevicesScreen(navController: NavHostController, viewModel: ControllerViewModel) {
    val devices by viewModel.devices.collectAsState()
    val online by viewModel.online.collectAsState()
    val advertising by viewModel.advertising.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.main_advertising), style = MaterialTheme.typography.titleMedium)
                Switch(checked = advertising, onCheckedChange = { viewModel.setAdvertising(it) })
            }
            if (devices.isEmpty()) Text(stringResource(R.string.devices_empty), Modifier.padding(16.dp))
            LazyColumn { items(devices, key = { it.deviceId }) { d ->
                Surface(onClick = { navController.navigate(Routes.device(d.deviceId)) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(d.model, style = MaterialTheme.typography.bodyLarge); Text(d.deviceId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(stringResource(if (d.deviceId in online) R.string.devices_online else R.string.devices_offline),
                            color = if (d.deviceId in online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            } }
        }
        FloatingActionButton(onClick = { navController.navigate(Routes.PROVISION.name) }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) { Icon(Icons.Default.Add, contentDescription = null) }
    }
}
```
`screens/DeviceScreen.kt`:
```kotlin
package edu.fnosari.momedm.activities.main.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.activities.main.components.AppPickerDialog
import java.text.DateFormat
import java.util.Date

@Composable
fun DeviceScreen(navController: NavHostController, viewModel: ControllerViewModel, deviceId: String) {
    val devices by viewModel.devices.collectAsState()
    val online by viewModel.online.collectAsState()
    val appsFor by viewModel.appsFor.collectAsState()
    val d = devices.firstOrNull { it.deviceId == deviceId }
    val yes = stringResource(R.string.yes); val no = stringResource(R.string.no)
    var pkg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(d?.model ?: deviceId, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(if (deviceId in online) R.string.devices_online else R.string.devices_offline), color = if (deviceId in online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.device_status), style = MaterialTheme.typography.titleMedium)
            val s = d?.lastStatus
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_kiosk)); Text(s?.let { if (it.kiosk) it.kioskPkg ?: yes else no } ?: "—") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_account)); Text(s?.let { if (it.account) yes else no } ?: "—") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_battery)); Text(s?.let { "${it.battery}%" } ?: "—") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_current)); Text(s?.currentApp ?: "—") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_last_seen)); Text(d?.let { DateFormat.getDateTimeInstance().format(Date(it.lastSeen)) } ?: "—") }
        } }
        Button(onClick = { viewModel.requestApps(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_kiosk_on)) }
        OutlinedButton(onClick = { viewModel.kioskOff(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_kiosk_off)) }
        OutlinedTextField(value = pkg, onValueChange = { pkg = it }, label = { Text(stringResource(R.string.device_install_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.install(deviceId, pkg.trim()) }, enabled = pkg.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_install)) }
        OutlinedButton(onClick = { viewModel.addAccount(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_add_account)) }
        OutlinedButton(onClick = { viewModel.refresh(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_refresh)) }
    }
    appsFor?.let { (id, apps) -> if (id == deviceId) AppPickerDialog(apps, onPick = { viewModel.kioskOn(deviceId, it.pkg) }, onDismiss = { viewModel.clearApps() }) }
}
```
`screens/ProvisionScreen.kt`:
```kotlin
package edu.fnosari.momedm.activities.main.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.controller.provisioning.QrBitmap
import edu.fnosari.momedm.persistence.ControllerPrefs

@Composable
fun ProvisionScreen(navController: NavHostController, viewModel: ControllerViewModel) {
    val pc = viewModel.provisioning
    val s by pc.state.collectAsState()
    DisposableEffect(Unit) { onDispose { pc.stop() } }
    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.provision_mode), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        listOf(ControllerPrefs.MODE_HOTSPOT to R.string.provision_mode_hotspot, ControllerPrefs.MODE_MANUAL to R.string.provision_mode_manual, ControllerPrefs.MODE_CUSTOM_URL to R.string.provision_mode_custom).forEach { (mode, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = s.mode == mode, onClick = { pc.setMode(mode) }); Text(stringResource(label)) }
        }
        if (s.mode != ControllerPrefs.MODE_HOTSPOT) {
            OutlinedTextField(s.ssid, { pc.setManual(it, s.password) }, label = { Text(stringResource(R.string.provision_ssid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(s.password, { pc.setManual(s.ssid, it) }, label = { Text(stringResource(R.string.provision_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (s.mode == ControllerPrefs.MODE_CUSTOM_URL) OutlinedTextField(s.customUrl, { pc.setCustomUrl(it) }, label = { Text(stringResource(R.string.provision_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pc.start() }) { Text(stringResource(R.string.provision_start)) }
            OutlinedButton(onClick = { pc.stop() }) { Text(stringResource(R.string.provision_stop)) }
        }
        s.ip?.takeIf { s.serverRunning }?.let { Text(stringResource(R.string.provision_serving, it), style = MaterialTheme.typography.bodySmall) }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        s.qrPayload?.let { payload ->
            val bmp = remember(payload) { QrBitmap.render(payload, 800) }
            Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(320.dp))
            Text(stringResource(R.string.provision_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

- [ ] **Step 5: `MainActivity.kt`** (role switch + BLEController pattern)
```kotlin
package edu.fnosari.momedm.activities.main

import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.components.OnlineIndicator
import edu.fnosari.momedm.activities.main.components.ServiceBanner
import edu.fnosari.momedm.activities.main.navigation.Routes
import edu.fnosari.momedm.activities.main.screens.DeviceScreen
import edu.fnosari.momedm.activities.main.screens.DevicesScreen
import edu.fnosari.momedm.activities.main.screens.ProvisionScreen
import edu.fnosari.momedm.activities.managed.ManagedHomeActivity
import edu.fnosari.momedm.activities.settings.SettingsActivity
import edu.fnosari.momedm.ui.components.ButtonRequestPermission
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import edu.fnosari.momedm.ui.layouts.Layout
import edu.fnosari.momedm.ui.theme.MomeDMTheme

/** Launcher entry. Device owner → managed home; otherwise the controller UI. */
class MainActivity : ComponentActivity() {
    companion object { private const val LOG_TAG = "MainActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(packageName)) {
            Log.d(LOG_TAG, "Device owner → managed role")
            startActivity(Intent(this, ManagedHomeActivity::class.java)); finish(); return
        }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            val vm: ControllerViewModel = viewModel()
            val snackbar = remember { SnackbarHostState() }
            MomeDMTheme {
                val required = remember { mutableStateListOf(BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, POST_NOTIFICATIONS, NEARBY_WIFI_DEVICES) }
                val missing = required.filter { ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                Log.d(LOG_TAG, "Missing permissions: $missing")
                if (missing.isNotEmpty()) {
                    BasicLayoutWithTopBar(title = context.getString(R.string.main_activity_title)) {
                        Column { for (p in missing) ButtonRequestPermission(context, p, p, granted = { required.remove(p) }, denied = { Log.d(LOG_TAG, "$p denied") }) }
                    }
                } else {
                    val advertising by vm.advertising.collectAsState()
                    val online by vm.online.collectAsState()
                    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
                    Layout.BasicLayoutWithTopBarAndDrawer(
                        title = context.getString(R.string.main_activity_title),
                        rightActions = {
                            OnlineIndicator(advertising, online.size)
                            IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) { Icon(Icons.Filled.Settings, contentDescription = context.getString(R.string.main_settings_button)) }
                        },
                        drawerItems = Routes.entries.map { r -> Layout.DrawerItem(context.getString(r.label), r.icon) { navController.navigate(r.name) } },
                        drawerName = context.getString(R.string.main_drawer_name),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            ServiceBanner(advertising)
                            NavHost(navController, startDestination = Routes.DEVICES.name, modifier = Modifier.weight(1f)) {
                                composable(Routes.DEVICES.name) { DevicesScreen(navController, vm) }
                                composable(Routes.PROVISION.name) { ProvisionScreen(navController, vm) }
                                composable(Routes.ROUTE_DEVICE) { back -> DeviceScreen(navController, vm, back.arguments?.getString("deviceId") ?: "") }
                            }
                            SnackbarHost(snackbar)
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Manifest** — inside `<application>`:
```xml
        <activity
            android:name=".activities.main.MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.MomeDM">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

- [ ] **Step 7: Build + tests** — `./gradlew :app:assembleDebug :app:testDebugUnitTest` → SUCCESS. Install on a non-DO phone (`./gradlew :app:installDebug`): grant permissions, see Devices screen, toggle advertising (notification appears), open Provision, generate QR in Hotspot mode (or Manual) — QR image renders.
- [ ] **Step 8: Commit** — `git add -A && git commit -m "feat(controller): main activity with devices, device detail and provisioning screens"`

---
### Task 15: Settings — Controller category (identity + regenerate secret)

**Files:**
- Create: `SRC/activities/settings/screens/SettingsControllerScreen.kt`
- Modify: `SRC/activities/settings/navigation/Routes.kt`, `SRC/activities/settings/screens/SettingsCategories.kt`, `SRC/activities/settings/SettingsActivity.kt`, strings

**Interfaces:**
- Consumes: `ControllerPrefs.identity()/regenerateSecret()`, `ControllerService.stop/start`, `BasicLayoutWithTopBar`, `SettingsCategoryItem`.
- Produces: `Routes.SETTINGS_CONTROLLER`, `SettingsControllerScreen(navController)`.

- [ ] **Step 1: Strings**
```xml
    <string name="settings_controller_id">Controller ID</string>
    <string name="settings_controller_secret">Secret fingerprint</string>
    <string name="settings_controller_regenerate">Regenerate secret</string>
    <string name="settings_controller_regenerate_warning">All devices provisioned with the current secret will stop authenticating until re-provisioned. Continue?</string>
    <string name="settings_controller_regenerated">Secret regenerated</string>
```

- [ ] **Step 2: Routes** — add `SETTINGS_CONTROLLER(R.string.settings_screen_category_controller, Icons.Outlined.Settings),` after `CATEGORIES`.

- [ ] **Step 3: `SettingsControllerScreen.kt`**
```kotlin
package edu.fnosari.momedm.activities.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.controller.ControllerLink
import edu.fnosari.momedm.controller.ControllerService
import edu.fnosari.momedm.controller.provisioning.ControllerIdentity
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.protocol.Crypto
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import kotlinx.coroutines.launch

@Composable
fun SettingsControllerScreen(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember { ControllerPrefs(DataStorePreferencesProvider(context)) }
    val scope = rememberCoroutineScope()
    var identity by remember { mutableStateOf<ControllerIdentity?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { identity = prefs.ensureIdentity() }
    BasicLayoutWithTopBar(title = stringResource(R.string.settings_screen_category_controller), leftAction = { navController.popBackStack() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_controller_id), style = MaterialTheme.typography.labelLarge)
            Text(identity?.controllerId ?: "…", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_controller_secret), style = MaterialTheme.typography.labelLarge)
            // Show only a fingerprint, never the secret itself.
            Text(identity?.let { Crypto.hmacHex(it.secretBytes, "fingerprint").take(16) } ?: "…", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { confirm = true }) { Text(stringResource(R.string.settings_controller_regenerate)) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        text = { Text(stringResource(R.string.settings_controller_regenerate_warning)) },
        confirmButton = { TextButton(onClick = {
            confirm = false
            scope.launch {
                identity = prefs.regenerateSecret()
                if (ControllerLink.advertising.value) { ControllerService.stop(context); ControllerService.start(context) }
                info = context.getString(R.string.settings_controller_regenerated)
            }
        }) { Text(stringResource(R.string.settings_dialog_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } })
}
```

- [ ] **Step 4: Wire** — in `SettingsCategories.kt` add before the Legal item:
```kotlin
            item { SettingsCategoryItem(title = context.getString(Routes.SETTINGS_CONTROLLER.label), icon = Routes.SETTINGS_CONTROLLER.icon, onClick = { navController.navigate(Routes.SETTINGS_CONTROLLER.name) }) }
```
In `SettingsActivity.kt` add `composable(Routes.SETTINGS_CONTROLLER.name) { SettingsControllerScreen(navController) }`.

- [ ] **Step 5: Build** — `./gradlew :app:assembleDebug` → SUCCESS.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat(settings): controller identity screen with secret regeneration"`

---

### Task 16: Docs + final verification (README, CLAUDE.md, testing checklist)

**Files:**
- Create: `README.md`, `CLAUDE.md`, `docs/testing.md`
- Modify: `SRC/connectivity/ble/README.md` (already touched in Task 4 — verify it lists the extensions)

- [ ] **Step 1: `README.md`** — sections: What it is (two roles, one APK); Architecture diagram (controller GATT server ⇄ managed client; protocol layers: frames → envelope → messages); Provisioning walkthrough (controller Provision screen → modes → QR → SUW tap ×6 → wizard steps); Commands table (from spec §1); Security (HMAC handshake, session key, seq, what is NOT encrypted); Building (`./gradlew assembleDebug`, `installDebug`, `testDebugUnitTest`); Known limitations (no silent Play install, usage access optional, http APK URL, hotspot caveat). Copy the command table and security paragraph from the spec verbatim.

- [ ] **Step 2: `CLAUDE.md`** — same shape as BLEController's: What this is; Build & run commands; Architecture tree (from this plan's File Structure); Key conventions (BLE package app-agnostic; protocol pure Kotlin; role switch; Routes enum/Layout pattern; version catalog; LOG_TAG); Gotchas (BLE not on emulator; DPC only testable on factory-reset device or `adb shell dpm set-device-owner edu.fnosari.momedm/.managed.AdminReceiver` on a device with no accounts; ATT MTU 23 fallback; `allowBackup=false` on purpose; UUIDs in `MdmGatt` are permanent).

- [ ] **Step 3: `docs/testing.md`** — manual checklist (copy exactly):
```markdown
# Manual test checklist (two physical devices)

## A. Controller standalone
- [ ] Fresh install on phone A (not device owner) → permission gate shows 5 permissions; after granting, Devices screen.
- [ ] Advertising toggle ON → persistent notification "Môme DM controller"; OFF → notification gone, banner shown.
- [ ] Provision screen, Hotspot mode → Generate QR → SSID/pass filled, "Serving APK at <ip>" and QR visible. Browser on another device on that hotspot: `http://<ip>:8080/momedm.apk` downloads.
- [ ] Manual mode with your Wi-Fi → QR visible; Custom URL mode → QR visible without server.
- [ ] Settings → Controller: id + fingerprint shown; Regenerate → fingerprint changes, service restarts.

## B. Provisioning device B (factory reset)
- [ ] Tap welcome screen 6×, scan QR (hotspot) → Wi-Fi joins, APK downloads, "Set up your device" → our wizard (account step, usage step) → home = Môme DM managed screen.
- [ ] If download fails on hotspot: retry with Manual (shared LAN) mode; if `http://` refused: Custom URL mode with an https host. Record which worked in this file.
- [ ] `adb shell dumpsys device_policy | grep -i owner` shows `edu.fnosari.momedm`.

## C. Link + commands
- [ ] B shows "Looking for controller…" then banner disappears (AUTHENTICATED) within ~10 s of A advertising. A's Devices list shows B online with model.
- [ ] Refresh status → card updates (battery, account yes/no, kiosk no).
- [ ] Kiosk ON → app picker lists B's apps (non-ASCII labels intact) → pick one → B enters lock task in that app; A shows kiosk=pkg. Back/home blocked on B.
- [ ] Kiosk OFF → B returns to managed home; status kiosk=no.
- [ ] Install (e.g. `org.mozilla.firefox`) with account on B → Play listing opens (also while kiosk ON).
- [ ] Add account → Google sign-in flow opens on B (kiosk OFF).
- [ ] Snackbar on A shows `OK: ...` / `ERR: ...` for every command.

## D. Resilience
- [ ] Toggle Bluetooth off/on on A → B reconnects and re-authenticates (backoff visible in logcat `ManagedLinkService`).
- [ ] Reboot B with kiosk ON → kiosk app relaunches, link re-established.
- [ ] Reboot B with kiosk OFF → managed home, link re-established.
- [ ] Second managed device C → both online simultaneously; commands go to the right device.
- [ ] Controller with a different secret (regenerate on A) → B never authenticates, A shows B offline, B keeps scanning; re-provision fixes it.
- [ ] Logcat on A shows unauthenticated centrals dropped after 5 s.
```

- [ ] **Step 4: Final verification**

Run: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass (protocol, controller, persistence, managed). List the test count in the commit body.

- [ ] **Step 5: Commit**
```bash
git add -A
git commit -m "docs: README, CLAUDE.md and manual test checklist"
```

---

## Self-review notes (author)

- Spec §1 commands → Task 8 (`CommandExecutor`), §3 provisioning → Tasks 6, 10, 13, 14; §4 BLE/protocol → Tasks 4, 5; §5 managed → Tasks 8, 9, 11; §6 controller → Tasks 12, 13, 14, 15; §7 errors → handled in endpoints/services; §8 testing → unit tests in 5, 6, 7, 8, 12 + `docs/testing.md`.
- Spec says kiosk allowlist `[pkg, self, com.android.vending]`; plan adds `com.google.android.gms` because Play needs GMS UI in lock task. Add-account while kiosk ON is not allowlisted on purpose (Settings would be escapable) — the controller should turn kiosk OFF first; `RESULT` reports whatever the intent does.
- `AUTH_OK` message added beyond the spec so the managed side learns when the session is live.
- Deviation: STATUS is pushed on auth, after every command, on battery change ≥5 %% and every 5 min — NOT on foreground-app or account change (no cheap signal for those; the 5-min timer covers them). Acceptable for v1; revisit if the controller needs live current-app.
