package edu.fnosari.momedm.connectivity.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Serializes GATT operations so only one is in flight at a time — the Android
 * BLE stack silently drops a second operation started before the first completes.
 *
 * Access is synchronized because [enqueue] runs on the caller's thread while
 * [signalOperationComplete] runs on a binder thread. A watchdog advances the
 * queue if the stack never reports completion, so a single lost callback can't
 * stall it forever.
 */
class BLEOperationQueue(private val gatt: BluetoothGatt) {

    companion object {
        private const val LOG_TAG = "BLEOperationQueue"

        /** Max time to wait for an operation's completion callback before giving up on it. */
        private const val OPERATION_TIMEOUT_MS = 5000L
    }

    private val lock = Any()
    private val queue: ArrayDeque<BLEOperation> = ArrayDeque()
    private var isProcessing = false

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Log.w(LOG_TAG, "BLE operation timed out after ${OPERATION_TIMEOUT_MS}ms; advancing queue")
        signalOperationComplete()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun enqueue(operation: BLEOperation) {
        synchronized(lock) {
            queue.add(operation)
            Log.d(LOG_TAG, "BLE operation enqueued: $operation (queue size: ${queue.size})")
        }
        processNext()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processNext() {
        val op: BLEOperation
        synchronized(lock) {
            if (isProcessing || queue.isEmpty()) return
            isProcessing = true
            op = queue.removeFirst()
        }

        Log.d(LOG_TAG, "BLE operation processing: $op")
        val started: Boolean = when (op) {
            is BLEOperation.ReadCharacteristic -> gatt.readCharacteristic(op.characteristic)
            is BLEOperation.WriteCharacteristic -> startWrite(op)
            is BLEOperation.WriteDescriptor -> startDescriptorWrite(op)
        }

        if (started) {
            timeoutHandler.postDelayed(timeoutRunnable, OPERATION_TIMEOUT_MS)
            Log.d(LOG_TAG, "BLE operation started: $op")
        } else {
            // Failed to even start — don't wait for a callback that won't come.
            Log.w(LOG_TAG, "BLE operation failed to start: $op")
            synchronized(lock) { isProcessing = false }
            processNext()
        }
    }

    @Suppress("DEPRECATION") // pre-Tiramisu write API
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startWrite(op: BLEOperation.WriteCharacteristic): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                op.characteristic,
                op.value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            op.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            op.characteristic.value = op.value
            gatt.writeCharacteristic(op.characteristic)
        }
    }

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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun signalOperationComplete() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        synchronized(lock) { isProcessing = false }
        Log.d(LOG_TAG, "BLE operation completed (queue size: ${queue.size})")
        processNext()
    }
}
