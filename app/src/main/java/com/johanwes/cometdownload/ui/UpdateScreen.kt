package com.johanwes.cometdownload.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.johanwes.cometdownload.formatBytes
import com.johanwes.cometdownload.update.AppRelease
import com.johanwes.cometdownload.update.AppUpdater
import com.johanwes.cometdownload.update.UpdateState

/** Colour of the "update waiting" dot and icon. */
val UpdateYellow = Color(0xFFFFC83D)

/** The Update tab: says whether a newer version exists and installs it from GitHub. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(showSettingsAction: Boolean, onSettings: () -> Unit) {
    val context = LocalContext.current
    val installed = remember { AppUpdater.installedVersion(context) }
    // Asked again whenever the screen comes back, e.g. from the system's permission page.
    var canInstall by remember { mutableStateOf(AppUpdater.canInstall(context)) }
    LifecycleResumeEffect(Unit) {
        canInstall = AppUpdater.canInstall(context)
        AppUpdater.check(context)
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Update")
                    Text(
                        "Version $installed installed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = { if (showSettingsAction) SettingsAction(onSettings) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 380.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val state = AppUpdater.state) {
                    UpdateState.Unknown, UpdateState.Checking -> {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                        Body("Checking for updates…")
                    }
                    is UpdateState.UpToDate -> {
                        StatusIcon(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary)
                        Title("No updates")
                        Body("You have the latest version.")
                        TextButton(onClick = { AppUpdater.check(context, force = true) }) { Text("Check again") }
                    }
                    is UpdateState.CheckFailed -> {
                        StatusIcon(Icons.Outlined.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant)
                        Title("Could not check for updates")
                        Body(state.message)
                        TextButton(onClick = { AppUpdater.check(context, force = true) }) { Text("Try again") }
                    }
                    is UpdateState.Available -> {
                        StatusIcon(Icons.Outlined.SystemUpdate, UpdateYellow)
                        Title("Update available: ${state.release.version}")
                        if (state.problem != null) {
                            Text(
                                state.problem,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        } else if (!canInstall) {
                            Body("Allow Comet Download to install its own updates first. You only do this once.")
                        } else {
                            Body("Your settings and library are kept. The app closes while it installs; tap the notification to open it again.")
                        }
                        if (canInstall) {
                            Button(
                                onClick = { AppUpdater.downloadAndInstall(context) },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text(downloadLabel(state.release)) }
                        } else {
                            Button(
                                onClick = { runCatching { context.startActivity(AppUpdater.installPermissionIntent(context)) } },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Allow updates") }
                        }
                        WhatsNew(state.release)
                    }
                    is UpdateState.Downloading -> {
                        StatusIcon(Icons.Outlined.SystemUpdate, UpdateYellow)
                        Title("Downloading ${state.release.version}")
                        val progress = state.progress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Body("${(progress * 100).toInt()}%")
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                    is UpdateState.Installing -> {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                        Title("Installing ${state.release.version}")
                        Body("Confirm the installation if Android asks. The app then closes; tap the notification to open the new version.")
                    }
                }
            }
        }
    }
}

private fun downloadLabel(release: AppRelease): String =
    "Download and install" + (release.size.takeIf { it > 0 }?.let { " (${formatBytes(it)})" } ?: "")

@Composable
private fun WhatsNew(release: AppRelease) {
    if (release.pageUrl.isBlank()) return
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(release.pageUrl) } }) { Text("What's new") }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(16.dp).size(32.dp))
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
