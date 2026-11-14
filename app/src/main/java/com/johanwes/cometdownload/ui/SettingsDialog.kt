package com.johanwes.cometdownload.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.johanwes.cometdownload.AppSettings
import com.johanwes.cometdownload.DEFAULT_SUBTITLE_LANGUAGES
import com.johanwes.cometdownload.Provider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    current: AppSettings,
    downloadsPath: String,
    firstRun: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> String?,
    onTestDebrid: suspend (Provider, String) -> Result<String>,
    onTestSubtitles: suspend (String, String, String) -> Result<String>,
) {
    var draft by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    // Captured once: saving on first run flips the live flag while the dialog fades out.
    val firstRun = remember { firstRun }
    // Whatever had focus underneath (e.g. the search field) shouldn't keep the keyboard up.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus(force = true) }

    // Drawn inside the activity window rather than a platform Dialog: the dialog window
    // doesn't pass system bar / keyboard insets through on all devices, this does.
    BackHandler(enabled = !firstRun, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
    // Full-screen scrim: taps on it dismiss. It sits behind the card as a sibling rather
    // than wrapping it, because a parent's clickable also fires for taps on the card's
    // empty areas (labels, padding) and would throw the draft away.
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (!firstRun) onDismiss()
            },
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (firstRun) "Welcome to Comet" else "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (firstRun) {
                        Text(
                            "Connect your debrid account to start searching and downloading.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Section("Debrid service") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Provider.entries.forEachIndexed { index, provider ->
                            SegmentedButton(
                                selected = draft.provider == provider,
                                onClick = { draft = draft.copy(provider = provider) },
                                shape = SegmentedButtonDefaults.itemShape(index, Provider.entries.size),
                            ) { Text(provider.label) }
                        }
                    }
                    SecretField(
                        value = draft.token,
                        onValueChange = {
                            draft = draft.copy(token = it)
                            error = null
                        },
                        label = "API token",
                    )
                    TestRow(
                        linkLabel = "Get token",
                        linkUrl = draft.provider.tokenUrl,
                        testKey = draft.provider to draft.token,
                        canTest = draft.token.isNotBlank(),
                        onTest = { onTestDebrid(draft.provider, draft.token) },
                    )
                }

                Section(
                    "Subtitles",
                    "Optional. Saves an .srt from OpenSubtitles next to each download. " +
                        "Needs an API key and your opensubtitles.com account.",
                ) {
                    SecretField(
                        value = draft.osApiKey,
                        onValueChange = { draft = draft.copy(osApiKey = it) },
                        label = "OpenSubtitles API key",
                    )
                    OutlinedTextField(
                        value = draft.osUsername,
                        onValueChange = { draft = draft.copy(osUsername = it) },
                        label = { Text("Username") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretField(
                        value = draft.osPassword,
                        onValueChange = { draft = draft.copy(osPassword = it) },
                        label = "Password",
                    )
                    TestRow(
                        linkLabel = "Get API key",
                        linkUrl = "https://www.opensubtitles.com/en/consumers",
                        testKey = Triple(draft.osApiKey, draft.osUsername, draft.osPassword),
                        canTest = draft.osApiKey.isNotBlank(),
                        onTest = { onTestSubtitles(draft.osApiKey, draft.osUsername, draft.osPassword) },
                    )
                    OutlinedTextField(
                        value = draft.subtitleLanguages,
                        onValueChange = { draft = draft.copy(subtitleLanguages = it) },
                        label = { Text("Languages") },
                        placeholder = { Text(DEFAULT_SUBTITLE_LANGUAGES) },
                        supportingText = { Text("In order of preference, like “en, sv”. The first one found is used.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Section("Storage") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            downloadsPath,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (!firstRun) TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { error = onSave(draft) }, enabled = draft.token.isNotBlank()) {
                        Text(if (firstRun) "Get started" else "Save")
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Section(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
    }
}

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, supporting: String? = null) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide $label" else "Show $label",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Get a token" link plus a Test button whose result shows inline; edits clear the result. */
@Composable
private fun TestRow(
    linkLabel: String,
    linkUrl: String,
    testKey: Any,
    canTest: Boolean,
    onTest: suspend () -> Result<String>,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember(testKey) { mutableStateOf<Result<String>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { runCatching { uriHandler.openUri(linkUrl) } }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(linkLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                enabled = canTest && !running,
                onClick = {
                    running = true
                    scope.launch {
                        result = onTest()
                        running = false
                    }
                },
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Test")
                }
            }
        }
        result?.let { outcome ->
            val ok = outcome.isSuccess
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    outcome.getOrNull() ?: outcome.exceptionOrNull()?.message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
