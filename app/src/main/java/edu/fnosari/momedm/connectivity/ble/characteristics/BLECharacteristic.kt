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
        READ_WRITE
    }


    /**
     * The properties of the BLE characteristic.
     *
     * Properties determine the operations that can be performed on the
     * characteristic (e.g., read, write). If the characteristic is writable,
     * both read and write properties are set. Otherwise, only the read property is set.
     */
    val properties: Int
        get() {
            when(permission){
                Permission.READ -> return BluetoothGattCharacteristic.PROPERTY_READ
                Permission.WRITE -> return BluetoothGattCharacteristic.PROPERTY_WRITE
                Permission.READ_WRITE -> return BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE
            }
        }

    /**
     * The permissions of the BLE characteristic.
     *
     * Permissions define the access control for the characteristic (e.g., read-only or read-write).
     * If the characteristic is writable, both read and write permissions are granted.
     * Otherwise, only read permission is granted.
     */
    val permissions: Int
        get() {
            when(permission){
                Permission.READ -> return BluetoothGattCharacteristic.PERMISSION_READ
                Permission.WRITE -> return BluetoothGattCharacteristic.PERMISSION_WRITE
                Permission.READ_WRITE -> return BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            }
        }
}