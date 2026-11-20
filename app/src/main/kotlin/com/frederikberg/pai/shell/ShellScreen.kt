package com.frederikberg.pai.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frederikberg.pai.R
import com.frederikberg.pai.kit.live.OfflineNotice
import com.frederikberg.pai.ui.OfflineCapsule

/** The places the signed-in shell leads to. A destination whose screen is not built yet is listed but cannot be opened. */
enum class ShellDestination(
    val label: Int,
    val available: Boolean,
) {
    Sessions(R.string.shell_sessions, available = false),
    Notifications(R.string.shell_notifications, available = false),
    Settings(R.string.shell_settings, available = true),
}

object ShellTags {
    const val ME = "shell-me"
    const val ME_NAME = "shell-me-name"
    const val ME_ROLE = "shell-me-role"

    fun destination(destination: ShellDestination) = "shell-destination-${destination.name.lowercase()}"
}

/** What the shell draws about the person: [displayName] is null until `GET /api/me` has answered. */
data class ShellUiState(
    val displayName: String?,
    val identity: String?,
    val isOwner: Boolean,
    val notice: OfflineNotice? = null,
)

/** Who is signed in, and the way to every part of the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(
    state: ShellUiState,
    onOpen: (ShellDestination) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                ListItem(
                    headlineContent = {
                        Text(
                            state.displayName ?: stringResource(R.string.shell_signed_in),
                            modifier = Modifier.testTag(ShellTags.ME_NAME),
                        )
                    },
                    supportingContent = {
                        Text(
                            stringResource(if (state.isOwner) R.string.shell_role_owner else R.string.shell_role_guest),
                            modifier = Modifier.testTag(ShellTags.ME_ROLE),
                        )
                    },
                    overlineContent = state.identity?.let { { Text(it) } },
                    modifier = Modifier.testTag(ShellTags.ME),
                )
                HorizontalDivider()
                ShellDestination.entries.forEach { destination ->
                    val label = stringResource(destination.label)
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent =
                            if (destination.available) null else ({ Text(stringResource(R.string.shell_not_yet)) }),
                        colors =
                            ListItemDefaults.colors(
                                headlineColor =
                                    if (destination.available) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            ),
                        modifier =
                            Modifier
                                .testTag(ShellTags.destination(destination))
                                .then(
                                    if (destination.available) Modifier.clickable { onOpen(destination) } else Modifier,
                                ),
                    )
                }
            }
            state.notice?.let {
                OfflineCapsule(it, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}
