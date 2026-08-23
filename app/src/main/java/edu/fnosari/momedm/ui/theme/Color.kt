package edu.fnosari.momedm.ui.theme

import androidx.compose.ui.graphics.Color

// Pronote-inspired palette
val GreenPrimary = Color(0xFF16866F)      // deep teal-green
val GreenDark = Color(0xFF0E5D4D)
val Mint = Color(0xFFCDEBDD)              // pastel green card
val MintDark = Color(0xFF0F3B31)
val OnMint = Color(0xFF0E4A3C)
val SheetWhite = Color(0xFFFFFFFF)
val OffWhite = Color(0xFFF4F6F4)          // subtle app background
val InkDark = Color(0xFF1E2523)
val GrayChip = Color(0xFFE8E8E8)
val OnGrayChip = Color(0xFF4C4C4C)

// Subject/class accent hues (stripe + chips), Pronote-style
val ClassPalette = listOf(
    Color(0xFF16866F),  // green
    Color(0xFFD4498A),  // pink
    Color(0xFF8A5FBF),  // lilac
    Color(0xFFE08A2E),  // orange
    Color(0xFF3F7FC1),  // blue
    Color(0xFF7EA43C),  // olive
)

fun classColor(id: Long): Color = ClassPalette[(id % ClassPalette.size).toInt()]

/** Pastel fill matching an accent color, for pill chips. */
fun pastelOf(accent: Color): Color = accent.copy(alpha = 0.16f)
