package edu.fnosari.momedm.activities.managed.provisioning

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.lifecycleScope
import edu.fnosari.momedm.R
import edu.fnosari.momedm.managed.ManagedLinkService
import edu.fnosari.momedm.managed.ManagedLinkState
import edu.fnosari.momedm.managed.ManagedSetup
import edu.fnosari.momedm.managed.PolicyManager
import edu.fnosari.momedm.managed.StatusCollector
import edu.fnosari.momedm.ui.ManagedThemed
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import kotlinx.coroutines.launch

/** Shown by the Setup Wizard right after we become device owner. Two optional steps, then HOME + link service. */
class PolicyComplianceActivity : ComponentActivity() {
    companion object { private const val LOG_TAG = "PolicyCompliance" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val extras = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        ManagedSetup.persistExtras(this, extras)
        val prefs = ManagedSetup.prefs(this)
        val policy = PolicyManager(this, prefs)
        val status = StatusCollector(this, prefs)

        setContent {
            ManagedThemed(this) {
                var step by remember { mutableIntStateOf(0) }
                var accountOk by remember { mutableStateOf(status.hasGoogleAccount()) }
                var usageOk by remember { mutableStateOf(status.hasUsageAccess()) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { accountOk = status.hasGoogleAccount(); usageOk = status.hasUsageAccess() } }
                    owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
                }
                BasicLayoutWithTopBar(title = getString(R.string.setup_title)) {
                    when (step) {
                        0 -> StepCard(getString(R.string.setup_account_title), getString(R.string.setup_account_text), getString(R.string.setup_account_button), accountOk,
                            onAction = { lifecycleScope.launch { policy.openAddAccount() } }, onNext = { step = 1 })
                        else -> StepCard(getString(R.string.setup_usage_title), getString(R.string.setup_usage_text), getString(R.string.setup_usage_button), usageOk,
                            onAction = {
                                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                                    .onFailure { Log.w(LOG_TAG, "Usage access settings unavailable", it); ManagedLinkState.lastError.value = getString(R.string.managed_usage_unavailable) }
                            }, onNext = { finishSetup(policy) }, last = true)
                    }
                }
            }
        }
    }

    private fun finishSetup(policy: PolicyManager) {
        Log.d(LOG_TAG, "Setup finished; setting HOME and starting link")
        runCatching { policy.setAsDefaultHome() }.onFailure { Log.w(LOG_TAG, "setAsDefaultHome failed", it) }
        ManagedLinkService.start(this)
        setResult(RESULT_OK); finish()
    }
}

@Composable
private fun StepCard(title: String, text: String, actionLabel: String, done: Boolean, onAction: () -> Unit, onNext: () -> Unit, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Text(if (done) androidx.compose.ui.res.stringResource(R.string.setup_status_done) else androidx.compose.ui.res.stringResource(R.string.setup_status_missing), style = MaterialTheme.typography.labelLarge)
        Button(onClick = onAction) { Text(actionLabel) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onNext) { Text(androidx.compose.ui.res.stringResource(if (last) R.string.setup_done else R.string.setup_skip)) }
            if (done && !last) Button(onClick = onNext) { Text(androidx.compose.ui.res.stringResource(R.string.setup_next)) }
        }
    }
}
