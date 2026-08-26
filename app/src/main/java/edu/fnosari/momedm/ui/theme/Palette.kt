package edu.fnosari.momedm.ui.theme

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The app's main colour, chosen by the parent. Plain ARGB maths so it can be unit-tested and
 * used from places that have no Compose scope, such as the system bar setup in MainActivity.
 */
object Palette {
    // Night blue, matching the app icon's sky. The old Pronote green remains a preset below.
    const val DEFAULT = 0xFF3D5A8F.toInt()

    /** Offered in settings. Deep enough to carry white text in light mode. */
    val PRESETS = listOf(
        DEFAULT,                  // night blue (the icon's sky)
        0xFF16866F.toInt(),       // green (Pronote-ish)
        0xFF10777C.toInt(),       // teal
        0xFF1D6FA5.toInt(),       // blue
        0xFF4A55A2.toInt(),       // indigo
        0xFF7A4E9B.toInt(),       // purple
        0xFFA8447A.toInt(),       // plum
        0xFFB4462F.toInt(),       // brick
        0xFFB07213.toInt(),       // ochre
        0xFF4E5D6C.toInt(),       // slate
    )

    fun red(argb: Int) = (argb shr 16) and 0xFF
    fun green(argb: Int) = (argb shr 8) and 0xFF
    fun blue(argb: Int) = argb and 0xFF

    /** [t] = 0 keeps [from], 1 gives [to]. Alpha is taken from [from]. */
    fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * f).roundToInt().coerceIn(0, 255)
        return (from and 0xFF000000.toInt()) or
            (mix(red(from), red(to)) shl 16) or
            (mix(green(from), green(to)) shl 8) or
            mix(blue(from), blue(to))
    }

    fun lighten(argb: Int, t: Float) = blend(argb, 0xFFFFFFFF.toInt(), t)
    fun darken(argb: Int, t: Float) = blend(argb, 0xFF000000.toInt(), t)

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun luminance(argb: Int): Float {
        fun channel(v: Int): Float {
            val c = v / 255f
            return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        return 0.2126f * channel(red(argb)) +
            0.7152f * channel(green(argb)) +
            0.0722f * channel(blue(argb))
    }

    /**
     * True when dark text and dark system-bar icons belong on top of this colour.
     *
     * The threshold sits above the WCAG crossover (0.179), which keeps white text on every
     * preset in light mode — they are brand surfaces and all land below it — while the paler
     * primaries used in dark mode flip to dark text, where white would be unreadable.
     */
    fun isLight(argb: Int): Boolean = luminance(argb) > 0.28f

    fun onColor(argb: Int): Int = if (isLight(argb)) 0xFF14201C.toInt() else 0xFFFFFFFF.toInt()

    /**
     * The colour actually used as `colorScheme.primary`: the chosen one in light mode, a lighter
     * version in dark mode, where a deep colour would swallow the top bar.
     */
    fun primaryFor(seed: Int, dark: Boolean): Int = if (dark) lighten(seed, 0.45f) else seed

    /** [h] in degrees 0–360, [s] and [v] in 0–1. */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val hue = ((h % 360f) + 360f) % 360f
        val sat = s.coerceIn(0f, 1f)
        val value = v.coerceIn(0f, 1f)
        val c = value * sat
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val m = value - c
        val (r, g, b) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    /** Returns hue in degrees, saturation and value in 0–1. */
    fun argbToHsv(argb: Int): Triple<Float, Float, Float> {
        val r = red(argb) / 255f
        val g = green(argb) / 255f
        val b = blue(argb) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return Triple(((hue % 360f) + 360f) % 360f, if (max == 0f) 0f else delta / max, max)
    }

    fun toHex(argb: Int): String = String.format("%06X", argb and 0xFFFFFF)

    /** Accepts "1B6E5B" or "#1b6e5b"; null when it is not six hex digits. */
    fun parseHex(text: String): Int? {
        val clean = text.trim().removePrefix("#")
        if (clean.length != 6 || clean.any { it.digitToIntOrNull(16) == null }) return null
        return (0xFF shl 24) or clean.toInt(16)
    }
}
