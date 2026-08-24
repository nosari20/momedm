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

        /** How long a pairing code stays live. Enrolment takes a couple of minutes. */
        private const val EXPIRY_MS = 5 * 60_000L
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
        /** True once a code was shown and then timed out, so the UI can say why it went away. */
        val expired: Boolean = false,
    )
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val hotspot = HotspotManager(context)
    private var http: ApkHttpServer? = null
    /** The in-flight hotspot IP poll started by [serveAndBuild], if any — cancelled by [stop] so a Stop
     * during the poll window can't have a late [onIpResolved] resurrect state after the user stopped. */
    private var pollJob: Job? = null
    private var expiryJob: Job? = null

    init { scope.launch { _state.value = State(prefs.wifiMode.first(), prefs.manualSsid.first(), prefs.manualPassword.first(), prefs.customUrl.first()) } }

    fun setMode(mode: String) { _state.update { it.copy(mode = mode) }; persist(); invalidate() }

    /**
     * Throws away a code that no longer matches the settings on screen, and tears down whatever was
     * serving it.
     *
     * A QR encodes the Wi-Fi credentials and download URL of the mode it was built for, so leaving it
     * on screen after the parent switches mode invites them to scan a code that points the child at
     * the wrong network — and a hotspot started for the previous mode would stay up as well. Cheap to
     * regenerate, so discard rather than try to patch it.
     */
    private fun invalidate() {
        val s = _state.value
        if (s.qrPayload == null && !s.serverRunning && s.hotspotSsid.isBlank()) return
        Log.d(LOG_TAG, "Pairing settings changed; discarding the generated code")
        stop()
    }

    /**
     * Fills the Shared Wi-Fi name with the network this phone is currently joined to, when the parent
     * has not typed one. The child device has to join the same network for the download to work, so
     * the parent's own network is nearly always the right answer — and re-typing it is only a chance
     * to get it wrong. Never overwrites an existing value.
     */
    fun prefillSsidIfBlank() {
        val s = _state.value
        if (s.mode != ControllerPrefs.MODE_MANUAL || s.ssid.isNotBlank()) return
        val ssid = WifiAddresses.currentSsid(context) ?: return
        Log.d(LOG_TAG, "Pre-filled the Wi-Fi name from the current connection")
        setManual(ssid, s.password)
    }
    fun setManual(ssid: String, pass: String) { _state.update { it.copy(ssid = ssid, password = pass) }; persist(); invalidate() }
    fun setCustomUrl(url: String) { _state.update { it.copy(customUrl = url) }; persist(); invalidate() }
    /** Persists only the user's manual Wi-Fi choice — never the transient hotspot credentials. */
    private fun persist() = scope.launch { val s = _state.value; prefs.setWifi(s.mode, s.ssid, s.password, s.customUrl) }

    /** Starts hotspot (if mode HOTSPOT) and the APK server (unless CUSTOM_URL), then builds the QR. */
    fun start() {
        // Tapping "Show the code" twice used to start a second local-only hotspot: the first
        // reservation was overwritten and never released, so that hotspot stayed up until the process
        // died. Nothing here is cheap enough to be worth doing twice.
        if (_state.value.serverRunning || _state.value.hotspotSsid.isNotBlank()) return
        _state.update { it.copy(error = null, qrPayload = null, expired = false) }
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
                // Exclude the phone's own *client* Wi-Fi address so what remains is the AP interface.
                // Android's local-only hotspot picks a randomised subnet and does not necessarily take
                // the ".1" of it, so the gateway heuristic alone is not enough: on a phone both joined
                // to Wi-Fi (wlan0) and hosting the hotspot (wlan2), the client address wins on
                // interface-name ordering — and the child device, which is on the hotspot, can never
                // reach it. That produced a QR advertising an unreachable address and a download that
                // simply timed out.
                val clientWifi = WifiAddresses.clientWifiIpv4(context)
                var ip: String? = null
                for (attempt in 0 until IP_POLL_ATTEMPTS) {
                    ip = NetUtils.localIpv4(preferGateway = true, exclude = setOfNotNull(clientWifi))
                    if (ip != null) break
                    delay(IP_POLL_DELAY_MS)
                }
                Log.d(LOG_TAG, "Hotspot IP resolved to $ip (client Wi-Fi excluded: ${clientWifi != null})")
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
        armExpiry()
        Log.d(LOG_TAG, "QR payload ready (${payload.length} chars)")
    }

    /**
     * Takes the code, the server and the hotspot down after [EXPIRY_MS].
     *
     * The QR carries the 32-byte shared secret and, in hotspot mode, the Wi-Fi password. Left alone
     * it stayed on screen and the listener stayed bound for as long as the screen was composed — a
     * parent who starts an enrolment and is called away leaves both sitting there. Enrolment takes a
     * couple of minutes; anything much past that is a code nobody is using.
     */
    private fun armExpiry() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(EXPIRY_MS)
            Log.d(LOG_TAG, "Pairing code expired; tearing down")
            stop()
            _state.update { it.copy(expired = true) }
        }
    }

    fun stop() {
        expiryJob?.cancel(); expiryJob = null
        pollJob?.cancel(); pollJob = null
        http?.stop(); http = null; hotspot.stop()
        _state.update { it.copy(hotspotSsid = "", hotspotPassword = "", ip = null, error = null, qrPayload = null, serverRunning = false) }
    }
}
