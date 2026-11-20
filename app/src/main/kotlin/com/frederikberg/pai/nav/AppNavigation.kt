package com.frederikberg.pai.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.frederikberg.pai.AppGraph
import com.frederikberg.pai.kit.session.Connection
import com.frederikberg.pai.settings.SettingsScreen
import com.frederikberg.pai.shell.ShellDestination
import com.frederikberg.pai.shell.ShellRoute
import kotlinx.serialization.Serializable

/** Every destination of the signed-in app. Serializable so the back stack survives process death. */
sealed interface Route {
    @Serializable
    data object Shell : Route

    @Serializable
    data object Settings : Route
}

@Composable
fun AppNavigation(
    graph: AppGraph,
    connection: Connection,
    versionName: String,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = Route.Shell) {
        composable<Route.Shell> {
            ShellRoute(
                connection = connection,
                onOpen = { destination ->
                    when (destination) {
                        ShellDestination.Settings -> navController.navigate(Route.Settings)
                        ShellDestination.Sessions, ShellDestination.Notifications -> Unit
                    }
                },
            )
        }
        composable<Route.Settings> {
            val theme by graph.preferences.theme.collectAsState()
            val me by connection.me.me.collectAsState()
            val capabilities by connection.me.capabilities.collectAsState()
            SettingsScreen(
                theme = theme,
                serverUrl = connection.serverUrl,
                me = me,
                isOwner = capabilities.isOwner,
                versionName = versionName,
                onTheme = graph.preferences::setTheme,
                onSignOut = onSignOut,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
