package edu.fnosari.momedm.activities.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.fnosari.momedm.activities.settings.navigation.Routes
import edu.fnosari.momedm.activities.settings.screens.SettingsControllerScreen
import edu.fnosari.momedm.activities.settings.screens.SettingsEasterEgg
import edu.fnosari.momedm.activities.settings.screens.SettingsMenu
import edu.fnosari.momedm.activities.settings.screens.SettingsScreen
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.ui.theme.MomeDMTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navController = rememberNavController()
            val context = LocalContext.current
            val preferencesProvider = remember { DataStorePreferencesProvider(context) }

            MomeDMTheme {
                NavHost(
                    navController = navController,
                    enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(200)) },
                    exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(200)) },
                    popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(200)) },
                    popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(200)) },
                    startDestination = Routes.CATEGORIES.name
                ) {
                    composable(Routes.CATEGORIES.name) { SettingsMenu(navController) }

                    composable(Routes.SETTINGS_CONTROLLER.name) { SettingsControllerScreen(navController) }

                    composable(Routes.SETTINGS_LEGAL.name) {
                        SettingsScreen(
                            navController,
                            preferencesProvider,
                            listOf()
                        )
                    }
                    composable(Routes.SETTINGS_LICENSES.name) {
                        SettingsScreen(
                            navController,
                            preferencesProvider,
                            listOf()
                        )
                    }
                    composable(Routes.SETTINGS_EASTEREGG.name) {
                        SettingsEasterEgg(navController)
                    }
                }
            }
        }

    }
}