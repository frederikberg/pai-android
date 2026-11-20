package com.frederikberg.pai.platform

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun prefs(fixtureMode: Boolean = false) = AppPreferences(context, fixtureMode)

    @Test
    fun theThemeFollowsTheSystemUntilChosenAndThenSurvivesARestart() {
        assertEquals(ThemeChoice.System, prefs().theme.value)

        prefs().setTheme(ThemeChoice.Dark)

        assertEquals(ThemeChoice.Dark, prefs().theme.value)
    }

    @Test
    fun aFixtureRunNeverTouchesTheRealSettings() {
        prefs(fixtureMode = true).setTheme(ThemeChoice.Light)

        assertEquals(ThemeChoice.System, prefs().theme.value)
        assertEquals(ThemeChoice.Light, prefs(fixtureMode = true).theme.value)
    }
}
