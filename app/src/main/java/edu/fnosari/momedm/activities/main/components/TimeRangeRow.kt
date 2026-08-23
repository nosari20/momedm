package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import java.util.Locale

/** Formats minutes-since-midnight as HH:mm for display. */
fun formatMinutes(minutes: Int): String = String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)

/**
 * One "label — start to end" row; tapping either time opens a clock picker. Times are minutes since
 * local midnight, matching [edu.fnosari.momedm.protocol.LockSchedule].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeRow(label: String, startMinutes: Int, endMinutes: Int, onChange: (Int, Int) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }   // "start", "end", or null
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { editing = "start" }) { Text(formatMinutes(startMinutes)) }
            Text("→")   // language-neutral, so it needs no string resource
            TextButton(onClick = { editing = "end" }) { Text(formatMinutes(endMinutes)) }
        }
    }
    editing?.let { which ->
        val initial = if (which == "start") startMinutes else endMinutes
        val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.hour * 60 + state.minute
                    if (which == "start") onChange(picked, endMinutes) else onChange(startMinutes, picked)
                    editing = null
                }) { Text(stringResource(R.string.settings_dialog_confirm)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
        )
    }
}
