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
