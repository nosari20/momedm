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
import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Foreground service hosting the BLE GATT server and all managed-device sessions. */
class ControllerService : Service() {
    companion object {
        private const val LOG_TAG = "ControllerService"
        const val CHANNEL_ID = "controller"
        const val NOTIFICATION_ID = 2
        /** Intent extra telling a running service to reload its identity and restart the BLE server. */
        const val EXTRA_RELOAD_IDENTITY = "reload_identity"
        fun start(context: Context) = context.startForegroundService(Intent(context, ControllerService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, ControllerService::class.java)) }
        /** Tells the already-running service to re-read the (just-rotated) secret and restart the BLE server with it. */
        fun reloadIdentity(context: Context) = context.startForegroundService(Intent(context, ControllerService::class.java).putExtra(EXTRA_RELOAD_IDENTITY, true))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gatt = MdmService()
    private var server: BLEServer? = null
    private var sessions: SessionManager? = null
    /** The session-tick loop launched by the current [startServer] call; cancelled before restarting so a stale loop can't keep ticking an orphaned [SessionManager]. */
    private var tickJob: Job? = null
    /** Collects [ControllerLink.prefsChanged] and re-pushes SET_PREFS to every online child; cancelled before restarting for the same reason as [tickJob]. */
    private var prefsJob: Job? = null
    /** Mutated from binder threads (device connect/disconnect callbacks), read from the main scope's coroutines. */
    private val devicesByKey = ConcurrentHashMap<String, BluetoothDevice>()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_RELOAD_IDENTITY, false) == true) {
            Log.d(LOG_TAG, "Reload identity requested — restarting BLE server")
            scope.launch { restartServer() }
        }
        return START_STICKY
    }

    /** Tears down the current BLE server/session state and starts fresh, picking up whatever identity [ControllerPrefs.ensureIdentity] now returns. */
    private suspend fun restartServer() {
        tickJob?.cancel(); tickJob = null
        prefsJob?.cancel(); prefsJob = null
        try { server?.stopServer() } catch (e: BLEException) { Log.w(LOG_TAG, "restart stop failed: ${e.message}") }
        server = null
        devicesByKey.clear()
        sessions = null
        ControllerLink.commander = null
        ControllerLink.online.value = emptySet()
        startServer()
    }

    private suspend fun startServer() {
        val identity = prefs.ensureIdentity()
        val sm = SessionManager(identity.secretBytes, object : SessionManager.Transport {
            override fun sendFrame(key: String, frame: String) {
                val device = devicesByKey[key] ?: return
                // Staging the outgoing frame on the shared `gatt.cmd.value` field is safe only because
                // every call into SessionManager (and therefore every sendFrame) runs inside its monitor
                // (@Synchronized), so no other frame can be staged here concurrently.
                gatt.cmd.value = frame
                try { server?.notifyDevice(device, gatt, gatt.cmd) } catch (e: BLEException) { Log.w(LOG_TAG, "notify failed: ${e.message}") }
            }
            override fun disconnect(key: String) { devicesByKey[key]?.let { server?.disconnectDevice(it) } }
        }, object : SessionManager.Events {
            override fun onAuthenticated(key: String, hello: Message.Hello) {
                Log.d(LOG_TAG, "Authenticated ${hello.deviceId} (${hello.model})")
                ControllerLink.logEvent(ControllerLink.LinkEvent.Kind.AUTHENTICATED, "${hello.model} (${hello.deviceId.take(8)})")
                // `sessions` (not the local `sm`, which isn't in scope while its own initializer — this
                // anonymous object — is still being constructed) is guaranteed set by the time this callback
                // actually fires, since it only fires after `sm` finishes construction and `sessions = sm` runs.
                scope.launch { registry.upsertSeen(hello.deviceId, hello.model, System.currentTimeMillis()); refreshOnline(); sessions?.let { pushPrefs(it, hello.deviceId) } }
            }
            override fun onMessage(key: String, deviceId: String, m: Message) {
                when (m) {
                    // Sanitised on the way in, exactly as the child sanitises a Cmd on the way in. A
                    // Status carries the whole SafetyConfig back (deliberately — the parent keeps no
                    // copy and has to merge against it), and it is written verbatim into a DataStore
                    // blob on every push, so an inflated appConfigs would have the parent rewriting a
                    // very large preferences file every few minutes.
                    is Message.Status -> scope.launch {
                        val safe = m.copy(safety = m.safety?.sanitized())
                        registry.updateStatus(deviceId, safe, System.currentTimeMillis())
                    }
                    is Message.Result -> ControllerLink.results.tryEmit(deviceId to m)
                    is Message.Ping -> scope.launch { registry.upsertSeen(deviceId, registry.get(deviceId)?.model ?: "?", System.currentTimeMillis()) }
                    is Message.Apps -> ControllerLink.apps.tryEmit(deviceId to m)
                    is Message.Schema -> ControllerLink.schemas.tryEmit(deviceId to m)
                    else -> Log.w(LOG_TAG, "Unexpected ${m::class.simpleName} from $deviceId")
                }
            }
            override fun onDropped(key: String, deviceId: String?) {
                // deviceId == null means the central connected but never completed the handshake before
                // the auth timeout — the signature of a child holding a different shared secret. Worth
                // surfacing, because in the device list that is indistinguishable from a child that
                // never showed up at all.
                if (deviceId == null) ControllerLink.logEvent(ControllerLink.LinkEvent.Kind.REJECTED, key)
                refreshOnline()
            }
        })
        sessions = sm
        ControllerLink.commander = { deviceId, cmd -> sm.send(deviceId, cmd) }
        prefsJob?.cancel()
        prefsJob = scope.launch { ControllerLink.prefsChanged.collect { sm.onlineDeviceIds().forEach { pushPrefs(sm, it) } } }
        try {
            val s = BLEServer(this, clientLimit = 7, callBack = object : BLEServer.BLEServerCallBack {
                override fun onDeviceConnected(device: BluetoothDevice) {
                    devicesByKey[device.address] = device; sm.onConnected(device.address)
                    ControllerLink.logEvent(ControllerLink.LinkEvent.Kind.CONNECTED, device.address)
                }
                override fun onDeviceDisconnected(device: BluetoothDevice) {
                    devicesByKey.remove(device.address); sm.onDisconnected(device.address)
                    ControllerLink.logEvent(ControllerLink.LinkEvent.Kind.DISCONNECTED, device.address)
                }
                override fun onCharacteristicWriteRequest(characteristic: BLECharacteristic, service: BLEService, device: BluetoothDevice, value: String): Boolean {
                    // A frame from a link we hold no session for (our server restarted / the session was
                    // dropped while the BLE link stayed up) is rejected at GATT level: notifications to such
                    // a stale client do not reach it, but a failed write does, and makes it reconnect.
                    if (characteristic.uuid != MdmGatt.RSP_UUID) return true
                    val accepted = sm.onFrame(device.address, value)
                    if (!accepted) ControllerLink.logEvent(ControllerLink.LinkEvent.Kind.REJECTED, device.address)
                    return accepted
                }
            }, includeDeviceName = false)
            s.addService(gatt); s.startServer(); server = s
            ControllerLink.advertising.value = true
            ControllerLink.logEvent(ControllerLink.LinkEvent.Kind.ADVERTISING, "started")
            Log.d(LOG_TAG, "GATT server started")
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "Server start failed: ${e.message}"); ControllerLink.logEvent(ControllerLink.LinkEvent.Kind.ERROR, e.message ?: "BLE error"); ControllerLink.errors.tryEmit(e.message ?: "BLE error"); stopSelf(); return
        }
        tickJob = scope.launch { while (isActive) { delay(1_000); sm.tick(System.currentTimeMillis()) } }
    }

    /** Sends the current [ControllerPrefs.childPrefsNow] to [deviceId] via [sm], logging (not throwing) if it's offline. */
    private suspend fun pushPrefs(sm: SessionManager, deviceId: String) {
        val cmd = Message.Cmd(java.util.UUID.randomUUID().toString().substring(0, 8), CmdType.SET_PREFS, prefs = prefs.childPrefsNow())
        if (!sm.send(deviceId, cmd)) Log.w(LOG_TAG, "SET_PREFS not sent to $deviceId (offline)")
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
        tickJob?.cancel(); prefsJob?.cancel()
        ControllerLink.commander = null
        ControllerLink.advertising.value = false; ControllerLink.online.value = emptySet()
        ControllerLink.logEvent(ControllerLink.LinkEvent.Kind.ADVERTISING, "stopped")
        try { server?.stopServer() } catch (e: BLEException) { Log.w(LOG_TAG, "stop failed: ${e.message}") }
        scope.cancel()
        super.onDestroy()
    }
}
