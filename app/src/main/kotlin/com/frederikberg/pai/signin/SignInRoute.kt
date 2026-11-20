package com.frederikberg.pai.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.frederikberg.pai.kit.session.Rejection
import com.frederikberg.pai.kit.session.SessionController

/**
 * The sign-in screen wired to the session: turns taps into session calls. [openBrowser] shows the
 * provider's page and is the one thing here that needs an Activity.
 */
@Composable
fun SignInRoute(
    session: SessionController,
    rejection: Rejection?,
    fixtureMode: Boolean,
    openBrowser: (url: String) -> Unit,
    launch: (suspend () -> Unit) -> Unit,
) {
    val status by session.signInStatus.collectAsState()

    SignInScreen(
        state = SignInUiState(status, rejection, fixtureMode),
        onSignIn = { launch { session.beginSignIn()?.let(openBrowser) } },
        onCancel = session::cancelSignIn,
    )
}
