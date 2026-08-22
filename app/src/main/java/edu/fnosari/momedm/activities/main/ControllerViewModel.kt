package edu.fnosari.momedm.activities.main

import android.app.Application
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Controller UI state: registry, online set, advertising flag, command results; owns the provisioning controller. */
class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = ControllerPrefs(DataStorePreferencesProvider(application))
    private val registry = DeviceRegistry(prefs, viewModelScope)
    val provisioning = ProvisioningController(application, prefs, viewModelScope)

    val devices: StateFlow<List<DeviceRecord>> = registry.devices
    val online: StateFlow<Set<String>> = ControllerLink.online
    val advertising: StateFlow<Boolean> = ControllerLink.advertising
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events
    private val _appsFor = MutableStateFlow<Pair<String, List<AppInfo>?>?>(null)
    /** (deviceId, apps) while the picker is open; apps == null while loading. */
    val appsFor: StateFlow<Pair<String, List<AppInfo>?>?> = _appsFor

    init {
        val app = application
        viewModelScope.launch { ControllerLink.results.collect { (id, r) -> _events.emit(app.getString(R.string.device_result, if (r.ok) "OK" else "ERR", r.msg)) } }
        viewModelScope.launch { ControllerLink.apps.collect { (id, a) -> if (_appsFor.value?.first == id) _appsFor.value = id to a.apps } }
        viewModelScope.launch { ControllerLink.errors.collect { _events.emit(it) } }
        viewModelScope.launch { if (prefs.advertiseOnLaunch.first() && !ControllerLink.advertising.value) ControllerService.start(app) }
        // Service-side registry writes land in DataStore; re-read the blob so the UI list refreshes.
        viewModelScope.launch { prefs.registryJson.collect { registry.reload() } }
    }

    fun setAdvertising(on: Boolean) { val app = getApplication<Application>(); viewModelScope.launch { prefs.setAdvertiseOnLaunch(on) }; if (on) ControllerService.start(app) else ControllerService.stop(app) }

    private fun send(deviceId: String, type: CmdType, pkg: String? = null) {
        val app = getApplication<Application>()
        val id = ControllerLink.sendCommand(deviceId, type, pkg)
        viewModelScope.launch { _events.emit(if (id == null) app.getString(R.string.device_offline_msg) else app.getString(R.string.device_sent)) }
    }
    fun kioskOn(deviceId: String, pkg: String) { _appsFor.value = null; send(deviceId, CmdType.KIOSK_ON, pkg) }
    fun kioskOff(deviceId: String) = send(deviceId, CmdType.KIOSK_OFF)
    fun install(deviceId: String, pkg: String) = send(deviceId, CmdType.INSTALL, pkg)
    fun addAccount(deviceId: String) = send(deviceId, CmdType.ADD_ACCOUNT)
    fun refresh(deviceId: String) = send(deviceId, CmdType.GET_STATUS)
    fun requestApps(deviceId: String) { _appsFor.value = deviceId to null; send(deviceId, CmdType.LIST_APPS) }
    fun clearApps() { _appsFor.value = null }
}
