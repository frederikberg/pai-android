package com.frederikberg.pai.shell

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.pai.kit.live.OfflineNotice
import com.frederikberg.pai.kit.live.Reachability
import com.frederikberg.pai.ui.theme.PaiTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ShellScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<ShellDestination>()

    private fun show(state: ShellUiState) {
        compose.setContent { PaiTheme(dynamicColor = false) { ShellScreen(state, onOpen = { opened += it }) } }
    }

    private val guest = ShellUiState(displayName = "Alex Example", identity = "alex@example.com", isOwner = false)

    @Test
    fun theMeRowNamesThePersonAndTheirRole() {
        show(guest)

        compose.onNodeWithTag(ShellTags.ME_NAME, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Alex Example").assertExists()
        compose.onNodeWithText("alex@example.com").assertExists()
        compose.onNodeWithText("Guest").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shell.png")
    }

    @Test
    fun theOwnerIsLabelledAsOne() {
        show(guest.copy(isOwner = true))

        compose.onNodeWithText("Owner").assertExists()
    }

    @Test
    fun beforeTheServerHasAnsweredTheRowSaysOnlyThatTheyAreSignedIn() {
        show(ShellUiState(displayName = null, identity = null, isOwner = false))

        compose.onNodeWithText("Signed in").assertExists()
    }

    @Test
    fun onlyDestinationsThatExistCanBeOpened() {
        show(guest)

        compose.onNodeWithTag(ShellTags.destination(ShellDestination.Sessions)).performClick()
        compose.onNodeWithTag(ShellTags.destination(ShellDestination.Notifications)).performClick()
        assertEquals(emptyList<ShellDestination>(), opened)

        compose.onNodeWithTag(ShellTags.destination(ShellDestination.Settings)).assertHasClickAction().performClick()
        assertEquals(listOf(ShellDestination.Settings), opened)
    }

    @Test
    fun anOfflineServerIsSaidSoInACapsule() {
        show(guest.copy(notice = OfflineNotice.make(Reachability.Offline)))

        compose.onNodeWithText("Offline").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shell-offline.png")
    }
}
