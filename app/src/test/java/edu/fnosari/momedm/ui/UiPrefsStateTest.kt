package edu.fnosari.momedm.ui

import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.InMemoryPreferencesProvider
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.ChildPrefs
import edu.fnosari.momedm.ui.theme.Palette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UiPrefsStateTest {
    @Test fun defaultsMatchPalette() = runTest {
        assertEquals(Palette.DEFAULT, ChildPrefs.DEFAULT_ACCENT)
        assertEquals(UiPrefs(), ControllerPrefs(InMemoryPreferencesProvider()).uiPrefs().first())
        assertEquals(UiPrefs(), ManagedPrefs(InMemoryPreferencesProvider()).uiPrefs().first())
    }
    @Test fun flowsFollowPrefs() = runTest {
        val c = ControllerPrefs(InMemoryPreferencesProvider()); c.setUiPrefs("fr", "dark", 7)
        assertEquals(UiPrefs("fr", "dark", 7), c.uiPrefs().first())
        val m = ManagedPrefs(InMemoryPreferencesProvider()); m.setChildPrefs(ChildPrefs("en", "light", 9, null, null))
        assertEquals(UiPrefs("en", "light", 9), m.uiPrefs().first())
    }
}
