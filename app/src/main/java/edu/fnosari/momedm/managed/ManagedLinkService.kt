package edu.fnosari.momedm.managed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import edu.fnosari.momedm.R
import edu.fnosari.momedm.connectivity.ble.BLEClient
import edu.fnosari.momedm.connectivity.ble.BLEException
import edu.fnosari.momedm.connectivity.ble.characteristics.BLECharacteristic
import edu.fnosari.momedm.connectivity.ble.services.BLEService
import edu.fnosari.momedm.link.MdmGatt
import edu.fnosari.momedm.link.MdmService
import edu.fnosari.momedm.managed.ManagedLinkState.LinkState
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.ManagedEndpoint
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service owning the BLE link to the controller: scan → connect → handshake → commands.
 * Reconnects with backoff (2 s → 30 s). Pushes STATUS on auth, on change, and every 5 minutes.
 */
class ManagedLinkService : Service() {
    companion object {
        private const val LOG_TAG = "ManagedLinkService"
        const val CHANNEL_ID = "managed_link"
        const val NOTIFICATION_ID = 1
        const val EXTRA_FROM_BOOT = "from_boot"
        private const val STATUS_PERIOD_MS = 5 * 60_000L
        private const val BACKOFF_MIN_MS = 2_000L
        private const val BACKOFF_MAX_MS = 30_000L

        fun start(context: Context, fromBoot: Boolean = false) {
            context.startForegroundService(Intent(context, ManagedLinkService::class.java).putExtra(EXTRA_FROM_BOOT, fromBoot))
        }
        fun stop(context: Context) { context.stopService(Intent(context, ManagedLinkService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: ManagedPrefs
    private lateinit var policy: PolicyManager
    private lateinit var status: StatusCollector
    private lateinit var executor: CommandExecutor
    private val gatt = MdmService()
    private var client: BLEClient? = null
    private var endpoint: ManagedEndpoint? = null
    private var mtu = 23
    private var backoffMs = BACKOFF_MIN_MS
    private var statusJob: Job? = null
    private var lastBattery = -1
    private var started = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pct = status.batteryPercent()
            if (lastBattery < 0 || abs(pct - lastBattery) >= 5) { lastBattery = pct; pushStatus() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = ManagedSetup.prefs(this)
        policy = PolicyManager(this, prefs)
        status = StatusCollector(this, prefs)
        executor = CommandExecutor(policy, status)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.managed_notif_scanning)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // minSdk 34: RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED is mandatory when registering a context-registered receiver.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        if (!started) { started = true; scope.launch { if (fromBoot) policy.restoreKiosk(); startLink() } }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(batteryReceiver)
        statusJob?.cancel(); client?.disconnect(); scope.cancel()
        ManagedLinkState.state.value = LinkState.IDLE
        super.onDestroy()
    }

    private suspend fun startLink() {
        val secret = prefs.secretBytes()
        if (secret == null) { Log.w(LOG_TAG, "Not provisioned; no secret"); ManagedLinkState.lastError.value = "Not provisioned"; return }
        val deviceId = prefs.ensureDeviceId()
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        endpoint = ManagedEndpoint(secret, deviceId, model, { frame -> sendFrame(frame) }, object : ManagedEndpoint.Listener {
            override fun onAuthenticated() {
                Log.d(LOG_TAG, "Authenticated")
                backoffMs = BACKOFF_MIN_MS
                setState(LinkState.AUTHENTICATED)
                pushStatus(); startPeriodicStatus()
            }
            override fun onCommand(cmd: Message.Cmd) { scope.launch { handleCommand(cmd) } }
            override fun onProtocolError(reason: String) {
                Log.w(LOG_TAG, "Protocol error: $reason"); ManagedLinkState.lastError.value = reason
                client?.disconnect(); scheduleRescan()
            }
        })
        client = BLEClient(this, serverName = null, servicesToListen = listOf(gatt), callBack = object : BLEClient.BLEClientCallBack {
            override fun onMtuChanged(mtu: Int) { this@ManagedLinkService.mtu = mtu }
            override fun onConnected() { Log.d(LOG_TAG, "Connected, mtu=$mtu"); setState(LinkState.CONNECTED); endpoint?.onConnected(mtu) }
            override fun onDisconnected() { Log.d(LOG_TAG, "Disconnected"); statusJob?.cancel(); endpoint?.reset(); scheduleRescan() }
            override fun onCharacteristicChanged(characteristic: BLECharacteristic, service: BLEService) {
                if (characteristic.uuid == MdmGatt.CMD_UUID) endpoint?.onFrame(characteristic.value)
            }
        }, serviceUuid = MdmGatt.SERVICE_UUID)
        scan()
    }

    private fun scan() {
        setState(LinkState.SCANNING)
        try { client?.startScan(onTimeout = { Log.d(LOG_TAG, "Scan timeout"); scheduleRescan() }) }
        catch (e: BLEException) { Log.w(LOG_TAG, "Scan failed: ${e.message}"); ManagedLinkState.lastError.value = e.message; scheduleRescan() }
    }

    private fun scheduleRescan() {
        setState(LinkState.SCANNING)
        val delayMs = backoffMs; backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ scan() }, delayMs)
    }

    private fun sendFrame(frame: String) {
        gatt.rsp.value = frame
        try { client?.writeCharacteristic(gatt, gatt.rsp) } catch (e: BLEException) { Log.w(LOG_TAG, "Write failed: ${e.message}") }
    }

    private suspend fun handleCommand(cmd: Message.Cmd) {
        Log.d(LOG_TAG, "Command ${cmd.type} ${cmd.pkg ?: ""}")
        val replies = executor.execute(cmd)
        for (m in replies) safeSend(m)
        replies.filterIsInstance<Message.Status>().lastOrNull()?.let { ManagedLinkState.lastStatus.value = it }
    }

    private fun safeSend(m: Message) { try { endpoint?.send(m) } catch (e: IllegalStateException) { Log.w(LOG_TAG, "Not authenticated; dropping ${m::class.simpleName}") } }

    private fun pushStatus() { scope.launch { val s = status.collect(); ManagedLinkState.lastStatus.value = s; safeSend(s) } }

    private fun startPeriodicStatus() {
        statusJob?.cancel()
        statusJob = scope.launch { while (isActive) { delay(STATUS_PERIOD_MS); pushStatus() } }
    }

    private fun setState(s: LinkState) {
        ManagedLinkState.state.value = s
        val text = when (s) { LinkState.AUTHENTICATED -> getString(R.string.managed_notif_authenticated); LinkState.CONNECTED -> getString(R.string.managed_notif_connected); else -> getString(R.string.managed_notif_scanning) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.managed_notif_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle(getString(R.string.managed_notif_title)).setContentText(text).setOngoing(true).build()
}
