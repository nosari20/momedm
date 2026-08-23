package edu.fnosari.momedm.ui

import android.graphics.Color as AColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.ui.theme.MomeDMTheme
import edu.fnosari.momedm.ui.theme.Palette
import edu.fnosari.momedm.ui.theme.isDarkTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** The three look-and-feel preferences shared by both roles. */
data class UiPrefs(val language: String = "system", val theme: String = "system", val accent: Int = Palette.DEFAULT)

/** Parent phone: its own choices. */
fun ControllerPrefs.uiPrefs(): Flow<UiPrefs> = combine(language, theme, accent) { l, t, a -> UiPrefs(l, t, a) }
/** Child device: whatever the parent pushed with SET_PREFS. */
fun ManagedPrefs.uiPrefs(): Flow<UiPrefs> = childPrefs.map { UiPrefs(it.language, it.theme, it.accent) }

/** Mirrors MaClasse MainActivity: status-bar icons follow the top bar's brightness, nav bar follows the theme. */
@Composable
fun SystemBars(activity: ComponentActivity, dark: Boolean, seed: Int) {
    val barLight = Palette.isLight(Palette.primaryFor(seed, dark))
    LaunchedEffect(dark, barLight) {
        activity.enableEdgeToEdge(
            statusBarStyle = if (barLight) SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT) else SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
        )
    }
}

/** Wraps [content] in [MomeDMTheme] driven by a [UiPrefs] flow, plus system bars. */
@Composable
fun ThemedByPrefs(activity: ComponentActivity, prefs: Flow<UiPrefs>, content: @Composable () -> Unit) {
    val ui by prefs.collectAsState(initial = UiPrefs())
    val dark = isDarkTheme(ui.theme, isSystemInDarkTheme())
    SystemBars(activity, dark, ui.accent)
    MomeDMTheme(darkTheme = dark, seed = ui.accent, content = content)
}

/** Parent-role theming from [ControllerPrefs]. */
@Composable
fun ControllerThemed(activity: ComponentActivity, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ControllerPrefs(DataStorePreferencesProvider(context)) }
    ThemedByPrefs(activity, prefs.uiPrefs(), content)
}

/** Child-role theming from the pushed [edu.fnosari.momedm.protocol.ChildPrefs]. */
@Composable
fun ManagedThemed(activity: ComponentActivity, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ManagedPrefs(DataStorePreferencesProvider(context)) }
    ThemedByPrefs(activity, prefs.uiPrefs(), content)
}
