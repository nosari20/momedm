package edu.fnosari.momedm.connectivity.ble

import android.bluetooth.BluetoothGattCharacteristic

sealed class BLEOperation {
    data class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic) : BLEOperation()
    data class WriteCharacteristic(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : BLEOperation()
}