package com.frederikberg.pai.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.frederikberg.pai.kit.live.OfflineNotice
import com.frederikberg.pai.kit.session.Connection

/** The shell wired to the signed-in connection: who `GET /api/me` says is signed in, and whether the server is reachable. */
@Composable
fun ShellRoute(
    connection: Connection,
    onOpen: (ShellDestination) -> Unit,
) {
    val me by connection.me.me.collectAsState()
    val capabilities by connection.me.capabilities.collectAsState()
    val reachability by connection.reachability.collectAsState()
    ShellScreen(
        state =
            ShellUiState(
                displayName = me?.displayName,
                identity = me?.identity,
                isOwner = capabilities.isOwner,
                notice = OfflineNotice.make(reachability),
            ),
        onOpen = onOpen,
    )
}
