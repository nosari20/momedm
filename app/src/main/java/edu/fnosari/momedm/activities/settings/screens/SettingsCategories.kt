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
                title = context.getString(Routes.SETTINGS_LEGAL.label),
                icon = Routes.SETTINGS_LEGAL.icon,
                onClick = { navController.navigate(Routes.SETTINGS_LEGAL.name) })
            }
            item { SettingsCategoryItem(
                title = context.getString(Routes.SETTINGS_LICENSES.label),
                icon = Routes.SETTINGS_LICENSES.icon,
                onClick = { navController.navigate(Routes.SETTINGS_LICENSES.name) })
            }

            item { SettingsCategoryDivider() }

            item {
                var clickCount = 0
                SettingsAppVersion(
                versionText = context.getString(R.string.settings_screen_version, appVersion?.versionName, appVersion?.versionNumber),
                copyrights = context.getString(R.string.settings_screen_copyrights),
                onClick = {
                    clickCount++
                    if(clickCount == 8){
                        clickCount = 0
                        navController.navigate(Routes.SETTINGS_EASTEREGG.name)
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