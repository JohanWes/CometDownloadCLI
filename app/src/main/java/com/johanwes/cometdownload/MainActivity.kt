package com.johanwes.cometdownload

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var api: PyObject

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = securePrefs()
        requestStoragePermission()
        requestManageStoragePermission()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloads.mkdirs()
        api = Python.getInstance()
            .getModule("comet.android_api")
            .callAttr("get_api", downloads.absolutePath, 2)

        setContent {
            CometApp(api = api, prefs = prefs, downloadsPath = downloads.absolutePath)
        }
    }

    override fun onDestroy() {
        if (::api.isInitialized && isFinishing) {
            runCatching { api.callAttr("shutdown") }
        }
        super.onDestroy()
    }

    private fun securePrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                "comet_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            getSharedPreferences("comet_secrets_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ),
                1001,
            )
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            return
        }
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(appIntent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
    }
}

data class MediaItem(
    val json: String,
    val mediaType: String,
    val title: String,
    val year: String,
    val imdbId: String,
)

data class StreamItem(
    val json: String,
    val index: Int,
    val resolution: String,
    val cached: Boolean,
    val sizeBytes: Long,
    val description: String,
    val subtitleHint: Boolean,
)

data class JobItem(
    val id: Int,
    val label: String,
    val status: String,
    val phase: String,
    val totalFiles: Int,
    val completedFiles: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytes: Double,
    val etaSeconds: Double,
    val destinations: List<String>,
    val errorText: String,
)

@Composable
fun CometApp(api: PyObject, prefs: SharedPreferences, downloadsPath: String) {
    var provider by remember { mutableStateOf(prefs.getString("provider", "realdebrid") ?: "realdebrid") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var osKey by remember { mutableStateOf(prefs.getString("opensubtitles_api_key", "") ?: "") }
    var osUser by remember { mutableStateOf(prefs.getString("opensubtitles_username", "") ?: "") }
    var osPassword by remember { mutableStateOf(prefs.getString("opensubtitles_password", "") ?: "") }
    var setupOpen by remember { mutableStateOf(token.isBlank()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val mediaResults = remember { mutableStateListOf<MediaItem>() }
    val streamResults = remember { mutableStateListOf<StreamItem>() }
    val jobs = remember { mutableStateListOf<JobItem>() }
    var selectedMedia by remember { mutableStateOf<MediaItem?>(null) }
    var season by remember { mutableStateOf("") }
    var episode by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(provider, token, osKey, osUser, osPassword) {
        if (token.isNotBlank()) {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.callAttr("configure", provider, token, osKey, osUser, osPassword)
                }
            }.onFailure { error = it.message ?: "Configuration failed." }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (token.isNotBlank()) {
                runCatching {
                    val parsed = withContext(Dispatchers.IO) { parseJobs(api.callAttr("jobs").toString()) }
                    jobs.clear()
                    jobs.addAll(parsed)
                }
            }
            delay(1200)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    CometTopBar(
                        provider = provider,
                        downloadsPath = downloadsPath,
                        onSettings = { setupOpen = true },
                        onRefreshJobs = {
                            runCatching {
                                val parsed = api.callAttr("jobs").toString()
                                jobs.clear()
                                jobs.addAll(parseJobs(parsed))
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SearchPanel(
                        query = query,
                        onQueryChange = { query = it },
                        busy = busy,
                        onSearch = {
                            busy = true
                            error = ""
                            selectedMedia = null
                            streamResults.clear()
                            mediaResults.clear()
                            runAsync(
                                block = { api.callAttr("search", query).toString() },
                                onDone = {
                                    mediaResults.addAll(parseMedia(it))
                                    busy = false
                                },
                                onError = {
                                    error = it
                                    busy = false
                                },
                            )
                        },
                    )
                    if (error.isNotBlank()) ErrorText(error)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(2f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MediaResults(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            items = mediaResults,
                            selected = selectedMedia,
                            onSelect = {
                                selectedMedia = it
                                streamResults.clear()
                                season = ""
                                episode = ""
                            },
                        )
                        StreamPanel(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            media = selectedMedia,
                            season = season,
                            episode = episode,
                            onSeasonChange = { season = it.filter(Char::isDigit) },
                            onEpisodeChange = { episode = it.filter(Char::isDigit) },
                            streams = streamResults,
                            busy = busy,
                            onLoadStreams = {
                                val media = selectedMedia ?: return@StreamPanel
                                busy = true
                                error = ""
                                streamResults.clear()
                                runAsync(
                                    block = {
                                        api.callAttr(
                                            "streams",
                                            media.json,
                                            season.toIntOrNull() ?: 0,
                                            episode.toIntOrNull() ?: 0,
                                        ).toString()
                                    },
                                    onDone = {
                                        streamResults.addAll(parseStreams(it))
                                        busy = false
                                    },
                                    onError = {
                                        error = it
                                        busy = false
                                    },
                                )
                            },
                            onDownload = { stream ->
                                val media = selectedMedia ?: return@StreamPanel
                                busy = true
                                error = ""
                                runAsync(
                                    block = {
                                        api.callAttr(
                                            "enqueue",
                                            media.json,
                                            stream.json,
                                            season.toIntOrNull() ?: 0,
                                            episode.toIntOrNull() ?: 0,
                                        ).toString()
                                    },
                                    onDone = {
                                        jobs.clear()
                                        jobs.addAll(parseJobs(api.callAttr("jobs").toString()))
                                        busy = false
                                    },
                                    onError = {
                                        error = it
                                        busy = false
                                    },
                                )
                            },
                        )
                    }
                    JobPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        jobs = jobs,
                        onCancel = { id ->
                            runCatching { api.callAttr("cancel", id) }
                        },
                        onClear = {
                            runCatching {
                                api.callAttr("clear_finished")
                                jobs.clear()
                                jobs.addAll(parseJobs(api.callAttr("jobs").toString()))
                            }
                        },
                    )
                }
            }

            if (setupOpen) {
                SetupDialogLikePanel(
                    provider = provider,
                    token = token,
                    osKey = osKey,
                    osUser = osUser,
                    osPassword = osPassword,
                    onProviderChange = { provider = it },
                    onTokenChange = { token = it },
                    onOsKeyChange = { osKey = it },
                    onOsUserChange = { osUser = it },
                    onOsPasswordChange = { osPassword = it },
                    onSave = {
                        if (token.isBlank()) {
                            error = "A provider API token is required."
                        } else {
                            prefs.edit()
                                .putString("provider", provider)
                                .putString("token", token)
                                .putString("opensubtitles_api_key", osKey)
                                .putString("opensubtitles_username", osUser)
                                .putString("opensubtitles_password", osPassword)
                                .apply()
                            runCatching {
                                api.callAttr("configure", provider, token, osKey, osUser, osPassword)
                            }.onSuccess {
                                setupOpen = false
                                error = ""
                            }.onFailure {
                                error = it.message ?: "Configuration failed."
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CometTopBar(provider: String, downloadsPath: String, onSettings: () -> Unit, onRefreshJobs: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("Comet Download", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${providerLabel(provider)} · $downloadsPath",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(onClick = onRefreshJobs) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh jobs")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
    )
}

@Composable
fun SearchPanel(query: String, onQueryChange: (String) -> Unit, busy: Boolean, onSearch: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Movie or series") },
                singleLine = true,
            )
            Button(onClick = onSearch, enabled = !busy && query.isNotBlank()) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Search")
            }
        }
    }
}

@Composable
fun MediaResults(
    modifier: Modifier = Modifier,
    items: List<MediaItem>,
    selected: MediaItem?,
    onSelect: (MediaItem) -> Unit,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Search results", fontWeight = FontWeight.SemiBold)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items) { item ->
                    Card(
                        onClick = { onSelect(item) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (item == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${item.mediaType} · ${item.year} · ${item.imdbId}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StreamPanel(
    modifier: Modifier = Modifier,
    media: MediaItem?,
    season: String,
    episode: String,
    onSeasonChange: (String) -> Unit,
    onEpisodeChange: (String) -> Unit,
    streams: List<StreamItem>,
    busy: Boolean,
    onLoadStreams: () -> Unit,
    onDownload: (StreamItem) -> Unit,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (media == null) "Stream selection" else "Streams for ${media.title}",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (media?.mediaType == "series") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = season,
                        onValueChange = onSeasonChange,
                        label = { Text("Season") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = episode,
                        onValueChange = onEpisodeChange,
                        label = { Text("Episode optional") },
                        singleLine = true,
                    )
                }
            }
            Button(
                onClick = onLoadStreams,
                enabled = media != null && !busy && (media.mediaType != "series" || season.isNotBlank()),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Load streams")
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(streams) { stream ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${stream.resolution} · ${formatBytes(stream.sizeBytes)}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${if (stream.cached) "cached" else "uncached"}${if (stream.subtitleHint) " · subtitle hint" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(stream.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Button(onClick = { onDownload(stream) }, enabled = !busy) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Queue")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JobPanel(modifier: Modifier, jobs: List<JobItem>, onCancel: (Int) -> Unit, onClear: () -> Unit) {
    Card(modifier = modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.ClearAll, contentDescription = "Clear finished")
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(jobs) { job ->
                    JobRow(job = job, onCancel = { onCancel(job.id) })
                }
            }
        }
    }
}

@Composable
fun JobRow(job: JobItem, onCancel: () -> Unit) {
    val progress = if (job.totalBytes > 0) {
        (job.downloadedBytes.toFloat() / job.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(job.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${job.status} · ${job.phase}", style = MaterialTheme.typography.bodySmall)
                }
                if (job.status == "queued" || job.status == "resolving" || job.status == "downloading") {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Cancel, contentDescription = "Cancel")
                    }
                }
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${formatBytes(job.downloadedBytes)} / ${formatBytes(job.totalBytes)} · ${formatBytes(job.speedBytes.toLong())}/s",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (job.status == "resolving" || job.status == "downloading") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (job.errorText.isNotBlank()) {
                ErrorText(job.errorText)
            }
            job.destinations.lastOrNull()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupDialogLikePanel(
    provider: String,
    token: String,
    osKey: String,
    osUser: String,
    osPassword: String,
    onProviderChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onOsKeyChange: (String) -> Unit,
    onOsUserChange: (String) -> Unit,
    onOsPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(modifier = Modifier.fillMaxWidth(0.72f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Setup", style = MaterialTheme.typography.headlineSmall)
                    ProviderPicker(provider = provider, onProviderChange = onProviderChange)
                    OutlinedTextField(
                        value = token,
                        onValueChange = onTokenChange,
                        label = { Text("${providerLabel(provider)} API token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text("OpenSubtitles", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = osKey, onValueChange = onOsKeyChange, label = { Text("API key optional") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = osUser, onValueChange = onOsUserChange, label = { Text("Username optional") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = osPassword, onValueChange = onOsPasswordChange, label = { Text("Password optional") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onSave, enabled = token.isNotBlank()) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPicker(provider: String, onProviderChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = providerLabel(provider),
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Real-Debrid") }, onClick = {
                onProviderChange("realdebrid")
                expanded = false
            })
            DropdownMenuItem(text = { Text("TorBox") }, onClick = {
                onProviderChange("torbox")
                expanded = false
            })
        }
    }
}

@Composable
fun ErrorText(value: String) {
    Text(value, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

fun runAsync(block: suspend () -> String, onDone: (String) -> Unit, onError: (String) -> Unit) {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
        runCatching { withContext(Dispatchers.IO) { block() } }
            .onSuccess(onDone)
            .onFailure { onError(it.message ?: "Request failed.") }
    }
}

fun parseMedia(raw: String): List<MediaItem> {
    val array = JSONArray(raw)
    return (0 until array.length()).map { index ->
        val obj = array.getJSONObject(index)
        MediaItem(
            json = obj.toString(),
            mediaType = obj.getString("media_type"),
            title = obj.getString("title"),
            year = obj.optString("year", "?"),
            imdbId = obj.getString("imdb_id"),
        )
    }
}

fun parseStreams(raw: String): List<StreamItem> {
    val array = JSONArray(raw)
    return (0 until array.length()).map { index ->
        val obj = array.getJSONObject(index)
        StreamItem(
            json = obj.toString(),
            index = obj.getInt("index"),
            resolution = obj.getString("resolution"),
            cached = obj.getBoolean("is_cached"),
            sizeBytes = obj.getLong("size_bytes"),
            description = obj.optString("description", ""),
            subtitleHint = obj.optBoolean("has_subtitle_hint", false),
        )
    }
}

fun parseJobs(raw: String): List<JobItem> {
    val array = JSONArray(raw)
    return (0 until array.length()).map { index ->
        val obj = array.getJSONObject(index)
        val destinations = obj.optJSONArray("destinations") ?: JSONArray()
        JobItem(
            id = obj.getInt("job_id"),
            label = obj.getString("label"),
            status = obj.getString("status"),
            phase = obj.getString("phase"),
            totalFiles = obj.optInt("total_files", 1),
            completedFiles = obj.optInt("completed_files", 0),
            totalBytes = obj.optLong("total_bytes", 0L),
            downloadedBytes = obj.optLong("downloaded_bytes", 0L),
            speedBytes = obj.optDouble("speed_bytes_per_second", 0.0),
            etaSeconds = obj.optDouble("eta_seconds", -1.0),
            destinations = (0 until destinations.length()).map { destinations.getString(it) },
            errorText = obj.optString("error_text", ""),
        )
    }
}

fun providerLabel(provider: String): String {
    return when (provider) {
        "torbox" -> "TorBox"
        else -> "Real-Debrid"
    }
}

fun formatBytes(bytes: Long): String {
    val safe = max(bytes, 0L).toDouble()
    return when {
        safe >= 1024.0 * 1024.0 * 1024.0 -> "%.2f GB".format(safe / 1024.0 / 1024.0 / 1024.0)
        safe >= 1024.0 * 1024.0 -> "%.2f MB".format(safe / 1024.0 / 1024.0)
        safe >= 1024.0 -> "%.1f KB".format(safe / 1024.0)
        else -> "${safe.toLong()} B"
    }
}
