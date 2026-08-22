package edu.fnosari.momedm.controller.provisioning

import android.content.Context
import android.util.Log
import edu.fnosari.momedm.persistence.ControllerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Drives the Provision screen: Wi-Fi source, APK hosting, QR payload. */
class ProvisioningController(private val context: Context, private val prefs: ControllerPrefs, private val scope: CoroutineScope) {
    companion object {
        private const val LOG_TAG = "ProvisioningController"
        private const val IP_POLL_ATTEMPTS = 10
        private const val IP_POLL_DELAY_MS = 300L
    }

    /**
     * [ssid]/[password] are the user's persisted *manual* Wi-Fi credentials (mode [ControllerPrefs.MODE_MANUAL]);
     * [hotspotSsid]/[hotspotPassword] are the transient credentials of a locally-started hotspot (mode
     * [ControllerPrefs.MODE_HOTSPOT]) and are never persisted — the two pairs must stay separate so
     * starting a hotspot can't clobber the user's saved manual Wi-Fi choice.
     */
    data class State(
        val mode: String = ControllerPrefs.MODE_HOTSPOT, val ssid: String = "", val password: String = "", val customUrl: String = "",
        val hotspotSsid: String = "", val hotspotPassword: String = "",
        val ip: String? = null, val serverRunning: Boolean = false, val qrPayload: String? = null, val error: String? = null,
    )
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val hotspot = HotspotManager(context)
    private var http: ApkHttpServer? = null
    /** The in-flight hotspot IP poll started by [serveAndBuild], if any — cancelled by [stop] so a Stop
     * during the poll window can't have a late [onIpResolved] resurrect state after the user stopped. */
    private var pollJob: Job? = null

    init { scope.launch { _state.value = State(prefs.wifiMode.first(), prefs.manualSsid.first(), prefs.manualPassword.first(), prefs.customUrl.first()) } }

    fun setMode(mode: String) { _state.update { it.copy(mode = mode) }; persist() }
    fun setManual(ssid: String, pass: String) { _state.update { it.copy(ssid = ssid, password = pass) }; persist() }
    fun setCustomUrl(url: String) { _state.update { it.copy(customUrl = url) }; persist() }
    /** Persists only the user's manual Wi-Fi choice — never the transient hotspot credentials. */
    private fun persist() = scope.launch { val s = _state.value; prefs.setWifi(s.mode, s.ssid, s.password, s.customUrl) }

    /** Starts hotspot (if mode HOTSPOT) and the APK server (unless CUSTOM_URL), then builds the QR. */
    fun start() {
        _state.update { it.copy(error = null, qrPayload = null) }
        when (_state.value.mode) {
            ControllerPrefs.MODE_HOTSPOT -> hotspot.start(
                onReady = { ssid, pass -> _state.update { it.copy(hotspotSsid = ssid, hotspotPassword = pass) }; serveAndBuild() },
                onFailed = { reason -> _state.update { it.copy(error = reason) } },
            )
            ControllerPrefs.MODE_MANUAL -> serveAndBuild()
            else -> buildQr()
        }
    }

    private fun serveAndBuild() {
        pollJob?.cancel(); pollJob = null
        try {
            // daemon = true: the listener thread must not keep the process alive after the UI is gone.
            if (http == null) { http = ApkHttpServer(context.applicationInfo.sourceDir).also { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true) } }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "HTTP server failed", e); _state.update { it.copy(error = "HTTP server: ${e.message}") }; return
        }
        if (_state.value.mode == ControllerPrefs.MODE_HOTSPOT) {
            // The AP interface's address can take a moment to settle after the hotspot reports ready; poll for it.
            pollJob = scope.launch {
                var ip: String? = null
                for (attempt in 0 until IP_POLL_ATTEMPTS) {
                    ip = NetUtils.localIpv4(preferGateway = true)
                    if (ip != null) break
                    delay(IP_POLL_DELAY_MS)
                }
                // stop() may have cancelled this job while it was polling; never apply a stale result.
                ensureActive()
                onIpResolved(ip)
            }
        } else {
            onIpResolved(NetUtils.localIpv4(preferGateway = false))
        }
    }

    private fun onIpResolved(ip: String?) {
        if (ip == null) {
            // Nothing to serve without an address to advertise — tear the HTTP server back down so
            // `serverRunning` reflects reality instead of claiming a server nobody can reach.
            http?.stop(); http = null
            _state.update { it.copy(error = "no IPv4 address", serverRunning = false) }
            return
        }
        _state.update { it.copy(serverRunning = true, ip = ip) }
        buildQr()
    }

    private fun buildQr() = scope.launch {
        val id = prefs.ensureIdentity(); val s = _state.value
        val url = if (s.mode == ControllerPrefs.MODE_CUSTOM_URL) s.customUrl else QrPayloadBuilder.apkUrl(s.ip ?: return@launch)
        if (url.isBlank()) { _state.update { it.copy(error = "missing APK URL") }; return@launch }
        val checksum = try { SignatureChecksum.compute(context) } catch (e: Exception) { _state.update { it.copy(error = "checksum: ${e.message}") }; return@launch }
        val (ssid, password) = if (s.mode == ControllerPrefs.MODE_HOTSPOT) s.hotspotSsid to s.hotspotPassword else s.ssid to s.password
        val payload = QrPayloadBuilder.build(ProvisioningParams(url, checksum, ssid.ifBlank { null }, password, id.controllerId, id.secretBase64))
        _state.update { it.copy(qrPayload = payload) }
        Log.d(LOG_TAG, "QR payload ready (${payload.length} chars)")
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
        http?.stop(); http = null; hotspot.stop()
        _state.update { it.copy(hotspotSsid = "", hotspotPassword = "", ip = null, error = null, qrPayload = null, serverRunning = false) }
    }
}
