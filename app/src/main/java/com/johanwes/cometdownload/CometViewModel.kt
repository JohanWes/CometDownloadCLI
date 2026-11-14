package com.johanwes.cometdownload

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.johanwes.cometdownload.player.Playback
import com.johanwes.cometdownload.player.PlaybackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

enum class Tab { Search, Library }

enum class MessageAction { None, ViewLibrary, StorageAccess }

enum class LibraryFilter(val label: String) { All("All"), Movies("Movies"), Series("Series") }

enum class LibrarySort(val label: String) { Recent("Recently added"), Title("Title"), Rating("IMDb rating") }

data class UiMessage(val text: String, val action: MessageAction = MessageAction.None)

/** Season/episode scope for a series; episode 0 means the whole season. */
data class EpisodeScope(val season: Int = 1, val episode: Int = 0)

/** What a stream row shows: free to queue, being queued, or tied to a download job. */
sealed interface StreamState {
    data object Ready : StreamState
    data object Queueing : StreamState
    data class Linked(val job: JobItem) : StreamState
}

class CometViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    // Comes up already configured from the stored settings.
    private val backend = CometBackend.get(application)
    val downloadsPath: String = backend.downloadsPath

    private val uiPrefs: SharedPreferences = application.getSharedPreferences("comet_ui", Context.MODE_PRIVATE)

    // Settings
    var settings by mutableStateOf(store.load())
        private set
    var settingsOpen by mutableStateOf(!settings.isConfigured)
        private set

    // Navigation
    var tab by mutableStateOf(Tab.Search)

    // Search
    var query by mutableStateOf("")
    var lastQuery by mutableStateOf("")
        private set
    var results by mutableStateOf<Load<List<MediaItem>>>(Load.Idle)
        private set

    /** Goes up with every search that is started, so the results list can start at the top again. */
    var searchCount by mutableIntStateOf(0)
        private set
    var recentSearches by mutableStateOf(loadRecent())
        private set

    // Selected title + streams
    var selected by mutableStateOf<MediaItem?>(null)
        private set
    var scope by mutableStateOf(EpisodeScope())
        private set
    var streams by mutableStateOf<Load<List<StreamItem>>>(Load.Idle)
        private set
    var details by mutableStateOf<Load<MediaDetails>>(Load.Idle)
        private set
    private var queueing by mutableStateOf(emptySet<String>())
    /** Stream key -> id of the job it created, so rows can mirror live job state. */
    private var streamJobs by mutableStateOf(emptyMap<String, Int>())
    /** Job id -> size of its stream, so the free-space check can count unfinished downloads. */
    private var jobSizes = emptyMap<Int, Long>()

    // Downloads
    var jobs by mutableStateOf(emptyList<JobItem>())
        private set
    var freeBytes by mutableStateOf(-1L)
        private set
    var storageGranted by mutableStateOf(hasStorageAccess(application))
        private set
    /** Video path -> where its playback stopped, for videos that have been played. */
    var playback by mutableStateOf(emptyMap<String, Playback>())
        private set
    private val playbackStore = PlaybackStore(application)

    // Library: the finished downloads, grouped per title
    /** IMDb id -> plot, artwork and episode titles kept on the device. */
    private var libraryDetails by mutableStateOf(emptyMap<String, MediaDetails>())
    private var detailIds = emptySet<String>()
    private var libraryDetailsJob: Job? = null
    val library: List<LibraryTitle> by derivedStateOf { buildLibrary(jobs, playback, libraryDetails) }
    private var openTitleKey by mutableStateOf<String?>(null)
    /** The title whose page is open in the Library tab. */
    val openTitle: LibraryTitle? get() = openTitleKey?.let { key -> library.firstOrNull { it.key == key } }
    var libraryFilter by mutableStateOf(LibraryFilter.All)
    var libraryTopRequests by mutableIntStateOf(0)
        private set
    private var handledTopRequests = 0
    var librarySort by mutableStateOf(
        LibrarySort.entries.firstOrNull { it.name == uiPrefs.getString(KEY_LIBRARY_SORT, null) } ?: LibrarySort.Recent,
    )
        private set

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var searchJob: Job? = null
    private var streamsJob: Job? = null
    private var detailsJob: Job? = null
    private var pollJob: Job? = null

    init {
        refreshFreeSpace()
        // Activity recreated while downloads run (same process): make sure the service is up.
        if (runCatching { backend.activeCount() }.getOrDefault(0) > 0) {
            DownloadForegroundService.start(application)
        }
    }

    fun search() {
        val text = query.trim()
        if (text.isBlank()) return
        searchJob?.cancel()
        lastQuery = text
        searchCount++
        results = Load.Loading
        searchJob = viewModelScope.launch {
            results = call { backend.search(text) }.fold(
                onSuccess = {
                    // Typos and dead ends shouldn't pile up as "recent searches" chips.
                    if (it.isNotEmpty()) rememberSearch(text)
                    Load.Ready(it)
                },
                onFailure = { Load.Failed(it.friendlyMessage("Search failed.")) },
            )
        }
    }

    private var autoFocusUsed = false

    /** True once per process: the search field takes focus on the very first open. */
    fun consumeAutoFocus(): Boolean {
        if (autoFocusUsed) return false
        autoFocusUsed = true
        return true
    }

    fun searchFor(text: String) {
        query = text
        search()
    }

    /** The clear (X) button: back to the empty state so recent searches show again. */
    fun clearSearch() {
        searchJob?.cancel()
        query = ""
        results = Load.Idle
    }

    fun forgetSearch(text: String) {
        saveRecent(recentSearches - text)
    }

    private fun rememberSearch(text: String) {
        saveRecent((listOf(text) + recentSearches.filterNot { it.equals(text, ignoreCase = true) }).take(RECENT_LIMIT))
    }

    private fun saveRecent(values: List<String>) {
        recentSearches = values
        uiPrefs.edit().putString(KEY_RECENT, values.joinToString("\n")).apply()
    }

    private fun loadRecent(): List<String> =
        uiPrefs.getString(KEY_RECENT, null).orEmpty().split("\n").filter { it.isNotBlank() }

    fun select(media: MediaItem?) {
        if (media == selected) return
        selected = media
        streamsJob?.cancel()
        detailsJob?.cancel()
        // Going back keeps the old details/streams so the closing page doesn't flash empty.
        if (media == null) return
        scope = EpisodeScope()
        streams = Load.Idle
        details = Load.Idle
        loadStreams()
        loadDetails(media)
    }

    /** Reloads streams and (bypassing the metadata cache) the title details. */
    fun refresh() {
        val media = selected ?: return
        loadStreams()
        loadDetails(media, refresh = true)
    }

    private fun loadDetails(media: MediaItem, refresh: Boolean = false) {
        detailsJob?.cancel()
        if (details !is Load.Ready) details = Load.Loading
        detailsJob = viewModelScope.launch {
            val result = call { backend.details(media, refresh) }
            details = result.fold(
                onSuccess = { Load.Ready(it) },
                onFailure = { error ->
                    (details as? Load.Ready) ?: Load.Failed(error.friendlyMessage("Could not load details."))
                },
            )
            // Some shows don't have a season 1 (e.g. they start at a year); jump to the first
            // real season unless the user already picked one.
            val seasons = result.getOrNull()?.seasons.orEmpty()
            if (media.isSeries && seasons.isNotEmpty() && scope == EpisodeScope() && 1 !in seasons) {
                scope = EpisodeScope(season = seasons.first())
                loadStreams()
            }
        }
    }

    /** [debounceMs] lets steppers coalesce rapid taps into one lookup; pickers pass 0. */
    fun changeScope(newScope: EpisodeScope, debounceMs: Long = 0) {
        val clamped = EpisodeScope(season = newScope.season.coerceAtLeast(1), episode = newScope.episode.coerceAtLeast(0))
        if (clamped == scope) return
        scope = clamped
        loadStreams(debounceMs = debounceMs)
    }

    fun loadStreams(debounceMs: Long = 0) {
        val media = selected ?: return
        val current = scope
        streamsJob?.cancel()
        streams = Load.Loading
        streamsJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            streams = call { backend.streams(media, current) }.fold(
                onSuccess = { Load.Ready(it) },
                onFailure = { Load.Failed(it.friendlyMessage("Could not load streams.")) },
            )
        }
    }

    // Not the raw JSON: its list position and cached flag change when the list is reloaded,
    // which would unlink the row from its running job and offer the download again.
    private fun streamKey(stream: StreamItem): String = stream.key

    fun streamState(stream: StreamItem): StreamState {
        val key = streamKey(stream)
        if (key in queueing) return StreamState.Queueing
        val job = streamJobs[key]?.let { id -> jobs.firstOrNull { it.id == id } }
        // Failed/cancelled jobs free the row up again; the row shows why.
        return if (job != null) StreamState.Linked(job) else StreamState.Ready
    }

    fun enqueue(stream: StreamItem) {
        val media = selected ?: return
        val current = scope
        val key = streamKey(stream)
        if (key in queueing) return
        val linked = streamState(stream)
        if (linked is StreamState.Linked && !linked.job.canRetry) return
        // "Try again" on a failed/cancelled row replaces that job instead of leaving its
        // card behind next to a second one.
        val retryId = (linked as? StreamState.Linked)?.job?.id
        if (!checkCanDownload(stream.sizeBytes)) return
        queueing = queueing + key
        viewModelScope.launch {
            call {
                val retried = retryId?.let { id -> runCatching { backend.retry(id) }.getOrNull() }
                retried ?: backend.enqueue(media, stream, current)
            }.onSuccess { jobId ->
                streamJobs = streamJobs + (key to jobId)
                jobSizes = jobSizes + (jobId to stream.sizeBytes)
                DownloadForegroundService.start(getApplication())
                refreshJobs()
                val provider = settings.provider.label
                val note = buildString {
                    append(if (stream.cached) "Added to downloads" else "Requested from $provider. It downloads once they have it")
                    if (isOnMeteredNetwork()) append(" (you're on mobile data)")
                }
                messageChannel.trySend(UiMessage(note, MessageAction.ViewLibrary))
            }.onFailure {
                messageChannel.trySend(UiMessage(it.friendlyMessage("Could not queue download.")))
            }
            queueing = queueing - key
        }
    }

    fun retry(job: JobItem) {
        val size = jobSizes[job.id] ?: 0L
        if (!checkCanDownload(size)) return
        viewModelScope.launch {
            call { backend.retry(job.id) }
                .onSuccess { newId ->
                    streamJobs = streamJobs.mapValues { (_, id) -> if (id == job.id) newId else id }
                    if (size > 0) jobSizes = jobSizes + (newId to size)
                    DownloadForegroundService.start(getApplication())
                }
                .onFailure { messageChannel.trySend(UiMessage(it.friendlyMessage("Could not retry."))) }
            refreshJobs()
        }
    }

    /** Storage access and free space checks; reports the problem and returns false if blocked. */
    private fun checkCanDownload(sizeBytes: Long): Boolean {
        refreshStorageAccess()
        if (!storageGranted) {
            messageChannel.trySend(UiMessage("Allow file access so downloads can be saved.", MessageAction.StorageAccess))
            return false
        }
        refreshFreeSpace()
        val free = freeBytes
        // Downloads that are queued or running haven't used their space yet.
        val reserved = jobs.filter { it.isActive }.sumOf { job ->
            val size = jobSizes[job.id] ?: 0L
            (size * (1f - (job.overallProgress ?: 0f))).toLong()
        }
        if (sizeBytes > 0 && free >= 0 && sizeBytes + reserved + FREE_SPACE_MARGIN > free) {
            val running = if (reserved > 0) " (${formatBytes(reserved)} of it is for downloads in progress)" else ""
            messageChannel.trySend(
                UiMessage("Not enough space: needs ${formatBytes(sizeBytes)}, ${formatBytes(free)} free$running."),
            )
            return false
        }
        return true
    }

    /** The most relevant job for one episode of the open series (active first, then done). */
    fun episodeJob(season: Int, episode: Int): JobItem? {
        val media = selected ?: return null
        val matches = jobs.filter { it.imdbId == media.imdbId && it.season == season && it.episode == episode }
        return matches.firstOrNull { it.isActive } ?: matches.lastOrNull { it.state == JobState.Completed }
    }

    fun refetchSubtitles(job: JobItem, tryAnother: Boolean) {
        viewModelScope.launch {
            call { backend.refetchSubtitles(job.id, tryAnother) }
                .onFailure { messageChannel.trySend(UiMessage(it.friendlyMessage("Could not fetch subtitles."))) }
            refreshJobs()
        }
    }

    suspend fun testDebrid(provider: Provider, token: String): Result<String> =
        call { backend.testDebrid(provider, token) }
            .recoverCatching { throw IllegalStateException(it.friendlyMessage("Test failed.")) }

    suspend fun testOpenSubtitles(apiKey: String, username: String, password: String): Result<String> =
        call { backend.testOpenSubtitles(apiKey, username, password) }
            .recoverCatching { throw IllegalStateException(it.friendlyMessage("Test failed.")) }

    private fun isOnMeteredNetwork(): Boolean = runCatching {
        val connectivity = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        connectivity.isActiveNetworkMetered
    }.getOrDefault(false)

    fun cancel(job: JobItem) {
        viewModelScope.launch {
            call { backend.cancel(job.id) }
                .onFailure { messageChannel.trySend(UiMessage(it.friendlyMessage("Could not cancel."))) }
            refreshJobs()
        }
    }

    /** Failed and cancelled jobs only: completed downloads stay listed until removed. */
    fun clearFailed() {
        viewModelScope.launch {
            call { backend.clearFailed() }
            refreshJobs()
        }
    }

    /** Takes a finished download off the list; with [deleteFiles] its files go too. */
    fun remove(job: JobItem, deleteFiles: Boolean) {
        viewModelScope.launch {
            call { backend.removeJob(job.id, deleteFiles) }
                .onFailure { messageChannel.trySend(UiMessage(it.friendlyMessage("Could not remove it."))) }
            refreshJobs()
            if (deleteFiles) refreshFreeSpaceAfterDelete()
        }
    }

    fun showMessage(text: String) {
        messageChannel.trySend(UiMessage(text))
    }

    // Library

    fun openTitle(title: LibraryTitle?) {
        openTitleKey = title?.key
    }

    /** A tap on a tab; on the tab that is already showing it goes back to its front page. */
    fun goTo(destination: Tab) {
        if (destination == Tab.Library && tab == Tab.Library) openTitleKey = null
        tab = destination
    }

    /** The Library tab at the top of its front page, where running downloads are listed. */
    fun showDownloads() {
        openTitleKey = null
        tab = Tab.Library
        libraryTopRequests++
    }

    /** True once per [showDownloads]: the library should scroll back to its top. */
    fun consumeLibraryTopRequest(): Boolean {
        if (libraryTopRequests == handledTopRequests) return false
        handledTopRequests = libraryTopRequests
        return true
    }

    fun changeSort(sort: LibrarySort) {
        librarySort = sort
        uiPrefs.edit().putString(KEY_LIBRARY_SORT, sort.name).apply()
    }

    /** Poster for a job that is not in the library (yet): the stored one, else the one from search. */
    fun posterFor(job: JobItem): String =
        libraryDetails[job.imdbId]?.poster?.ifBlank { null } ?: job.media?.poster.orEmpty()

    /** Opens the title in Search, to download more of it. */
    fun findMore(title: LibraryTitle) {
        if (title.media.json.isBlank()) return
        tab = Tab.Search
        select(title.media)
    }

    fun setWatched(episodes: List<LibraryEpisode>, watched: Boolean) {
        episodes.forEach { playbackStore.setWatched(it.path, watched) }
        refreshPlayback()
    }

    /** Deletes single videos (an episode, a season) with their subtitles. */
    fun deleteEpisodes(episodes: List<LibraryEpisode>) {
        viewModelScope.launch {
            episodes.groupBy { it.jobId }.forEach { (jobId, files) ->
                call { backend.deleteFiles(jobId, files.map { it.path }) }
                    .onFailure { messageChannel.trySend(UiMessage(it.friendlyMessage("Could not delete it."))) }
            }
            refreshJobs()
            refreshPlayback()
            refreshFreeSpaceAfterDelete()
        }
    }

    /** Takes a whole title out of the library; with [deleteFiles] its videos and subtitles go too. */
    fun removeTitle(title: LibraryTitle, deleteFiles: Boolean) {
        viewModelScope.launch {
            title.jobs.forEach { job ->
                call { backend.removeJob(job.id, deleteFiles) }
                    .onFailure { messageChannel.trySend(UiMessage(it.friendlyMessage("Could not remove it."))) }
            }
            refreshJobs()
            if (deleteFiles) refreshFreeSpaceAfterDelete()
        }
    }

    /** Stored details first (instant, also offline), then whatever is missing or outdated. */
    private fun refreshLibraryDetails() {
        libraryDetailsJob?.cancel()
        libraryDetailsJob = viewModelScope.launch {
            call { backend.libraryDetails(fetch = false) }.onSuccess { libraryDetails = it }
            call { backend.libraryDetails(fetch = true) }.onSuccess { libraryDetails = it }
        }
    }

    /** Polls the download manager while the UI is visible. */
    fun setVisible(visible: Boolean) {
        AppVisibility.visible = visible
        pollJob?.cancel()
        if (!visible) return
        refreshStorageAccess()
        // Coming back from the player: resume positions may have moved.
        refreshPlayback()
        // Cheap when everything is stored; picks up what couldn't be fetched while offline.
        if (detailIds.isNotEmpty()) refreshLibraryDetails()
        pollJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                refreshJobs()
                if (tick++ % 10 == 0) refreshFreeSpace()
                delay(if (jobs.any { it.isActive }) 1000 else 3000)
            }
        }
    }

    fun refreshStorageAccess() {
        storageGranted = hasStorageAccess(getApplication())
    }

    private fun refreshFreeSpace() {
        freeBytes = runCatching { StatFs(downloadsPath).availableBytes }.getOrDefault(-1L)
    }

    /** The space of a deleted file is handed back a moment after the delete returns, so look again. */
    private suspend fun refreshFreeSpaceAfterDelete() {
        refreshFreeSpace()
        for (wait in listOf(1_000L, 3_000L)) {
            delay(wait)
            refreshFreeSpace()
        }
    }

    private suspend fun refreshJobs() {
        call { backend.jobs() }.onSuccess { fresh ->
            val finishedBefore = jobs.count { it.state == JobState.Completed }
            jobs = fresh
            if (fresh.count { it.state == JobState.Completed } != finishedBefore) refreshPlayback()
            val ids = fresh.map { it.imdbId }.filter { it.isNotBlank() }.toSet()
            if (ids != detailIds) {
                detailIds = ids
                refreshLibraryDetails()
            }
        }
    }

    private fun refreshPlayback() {
        playback = jobs.asSequence()
            .filter { it.state == JobState.Completed }
            .flatMap { it.videoFiles }
            .associateWith(playbackStore::get)
            .filterValues { it.watched || it.positionMs > 0 }
    }

    fun openSettings() {
        settingsOpen = true
    }

    fun closeSettings() {
        if (settings.isConfigured) settingsOpen = false
    }

    /** Returns an error message, or null when the settings were applied. */
    fun saveSettings(draft: AppSettings): String? {
        val cleaned = draft.copy(
            token = draft.token.trim(),
            osApiKey = draft.osApiKey.trim(),
            osUsername = draft.osUsername.trim(),
            osPassword = draft.osPassword.trim(),
            subtitleLanguages = draft.subtitleLanguages.trim().ifBlank { DEFAULT_SUBTITLE_LANGUAGES },
        )
        if (!cleaned.isConfigured) return "An API token is required."
        runCatching { backend.configure(cleaned) }.onFailure { return it.friendlyMessage("Configuration failed.") }
        store.save(cleaned)
        val providerChanged = cleaned.provider != settings.provider || cleaned.token != settings.token
        settings = cleaned
        settingsOpen = false
        // Cached flags depend on the provider, so refresh the open stream list.
        if (providerChanged && selected != null) loadStreams()
        return null
    }

    private suspend fun <T> call(block: () -> T): Result<T> = try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private companion object {
        const val KEY_RECENT = "recent_searches"
        const val KEY_LIBRARY_SORT = "library_sort"
        const val RECENT_LIMIT = 8
        const val FREE_SPACE_MARGIN = 512L * 1024 * 1024
    }
}

/** Whether the app may write to the public Download folder with plain file paths. */
fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        // Android 10 and older: the classic storage permission (legacy storage on 10).
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

/** UI visibility, read by the download service to decide whether a "finished" notice is needed. */
object AppVisibility {
    @Volatile
    var visible: Boolean = false
}
