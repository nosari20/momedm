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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
        /** Tear the current link down and rebuild it from persisted prefs (new secret / re-provisioning). */
        const val EXTRA_RESTART = "restart"
        private const val STATUS_PERIOD_MS = 5 * 60_000L
        /** Sealed PING cadence while authenticated: bounds how long a dead session goes unnoticed. */
        private const val KEEPALIVE_PERIOD_MS = 60_000L
        private const val BACKOFF_MIN_MS = 2_000L
        private const val BACKOFF_MAX_MS = 30_000L

        fun start(context: Context, fromBoot: Boolean = false) {
            context.startForegroundService(Intent(context, ManagedLinkService::class.java).putExtra(EXTRA_FROM_BOOT, fromBoot))
        }
        fun stop(context: Context) { context.stopService(Intent(context, ManagedLinkService::class.java)) }
        /** Restarts the link in place: drops client + endpoint and re-reads the secret/device id from prefs.
         * Use this instead of stop()+start(), which is an async pair that may collapse into a no-op. */
        fun restart(context: Context) {
            context.startForegroundService(Intent(context, ManagedLinkService::class.java).putExtra(EXTRA_RESTART, true))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: ManagedPrefs
    private lateinit var policy: PolicyManager
    private lateinit var status: StatusCollector
    private lateinit var executor: CommandExecutor
    private val gatt = MdmService()
    @Volatile private var client: BLEClient? = null
    @Volatile private var endpoint: ManagedEndpoint? = null
    @Volatile private var mtu = 23
    @Volatile private var backoffMs = BACKOFF_MIN_MS
    @Volatile private var statusJob: Job? = null
    private var lastBattery = -1
    /**
     * Guards the stage-then-write pair in [sendFrame]. `gatt.rsp.value` is a single field shared by the
     * one [MdmService] instance, so two threads staging a frame concurrently (an endpoint callback on a
     * GATT binder thread and a coroutine reply on the main thread) could interleave and write the wrong
     * frame twice. Mirrors ControllerService's staging note, which relies on SessionManager's monitor.
     */
    private val sendLock = Any()
    /** Set once in [onDestroy]; guards [scan]/[scheduleRescan] against firing after teardown. */
    @Volatile private var destroyed = false
    /**
     * Synchronous in-flight guard for [startLink]. Both call sites — [onStartCommand] and
     * [scan]'s `client == null` retry — run on the main thread, so a plain check-and-set here
     * is atomic and closes the check-then-act race that [startLink] itself can't guard against:
     * it suspends on [ManagedPrefs.secretBytes]/[ManagedPrefs.ensureDeviceId] *before* assigning
     * [client], so two overlapping callers (e.g. a boot start racing a post-provisioning start)
     * could otherwise both see `client == null` and each construct their own `BLEClient`/
     * `ManagedEndpoint`, orphaning one scanning client and cross-wiring callbacks.
     */
    @Volatile private var linkStarting = false

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
        // Also here, not just at provisioning: a device enrolled by an earlier build never got
        // these granted and would otherwise stay unable to scan forever. Idempotent.
        runCatching { policy.grantOwnRuntimePermissions() }.onFailure { Log.w(LOG_TAG, "self-grant failed", it) }
        scope.launch { policy.restoreSafety() }
        scope.launch { ManagedLinkState.statusPushRequests.collect { pushStatus() } }
        startPauseWatchdog()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.managed_notif_scanning)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // minSdk 34: RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED is mandatory when registering a context-registered receiver.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        if (intent?.getBooleanExtra(EXTRA_RESTART, false) == true) {
            Log.d(LOG_TAG, "Restart requested — tearing down link")
            main.removeCallbacksAndMessages(null)
            statusJob?.cancel()
            client?.stopScan(); client?.disconnect(); client = null
            endpoint?.reset(); endpoint = null
            backoffMs = BACKOFF_MIN_MS
            ManagedLinkState.lastError.value = null
        }
        // No separate "started" latch: the link is (re)kicked off whenever there is no live
        // client yet. This keeps a "Not provisioned" first call a genuine no-op that a later
        // start(context) — e.g. right after provisioning completes — can retry. linkStarting
        // guards the window before startLink() (a suspend fun) gets around to assigning client.
        if (client == null && !linkStarting) {
            linkStarting = true
            scope.launch {
                // Boot-time restore goes through LockController rather than calling a kiosk-only
                // restore directly: a plain kiosk restore has no idea a complete lock should
                // currently outrank child mode, and racing BootReceiver's own reevaluate() (which
                // does know) is exactly how a complete lock got silently downgraded to the ordinary
                // child-mode allowlist on reboot. reevaluate() re-applies child mode whenever that is
                // genuinely all that applies, so it is a strict superset of the old restore.
                // PIN pause does not survive reboot; clear it here as well as in BootReceiver
                // to close the race where whichever path runs first could observe a stale deadline.
                try { if (fromBoot) { prefs.setPauseUntil(0L); LockController(this@ManagedLinkService, prefs, policy).reevaluate() }; startLink() } finally { linkStarting = false }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
        statusJob?.cancel()
        client?.stopScan()
        client?.disconnect()
        scope.cancel()
        ManagedLinkState.state.value = LinkState.IDLE
        super.onDestroy()
    }

    private suspend fun startLink() {
        val secret = prefs.secretBytes()
        if (secret == null) { Log.w(LOG_TAG, "Not provisioned; no secret"); ManagedLinkState.lastError.value = "Not provisioned"; return }
        try {
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
                    // client.disconnect() is a synchronous local close — it does not fire
                    // onDisconnected(), so the periodic status job must be cancelled here too.
                    statusJob?.cancel()
                    client?.disconnect(); scheduleRescan()
                }
            })
            client = BLEClient(this, serverName = null, servicesToListen = listOf(gatt), callBack = object : BLEClient.BLEClientCallBack {
                override fun onMtuChanged(mtu: Int) { this@ManagedLinkService.mtu = mtu }
                override fun onConnected() { Log.d(LOG_TAG, "Connected, mtu=$mtu"); setState(LinkState.CONNECTED); endpoint?.onConnected(mtu) }
                override fun onDisconnected() { Log.d(LOG_TAG, "Disconnected"); statusJob?.cancel(); endpoint?.reset(); scheduleRescan() }
                override fun onWriteFailed(characteristic: android.bluetooth.BluetoothGattCharacteristic, status: Int) {
                    // The controller rejected our frame: it no longer holds a session for this link (e.g. its
                    // GATT server restarted). Notifications would not reach us on this stale link, so drop it
                    // and reconnect from scratch.
                    Log.w(LOG_TAG, "Write rejected (status $status); link stale, reconnecting")
                    ManagedLinkState.lastError.value = "link reset by controller"
                    statusJob?.cancel(); endpoint?.reset(); client?.disconnect(); scheduleRescan()
                }
                override fun onCharacteristicChanged(characteristic: BLECharacteristic, service: BLEService) {
                    if (characteristic.uuid == MdmGatt.CMD_UUID) endpoint?.onFrame(characteristic.value)
                }
            }, serviceUuid = MdmGatt.SERVICE_UUID)
            scan()
        } catch (t: Throwable) {
            // BLEClient's constructor (and startScan below) can throw BLEException — most
            // commonly "Bluetooth not enabled". Never let that escape and crash the service;
            // just log, surface it, and let the backoff loop retry.
            Log.e(LOG_TAG, "startLink failed: ${t.message}", t)
            ManagedLinkState.lastError.value = t.message ?: t::class.simpleName
            // Counts as a failed attempt too (client construction itself is what failed) — bump
            // here or a sustained outage (e.g. Bluetooth left off) would retry forever at the
            // base interval and never back off toward BACKOFF_MAX_MS.
            bumpBackoff(); scheduleRescan()
        }
    }

    /**
     * Durable end-of-pause guarantee: re-locks the device when a parent-PIN pause lapses.
     *
     * The launcher's `ManagedViewModel` runs its own countdown, but that one exists only to drive the
     * UI banner and dies with the Activity — a child who swipes the launcher away (or whose process
     * gets reclaimed) mid-pause would otherwise stay unlocked until something else happened to touch
     * the config. This service outlives the launcher, so it is what actually calls
     * [LockController.reevaluate] — not a direct kiosk re-apply, since only [LockController] knows
     * whether a complete lock should apply instead of plain child mode. `collectLatest` restarts the
     * wait whenever the config changes (a new pause, a KIOSK_OFF, a re-lock), and the deadline is
     * re-read after the delay so a pause that was extended or cancelled while we slept is not resumed
     * out from under the parent.
     */
    private fun startPauseWatchdog() = scope.launch {
        prefs.kioskConfig.collectLatest { c ->
            val now = System.currentTimeMillis()
            if (c.on && c.pauseUntil > 0L) {
                val wait = c.pauseUntil - now
                if (wait > 0) delay(wait)
                if (prefs.kioskConfig.first().let { it.on && it.pauseUntil > 0L && it.pauseUntil <= System.currentTimeMillis() }) {
                    Log.d(LOG_TAG, "Pause lapsed; re-evaluating")
                    // Re-evaluate through LockController rather than a direct kiosk re-apply:
                    // this watchdog only knows a pause has lapsed, not whether a complete lock should
                    // apply instead of child mode. LockController does know, and also clears the
                    // stale pauseUntil that would otherwise re-trigger this same branch.
                    LockController(this@ManagedLinkService, prefs, policy).reevaluate()
                }
            }
        }
    }

    private fun scan() {
        if (destroyed) return
        val c = client
        if (c == null) {
            // Construction of the BLEClient itself failed last time (e.g. Bluetooth was off) —
            // retry the whole link setup rather than looping on a scan call that can never run.
            // scan() runs on the main thread (direct call from startLink(), or via the main
            // Handler in scheduleRescan()), so this check-and-set of linkStarting is atomic and
            // shares the same in-flight guard as onStartCommand.
            if (!linkStarting) {
                linkStarting = true
                scope.launch { try { startLink() } finally { linkStarting = false } }
            }
            return
        }
        setState(LinkState.SCANNING)
        // Advanced here (only when a scan is actually attempted) rather than in
        // scheduleRescan(), so concurrent/duplicate reschedule requests for the same failure
        // (e.g. onProtocolError and a later onDisconnected) collapse into a single doubling.
        bumpBackoff()
        try { c.startScan(onTimeout = { Log.d(LOG_TAG, "Scan timeout"); scheduleRescan() }) }
        catch (t: Throwable) { Log.w(LOG_TAG, "Scan failed: ${t.message}"); ManagedLinkState.lastError.value = t.message ?: t::class.simpleName; scheduleRescan() }
    }

    private fun bumpBackoff() { backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS) }

    private fun scheduleRescan() {
        if (destroyed) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ scan() }, backoffMs)
    }

    private fun sendFrame(frame: String) {
        synchronized(sendLock) {
            gatt.rsp.value = frame
            // Any Throwable, not just BLEException: writeCharacteristic can also raise from the platform
            // stack, and this runs on a GATT binder thread where an escape would take the process down.
            try { client?.writeCharacteristic(gatt, gatt.rsp) }
            catch (t: Throwable) { Log.w(LOG_TAG, "Write failed: ${t.message}", t); ManagedLinkState.lastError.value = t.message ?: t::class.simpleName }
        }
    }

    private suspend fun handleCommand(cmd: Message.Cmd) {
        Log.d(LOG_TAG, "Command ${cmd.type} ${cmd.pkg ?: ""}")
        val replies = executor.execute(cmd)
        for (m in replies) safeSend(m)
        replies.filterIsInstance<Message.Status>().lastOrNull()?.let { ManagedLinkState.lastStatus.value = it }
    }

    /**
     * Sends [m] if the session is live, never throwing. Beyond "not authenticated"
     * ([IllegalStateException]), an oversized payload makes `Framer.split` raise
     * [IllegalArgumentException]; both — and anything else the transport raises — are logged and the
     * message dropped rather than crashing the service.
     */
    private fun safeSend(m: Message) {
        try { endpoint?.send(m) }
        catch (e: IllegalStateException) { Log.w(LOG_TAG, "Not authenticated; dropping ${m::class.simpleName}") }
        catch (e: IllegalArgumentException) { Log.w(LOG_TAG, "Payload too large; dropping ${m::class.simpleName}: ${e.message}") }
        catch (t: Throwable) { Log.w(LOG_TAG, "Send failed; dropping ${m::class.simpleName}", t); ManagedLinkState.lastError.value = t.message ?: t::class.simpleName }
    }

    private fun pushStatus() { scope.launch { val s = status.collect(); ManagedLinkState.lastStatus.value = s; safeSend(s) } }

    private fun startPeriodicStatus() {
        statusJob?.cancel()
        statusJob = scope.launch {
            var sinceStatus = 0L
            while (isActive) {
                delay(KEEPALIVE_PERIOD_MS); sinceStatus += KEEPALIVE_PERIOD_MS
                if (sinceStatus >= STATUS_PERIOD_MS) { sinceStatus = 0L; pushStatus() } else safeSend(Message.Ping)
            }
        }
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
