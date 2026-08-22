# BLE framework (`connectivity.ble`)

A small, self-contained Bluetooth Low Energy layer for Android that models GATT
**services** and **characteristics** as Kotlin classes and hides the awkward parts
of the platform API (operation serialization, notification subscription, GATT
cleanup). It supports both roles:

- **`BLEClient`** — central: scan for a peripheral by name, connect, discover,
  subscribe to notifications, read/write characteristics.
- **`BLEServer`** — peripheral: expose services, advertise, answer reads/writes,
  push notifications.

It is plain Android — no DI, no third-party BLE library.

## What it handles for you

- **One GATT op at a time.** `BLEOperationQueue` serializes reads/writes (the
  stack silently drops overlapping operations) and has a 5 s stall watchdog so a
  lost callback can't deadlock the queue.
- **Notifications done right.** On the client, every listened characteristic is
  subscribed by writing the CCCD descriptor (`0x2902`) after discovery — not just
  `setCharacteristicNotification`, which alone delivers nothing on most peripherals.
- **No GATT leaks.** The client closes the `BluetoothGatt` on every disconnect
  (and on error statuses like 133), preventing the client-interface leak that
  eventually breaks all connections.
- **Permission-safe callbacks.** System callbacks run on binder threads, so they
  log-and-return on a missing permission instead of throwing (which would crash).
- **Version-safe writes.** Uses the API-33 `writeCharacteristic` overload with a
  fallback for API 31–32.
- **NOTIFY characteristics.** `Permission.NOTIFY` sets `PROPERTY_NOTIFY` +
  `PERMISSION_READ` and gets a CCCD descriptor (`0x2902`) on the server side.
- **CCCD writes go through the op queue.** The client's subscription write is a
  queued `BLEOperation.WriteDescriptor`, serialized with reads/writes like any
  other GATT op.
- **MTU negotiation.** The client requests MTU 517 right after service discovery
  and reports the negotiated value via `onMtuChanged(mtu)` before `onConnected()`.
- **Per-device serialized notifications.** `BLEServer.notifyDevice` queues one
  notification per device at a time (`onNotificationSent` advances the queue),
  so a slow/unresponsive central can't stall notifications to others.
- **Scan/advertise by service UUID.** `BLEClient(serviceUuid = ...)` filters the
  scan by advertised service UUID (name optional); `BLEServer(includeDeviceName = ...)`
  controls whether the advertised device name is included.
- **`disconnectDevice`.** The server can drop a specific central's link.

## Requirements

- `minSdk 31` (the permission model is `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`).
  To support older APIs you'd re-add legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` +
  location handling.
- Manifest permissions:

  ```xml
  <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
      android:usesPermissionFlags="neverForLocation" />
  <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
  <!-- server / advertising only: -->
  <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
  ```

- Request `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (and `BLUETOOTH_ADVERTISE` for the
  server) at runtime before use.
- BLE does not work on emulators — test on a device.

## Installing in another project

Copy the `connectivity/ble` package and re-namespace it. Files:

```
ble/
├── BLEClient.kt            # central role
├── BLEServer.kt            # peripheral role
├── BLEOperationQueue.kt    # internal op serialization
├── BLEOperation.kt         # sealed Read/Write op
├── BLEException.kt
├── services/BLEService.kt          # abstract base
└── characteristics/BLECharacteristic.kt  # abstract base + Permission enum
```

> **One external coupling:** `BLEServer.connectedDevices` is backed by a Compose
> `mutableStateListOf` (so it's observable from Compose). If you don't use Compose,
> swap it for a plain `mutableListOf`.

## Core model

Subclass `BLEService` and `BLECharacteristic` to declare your GATT layout. A
characteristic's `value` is a `String` (converted to/from bytes as UTF-8 at the
GATT boundary).

```kotlin
class HeartRateCharacteristic : BLECharacteristic(
    uuid       = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"),
    name       = "HeartRate",
    value      = "0",
    permission = Permission.READ_WRITE   // READ | WRITE | READ_WRITE
)

class HeartRateService : BLEService(
    uuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),
    name = "HeartRate",
    type = BluetoothGattService.SERVICE_TYPE_PRIMARY
) {
    init { addCharacteristic(HeartRateCharacteristic()) }
}
```

## Client usage

```kotlin
val service = HeartRateService()

val client = BLEClient(
    context = context,
    serverName = "MyPeripheral",        // matched against the advertised name
    servicesToListen = listOf(service), // discovered + auto-subscribed
    callBack = object : BLEClient.BLEClientCallBack {
        override fun onConnected() { /* link up; safe to read/write */ }
        override fun onDisconnected() { /* link down (auto-closed) */ }
        override fun onMtuChanged(mtu: Int) { /* negotiated MTU; payload = mtu - 3 */ }
        override fun onCharacteristicChanged(
            characteristic: BLECharacteristic,
            service: BLEService
        ) {
            // Fired on notify and on read completion.
            // `characteristic` is your subclass instance; `characteristic.value` is updated.
        }
    }
)

client.startScan(onTimeout = { /* not found within 30 s — retry, prompt, etc. */ })

// Writes/reads are queued and run one at a time:
val ch = service.characteristics.first()
ch.value = "72"
client.writeCharacteristic(service, ch)
client.readCharacteristic(service, ch)

client.disconnect()   // closes the GATT silently (no onDisconnected callback)
```

Notes:
- `startScan`, `writeCharacteristic`, `readCharacteristic` throw `BLEException`
  on missing Bluetooth/permissions — call them from app code where you can catch.
- Matching is by **advertised device name** == `serverName`.
- The same `BLECharacteristic` instance you pass in is what you receive back in
  `onCharacteristicChanged` (its `value` is set before the callback).

## Server usage

```kotlin
val service = HeartRateService()

val server = BLEServer(
    context = context,
    clientLimit = 5,                    // optional, default 5
    callBack = object : BLEServer.BLEServerCallBack {
        override fun onDeviceConnected(device: BluetoothDevice) {}
        override fun onDeviceDisconnected(device: BluetoothDevice) {}
        override fun onCharacteristicWriteRequest(
            characteristic: BLECharacteristic,
            service: BLEService,
            device: BluetoothDevice
        ) {
            // A central wrote `characteristic.value`; react here.
        }
    }
)

server.addService(service)
server.startServer()                    // builds the GATT table + starts advertising

// Push a new value to all connected centrals (notify):
val ch = service.characteristics.first()
ch.value = "80"
server.updateCharacteristic(service, ch)

server.stopServer()
```

`server.connectedDevices` exposes the current centrals; `server.serverName` is the
adapter name being advertised.

## Threading & gotchas

- GATT callbacks arrive on **binder threads**. `BLECharacteristic.value` is a plain
  `String` mutated from those threads — hop to your main/UI thread (or a `StateFlow`)
  before rendering, and don't assume ordering across characteristics.
- Default ATT MTU is 23 bytes → ~20-byte payloads. The client negotiates MTU 517
  automatically after discovery; `onMtuChanged`/`BLEServer.BLEServerCallBack.onMtuChanged`
  report the value actually granted (fall back accordingly for large payloads).
- A characteristic's `properties`/`permissions` are derived from its `Permission`;
  if you need `INDICATE`, extend the `Permission` enum and the
  `BLECharacteristic.properties` mapping.
- `BLEClient` matches and subscribes to characteristics by UUID; UUIDs must be
  unique within a service.
