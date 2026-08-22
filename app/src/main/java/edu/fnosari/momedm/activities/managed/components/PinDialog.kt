package edu.fnosari.momedm.activities.managed.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import edu.fnosari.momedm.R

/** Parent-PIN prompt: masked numeric field, 4–6 digits; [lockedForMs] > 0 disables submit and shows the countdown. */
@Composable
fun PinDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit, error: String?, lockedForMs: Long) {
    var pin by remember { mutableStateOf("") }
    val locked = lockedForMs > 0L
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_title)) },
        text = {
            Column {
                OutlinedTextField(value = pin, onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) pin = v },
                    label = { Text(stringResource(R.string.pin_hint)) }, singleLine = true, enabled = !locked,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), isError = error != null)
                if (locked) Text(stringResource(R.string.pin_locked, (lockedForMs / 1000L) + 1), color = MaterialTheme.colorScheme.error)
                else error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pin); pin = "" }, enabled = !locked && pin.length >= 4) { Text(stringResource(R.string.pin_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pin_cancel)) } })
}
