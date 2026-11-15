package com.johanwes.cometdownload.player

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.MediaSession
import com.johanwes.cometdownload.CometBackend
import com.johanwes.cometdownload.Load
import com.johanwes.cometdownload.OnlineSubtitle
import com.johanwes.cometdownload.SettingsStore
import com.johanwes.cometdownload.SubtitleLanguage
import com.johanwes.cometdownload.friendlyMessage
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One video of the playlist: a movie, or an episode of the show being played. [label] says
 * which one ("S1 E2 · Cat's in the Bag..."); blank for a movie.
 */
data class PlayerItem(val path: String, val label: String) {
    val fileName: String = File(path).name

    companion object {
        private val EPISODE_TAG = Regex("""[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})""")

        /** Without a label from the library, the file name may still say which episode it is. */
        fun of(path: String, label: String?): PlayerItem = PlayerItem(
            path = path,
            label = label?.ifBlank { null }
                ?: EPISODE_TAG.find(File(path).name)
                    ?.let { "S${it.groupValues[1].toInt()} E${it.groupValues[2].toInt()}" }
                    .orEmpty(),
        )
    }
}

/**
 * Playback state for the player screen. Owns the ExoPlayer instance, so playback carries on
 * across rotation and folding; the activity only renders it.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application), Player.Listener {
    val player: ExoPlayer = buildPlayer(application)
    private val store = PlaybackStore(application)

    // Lets headset and Bluetooth buttons control playback. Held only while the player is on
    // screen: a session that outlives it lets a headset button start playback with nothing visible.
    private var session: MediaSession? = null

    // Python takes a moment to start; nothing here needs it until subtitles are searched.
    private val backend by lazy { CometBackend.get(application) }

    /** Subtitle languages in the user's order of preference, e.g. ["en", "sv"]. */
    private val preferredLanguages = viewModelScope.async(Dispatchers.IO) {
        SettingsStore(application).load().subtitleLanguages
            .lowercase().split(Regex("""[\s,;]+""")).filter { it.isNotBlank() }
    }

    var title by mutableStateOf("")
        private set
    var items by mutableStateOf(emptyList<PlayerItem>())
        private set
    var index by mutableIntStateOf(0)
        private set
    val current: PlayerItem? get() = items.getOrNull(index)

    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(true)
        private set
    var ended by mutableStateOf(false)
        private set
    /** Played through to the end of the last video: there is nothing left to show here. */
    var finished by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** Width / height of the picture as it should be shown. */
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    /** Fill the screen (cropping the picture) instead of fitting all of it. */
    var zoomed by mutableStateOf(false)

    var chapters by mutableStateOf(emptyList<ChapterMark>())
        private set
    var audioOptions by mutableStateOf(emptyList<AudioOption>())
        private set
    var textOptions by mutableStateOf(emptyList<TextOption>())
        private set
    var sidecars by mutableStateOf(emptyList<Sidecar>())
        private set
    var selection by mutableStateOf<SubtitleSelection>(SubtitleSelection.Off)
        private set
    /** What the subtitle view shows right now. */
    var cues by mutableStateOf(emptyList<Cue>())
        private set
    var subtitleDelayMs by mutableLongStateOf(0L)
        private set
    var subtitleScale by mutableFloatStateOf(store.subtitleScale)
        private set
    var subtitleStyle by mutableStateOf(store.subtitleStyle)
        private set

    // Finding more subtitles online
    var languages by mutableStateOf(emptyList<SubtitleLanguage>())
        private set
    var searchLanguage by mutableStateOf("")
        private set
    var online by mutableStateOf<Load<List<OnlineSubtitle>>>(Load.Idle)
        private set
    /** File id of the subtitle being downloaded, if any. */
    var downloading by mutableStateOf<Long?>(null)
        private set
    var subtitleNotice by mutableStateOf<String?>(null)
        private set

    private var opened = false
    private var prepared = false
    private var scrubbing = false
    /** The default subtitle and audio choice is made once per video, when its tracks are known. */
    private var defaultsApplied = false
    private var sidecarTrack: SubtitleTrack? = null
    private var cueJob: Job? = null
    private var searchJob: Job? = null
    private var visibleCueIndexes = emptyList<Int>()
    private var savedPositionMs = -1L

    init {
        player.addListener(this)
        // Debug builds log what the player does (decoders chosen, dropped frames, errors).
        if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            player.addAnalyticsListener(EventLogger("CometPlayer"))
        }
        viewModelScope.launch {
            // Remember where playback is, so a crash or a killed process loses little.
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                // Also while paused: a seek made then should survive the app being killed.
                if (player.currentPosition != savedPositionMs) savePosition()
            }
        }
        viewModelScope.launch(Dispatchers.IO) { store.prune() }
    }

    /** Starts playing [paths] at [startIndex]; later calls (activity recreated) do nothing. */
    fun open(paths: List<String>, labels: List<String>, startIndex: Int, title: String) {
        if (opened || paths.isEmpty()) return
        opened = true
        this.title = title
        items = paths.mapIndexed { i, path -> PlayerItem.of(path, labels.getOrNull(i)) }
        index = startIndex.coerceIn(0, items.lastIndex)
        val mediaItems = items.map { MediaItem.Builder().setUri(Uri.fromFile(File(it.path))).setMediaId(it.path).build() }
        player.setMediaItems(mediaItems, index, store.get(items[index].path).positionMs)
        onItemChanged()
        player.play()
        updatePlaying()
        // Loading starts when the picture has somewhere to go (onSurfaceSizeChanged): a decoder
        // started without a surface has to be re-pointed at it, which some devices get wrong
        // (black picture). The timeout only covers a surface that never shows up.
        viewModelScope.launch {
            delay(SURFACE_WAIT_MS)
            prepareOnce()
        }
    }

    private fun prepareOnce() {
        if (prepared) return
        prepared = true
        player.prepare()
    }

    // --- Transport ----------------------------------------------------------------------

    fun togglePlay() {
        when {
            ended -> {
                player.seekTo(0)
                player.play()
            }
            isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun pause() = player.pause()

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        player.seekTo(if (durationMs > 0) target.coerceAtMost(durationMs) else target)
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    /** While the seek bar is dragged the player shows frames as fast as it can find them. */
    fun setScrubbing(active: Boolean) {
        scrubbing = active
        player.isScrubbingModeEnabled = active
    }

    val hasNext: Boolean get() = index < items.lastIndex
    val hasPrevious: Boolean get() = index > 0

    fun playItem(target: Int) {
        if (target !in items.indices || target == index) return
        savePosition()
        player.seekTo(target, 0)
        player.play()
    }

    fun changeSpeed(value: Float) {
        speed = value
        player.setPlaybackSpeed(value)
    }

    /** Temporary fast-forward while the screen is held down. */
    fun setBoost(active: Boolean) {
        player.setPlaybackSpeed(if (active) BOOST_SPEED else speed)
    }

    fun retry() {
        error = null
        player.prepare()
        player.play()
    }

    /**
     * Called as the player is being left. The library reads the resume point when it comes
     * back to the front, which is before this screen is stopped.
     */
    fun onLeaving() = savePosition()

    /** Called when the screen becomes visible. */
    fun onForeground() {
        if (session == null) {
            session = MediaSession.Builder(getApplication<Application>(), player).setId("comet-player-${System.nanoTime()}").build()
        }
    }

    /** Called when the screen is no longer visible: there is no background playback. */
    fun onBackground() {
        player.pause()
        savePosition()
        session?.release()
        session = null
    }

    // --- Audio and subtitles --------------------------------------------------------------

    fun selectAudio(option: AudioOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
            .build()
        val path = current?.path ?: return
        store.update(path) { it.copy(audio = audioOptions.indexOfFirst { o -> o.group == option.group && o.trackIndex == option.trackIndex }) }
    }

    /** A choice made by the user: applied and remembered for this video. */
    fun selectSubtitle(choice: SubtitleSelection) {
        applySubtitle(choice)
        current?.path?.let { path -> store.update(path) { it.copy(subtitle = choice.key) } }
    }

    private fun applySubtitle(choice: SubtitleSelection) {
        selection = choice
        cueJob?.cancel()
        sidecarTrack = null
        visibleCueIndexes = emptyList()
        cues = emptyList()
        val parameters = player.trackSelectionParameters.buildUpon()
        when (choice) {
            is SubtitleSelection.Embedded -> {
                val option = textOptions.getOrNull(choice.ordinal)
                if (option == null) {
                    selection = SubtitleSelection.Off
                    parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
                }
            }
            // Files next to the video are drawn by us, so the player's own text output is off.
            is SubtitleSelection.File, SubtitleSelection.Off -> parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        player.trackSelectionParameters = parameters.build()
        if (choice is SubtitleSelection.File) showSidecar(choice.path)
    }

    private fun showSidecar(path: String) {
        cueJob = viewModelScope.launch {
            val track = runCatching { withContext(Dispatchers.IO) { loadSubtitleTrack(path) } }.getOrNull()
            if (track == null || track.isEmpty) {
                subtitleNotice = "That subtitle file couldn't be read."
                applySubtitle(SubtitleSelection.Off)
                return@launch
            }
            sidecarTrack = track
            // Cheap enough to poll: a binary search, and state only changes when a line does.
            while (isActive) {
                val active = track.activeAt(player.currentPosition - subtitleDelayMs)
                if (active != visibleCueIndexes) {
                    visibleCueIndexes = active
                    cues = track.cuesAt(active)
                }
                delay(if (player.isPlaying) CUE_POLL_PLAYING_MS else CUE_POLL_PAUSED_MS)
            }
        }
    }

    fun changeSubtitleDelay(valueMs: Long) {
        subtitleDelayMs = valueMs.coerceIn(-MAX_SUBTITLE_DELAY_MS, MAX_SUBTITLE_DELAY_MS)
        player.subtitleDelayMilliseconds = subtitleDelayMs
        current?.path?.let { path -> store.update(path) { it.copy(subtitleDelayMs = subtitleDelayMs) } }
    }

    fun changeSubtitleScale(value: Float) {
        subtitleScale = value.coerceIn(MIN_SUBTITLE_SCALE, MAX_SUBTITLE_SCALE)
        store.subtitleScale = subtitleScale
    }

    fun changeSubtitleStyle(value: SubtitleStyle) {
        subtitleStyle = value.copy(border = value.border.coerceIn(0, SubtitleStyle.MAX_BORDER))
        store.subtitleStyle = subtitleStyle
    }

    fun dismissSubtitleNotice() {
        subtitleNotice = null
    }

    // --- More subtitles found online -------------------------------------------------------

    /** Looks up subtitles the first time the panel opens for a video. */
    fun ensureOnlineSearch() {
        if (online is Load.Idle) searchOnline(searchLanguage)
    }

    /** [language] is one code ("sv"); blank searches all preferred languages at once. */
    fun searchOnline(language: String) {
        val path = current?.path ?: return
        searchLanguage = language
        searchJob?.cancel()
        online = Load.Loading
        searchJob = viewModelScope.launch {
            online = io {
                if (languages.isEmpty()) languages = backend.subtitleLanguages()
                backend.searchSubtitles(path, language)
            }.fold(
                onSuccess = { Load.Ready(it) },
                onFailure = { Load.Failed(it.friendlyMessage("Could not search for subtitles.")) },
            )
        }
    }

    /** Shows [subtitle]: straight away when it is already on disk, after downloading otherwise. */
    fun useOnline(subtitle: OnlineSubtitle) {
        val path = current?.path ?: return
        if (subtitle.path.isNotBlank() && File(subtitle.path).exists()) {
            selectSubtitle(SubtitleSelection.File(subtitle.path))
            return
        }
        if (downloading != null) return
        downloading = subtitle.fileId
        viewModelScope.launch {
            io { backend.downloadSubtitle(path, subtitle) }
                .onSuccess { saved ->
                    if (path == current?.path) {
                        sidecars = withContext(Dispatchers.IO) { findSidecars(path) }
                        selectSubtitle(SubtitleSelection.File(saved))
                        (online as? Load.Ready)?.let { ready ->
                            online = Load.Ready(
                                ready.value.map { if (it.fileId == subtitle.fileId) it.copy(path = saved) else it },
                            )
                        }
                    }
                }
                .onFailure { subtitleNotice = it.friendlyMessage("Could not download that subtitle.") }
            downloading = null
        }
    }

    // --- Player events --------------------------------------------------------------------

    override fun onIsPlayingChanged(playing: Boolean) {
        if (!playing) savePosition()
    }

    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        if (opened && width > 0 && height > 0) prepareOnce()
    }

    override fun onPlaybackStateChanged(state: Int) {
        isBuffering = state == Player.STATE_BUFFERING
        ended = state == Player.STATE_ENDED
        if (state == Player.STATE_ENDED) {
            savePosition()
            // Dragging the seek bar to its end is not the same as having watched to the end.
            if (!scrubbing) finished = true
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        // The duration becomes known (or changes with the video) without an event of its own.
        durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        updatePlaying()
    }

    /** "Playing" as the viewer means it: also while a seek or the start is still loading. */
    private fun updatePlaying() {
        val state = player.playbackState
        // Before loading has begun (the picture has nowhere to go yet) the player is idle.
        val starting = state == Player.STATE_IDLE && !prepared && error == null
        isPlaying = player.playWhenReady &&
            (state == Player.STATE_READY || state == Player.STATE_BUFFERING || starting)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val newIndex = player.currentMediaItemIndex
        if (newIndex == index) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && durationMs > 0) {
            // The previous episode played to its end.
            current?.path?.let { store.savePosition(it, durationMs, durationMs) }
        }
        index = newIndex
        onItemChanged()
    }

    override fun onTracksChanged(tracks: Tracks) {
        audioOptions = audioOptions(tracks)
        textOptions = textOptions(tracks)
        chapters = chapterMarks(tracks)
        if (!defaultsApplied && tracks.groups.isNotEmpty()) {
            defaultsApplied = true
            viewModelScope.launch { applyDefaults() }
        }
    }

    override fun onCues(cueGroup: CueGroup) {
        if (selection is SubtitleSelection.Embedded) cues = cueGroup.cues
    }

    override fun onVideoSizeChanged(size: VideoSize) {
        if (size.width > 0 && size.height > 0) {
            videoAspect = size.width * size.pixelWidthHeightRatio / size.height
        }
    }

    override fun onPlayerError(playbackError: PlaybackException) {
        savePosition()
        error = playbackErrorMessage(playbackError)
    }

    override fun onCleared() {
        savePosition()
        session?.release()
        session = null
        player.removeListener(this)
        player.release()
    }

    // --- Internals ------------------------------------------------------------------------

    /** Resets everything that belongs to one video when another one starts. */
    private fun onItemChanged() {
        defaultsApplied = false
        error = null
        ended = false
        durationMs = 0
        chapters = emptyList()
        audioOptions = emptyList()
        textOptions = emptyList()
        searchJob?.cancel()
        online = Load.Idle
        searchLanguage = ""
        applySubtitle(SubtitleSelection.Off)
        val path = current?.path ?: return
        subtitleDelayMs = store.get(path).subtitleDelayMs
        player.subtitleDelayMilliseconds = subtitleDelayMs
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { findSidecars(path) }
            if (path == current?.path) sidecars = found
        }
    }

    /** The remembered subtitle and audio track, or the best subtitle in a preferred language. */
    private suspend fun applyDefaults() {
        val path = current?.path ?: return
        val saved = store.get(path)
        val files = withContext(Dispatchers.IO) { findSidecars(path) }
        val wanted = preferredLanguages.await()
        if (path != current?.path) return
        sidecars = files

        audioOptions.getOrNull(saved.audio)?.takeIf { it.supported && !it.selected }?.let(::selectAudio)

        val remembered = when (val key = saved.subtitle) {
            null -> null
            SubtitleSelection.Off.key -> SubtitleSelection.Off
            else -> files.firstOrNull { SubtitleSelection.File(it.path).key == key }?.let { SubtitleSelection.File(it.path) }
                ?: key.removePrefix("track:").toIntOrNull()
                    ?.takeIf { key.startsWith("track:") && it in textOptions.indices }
                    ?.let(SubtitleSelection::Embedded)
        }
        applySubtitle(remembered ?: bestSubtitle(wanted, files))
    }

    /**
     * First preferred language that has anything. Within a language: text inside the file
     * (always in sync), then a downloaded file, then picture-based tracks.
     */
    private fun bestSubtitle(wanted: List<String>, files: List<Sidecar>): SubtitleSelection {
        val usable = textOptions.withIndex().filter { it.value.supported && !it.value.forced }
        for (language in wanted) {
            usable.firstOrNull { it.value.language == language && !it.value.image }
                ?.let { return SubtitleSelection.Embedded(it.index) }
            files.firstOrNull { it.language == language }?.let { return SubtitleSelection.File(it.path) }
            usable.firstOrNull { it.value.language == language }?.let { return SubtitleSelection.Embedded(it.index) }
        }
        return SubtitleSelection.Off
    }

    private fun savePosition() {
        val path = current?.path ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        savedPositionMs = player.currentPosition
        store.savePosition(path, if (ended) duration else savedPositionMs, duration)
    }

    private suspend fun <T> io(block: () -> T): Result<T> = try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    companion object {
        const val MIN_SUBTITLE_SCALE = 0.6f
        const val MAX_SUBTITLE_SCALE = 2f
        const val MAX_SUBTITLE_DELAY_MS = 60_000L
        const val BOOST_SPEED = 2f
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val SURFACE_WAIT_MS = 1_500L
        private const val CUE_POLL_PLAYING_MS = 40L
        private const val CUE_POLL_PAUSED_MS = 200L
    }
}
