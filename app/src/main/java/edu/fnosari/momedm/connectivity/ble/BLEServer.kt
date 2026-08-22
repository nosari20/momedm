package edu.fnosari.momedm.connectivity.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.ActivityCompat
import edu.fnosari.momedm.connectivity.ble.characteristics.BLECharacteristic
import edu.fnosari.momedm.connectivity.ble.services.BLEService
import java.util.UUID

/**
 * Represents a Bluetooth Low Energy (BLE) server for managing and handling BLE services and characteristics.
 *
 * This class sets up a BLE server that can manage multiple BLE services, each with its own characteristics.
 * It handles various BLE operations such as initializing the server, handling Bluetooth state, adding services,
 * reading and writing characteristics, and managing BLE advertising.
 *
 * @param context The context used to access system services related to Bluetooth.
 * @param clientLimit The maximum number of clients that can connect to the server.
 * @param includeDeviceName Whether the advertised device name is included in advertise data.
 *
 * @property context The application context.
 * @property clientLimit The maximum number of clients allowed.
 * @property _bluetoothManager The Bluetooth manager responsible for managing Bluetooth connections.
 * @property _bluetoothAdapter The Bluetooth adapter used for handling BLE operations.
 * @property _gattServer The GATT server responsible for managing and handling BLE services and characteristics.
 * @property _connectedDevices A list of currently connected Bluetooth devices.
 * @property _services A list of BLE services added to the server.
 * @property connectedDevices Read-only access to the list of connected devices.
 * @property serverName The name of the Bluetooth adapter used by the server.
 * @property gattServerCallback A callback class responsible for handling BLE server events.
 *
 * @throws BLEException if any Bluetooth-related operation fails due to permission issues or limitations.
 */
class BLEServer(
    private val context: Context,
    private val clientLimit: Int = 5,
    private val callBack: BLEServerCallBack,
    private val includeDeviceName: Boolean = true,
) {

    companion object {
        private const val LOG_TAG = "BLEServer"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val NOTIFY_TIMEOUT_MS = 5000L
    }

    /**
     * Callback interface for BLE events.
     */
    interface BLEServerCallBack {
        /**
         * A central wrote to [characteristic]. [value] is the UTF-8 decode of *this* write request's
         * bytes — use it (not [BLECharacteristic.value]) to read what was written: [BLECharacteristic.value]
         * is a single field shared by every characteristic instance, so with multiple centrals connected
         * concurrently a second write can overwrite it before this callback for the first one runs.
         * [BLECharacteristic.value] is still updated for compatibility with read-back use cases.
         */
        fun onCharacteristicWriteRequest(
            characteristic: BLECharacteristic,
            service: BLEService,
            device: BluetoothDevice,
            value: String
        )
        fun onDeviceConnected(device: BluetoothDevice)
        fun onDeviceDisconnected(device: BluetoothDevice)
        /** ATT MTU negotiated by [device]; payload per notification = mtu - 3. */
        fun onMtuChanged(device: BluetoothDevice, mtu: Int) {}
    }

    /**
     * Enum representing possible exit codes for the BLE server.
     */
    enum class BLEServerExitCode {
        ERROR_BLUETOOTH_NOT_SUPPORTED,
        ERROR_BLUETOOTH_BLE_NOT_SUPPORTED,
        ERROR_BLUETOOTH_NOT_AVAILABLE,
        ERROR_BLUETOOTH_NOT_ENABLED,
        ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED,
        ERROR_BLUETOOTH_ADVERTISE_PERMISSION_NOT_GRANTED,
        ERROR_BLUETOOTH_CLIENT_LIMIT_REACHED,
        ERROR_BLUETOOTH_WRITE_SERVICE_OR_CHARACTERISTIC_NOT_FOUND
    }

    // BluetoothManager instance to manage Bluetooth adapter and connections
    private lateinit var _bluetoothManager: BluetoothManager

    // GATT server instance to handle Bluetooth Low Energy (BLE) connections
    private lateinit var _gattServer: BluetoothGattServer

    // Bluetooth adapter for managing Bluetooth functionality
    private var _bluetoothAdapter: BluetoothAdapter? = null

    // List of currently connected Bluetooth devices
    private var _connectedDevices: MutableList<BluetoothDevice> = mutableStateListOf()

    // List of BLE services managed by this server
    private var _services: MutableList<BLEService> = arrayListOf()

    // Public getter for accessing the list of connected devices
    val connectedDevices: MutableList<BluetoothDevice> get() = _connectedDevices

    // Public getter for the name of the Bluetooth adapter.
    val serverName: String
        get() {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ){
                Log.d(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
                throw BLEException("BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
            }else{
                return _bluetoothAdapter!!.name;
            }
        }

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

     // Callback class responsible for handling BLE server events.
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        /**
         * Handles changes in the connection state of a remote device.
         * Logs connection and disconnection events, enforces client limits, and maintains a list of connected devices.
         */
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Runs on a binder thread — log and bail instead of throwing.
                Log.e(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(LOG_TAG, "Device connected: ${device.name} (${device.address})")

                if (_connectedDevices.size >= clientLimit) {
                    _gattServer.cancelConnection(device)
                    Log.w(LOG_TAG, "Client limit reached, disconnecting ${device.name} (${device.address}): ${BLEServerExitCode.ERROR_BLUETOOTH_CLIENT_LIMIT_REACHED}")
                } else {
                    Log.d(LOG_TAG, "Device added to connected devices.")
                    _connectedDevices.add(device)
                    callBack.onDeviceConnected(device)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(LOG_TAG, "Device disconnected: ${device.name} (${device.address})")
                Log.d(LOG_TAG, "Device removed from connected devices.")
                _connectedDevices.remove(device)
                callBack.onDeviceDisconnected(device)
            }
        }

        /**
         * Handles requests from clients to read a characteristic.
         * Responds with the stored characteristic value.
         */
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            Log.d(LOG_TAG,"Reading ${characteristic.uuid}")
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
                return
            }
            val c = _services.find { it.uuid == characteristic.service.uuid }?.characteristics?.find { it.uuid == characteristic.uuid }

            if (c != null) {
                Log.d(LOG_TAG,"Responding to read of ${characteristic.uuid} (${c.value.length} chars)")
                _gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, c.value.toByteArray())
            }
        }

        /**
         * Handles requests from clients to write to a characteristic.
         * Updates the characteristic value and optionally sends a response if required.
         */
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            //super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

            // UUID + length only: characteristic payloads carry application data (here, protocol
            // frames) and must never reach logcat.
            Log.d(LOG_TAG,"Write request on ${characteristic.uuid} (${value.size} bytes)")
            val serviceSearch = _services.filter { it.characteristics.filter { it.uuid == characteristic.uuid }.isNotEmpty() }
            if(serviceSearch.isNotEmpty()){
                val service = serviceSearch.first()
                val serviceCharacteristicSearch = service.characteristics.filter { it.uuid == characteristic.uuid }
                if(serviceCharacteristicSearch.isNotEmpty()){
                    val serviceCharacteristic = serviceCharacteristicSearch.first()
                    val writtenValue = value.toString(Charsets.UTF_8)
                    Log.d(LOG_TAG, "${device.address} requesting to write to characteristic ${serviceCharacteristic.name} (${serviceCharacteristic.uuid}) from service ${service.name} (${service.uuid}) (${value.size} bytes)")
                    serviceCharacteristic.value = writtenValue
                    callBack.onCharacteristicWriteRequest(serviceCharacteristic, service, device, writtenValue)

                    if(responseNeeded){
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Log.e(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
                            return
                        }
                        _gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                }
            }

        }

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
    }



    /**
     * Initializes the BLE server by setting up Bluetooth and GATT server.
     */
    init {
        Log.d(LOG_TAG,"Initializing BLE Server")
        initBluetooth()
        initGattServer()

    }

    /**
     * Initializes the Bluetooth components required for the BLE server.
     *
     * @throws BLEException if Bluetooth is not supported, BLE is not supported, Bluetooth is unavailable, or Bluetooth is disabled.
     */
    @Throws(BLEException::class)
    private fun initBluetooth() {

        // Check if bluetooth is supported
        Log.d(LOG_TAG,"Check if bluetooth is supported")
        val bluetoothAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        if(!bluetoothAvailable){
            Log.d(LOG_TAG,"Bluetooth not supported: ${BLEServerExitCode.ERROR_BLUETOOTH_NOT_SUPPORTED}")
            throw BLEException("Bluetooth not supported: ${BLEServerExitCode.ERROR_BLUETOOTH_NOT_SUPPORTED}")
        }
        Log.d(LOG_TAG,"Bluetooth supported")

        // Check if BLE is supported
        Log.d(LOG_TAG,"Check if bluetooth BLE is supported")
        val bluetoothLEAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        if(!bluetoothLEAvailable){
            Log.d(LOG_TAG,"Bluetooth BLE not supported: ${BLEServerExitCode.ERROR_BLUETOOTH_BLE_NOT_SUPPORTED}")
            throw BLEException("Bluetooth BLE not supported: ${BLEServerExitCode.ERROR_BLUETOOTH_BLE_NOT_SUPPORTED}")
        }
        Log.d(LOG_TAG,"Bluetooth BLE supported")

        // Get bluetooth manager
        _bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        _bluetoothAdapter = _bluetoothManager.adapter

        // Check if bluetooth is available
        Log.d(LOG_TAG,"Check if bluetooth is available")
        if (_bluetoothAdapter == null) {
            Log.d(LOG_TAG,"Bluetooth not available: ${BLEServerExitCode.ERROR_BLUETOOTH_NOT_AVAILABLE}")
            throw BLEException("Bluetooth not available: ${BLEServerExitCode.ERROR_BLUETOOTH_NOT_AVAILABLE}")
        }else{
            Log.d(LOG_TAG,"Bluetooth available")
            // Check if bluetooth is enabled
            Log.d(LOG_TAG,"Check if bluetooth is enabled")
            if (!_bluetoothAdapter!!.isEnabled) {
                Log.d(LOG_TAG,"Bluetooth not enabled: ${BLEServerExitCode.ERROR_BLUETOOTH_NOT_ENABLED}")
                throw BLEException("Bluetooth not enabled: ${BLEServerExitCode.ERROR_BLUETOOTH_NOT_ENABLED}")
            }else{
                Log.d(LOG_TAG,"Bluetooth enabled")
            }
        }
    }

    /**
     * Initializes the GATT server used for handling BLE communication.
     *
     * Sets up the necessary BluetoothGattServer and handles various BLE events like connection state changes,
     * characteristic read and write requests.
     *
     * @throws BLEException if permission is not granted to use Bluetooth.
     */
    @Throws(BLEException::class)
    private fun initGattServer(){
        Log.d(LOG_TAG,"Creating GATT server")
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw BLEException("BLUETOOTH_CONNECT permission not granted.")
        }
        this._gattServer = _bluetoothManager.openGattServer(context, gattServerCallback)

    }

    /**
     * Adds a BLE service to the server.
     *
     * @param service The BLE service to be added.
     */
    fun addService(service: BLEService){
        this._services += service
    }

    /**
     * Starts the BLE server, enabling advertising and handling client connections.
     *
     * Sets up the necessary _services and starts advertising them using Bluetooth LE.
     *
     * @throws BLEException if permission is not granted to advertise or if there are issues during setup.
     */
    @Throws(BLEException::class)
    fun startServer(){

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
            throw BLEException("BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
        }

        // Setup _services
        _gattServer.clearServices()

        for(service in _services){

            Log.d(LOG_TAG,"Adding service ${service.uuid} (type:${service.type})")


            val s = BluetoothGattService(
                service.uuid,
                service.type
            )

            for(characteristic in service.characteristics){

                Log.d(LOG_TAG,"Adding characteristic ${characteristic.uuid}")

                val c = BluetoothGattCharacteristic(
                    characteristic.uuid,
                    characteristic.properties,
                    characteristic.permissions
                )

                if (characteristic.notifies) {
                    c.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                }

                s.addCharacteristic(c)
            }

            _gattServer.addService(s)
        }


        Log.d(LOG_TAG, "Starting server")
        val advertiser = _bluetoothAdapter!!.bluetoothLeAdvertiser!!
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder().setIncludeDeviceName(includeDeviceName)


        for(service in _services) {
            data.addServiceUuid(ParcelUuid(service.uuid))
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(LOG_TAG,"BLUETOOTH_ADVERTISE permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_ADVERTISE_PERMISSION_NOT_GRANTED}")
            throw BLEException("BLUETOOTH_ADVERTISE permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_ADVERTISE_PERMISSION_NOT_GRANTED}")

        }
        _advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                super.onStartSuccess(settingsInEffect)
                Log.d(LOG_TAG, "Advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                Log.e(LOG_TAG, "Advertising failed: $errorCode")
            }
        }
        advertiser.startAdvertising(settings, data.build(), _advertiseCallback)

    }

    /**
     * Stops the BLE server and stops advertising.
     *
     * This function will clear _services and stop any ongoing BLE advertising.
     *
     * @throws BLEException if an error occurs while stopping the server.
     */
    @Throws(BLEException::class)
    fun stopServer(){
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
            throw BLEException("BLUETOOTH_CONNECT permission not granted: ${BLEServerExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
            _advertiseCallback?.let { _bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
            _advertiseCallback = null
        }
        _notifyHandler.removeCallbacks(_notifyTimeout)
        synchronized(_notifyLock) {
            _notifyQueue.clear()
            _notifyInFlight = false
        }
        for (device in _connectedDevices){
            _gattServer.cancelConnection(device)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            _gattServer.close()
            _gattServer.clearServices()
        }, 1000)

    }


    /**
     * Updates the value of a specified characteristic within a given BLE service
     * and notifies all connected devices of the change.
     *
     * @param service The BLE service containing the characteristic.
     * @param characteristic The characteristic to be updated and notified.
     * @throws BLEException if the BLUETOOTH_CONNECT permission is not granted.
     */
    @Throws(BLEException::class)
    fun updateCharacteristic(service: BLEService, characteristic: BLECharacteristic){
        for (device in _connectedDevices) notifyDevice(device, service, characteristic)
    }

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


}