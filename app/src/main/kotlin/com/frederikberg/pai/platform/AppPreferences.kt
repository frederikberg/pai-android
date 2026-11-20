package com.frederikberg.pai.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which colour scheme the app uses; [System] follows the device. */
enum class ThemeChoice { System, Light, Dark }

/**
 * Everything the app remembers that is not a credential. A fixture run gets its own file so canned
 * data never leaks into the real settings.
 */
class AppPreferences(
    context: Context,
    fixtureMode: Boolean,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(if (fixtureMode) FIXTURE_FILE else FILE, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    fun setTheme(choice: ThemeChoice) {
        prefs.edit().putString(THEME, choice.name).apply()
        _theme.value = choice
    }

    private fun readTheme(): ThemeChoice =
        ThemeChoice.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: ThemeChoice.System

    private companion object {
        const val FILE = "pai"
        const val FIXTURE_FILE = "pai_fixture"
        const val THEME = "theme"
    }
}
