package com.frederikberg.pai

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.frederikberg.pai.settings.SettingsTags
import com.frederikberg.pai.shell.ShellDestination
import com.frederikberg.pai.shell.ShellTags
import com.frederikberg.pai.signin.SignInTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The whole signed-in path on canned data: the shell, settings, sign-out back to the sign-in screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class MainActivityFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun launchFixture(): ActivityScenario<MainActivity> {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(FixtureMode.EXTRA, true)
        return ActivityScenario.launch(intent)
    }

    private fun waitForText(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun fixtureModeOpensOnTheShellAndWalksToSettingsAndBackToSignIn() {
        launchFixture().use {
            waitForText("Alex Example")
            check(ApplicationProvider.getApplicationContext<PaiApp>().currentGraph().fixtureMode)

            compose.onNodeWithTag(ShellTags.destination(ShellDestination.Settings)).performClick()
            waitForText("Connection")

            compose.onNodeWithTag(SettingsTags.SIGN_OUT).performClick()
            compose.onNodeWithTag(SettingsTags.CONFIRM_SIGN_OUT).performClick()

            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasTestTag(SignInTags.SIGN_IN)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun backFromSettingsReturnsToTheShell() {
        launchFixture().use { scenario ->
            waitForText("Alex Example")
            compose.onNodeWithTag(ShellTags.destination(ShellDestination.Settings)).performClick()
            waitForText("Connection")

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            waitForText("Alex Example")
        }
    }
}
