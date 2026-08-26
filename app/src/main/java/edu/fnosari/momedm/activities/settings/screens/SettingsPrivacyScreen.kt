package edu.fnosari.momedm.activities.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import java.util.Locale

/**
 * The full privacy policy, readable inside the app — offline, like everything else here. The same
 * text is published for Play (docs/privacy-policy.md); this renders the bundled copy in the UI
 * language, so a parent (or a curious child) never needs a browser or a network to read exactly
 * what the app does and does not do. Keep the three copies in sync: docs/play/privacy-policy.md
 * (annotated source), docs/privacy-policy.md (published page, EN), res/raw (bundled EN + FR).
 */
@Composable
fun SettingsPrivacyScreen(navController: NavHostController) {
    val context = LocalContext.current
    val text = remember {
        val fr = context.resources.configuration.locales[0].language == Locale.FRENCH.language
        val res = if (fr) R.raw.privacy_policy_fr else R.raw.privacy_policy_en
        context.resources.openRawResource(res).bufferedReader().readText()
            // Markdown links read as raw [text](url) noise on screen; keep the visible text only.
            .replace(Regex("""\[([^\]]+)]\([^)]*\)""")) { it.groupValues[1] }
    }
    BasicLayoutWithTopBar(
        title = stringResource(R.string.settings_privacy),
        leftAction = { navController.popBackStack() },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // A tiny markdown-lite renderer: headings, emphasis lines, bullets, paragraphs. The
            // policy is hand-written prose — no tables, no links that need to be tappable — so a
            // full markdown engine (a new dependency) would buy nothing.
            for (block in text.split(Regex("\n\n+"))) {
                val b = block.trim()
                if (b.isEmpty()) continue
                when {
                    b.startsWith("# ") -> Text(
                        b.removePrefix("# "),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    )
                    b.startsWith("## ") -> Text(
                        b.removePrefix("## "),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    b.startsWith("*") && b.endsWith("*") -> Text(
                        b.trim('*').replace("\n", " "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    b.startsWith("- ") -> Column(Modifier.padding(bottom = 8.dp)) {
                        for (item in b.split(Regex("\n(?=- )"))) {
                            Text(
                                "\u2022 " + item.removePrefix("- ").replace("\n", " ").replace("**", "").replace("`", ""),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    else -> Text(
                        b.replace("\n", " ").replace("**", "").replace("`", ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}
