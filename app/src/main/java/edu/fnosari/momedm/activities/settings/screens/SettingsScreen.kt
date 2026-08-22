package edu.fnosari.momedm.activities.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.persistence.preferences.PreferencesProvider
import edu.fnosari.momedm.persistence.preferences.Preference
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar


@Composable
fun SettingsScreen(
    navController: NavHostController,
    preferencesProvider: PreferencesProvider,
    preferences: List<Preference<*>>
) {
    val listState = rememberLazyListState()
    BasicLayoutWithTopBar(
        title = LocalContext.current.getString(R.string.settings_screen_title),
        leftAction = {
            navController.popBackStack()
        },
        rightActions = {},
    ){

        LazyColumn(state = listState) {

            for (preference in preferences){
                when(preference){
                    is Preference.StringPreference -> item { SettingsScreenItemString(preference, preferencesProvider) }
                    is Preference.IntPreference -> item { SettingsScreenItemSlider(preference, preferencesProvider) }
                    is Preference.BooleanPreference -> item { SettingsScreenItemSwitch(preference, preferencesProvider) }
                    is Preference.DoublePreference -> Unit // no UI control yet
                }
            }
        }

    }
}

@Composable
fun SettingsScreenItemString(
    preference: Preference.StringPreference,
    preferencesProvider: PreferencesProvider
){
    val scope = rememberCoroutineScope()
    val text by preferencesProvider.readString(preference.key, preference.default).collectAsState(initial = preference.default) // Stored value, observed
    var showDialog by remember { mutableStateOf(false) }
    Surface() {
        Surface(
            onClick = {},
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .clickable { showDialog = true }
            ) {
                Text(LocalContext.current.getString(LocalContext.current.resources.getIdentifier("settings_screen_category_${preference.key}", "string", LocalContext.current.packageName)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showDialog) {
        var dialogText by remember { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch { preferencesProvider.write(preference.key, dialogText) }
                }) {
                    Text(LocalContext.current.getString(R.string.settings_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(LocalContext.current.getString(R.string.settings_dialog_dismiss))
                }
            },
            text = {
                Column {
                    Text(LocalContext.current.getString(R.string.settings_dialog_title_edit))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogText,
                        onValueChange = { dialogText = it },
                        singleLine = true
                    )
                }
            }
        )
    }
}


@Composable
fun SettingsScreenItemSlider(preference: Preference.IntPreference, preferencesProvider: PreferencesProvider) {
    val scope = rememberCoroutineScope()
    val saved by preferencesProvider.readInt(preference.key, preference.default).collectAsState(initial = preference.default)
    // Re-seed the drag position whenever the stored value changes.
    var sliderPosition by remember(saved) { mutableFloatStateOf(saved.toFloat()) }

    Surface() {
        Surface(
            onClick = {},
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(LocalContext.current.getString(LocalContext.current.resources.getIdentifier("settings_screen_category_${preference.key}", "string", LocalContext.current.packageName)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("$sliderPosition", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    Slider(
                        value = sliderPosition,
                        onValueChange = {
                            sliderPosition = it
                            scope.launch { preferencesProvider.write(preference.key, it.toInt()) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreenItemSwitch(preference: Preference.BooleanPreference, preferencesProvider: PreferencesProvider) {
    val scope = rememberCoroutineScope()
    val checked by preferencesProvider.readBoolean(preference.key, preference.default).collectAsState(initial = preference.default)

    Surface() {
        Surface(
            onClick = {},
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
            ){
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Text(LocalContext.current.getString(LocalContext.current.resources.getIdentifier("settings_screen_category_${preference.key}", "string", LocalContext.current.packageName)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text("$checked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Switch(

                        checked = checked,
                        onCheckedChange = {
                            scope.launch { preferencesProvider.write(preference.key, it) }
                        },
                        thumbContent = if (checked) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        } else {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        }
                    )
                }

            }

        }
    }
    /*
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        TextField(
            value = text,
            onValueChange = { newText -> text = newText }, // Update the state on input change
            label = { Text("Enter your text here") }, // Placeholder label
            singleLine = true, // Optional: Ensures input is single-line
            modifier = Modifier.fillMaxWidth(), // Make it stretch to fill parent width
        )

        Spacer(modifier = Modifier.height(16.dp)) // Add some spacing

        Text(text = "You entered: $text") // Display the entered text
    }
    */


}