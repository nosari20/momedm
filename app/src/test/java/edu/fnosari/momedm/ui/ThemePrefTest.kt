package edu.fnosari.momedm.ui

import edu.fnosari.momedm.ui.theme.THEME_DARK
import edu.fnosari.momedm.ui.theme.THEME_LIGHT
import edu.fnosari.momedm.ui.theme.THEME_SYSTEM
import edu.fnosari.momedm.ui.theme.isDarkTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePrefTest {
    @Test fun explicitDarkWinsOverLightSystem() {
        assertTrue(isDarkTheme(THEME_DARK, systemDark = false))
    }

    @Test fun explicitLightWinsOverDarkSystem() {
        assertFalse(isDarkTheme(THEME_LIGHT, systemDark = true))
    }

    @Test fun systemFollowsSystem() {
        assertTrue(isDarkTheme(THEME_SYSTEM, systemDark = true))
        assertFalse(isDarkTheme(THEME_SYSTEM, systemDark = false))
    }

    @Test fun unknownValueFallsBackToSystem() {
        assertTrue(isDarkTheme("nonsense", systemDark = true))
    }
}
