package edu.fnosari.momedm.connectivity.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

sealed class BLEOperation {
    data class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic) : BLEOperation()
    data class WriteCharacteristic(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : BLEOperation()
    /** CCCD (or any descriptor) write; completes on [android.bluetooth.BluetoothGattCallback.onDescriptorWrite]. */
    data class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : BLEOperation()
}
