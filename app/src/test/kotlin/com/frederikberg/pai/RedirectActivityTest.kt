package com.frederikberg.pai

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.frederikberg.pai.kit.session.SignInStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RedirectActivityTest {
    private val app = ApplicationProvider.getApplicationContext<PaiApp>()

    private fun handlers(uri: String): List<String> =
        app.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE),
                0,
            ).map { it.activityInfo.name }

    @Test
    fun onlyTheSignInCallbackAddressReachesTheRedirectActivity() {
        assertEquals(
            listOf("com.frederikberg.pai.RedirectActivity"),
            handlers("pai://oauth/callback?code=c&state=s"),
        )
        assertTrue(handlers("pai://oauth/other?code=c").isEmpty())
        assertTrue(handlers("pai://evil/callback?code=c").isEmpty())
        assertTrue(handlers("other://auth/callback?code=c").isEmpty())
    }

    @Test
    fun aCallbackWithNoSignInInProgressIsReportedAndTheAppComesForward() {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("pai://oauth/callback?code=c&state=s"))
        val activity = Robolectric.buildActivity(RedirectActivity::class.java, intent).create().get()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(activity.isFinishing)
        val session = app.currentGraph().session
        runBlocking { withTimeout(5_000) { while (session.signInStatus.value !is SignInStatus.Failed) delay(10) } }
    }
}
