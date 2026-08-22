package edu.fnosari.momedm.activities.settings.screens

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar

@Composable
fun SettingsEasterEgg(navController: NavHostController) {
    val context = LocalContext.current as Activity
    BasicLayoutWithTopBar(
        title = context.getString(R.string.settings_screen_category_easteregg_title),
        leftAction = {
            navController.popBackStack()
        },
        rightActions = {},
    ){
        Text(context.getString(R.string.settings_screen_category_easteregg_text))
    }
}

@Preview
@Composable
fun SettingsEasterEgg() {
    val context = LocalContext.current
    BasicLayoutWithTopBar(
        title = context.getString(R.string.settings_screen_category_easteregg_title),
        leftAction = {
        },
        rightActions = {},
    ){
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ){
            Text(
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                text = context.getString(R.string.settings_screen_category_easteregg_text)
            )
        }
    }
}