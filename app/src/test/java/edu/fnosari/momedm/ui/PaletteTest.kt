package edu.fnosari.momedm.ui

import edu.fnosari.momedm.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    private val green = Palette.DEFAULT      // 0xFF16866F

    @Test fun blendEndsAreTheInputs() {
        assertEquals(green, Palette.blend(green, 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), Palette.blend(green, 0xFFFFFFFF.toInt(), 1f))
    }

    @Test fun blendKeepsAlphaAndMovesTowardsTarget() {
        val mid = Palette.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(0xFF, (mid shr 24) and 0xFF)
        assertEquals(128, Palette.red(mid))
        assertEquals(128, Palette.green(mid))
        assertEquals(128, Palette.blue(mid))
    }

    @Test fun lightenRaisesLuminanceAndDarkenLowersIt() {
        assertTrue(Palette.luminance(Palette.lighten(green, 0.5f)) > Palette.luminance(green))
        assertTrue(Palette.luminance(Palette.darken(green, 0.5f)) < Palette.luminance(green))
    }

    @Test fun luminanceOfBlackAndWhiteAreTheExtremes() {
        assertEquals(0f, Palette.luminance(0xFF000000.toInt()), 0.001f)
        assertEquals(1f, Palette.luminance(0xFFFFFFFF.toInt()), 0.001f)
    }

    @Test fun everyPresetTakesWhiteTextInLightMode() {
        // the light theme paints the top bar with the raw preset, so none may read as "light"
        Palette.PRESETS.forEach { preset ->
            assertFalse("preset ${Integer.toHexString(preset)}", Palette.isLight(preset))
            assertEquals(0xFFFFFFFF.toInt(), Palette.onColor(preset))
        }
    }

    @Test fun darkModePrimaryIsLighterThanTheSeed() {
        Palette.PRESETS.forEach { preset ->
            val darkPrimary = Palette.primaryFor(preset, dark = true)
            assertTrue(Palette.luminance(darkPrimary) > Palette.luminance(preset))
        }
        assertEquals(green, Palette.primaryFor(green, dark = false))
    }

    @Test fun darkModeTopBarTakesDarkTextForEveryPreset() {
        // the dark theme lightens the primary, and white on a pale bar is unreadable
        Palette.PRESETS.forEach { preset ->
            val darkPrimary = Palette.primaryFor(preset, dark = true)
            assertTrue("preset ${Integer.toHexString(preset)}", Palette.isLight(darkPrimary))
            assertEquals(0xFF14201C.toInt(), Palette.onColor(darkPrimary))
        }
    }

    @Test fun hsvRoundTripsThroughEveryPreset() {
        Palette.PRESETS.forEach { preset ->
            val (h, s, v) = Palette.argbToHsv(preset)
            // one step of rounding per channel is tolerable; the colour must not drift
            val back = Palette.hsvToArgb(h, s, v)
            assertEquals(Palette.red(preset).toFloat(), Palette.red(back).toFloat(), 1f)
            assertEquals(Palette.green(preset).toFloat(), Palette.green(back).toFloat(), 1f)
            assertEquals(Palette.blue(preset).toFloat(), Palette.blue(back).toFloat(), 1f)
        }
    }

    @Test fun hsvHitsThePrimaries() {
        assertEquals(0xFFFF0000.toInt(), Palette.hsvToArgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), Palette.hsvToArgb(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), Palette.hsvToArgb(240f, 1f, 1f))
        assertEquals(0xFFFFFFFF.toInt(), Palette.hsvToArgb(0f, 0f, 1f))
        assertEquals(0xFF000000.toInt(), Palette.hsvToArgb(0f, 0f, 0f))
    }

    @Test fun hueWrapsInsteadOfBreaking() {
        assertEquals(Palette.hsvToArgb(10f, 1f, 1f), Palette.hsvToArgb(370f, 1f, 1f))
        assertEquals(Palette.hsvToArgb(350f, 1f, 1f), Palette.hsvToArgb(-10f, 1f, 1f))
    }

    @Test fun hexRoundTripsAndRejectsRubbish() {
        assertEquals("16866F", Palette.toHex(green))
        assertEquals(green, Palette.parseHex("16866F"))
        assertEquals(green, Palette.parseHex("#16866f"))
        assertEquals(green, Palette.parseHex("  16866F "))
        assertEquals(null, Palette.parseHex("16866"))
        assertEquals(null, Palette.parseHex("16866FF"))
        assertEquals(null, Palette.parseHex("ZZZZZZ"))
        assertEquals(null, Palette.parseHex(""))
    }

    @Test fun onColorFlipsWithBackgroundBrightness() {
        assertEquals(0xFFFFFFFF.toInt(), Palette.onColor(0xFF000000.toInt()))
        assertEquals(0xFF14201C.toInt(), Palette.onColor(0xFFFFFFFF.toInt()))
    }
}
