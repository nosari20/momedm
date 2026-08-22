package edu.fnosari.momedm.connectivity.ble.characteristics

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID


/**
 * Represents an abstract Bluetooth Low Energy (BLE) characteristic.
 *
 * This class provides the structure for defining a BLE characteristic,
 * including its UUID, value, writability, properties, and permissions.
 * It is designed to be extended to define specific characteristics in
 * a BLE service.
 *
 * @param uuid The universally unique identifier (UUID) for the BLE characteristic.
 * @param name The name of the characteristic as a string.
 * @param value The initial value of the characteristic as a string.
 * @param writable Indicates whether the characteristic supports writing (default is `false`).
 *
 * @property UUID The UUID of the characteristic.
 * @property value The current value of the characteristic.
 * @property properties The properties of the characteristic, such as read or write capabilities.
 * @property permissions The permissions of the characteristic, such as read or write access control.
 */
abstract class BLECharacteristic(val uuid: UUID, val name: String, open var value: String, private val permission: Permission = Permission.READ) {


    enum class Permission {
        READ,
        WRITE,
        READ_WRITE,
        /** Server pushes values; clients subscribe via CCCD. Readable so the last value can be fetched. */
        NOTIFY
    }


    /**
     * The properties of the BLE characteristic.
     *
     * Properties determine the operations that can be performed on the
     * characteristic (e.g., read, write). If the characteristic is writable,
     * both read and write properties are set. Otherwise, only the read property is set.
     */
    val properties: Int
        get() = when (permission) {
            Permission.READ -> BluetoothGattCharacteristic.PROPERTY_READ
            Permission.WRITE -> BluetoothGattCharacteristic.PROPERTY_WRITE
            Permission.READ_WRITE -> BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE
            Permission.NOTIFY -> BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        }

    /**
     * The permissions of the BLE characteristic.
     *
     * Permissions define the access control for the characteristic (e.g., read-only or read-write).
     * If the characteristic is writable, both read and write permissions are granted.
     * Otherwise, only read permission is granted.
     */
    val permissions: Int
        get() = when (permission) {
            Permission.READ -> BluetoothGattCharacteristic.PERMISSION_READ
            Permission.WRITE -> BluetoothGattCharacteristic.PERMISSION_WRITE
            Permission.READ_WRITE -> BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            Permission.NOTIFY -> BluetoothGattCharacteristic.PERMISSION_READ
        }

    /** True when a central should subscribe (write the CCCD) for this characteristic. */
    val notifies: Boolean get() = permission == Permission.NOTIFY
}