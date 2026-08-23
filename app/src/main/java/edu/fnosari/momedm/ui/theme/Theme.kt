package edu.fnosari.momedm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = OnMint,
    secondary = GreenDark,
    onSecondary = Color.White,
    secondaryContainer = GrayChip,
    onSecondaryContainer = OnGrayChip,
    background = OffWhite,
    onBackground = InkDark,
    surface = SheetWhite,
    onSurface = InkDark,
    surfaceVariant = Color(0xFFEAEFEC),
    onSurfaceVariant = Color(0xFF49544F),
    outline = Color(0xFFC2CCC7),
    error = Color(0xFFC0392B),
    errorContainer = Color(0xFFF7DAD5),
    onErrorContainer = Color(0xFF7B241C),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FBFA4),
    onPrimary = Color(0xFF00382C),
    primaryContainer = MintDark,
    onPrimaryContainer = Mint,
    secondary = Color(0xFF9ED9C8),
    secondaryContainer = Color(0xFF37423E),
    onSecondaryContainer = Color(0xFFD6DDD9),
    background = Color(0xFF141917),
    onBackground = Color(0xFFE2E7E4),
    surface = Color(0xFF1B211E),
    onSurface = Color(0xFFE2E7E4),
    surfaceVariant = Color(0xFF2A322E),
    onSurfaceVariant = Color(0xFFB6C0BA),
    outline = Color(0xFF56605B),
)

private val PronoteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

const val THEME_SYSTEM = "system"
const val THEME_LIGHT = "light"
const val THEME_DARK = "dark"

/** Resolves the stored theme preference against the current system setting. */
fun isDarkTheme(pref: String, systemDark: Boolean): Boolean = when (pref) {
    THEME_DARK -> true
    THEME_LIGHT -> false
    else -> systemDark
}

/** Recolours the scheme around the parent's chosen main colour. */
private fun ColorScheme.withSeed(seed: Int, dark: Boolean): ColorScheme {
    val primary = Palette.primaryFor(seed, dark)
    return copy(
        primary = Color(primary),
        onPrimary = Color(Palette.onColor(primary)),
        primaryContainer = Color(
            if (dark) Palette.darken(seed, 0.55f) else Palette.lighten(seed, 0.82f)
        ),
        onPrimaryContainer = Color(
            if (dark) Palette.lighten(seed, 0.75f) else Palette.darken(seed, 0.45f)
        ),
        secondary = Color(if (dark) Palette.lighten(seed, 0.6f) else Palette.darken(seed, 0.2f)),
        onSecondary = Color(Palette.onColor(if (dark) Palette.lighten(seed, 0.6f) else Palette.darken(seed, 0.2f))),
    )
}

@Composable
fun MomeDMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seed: Int = Palette.DEFAULT,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = base.withSeed(seed, darkTheme),
        typography = Typography,
        shapes = PronoteShapes,
        content = content,
    )
}
