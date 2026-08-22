package edu.fnosari.momedm.controller.provisioning

import android.content.Context
import android.util.Log
import edu.fnosari.momedm.persistence.ControllerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Drives the Provision screen: Wi-Fi source, APK hosting, QR payload. */
class ProvisioningController(private val context: Context, private val prefs: ControllerPrefs, private val scope: CoroutineScope) {
    companion object { private const val LOG_TAG = "ProvisioningController" }

    data class State(
        val mode: String = ControllerPrefs.MODE_HOTSPOT, val ssid: String = "", val password: String = "", val customUrl: String = "",
        val ip: String? = null, val serverRunning: Boolean = false, val qrPayload: String? = null, val error: String? = null,
    )
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val hotspot = HotspotManager(context)
    private var http: ApkHttpServer? = null

    init { scope.launch { _state.value = State(prefs.wifiMode.first(), prefs.manualSsid.first(), prefs.manualPassword.first(), prefs.customUrl.first()) } }

    fun setMode(mode: String) { update { copy(mode = mode) }; persist() }
    fun setManual(ssid: String, pass: String) { update { copy(ssid = ssid, password = pass) }; persist() }
    fun setCustomUrl(url: String) { update { copy(customUrl = url) }; persist() }
    private fun persist() = scope.launch { val s = _state.value; prefs.setWifi(s.mode, s.ssid, s.password, s.customUrl) }
    private fun update(f: State.() -> State) { _state.value = _state.value.f() }

    /** Starts hotspot (if mode HOTSPOT) and the APK server (unless CUSTOM_URL), then builds the QR. */
    fun start() {
        update { copy(error = null, qrPayload = null) }
        when (_state.value.mode) {
            ControllerPrefs.MODE_HOTSPOT -> hotspot.start(onReady = { ssid, pass -> update { copy(ssid = ssid, password = pass) }; serveAndBuild() }, onFailed = { update { copy(error = it) } })
            ControllerPrefs.MODE_MANUAL -> serveAndBuild()
            else -> buildQr()
        }
    }

    private fun serveAndBuild() {
        try {
            if (http == null) { http = ApkHttpServer(context.applicationInfo.sourceDir).also { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) } }
            val ip = NetUtils.localIpv4() ?: run { update { copy(error = "no IPv4 address") }; return }
            update { copy(serverRunning = true, ip = ip) }
            buildQr()
        } catch (e: Exception) { Log.e(LOG_TAG, "HTTP server failed", e); update { copy(error = "HTTP server: ${e.message}") } }
    }

    private fun buildQr() = scope.launch {
        val id = prefs.ensureIdentity(); val s = _state.value
        val url = if (s.mode == ControllerPrefs.MODE_CUSTOM_URL) s.customUrl else QrPayloadBuilder.apkUrl(s.ip ?: return@launch)
        if (url.isBlank()) { update { copy(error = "missing APK URL") }; return@launch }
        val checksum = try { SignatureChecksum.compute(context) } catch (e: Exception) { update { copy(error = "checksum: ${e.message}") }; return@launch }
        val payload = QrPayloadBuilder.build(ProvisioningParams(url, checksum, s.ssid.ifBlank { null }, s.password, id.controllerId, id.secretBase64))
        update { copy(qrPayload = payload) }
        Log.d(LOG_TAG, "QR payload ready (${payload.length} chars)")
    }

    fun stop() { http?.stop(); http = null; hotspot.stop(); update { copy(serverRunning = false, qrPayload = null) } }
}
