package edu.fnosari.momedm.activities.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.settings.components.AccentDialog
import edu.fnosari.momedm.activities.settings.components.ChoiceDialog
import edu.fnosari.momedm.activities.settings.components.CustomColorDialog
import edu.fnosari.momedm.activities.settings.components.SettingRow
import edu.fnosari.momedm.controller.ControllerLink
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.ui.AppLocale
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import edu.fnosari.momedm.ui.theme.Palette
import edu.fnosari.momedm.ui.theme.THEME_DARK
import edu.fnosari.momedm.ui.theme.THEME_LIGHT
import edu.fnosari.momedm.ui.theme.THEME_SYSTEM
import kotlinx.coroutines.launch

/**
 * Language, light/dark, and accent-colour pickers pushed to the parent's own UI immediately and
 * to every online child via `SET_PREFS` (see [ControllerLink.prefsChanged]).
 */
@Composable
fun SettingsAppearanceScreen(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember { ControllerPrefs(DataStorePreferencesProvider(context)) }
    val scope = rememberCoroutineScope()
    val storedLanguage by prefs.language.collectAsState("system")
    val language = AppLocale.current(context, storedLanguage)
    val theme by prefs.theme.collectAsState("system")
    val accent by prefs.accent.collectAsState(Palette.DEFAULT)
    var showLanguage by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showAccent by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }

    BasicLayoutWithTopBar(title = stringResource(R.string.settings_appearance), leftAction = { navController.popBackStack() }) {
        Column(Modifier.fillMaxWidth()) {
            SettingRow(stringResource(R.string.language), languageLabel(language)) { showLanguage = true }
            HorizontalDivider()
            SettingRow(stringResource(R.string.theme), themeLabel(theme)) { showTheme = true }
            HorizontalDivider()
            SettingRow(stringResource(R.string.accent_color), null, trailingSwatch = accent) { showAccent = true }
        }
    }
    if (showLanguage) ChoiceDialog(stringResource(R.string.language), AppLocale.TAGS, language, { languageLabel(it) }, { showLanguage = false }) { tag ->
        showLanguage = false
        scope.launch {
            prefs.setUiPrefs(tag, theme, accent)
            ControllerLink.prefsChanged.tryEmit(Unit)
            // Apply the locale LAST, inside the same coroutine. On API 33+ AppLocale.apply changes
            // the per-app locale, which recreates this Activity and cancels this composition-bound
            // scope — with apply running first (as it used to), the persist and the prefsChanged
            // emit above were cancelled mid-flight, so online children never received SET_PREFS
            // and kept their old language until the next reconnect happened to push it.
            if (AppLocale.apply(context, tag)) (context as? android.app.Activity)?.recreate()
        }
    }
    if (showTheme) ChoiceDialog(stringResource(R.string.theme), listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK), theme, { themeLabel(it) }, { showTheme = false }) {
        showTheme = false; scope.launch { prefs.setUiPrefs(language, it, accent); ControllerLink.prefsChanged.tryEmit(Unit) }
    }
    if (showAccent) AccentDialog(accent, onPick = { showAccent = false; scope.launch { prefs.setUiPrefs(language, theme, it); ControllerLink.prefsChanged.tryEmit(Unit) } },
        onCustom = { showAccent = false; showCustom = true }, onDismiss = { showAccent = false })
    if (showCustom) CustomColorDialog(accent, onDismiss = { showCustom = false }) { showCustom = false; scope.launch { prefs.setUiPrefs(language, theme, it); ControllerLink.prefsChanged.tryEmit(Unit) } }
}

@Composable
private fun languageLabel(tag: String) = when (tag) {
    "fr" -> stringResource(R.string.lang_fr)
    "en" -> stringResource(R.string.lang_en)
    else -> stringResource(R.string.system_default)
}

@Composable
private fun themeLabel(pref: String) = when (pref) {
    THEME_LIGHT -> stringResource(R.string.theme_light)
    THEME_DARK -> stringResource(R.string.theme_dark)
    else -> stringResource(R.string.system_default)
}
