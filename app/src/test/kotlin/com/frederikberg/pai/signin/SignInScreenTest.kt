package com.frederikberg.pai.signin

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.pai.kit.session.Rejection
import com.frederikberg.pai.kit.session.SignInStatus
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
class SignInScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: SignInUiState) {
        compose.setContent {
            PaiTheme(dynamicColor = false) {
                SignInScreen(
                    state = state,
                    onSignIn = { calls += "sign-in" },
                    onCancel = { calls += "cancel" },
                )
            }
        }
    }

    private fun state(
        status: SignInStatus = SignInStatus.Idle,
        rejection: Rejection? = null,
    ) = SignInUiState(status, rejection)

    @Test
    fun theFirstLaunchOffersSignIn() {
        show(state())

        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signin-first-launch.png")
    }

    @Test
    fun tappingSignInAsks() {
        show(state())

        compose.onNodeWithTag(SignInTags.SIGN_IN).performClick()

        assertEquals(listOf("sign-in"), calls)
    }

    @Test
    fun whileTheBrowserIsOpenTheScreenWaitsAndOffersToCancel() {
        show(state(status = SignInStatus.WaitingForBrowser))

        compose.onNodeWithText("Waiting for the browser…").assertExists()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun whileTheCodeIsTradedThereIsNothingToCancel() {
        show(state(status = SignInStatus.Exchanging))

        compose.onNodeWithText("Finishing sign-in…").assertExists()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsNotEnabled()
    }

    @Test
    fun aFailureIsShownInPlace() {
        show(state(status = SignInStatus.Failed("The sign-in service could not be reached.")))

        compose.onNodeWithText("The sign-in service could not be reached.").assertExists()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsEnabled()
    }

    @Test
    fun aRefusedCredentialSaysSoInsteadOfReadingAsAFirstLaunch() {
        show(state(rejection = Rejection("token_expired")))

        compose.onNodeWithText("Your sign-in was refused. Sign in again.").assertExists()
        compose.onNodeWithText("token_expired").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signin-rejected.png")
    }

    @Test
    fun anUnrecognisedDeviceIsToldTheSameAsARevokedOne() {
        show(state(rejection = Rejection("device_unrecognised")))

        compose.onNodeWithText("This device was signed out", substring = true).assertExists()
        compose.onNodeWithText("device_unrecognised").assertDoesNotExist()
    }

    @Test
    fun aRevokedDeviceIsToldItWasSignedOut() {
        show(state(rejection = Rejection("device_revoked")))

        compose.onNodeWithText("This device was signed out", substring = true).assertExists()
        compose.onNodeWithText("Your sign-in was refused. Sign in again.").assertDoesNotExist()
        compose.onNodeWithText("device_revoked").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signin-device-revoked.png")
    }
}
