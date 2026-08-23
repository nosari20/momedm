package edu.fnosari.momedm.activities.settings.screens

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.settings.navigation.Routes
import edu.fnosari.momedm.activities.settings.components.SettingsAppVersion
import edu.fnosari.momedm.activities.settings.components.SettingsCategoryDivider
import edu.fnosari.momedm.activities.settings.components.SettingsCategoryItem
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import edu.fnosari.momedm.utils.getAppVersion


@Composable
fun SettingsMenu(navController: NavHostController) {
    val context = LocalActivity.current as Activity
    val appVersion = getAppVersion(context)
    val listState = rememberLazyListState()
    BasicLayoutWithTopBar(
        title = context.getString(Routes.CATEGORIES.label),
        leftAction = {
            context.finish()
        },
        rightActions = {},
    ){

        LazyColumn(state = listState) {

            item { SettingsCategoryItem(
                title = context.getString(Routes.APPEARANCE.label),
                icon = Routes.APPEARANCE.icon,
                supportingText = context.getString(R.string.settings_appearance_help),
                onClick = { navController.navigate(Routes.APPEARANCE.name) })
            }
            item { SettingsCategoryItem(
                title = context.getString(Routes.PIN.label),
                icon = Routes.PIN.icon,
                supportingText = context.getString(R.string.settings_pin_help),
                onClick = { navController.navigate(Routes.PIN.name) })
            }
            item { SettingsCategoryItem(
                title = context.getString(Routes.CONNECTION.label),
                icon = Routes.CONNECTION.icon,
                onClick = { navController.navigate(Routes.CONNECTION.name) })
            }

            item { SettingsCategoryItem(
                title = context.getString(Routes.ADVANCED.label),
                icon = Routes.ADVANCED.icon,
                supportingText = context.getString(R.string.settings_advanced_help),
                onClick = { navController.navigate(Routes.ADVANCED.name) })
            }

            item { SettingsCategoryDivider() }

            item { SettingsCategoryItem(
                title = context.getString(Routes.LEGAL.label),
                icon = Routes.LEGAL.icon,
                onClick = { navController.navigate(Routes.LEGAL.name) })
            }
            item { SettingsCategoryItem(
                title = context.getString(Routes.LICENSES.label),
                icon = Routes.LICENSES.icon,
                onClick = { navController.navigate(Routes.LICENSES.name) })
            }

            item { SettingsCategoryDivider() }

            item {
                var clickCount = 0
                SettingsAppVersion(
                versionText = context.getString(R.string.settings_version, appVersion?.versionName, appVersion?.versionNumber),
                copyrights = context.getString(R.string.settings_copyright),
                onClick = {
                    clickCount++
                    if(clickCount == 8){
                        clickCount = 0
                        navController.navigate(Routes.EASTEREGG.name)
                    }
                }) }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingsCategoriesPreview() {
    SettingsMenu(rememberNavController())
}