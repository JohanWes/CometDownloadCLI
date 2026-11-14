package com.johanwes.cometdownload

import com.johanwes.cometdownload.player.Playback

/** One downloaded video of a library title: an episode, or (one version of) a movie. */
data class LibraryEpisode(
    /** 0 for a movie, and for a series file whose name doesn't say. */
    val season: Int,
    val episode: Int,
    /** The episode's title, blank when it isn't known. */
    val title: String,
    val path: String,
    val bytes: Long,
    /** The download this file came from. */
    val jobId: Int,
    val playback: Playback?,
) {
    val watched: Boolean get() = playback?.watched == true
    val inProgress: Boolean get() = (playback?.positionMs ?: 0) > 0
    val positionMs: Long get() = playback?.positionMs ?: 0
    val progress: Float? get() = playback?.progress

    /** What is left to watch of a started video, in ms. */
    val remainingMs: Long?
        get() = playback?.takeIf { it.positionMs > 0 && it.durationMs > 0 }?.let { it.durationMs - it.positionMs }

    /** "S2 E5", or blank when the season or episode is unknown. */
    val tag: String get() = if (season > 0 && episode > 0) "S$season E$episode" else ""

    /** Copies of the same episode count as one when it comes to what has been watched. */
    internal val identity: Any get() = if (season > 0 && episode > 0) season to episode else path
}

/** A movie or a show in the library: every finished download of one title, as one entry. */
data class LibraryTitle(
    val key: String,
    val isSeries: Boolean,
    /** The title as it was searched for; `json` is blank when that was not recorded. */
    val media: MediaItem,
    /** Plot, artwork and episode titles stored on the device; null until they were fetched once. */
    val details: MediaDetails?,
    /** The finished downloads this entry is made of. */
    val jobs: List<JobItem>,
    /** Downloads of the same title that are still on their way. */
    val activeJobs: List<JobItem>,
    /** Every video, in watching order. */
    val episodes: List<LibraryEpisode>,
) {
    val name: String get() = media.title
    val poster: String get() = details?.poster?.ifBlank { null } ?: media.poster
    val background: String get() = details?.background.orEmpty()
    val rating: String get() = details?.rating?.ifBlank { null } ?: media.rating
    val bytes: Long get() = episodes.sumOf { it.bytes }
    /** When the newest download finished (seconds since the epoch). */
    val addedAt: Double get() = jobs.maxOf { it.finishedAt }
    val lastPlayedAt: Long get() = episodes.maxOf { it.playback?.updatedAt ?: 0 }
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
    fun episodesIn(season: Int): List<LibraryEpisode> = episodes.filter { it.season == season }

    private val watchedIds: Set<Any> get() = episodes.filter { it.watched }.map { it.identity }.toSet()

    /** Episodes (or, for a movie, 1) on the device, not counting second copies. */
    val episodeCount: Int get() = episodes.map { it.identity }.distinct().size
    val watchedCount: Int get() = watchedIds.size
    val allWatched: Boolean get() = watchedCount >= episodeCount
    fun watchedIn(season: Int): Int = episodesIn(season).filter { it.watched }.map { it.identity }.distinct().size
    fun countIn(season: Int): Int = episodesIn(season).map { it.identity }.distinct().size

    /**
     * What to play next: the video that was left half-way, or the first unwatched one after
     * the last one watched. Null once everything has been watched.
     */
    val nextUp: LibraryEpisode?
        get() {
            val watched = watchedIds
            val lastWatched = episodes.indexOfLast { it.watched }
            val started = episodes.filter { it.inProgress }.maxByOrNull { it.playback?.updatedAt ?: 0 }
            // A half-watched episode is only "next" if nothing later was finished after it.
            val lastWatchedAt = episodes.getOrNull(lastWatched)?.playback?.updatedAt ?: 0
            if (started != null && (started.playback?.updatedAt ?: 0) >= lastWatchedAt) return started
            return episodes.drop(lastWatched + 1).firstOrNull { it.identity !in watched }
                ?: episodes.firstOrNull { it.identity !in watched }
                ?: started
        }

    /** Something was started, and there is more to watch. */
    val canContinue: Boolean
        get() {
            val next = nextUp ?: return false
            return next.inProgress || (isSeries && watchedCount > 0)
        }

    /** The line under the poster: where you are, or what there is. */
    val statusLine: String
        get() {
            val next = nextUp
            val left = next?.remainingMs?.let(::formatTimeLeft)
            return when {
                !isSeries -> when {
                    left != null -> left
                    else -> listOfNotNull(media.year.ifBlank { null }, formatRuntime(runtimeMinutes)).joinToString(" · ")
                        .ifBlank { "Movie" }
                }
                next == null -> "Watched · ${episodesLabel(episodeCount)}"
                left != null -> listOf(next.tag, left).filter { it.isNotBlank() }.joinToString(" · ")
                watchedCount > 0 && next.tag.isNotBlank() -> "Next: ${next.tag}"
                // Kept short: it sits under a poster a third of a phone wide.
                else -> episodesLabel(episodeCount)
            }
        }

    val runtimeMinutes: Int get() = details?.runtimeMinutes?.takeIf { it > 0 } ?: media.runtimeMinutes
}

fun episodesLabel(count: Int): String = if (count == 1) "1 episode" else "$count episodes"

/** "23 min left", "1h 12m left". */
fun formatTimeLeft(ms: Long): String {
    val minutes = ((ms.coerceAtLeast(0) + 59_999) / 60_000).toInt()
    return when {
        minutes < 1 -> "Under a minute left"
        minutes < 60 -> "$minutes min left"
        else -> "${formatRuntime(minutes)} left"
    }
}

/** Jobs of one title share a key: its IMDb id (a job without one stands alone). */
val JobItem.titleKey: String get() = imdbId.ifBlank { "job-$id" }

/**
 * The library view of the download list: finished downloads grouped per title, each with its
 * videos in order, what was watched of them, and the stored details. Newest first.
 */
fun buildLibrary(
    jobs: List<JobItem>,
    playback: Map<String, Playback>,
    details: Map<String, MediaDetails>,
): List<LibraryTitle> {
    val active = jobs.filter { it.isActive }.groupBy { it.titleKey }
    return jobs
        .filter { it.state == JobState.Completed && it.files.isNotEmpty() }
        .groupBy { it.titleKey }
        .map { (key, group) ->
            val first = group.first()
            val stored = details[first.imdbId]
            val titles = stored?.episodes.orEmpty().associate { (it.season to it.episode) to it.title }
            val isSeries = group.any { it.isSeries }
            LibraryTitle(
                key = key,
                isSeries = isSeries,
                media = group.firstNotNullOfOrNull { it.media }
                    ?: MediaItem(json = "", isSeries = isSeries, title = first.title.ifBlank { first.label }, year = "", imdbId = first.imdbId),
                details = stored,
                jobs = group,
                activeJobs = active[key].orEmpty(),
                episodes = group
                    .flatMap { job ->
                        job.files.map { file ->
                            LibraryEpisode(
                                season = file.season,
                                episode = file.episode,
                                title = titles[file.season to file.episode].orEmpty(),
                                path = file.path,
                                bytes = file.bytes,
                                jobId = job.id,
                                playback = playback[file.path],
                            )
                        }
                    }
                    .distinctBy { it.path }
                    .sortedWith(compareBy({ it.season }, { it.episode }, { it.path })),
            )
        }
        .sortedByDescending { it.addedAt }
}
