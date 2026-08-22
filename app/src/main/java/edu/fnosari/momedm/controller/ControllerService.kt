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
        } catch (e: Throwable) {
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
