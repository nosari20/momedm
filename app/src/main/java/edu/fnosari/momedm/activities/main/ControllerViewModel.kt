package edu.fnosari.momedm.activities.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.fnosari.momedm.R
import edu.fnosari.momedm.controller.ControllerLink
import edu.fnosari.momedm.controller.ControllerService
import edu.fnosari.momedm.controller.provisioning.ProvisioningController
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.DeviceRecord
import edu.fnosari.momedm.persistence.DeviceRegistry
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Controller UI state: registry, online set, advertising flag, command results; owns the provisioning controller. */
class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    companion object { private const val LOG_TAG = "ControllerViewModel" }

    private val prefs = ControllerPrefs(DataStorePreferencesProvider(application))
    private val registry = DeviceRegistry(prefs, viewModelScope)
    val provisioning = ProvisioningController(application, prefs, viewModelScope)

    val devices: StateFlow<List<DeviceRecord>> = registry.devices
    val online: StateFlow<Set<String>> = ControllerLink.online
    val advertising: StateFlow<Boolean> = ControllerLink.advertising
    // replay = 1 so an error/result emitted just before the snackbar collector attaches (e.g. during
    // the permission gate, before the post-gate LaunchedEffect subscribes) is not lost.
    private val _events = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events
    private val _appsFor = MutableStateFlow<Pair<String, List<AppInfo>?>?>(null)
    /** (deviceId, apps) while the picker is open; apps == null while loading. */
    val appsFor: StateFlow<Pair<String, List<AppInfo>?>?> = _appsFor

    init {
        val app = application
        viewModelScope.launch { ControllerLink.results.collect { (id, r) -> _events.emit(app.getString(R.string.device_result, if (r.ok) "OK" else "ERR", r.msg)) } }
        viewModelScope.launch { ControllerLink.apps.collect { (id, a) -> if (_appsFor.value?.first == id) _appsFor.value = id to a.apps } }
        viewModelScope.launch { ControllerLink.errors.collect { _events.emit(it) } }
        // Service-side registry writes land in DataStore; re-read the blob so the UI list refreshes.
        viewModelScope.launch { prefs.registryJson.collect { registry.reload() } }
    }

    /**
     * Starts [ControllerService] if the user wants advertising on launch and it isn't already running.
     * Must be called only after the caller's permission gate has passed — calling it from [init] would
     * race the gate (the view model is created before permissions are granted), producing a doomed
     * service start whose failure the UI isn't yet subscribed to observe.
     */
    fun startServiceIfWanted() {
        val app = getApplication<Application>()
        viewModelScope.launch { if (prefs.advertiseOnLaunch.first() && !ControllerLink.advertising.value) ControllerService.start(app) }
    }

    fun setAdvertising(on: Boolean) { val app = getApplication<Application>(); viewModelScope.launch { prefs.setAdvertiseOnLaunch(on) }; if (on) ControllerService.start(app) else ControllerService.stop(app) }

    /** Returns the sent command's id, or null if [deviceId] has no authenticated session (offline). */
    private fun send(deviceId: String, type: CmdType, pkg: String? = null): String? {
        val id = ControllerLink.sendCommand(deviceId, type, pkg)
        Log.d(LOG_TAG, "send $type -> $deviceId: ${if (id == null) "offline" else "sent (id=$id)"}")
        announce(id)
        return id
    }
    fun kioskOn(deviceId: String, apps: List<String>, pinned: String?) {
        _appsFor.value = null
        val id = ControllerLink.sendCmd(deviceId) { Message.Cmd(it, CmdType.KIOSK_ON, apps = apps, pinned = pinned) }
        Log.d(LOG_TAG, "kioskOn -> $deviceId: ${apps.size} apps, pinned=$pinned: ${if (id == null) "offline" else "sent (id=$id)"}")
        announce(id)
    }
    /** Re-locks a paused child by re-sending its current config. */
    fun relock(deviceId: String) {
        val s = registry.get(deviceId)?.lastStatus ?: return
        if (s.kioskApps.isEmpty()) return
        val id = ControllerLink.sendCmd(deviceId) { Message.Cmd(it, CmdType.KIOSK_ON, apps = s.kioskApps, pinned = s.kioskPkg) }
        Log.d(LOG_TAG, "relock -> $deviceId: ${if (id == null) "offline" else "sent (id=$id)"}")
        announce(id)
    }
    fun rename(deviceId: String, nickname: String?) { viewModelScope.launch { registry.rename(deviceId, nickname) } }

    fun kioskOff(deviceId: String) { send(deviceId, CmdType.KIOSK_OFF) }
    fun install(deviceId: String, pkg: String) { send(deviceId, CmdType.INSTALL, pkg) }
    fun addAccount(deviceId: String) { send(deviceId, CmdType.ADD_ACCOUNT) }
    fun refresh(deviceId: String) { send(deviceId, CmdType.GET_STATUS) }
    fun requestApps(deviceId: String) {
        _appsFor.value = deviceId to null
        // Offline device: no picker to keep open, so clear it back out instead of hanging on "loading".
        if (send(deviceId, CmdType.LIST_APPS) == null) _appsFor.value = null
    }
    fun clearApps() { _appsFor.value = null }

    private fun announce(id: String?) {
        val app = getApplication<Application>()
        viewModelScope.launch { _events.emit(if (id == null) app.getString(R.string.device_offline_msg) else app.getString(R.string.device_sent)) }
    }
}
