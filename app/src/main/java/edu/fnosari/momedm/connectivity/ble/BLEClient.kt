package edu.fnosari.momedm.connectivity.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import edu.fnosari.momedm.connectivity.ble.characteristics.BLECharacteristic
import edu.fnosari.momedm.connectivity.ble.services.BLEService

/**
 * BLEClient manages Bluetooth Low Energy (BLE) connections to a specific _server.
 *
 * This class scans for BLE devices, connects to a specified _server, and listens to
 * specific BLE services and characteristics.
 *
 * @param context The application context required to access system Bluetooth services.
 * @param serverName Advertised device name to match, or null to accept any device passing [serviceUuid].
 * @param servicesToListen A list of BLE services to monitor for characteristic changes.
 * @param callBack A callback interface to notify when important BLE events occur.
 * @param serviceUuid When non-null, the scan is filtered on this advertised service UUID.
 */
class BLEClient(
    private val context: Context,
    private val serverName: String?,
    private val servicesToListen: List<BLEService>,
    val callBack: BLEClientCallBack,
    private val serviceUuid: UUID? = null,
) {
    companion object {
        private const val LOG_TAG = "BLEClient"

        /**
         * Client Characteristic Configuration Descriptor (CCCD) UUID (0x2902).
         * Writing ENABLE_NOTIFICATION_VALUE to it is what actually makes a
         * peripheral start pushing notifications — setCharacteristicNotification
         * alone only updates the local stack.
         */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 517
        private const val DEFAULT_MTU = 23
    }

    /**
     * Callback interface for BLE events.
     */
    interface BLEClientCallBack {
        fun onCharacteristicChanged(characteristic: BLECharacteristic, service: BLEService)
        /**
         * Fired once per connection, after MTU negotiation completes and NOTIFY
         * characteristics have been subscribed (their CCCD writes enqueued).
         */
        fun onConnected()
        fun onDisconnected()
        /**
         * Negotiated ATT MTU (payload = mtu - 3). The first call always precedes
         * [onConnected]. May fire again later for a remote-initiated MTU change on an
         * already-connected link — that later call does not re-trigger [onConnected].
         */
        fun onMtuChanged(mtu: Int) {}
    }

    /**
     * Enum representing possible BLE client exit codes.
     */
    enum class BLEClientExitCode {
        ERROR_BLUETOOTH_NOT_SUPPORTED,
        ERROR_BLUETOOTH_BLE_NOT_SUPPORTED,
        ERROR_BLUETOOTH_NOT_AVAILABLE,
        ERROR_BLUETOOTH_NOT_ENABLED,
        ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED,
        ERROR_BLUETOOTH_SCAN_PERMISSION_NOT_GRANTED,
        ERROR_BLUETOOTH_SCAN_TIMEOUT,
        ERROR_BLUETOOTH_WRITE_SERVICE_OR_CHARACTERISTIC_NOT_FOUND
    }


    /**
     * The Bluetooth device representing the _server to connect to.
     */
    private var _server: BluetoothDevice? = null

    /**
     * The BluetoothManager used to manage the device's Bluetooth adapter and connections.
     */
    private lateinit var _bluetoothManager: BluetoothManager

    /**
     * The Bluetooth adapter used for interacting with Bluetooth devices.
     * It can be null if the device does not support Bluetooth.
     */
    private var _bluetoothAdapter: BluetoothAdapter? = null

    /**
     * The Bluetooth GATT _server instance to handle Bluetooth Low Energy (BLE) connections
     * It can be null if the device is not connected
     */
    private var _gattServer: BluetoothGatt? = null

    /**
     * The Bluetooth GATT operations queue to manage BLE operations.
     * It can be null if the device is not connected
     */
    private var _operationQueue: BLEOperationQueue? = null

    /**
     * A list of Bluetooth GATT services that are being monitored for characteristic changes.
     * Initialized empty so reads/writes issued before a scan don't crash.
     */
    private var _listeningServices: MutableList<BluetoothGattService> = mutableListOf()

    /**
     * NOTIFY characteristics discovered during [BluetoothGattCallback.onServicesDiscovered],
     * remembered so their CCCD subscription can be enqueued after MTU negotiation completes
     * instead of racing it — [BluetoothGatt] allows only one outstanding ATT operation per
     * connection, and `requestMtu` is not a queued [BLEOperationQueue] op. Cleared on
     * [closeGatt] and [startScan].
     */
    private var _notifyCharacteristics: MutableList<BluetoothGattCharacteristic> = mutableListOf()

    /**
     * Whether [BLEClientCallBack.onConnected] has already fired for the current connection.
     * `onMtuChanged` can fire again later for a remote-initiated MTU change; that must still
     * reach [BLEClientCallBack.onMtuChanged] but must not re-fire `onConnected`.
     */
    private var _connectedReported: Boolean = false

    /**
     * The Bluetooth Low Energy scanner used to search for available BLE devices.
     * Resolved lazily in [startScan] rather than [initBluetooth]: Bluetooth can be toggled
     * off/on after this client is constructed, and fetching the scanner while the adapter is
     * null or disabled would NPE. Kept around so [stopScan] can use the same instance.
     */
    private var _bluetoothLeScanner: BluetoothLeScanner? = null

    /**
     * Indicates whether a BLE scan is currently in progress.
     */
    private var _isScanning: Boolean = false

    /**
     * Handler used to manage scan timeout operations on the main UI thread.
     */
    private val _scanHandler = Handler(Looper.getMainLooper())

    /**
     * The maximum scan duration before timing out (30 seconds).
     */
    private val _scanTimeout: Long = 30000

    /**
     * Callback for handling BLE scan results.
     */
    private val _scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // Runs on a binder thread — log and bail instead of throwing (which would crash the app).
                    Log.e(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
                    return@let
                }
                val deviceName = device.name ?: result.scanRecord?.deviceName

                Log.d(LOG_TAG, "Device found: ${deviceName} (${device.address})")

                // If the found device matches the expected _server name, attempt to connect
                val nameMatches = serverName == null || device.name == serverName || deviceName == serverName
                if (nameMatches && _server == null) {
                    Log.d(LOG_TAG, "Device name equals to _server name: ${device.name}, connecting.")
                    stopScan()
                    _server = device
                    connectToServer()
                }
            }
        }
    }
    /**
     * Callback for handling Bluetooth GATT (Generic Attribute Profile) events such as connection
     * state changes and characteristic updates.
     */
    private val _gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            // A non-success status (e.g. the infamous 133) can arrive even with
            // newState == CONNECTED. Treat it as a failed link: close and report.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(LOG_TAG, "GATT connection state change with error status $status; closing.")
                closeGatt()
                callBack.onDisconnected()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (gatt != null) {
                    _gattServer = gatt
                    _operationQueue = BLEOperationQueue(gatt)

                    Log.d(LOG_TAG, "Connected to GATT _server.")
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.e(
                            LOG_TAG,
                            "BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}"
                        )
                        return
                    }
                    // Start discovering available services on the connected GATT _server
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Always close on disconnect — otherwise the BluetoothGatt and its
                // client interface leak, eventually causing status-133 failures.
                Log.d(LOG_TAG, "Disconnected from GATT _server.")
                closeGatt()
                callBack.onDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                Log.w(LOG_TAG, "Service discovery failed: $status")
                return
            }
            _notifyCharacteristics.clear()
            gatt.services?.forEach { service ->
                Log.d(LOG_TAG, "Service discovered: ${service.uuid}")
                val serviceToListen = servicesToListen.firstOrNull { it.uuid == service.uuid } ?: return@forEach
                Log.d(LOG_TAG, "Service ${service.uuid} in services to listen")
                _listeningServices.add(service)
                service.characteristics.forEach { characteristic ->
                    val sCharacteristic = serviceToListen.characteristics.firstOrNull { it.uuid == characteristic.uuid }
                    if (sCharacteristic != null && sCharacteristic.notifies) {
                        Log.d(LOG_TAG, "Will subscribe to ${sCharacteristic.name} (${sCharacteristic.uuid})")
                        _notifyCharacteristics.add(characteristic)
                    }
                }
            }
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(LOG_TAG, "BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
                return
            }
            // MTU negotiation is not a queued GATT op, and BluetoothGatt allows only one
            // outstanding ATT operation per connection — request it before enqueuing any
            // CCCD write, or the write can be silently dropped/fail. CCCD writes for
            // _notifyCharacteristics are enqueued from onMtuChanged (or right here if
            // requestMtu fails to even start).
            if (!gatt.requestMtu(REQUESTED_MTU)) {
                Log.w(LOG_TAG, "requestMtu failed; assuming default MTU")
                subscribeToNotifyCharacteristics(gatt)
                callBack.onMtuChanged(DEFAULT_MTU)
                reportConnectedOnce()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val effective = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            Log.d(LOG_TAG, "MTU changed: $mtu (status $status) -> using $effective")
            if (!_connectedReported) {
                subscribeToNotifyCharacteristics(gatt)
            }
            callBack.onMtuChanged(effective)
            reportConnectedOnce()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(LOG_TAG, "Descriptor ${descriptor.uuid} write status: $status")
            _operationQueue?.signalOperationComplete()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // UUID + length only: characteristic payloads carry application data (here, protocol
            // frames) and must never reach logcat.
            Log.d(LOG_TAG, "Characteristic ${characteristic.uuid} changed (${value.size} bytes)")
            val serviceSearch = servicesToListen.filter { it.characteristics.filter { it.uuid == characteristic.uuid }.isNotEmpty() }
            if(serviceSearch.isNotEmpty()){
                val service = serviceSearch.first()
                val serviceCharacteristicSearch = service.characteristics.filter { it.uuid == characteristic.uuid }
                if(serviceCharacteristicSearch.isNotEmpty()){
                    val serviceCharacteristic = serviceCharacteristicSearch.first()
                    Log.d(LOG_TAG, "Characteristic ${serviceCharacteristic.name} (${serviceCharacteristic.uuid}) from service ${service.name} (${service.uuid}) changed (${value.size} bytes)")
                    serviceCharacteristic.value = value.toString(Charsets.UTF_8)
                    callBack.onCharacteristicChanged(serviceCharacteristic, service)
                }
            }

        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(LOG_TAG, "Characteristic ${characteristic.uuid} write status: $status")
            // Advance the queue so the next enqueued operation can run.
            _operationQueue?.signalOperationComplete()
        }


        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d(LOG_TAG, "Characteristic ${characteristic.uuid} read status: $status")
            // Advance the queue so the next enqueued operation can run.
            _operationQueue?.signalOperationComplete()

            val serviceSearch = servicesToListen.filter { it.characteristics.filter { it.uuid == characteristic.uuid }.isNotEmpty() }
            if(serviceSearch.isNotEmpty()){
                val service = serviceSearch.first()
                val serviceCharacteristicSearch = service.characteristics.filter { it.uuid == characteristic.uuid }
                if(serviceCharacteristicSearch.isNotEmpty()){
                    val serviceCharacteristic = serviceCharacteristicSearch.first()
                    Log.d(LOG_TAG, "Characteristic ${serviceCharacteristic.name} (${serviceCharacteristic.uuid}) from service ${service.name} (${service.uuid}) read (${value.size} bytes)")
                    serviceCharacteristic.value = value.toString(Charsets.UTF_8)
                    callBack.onCharacteristicChanged(serviceCharacteristic, service)
                }
            }

        }
    }


    init {
        Log.d(LOG_TAG,"Initializing BLE Client")
        initBluetooth()
    }

    /**
     * Initializes the Bluetooth components required for the BLE _server.
     *
     * This includes checking if Bluetooth and BLE are supported, available, and enabled,
     * and setting up the necessary BluetoothManager and BluetoothAdapter.
     *
     * @throws BLEException if any error occurs during initialization.
     */
    @Throws(BLEException::class)
    private fun initBluetooth() {

        // Check if bluetooth is supported
        Log.d(LOG_TAG,"Check if bluetooth is supported")
        val bluetoothAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        if(!bluetoothAvailable){
            Log.d(LOG_TAG,"Bluetooth not supported: ${BLEClientExitCode.ERROR_BLUETOOTH_NOT_SUPPORTED}")
            throw BLEException("Bluetooth not supported: ${BLEClientExitCode.ERROR_BLUETOOTH_NOT_SUPPORTED}")
        }
        Log.d(LOG_TAG,"Bluetooth supported")

        // Check if BLE is supported
        Log.d(LOG_TAG,"Check if bluetooth BLE is supported")
        val bluetoothLEAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        if(!bluetoothLEAvailable){
            Log.d(LOG_TAG,"Bluetooth BLE not supported: ${BLEClientExitCode.ERROR_BLUETOOTH_BLE_NOT_SUPPORTED}")
            throw BLEException("Bluetooth BLE not supported: ${BLEClientExitCode.ERROR_BLUETOOTH_BLE_NOT_SUPPORTED}")
        }
        Log.d(LOG_TAG,"Bluetooth BLE supported")

        // Get bluetooth manager
        _bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        _bluetoothAdapter = _bluetoothManager.adapter

        // Check if bluetooth is available
        Log.d(LOG_TAG,"Check if bluetooth is available")
        if (_bluetoothAdapter == null) {
            Log.d(LOG_TAG,"Bluetooth not available: ${BLEClientExitCode.ERROR_BLUETOOTH_NOT_AVAILABLE}")
            throw BLEException("Bluetooth not available: ${BLEClientExitCode.ERROR_BLUETOOTH_NOT_AVAILABLE}")
        }else{
            Log.d(LOG_TAG,"Bluetooth available")
            // Check if bluetooth is enabled
            Log.d(LOG_TAG,"Check if bluetooth is enabled")
            if (!_bluetoothAdapter!!.isEnabled) {
                Log.d(LOG_TAG,"Bluetooth not enabled: ${BLEClientExitCode.ERROR_BLUETOOTH_NOT_ENABLED}")
                throw BLEException("Bluetooth not enabled: ${BLEClientExitCode.ERROR_BLUETOOTH_NOT_ENABLED}")
            }else{
                Log.d(LOG_TAG,"Bluetooth enabled")
            }
        }
    }



    /**
     * Connect to GATT (Generic Attribute Profile) _server.
     * @throws BLEException if any error occurs.
     */
    private fun connectToServer() {
        if(_server != null){
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
                throw BLEException("BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
            }
            Log.d(LOG_TAG, "Connecting to _server: ${_server!!.name} (${_server!!.address})")
            _server!!.connectGatt(context, false, _gattCallback)
        }

    }

    /**
     * Starts scanning for BLE devices.
     * @throws BLEException if any error occurs.
     */
    @Throws(BLEException::class)
    fun startScan(onTimeout: (() -> Unit)? = null) {
        if(isScanning()) return

        // Resolved here (not in initBluetooth) because Bluetooth can be toggled off/on after
        // this client is constructed; grabbing the scanner while the adapter is null/disabled
        // would NPE. Checked before any state mutation so a disabled adapter cleanly throws
        // instead of leaving _isScanning stuck true.
        val scanner = _bluetoothAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: throw BLEException("Bluetooth not enabled: ${BLEClientExitCode.ERROR_BLUETOOTH_NOT_ENABLED}")

        _isScanning = true
        _listeningServices = mutableListOf()
        _notifyCharacteristics = mutableListOf()
        _connectedReported = false
        _server = null
        _gattServer = null

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(LOG_TAG,"BLUETOOTH_SCAN permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_SCAN_PERMISSION_NOT_GRANTED}")
            throw BLEException("BLUETOOTH_SCAN permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_SCAN_PERMISSION_NOT_GRANTED}")
        }

        _bluetoothLeScanner = scanner

        _scanHandler.postDelayed({
            if(isScanning()){
                stopScan()
                Log.d(LOG_TAG, "Scan timeout: ${BLEClientExitCode.ERROR_BLUETOOTH_SCAN_TIMEOUT}")
                if (onTimeout != null) {
                    onTimeout()
                }
            }
        }, _scanTimeout)

        Log.d(LOG_TAG, "Starting device scan, looking for name=$serverName service=$serviceUuid")

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters: List<ScanFilter>? = serviceUuid?.let { listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
        scanner.startScan(filters, scanSettings, _scanCallback)


    }

    /**
     * Stops an in-flight scan and cancels the pending scan-timeout callback. Idempotent — safe
     * to call when no scan is in progress or no scan was ever started. Never throws: a missing
     * permission or a failure to reach the platform scanner (e.g. Bluetooth turned off mid-scan)
     * is logged and swallowed rather than crashing the caller.
     */
    fun stopScan() {
        _scanHandler.removeCallbacksAndMessages(null)
        if(!isScanning()) return
        _isScanning = false
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(LOG_TAG,"BLUETOOTH_SCAN permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_SCAN_PERMISSION_NOT_GRANTED}")
            return
        }
        Log.d(LOG_TAG, "Stopping device scan")
        try {
            _bluetoothLeScanner?.stopScan(_scanCallback)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "stopScan failed: ${e.message}")
        }
    }

    /**
     * Checks if a BLE scan is currently in progress.
     *
     * @return true if scanning, false otherwise.
     */
    private fun isScanning(): Boolean {
        return _isScanning
    }

    /**
     * Disconnect from GATT server
     * @throws BLEException if any error occurs.
     */
    fun disconnect() {
        if (_gattServer != null) {
            Log.d(LOG_TAG, "Disconnecting from server.")
            closeGatt()
        }
    }

    /**
     * Closes the GATT client and resets all per-connection state. Idempotent.
     * [BluetoothGatt.close] requires no runtime permission.
     */
    private fun closeGatt() {
        _gattServer?.close()
        _gattServer = null
        _operationQueue = null
        _listeningServices = mutableListOf()
        _notifyCharacteristics = mutableListOf()
        _connectedReported = false
        _server = null
    }

    /**
     * Enqueues the CCCD subscription for every NOTIFY characteristic remembered from
     * [BluetoothGattCallback.onServicesDiscovered]. Must only be called once MTU
     * negotiation has been requested/settled — see [_notifyCharacteristics].
     */
    private fun subscribeToNotifyCharacteristics(gatt: BluetoothGatt) {
        _notifyCharacteristics.forEach { characteristic ->
            Log.d(LOG_TAG, "Subscribing to ${characteristic.uuid}")
            enableNotifications(gatt, characteristic)
        }
    }

    /** Fires [BLEClientCallBack.onConnected] at most once per connection. */
    private fun reportConnectedOnce() {
        if (_connectedReported) return
        _connectedReported = true
        callBack.onConnected()
    }

    /**
     * Subscribes to notifications/indications for a characteristic.
     *
     * Enabling notifications is a two-step handshake: tell the local stack with
     * [BluetoothGatt.setCharacteristicNotification], then write the CCCD
     * descriptor (0x2902) so the peripheral actually starts pushing updates.
     * Skipping the descriptor write is why notifications "silently" never arrive.
     */
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(LOG_TAG, "BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
            return
        }

        gatt.setCharacteristicNotification(characteristic, true)

        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(LOG_TAG, "Characteristic ${characteristic.uuid} has no CCCD (0x2902); notifications may not be delivered")
            return
        }

        _operationQueue?.enqueue(BLEOperation.WriteDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
        Log.d(LOG_TAG, "Queued CCCD write for ${characteristic.uuid}")
    }

    fun writeCharacteristic(service: BLEService, characteristic: BLECharacteristic) {

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
            throw BLEException("BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
        }
        try {
            val s = _listeningServices.filter { it.uuid == service.uuid }.first()
            val c = s.characteristics.filter { it.uuid == characteristic.uuid }.first()
            Log.d(LOG_TAG, "Writing to characteristic ${characteristic.name} (${characteristic.uuid}) from service ${service.name} (${service.uuid}) (${characteristic.value.length} chars)")
            _operationQueue!!.enqueue(BLEOperation.WriteCharacteristic(c, characteristic.value.toByteArray()))
        }catch (e: NoSuchElementException){
            Log.d(LOG_TAG,"Service ${service.name} (${service.uuid}) or characteristic ${characteristic.name} ( ${characteristic.uuid}) not found: ${BLEClientExitCode.ERROR_BLUETOOTH_WRITE_SERVICE_OR_CHARACTERISTIC_NOT_FOUND}")
            //Log.d(LOG_TAG)
        }

    }

    fun readCharacteristic(service: BLEService, characteristic: BLECharacteristic) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(LOG_TAG,"BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
            throw BLEException("BLUETOOTH_CONNECT permission not granted: ${BLEClientExitCode.ERROR_BLUETOOTH_CONNECT_PERMISSION_NOT_GRANTED}")
        }
        try {
            val s = _listeningServices.filter { it.uuid == service.uuid }.first()
            val c = s.characteristics.filter { it.uuid == characteristic.uuid }.first()
            _operationQueue!!.enqueue(BLEOperation.ReadCharacteristic(c))
        }catch (e: NoSuchElementException){
            Log.d(LOG_TAG,"Service ${service.name} (${service.uuid}) or characteristic ${characteristic.name} ( ${characteristic.uuid}) not found: ${BLEClientExitCode.ERROR_BLUETOOTH_WRITE_SERVICE_OR_CHARACTERISTIC_NOT_FOUND}")
        }

    }

}
