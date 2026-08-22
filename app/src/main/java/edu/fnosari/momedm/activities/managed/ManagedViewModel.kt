package edu.fnosari.momedm.activities.managed

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.fnosari.momedm.managed.ManagedLinkService
import edu.fnosari.momedm.managed.ManagedLinkState
import edu.fnosari.momedm.managed.ManagedSetup
import edu.fnosari.momedm.managed.PolicyManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Exposes the managed link state to the home UI and the few local actions. */
class ManagedViewModel(application: Application) : AndroidViewModel(application) {
    companion object { private const val LOG_TAG = "ManagedViewModel" }

    private val policy = PolicyManager(application, ManagedSetup.prefs(application))
    val linkState: StateFlow<ManagedLinkState.LinkState> = ManagedLinkState.state
    val lastStatus = ManagedLinkState.lastStatus
    val lastError = ManagedLinkState.lastError

    fun addAccount() { viewModelScope.launch { policy.openAddAccount() } }
    fun openUsageAccess() {
        runCatching { getApplication<Application>().startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(LOG_TAG, "Usage access settings unavailable", it); ManagedLinkState.lastError.value = "Usage access settings unavailable" }
    }
    fun restartLink() = ManagedLinkService.restart(getApplication())
    fun ensureLink() { if (ManagedLinkState.state.value == ManagedLinkState.LinkState.IDLE) ManagedLinkService.start(getApplication()) }
}
