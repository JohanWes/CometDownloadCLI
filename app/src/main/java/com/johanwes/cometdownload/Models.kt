package com.johanwes.cometdownload

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToLong

enum class Provider(val id: String, val label: String, val tokenUrl: String) {
    RealDebrid("realdebrid", "Real-Debrid", "https://real-debrid.com/apitoken"),
    TorBox("torbox", "TorBox", "https://torbox.app/settings");

    companion object {
        fun fromId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: RealDebrid
    }
}

data class AppSettings(
    val provider: Provider = Provider.RealDebrid,
    val token: String = "",
    val osApiKey: String = "",
    val osUsername: String = "",
    val osPassword: String = "",
    /** Subtitle languages in order of preference; the first one found is downloaded. */
    val subtitleLanguages: String = DEFAULT_SUBTITLE_LANGUAGES,
) {
    val isConfigured: Boolean get() = token.isNotBlank()
    val subtitlesEnabled: Boolean get() = osApiKey.isNotBlank()
}

const val DEFAULT_SUBTITLE_LANGUAGES = "en, sv"

data class MediaItem(
    val json: String,
    val isSeries: Boolean,
    val title: String,
    val year: String,
    val imdbId: String,
    val poster: String = "",
    /** IMDb rating as text ("8.4"), blank when unknown. */
    val rating: String = "",
    val runtimeMinutes: Int = 0,
    val genres: List<String> = emptyList(),
    val seasonCount: Int = 0,
) {
    val typeLabel: String get() = if (isSeries) "Series" else "Movie"

    /** "2024 · Movie · 2h 47m" or "2022 · Series · 2 seasons". */
    val meta: String
        get() {
            val extent = if (isSeries) {
                seasonCount.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" }
            } else {
                formatRuntime(runtimeMinutes)
            }
            return listOfNotNull(year.ifBlank { null }, typeLabel, extent).joinToString(" · ")
        }
}

data class Episode(
    val season: Int,
    val episode: Int,
    val title: String,
    val released: String,
    val aired: Boolean,
)

data class MediaDetails(
    val rating: String,
    val runtimeMinutes: Int,
    val genres: List<String>,
    val description: String,
    val poster: String,
    val background: String,
    val yearLabel: String,
    val episodes: List<Episode>,
) {
    val seasons: List<Int> get() = episodes.map { it.season }.distinct()
    fun episodesIn(season: Int): List<Episode> = episodes.filter { it.season == season }
}

data class StreamItem(
    val json: String,
    /** Identity of the release (its playback link); unlike [json] it survives a reload. */
    val key: String,
    val resolution: String,
    val cached: Boolean,
    val sizeBytes: Long,
    val description: String,
    val subtitleHint: Boolean,
) {
    /** "4K" stays as is, "1080P" becomes "1080p". */
    val resolutionLabel: String get() = if (resolution.endsWith("P")) resolution.lowercase() else resolution
}

enum class JobState { Queued, Resolving, Downloading, Completed, Failed, Cancelled }

/** One video of a finished download; [season] and [episode] are 0 for a movie or when unknown. */
data class JobFile(val path: String, val season: Int, val episode: Int, val bytes: Long)

data class JobItem(
    val id: Int,
    val label: String,
    val state: JobState,
    val phase: String,
    val totalFiles: Int,
    val completedFiles: Int,
    val currentFileIndex: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytes: Double,
    val etaSeconds: Double,
    val destinations: List<String>,
    val outputDir: String,
    val errorText: String,
    val cancelRequested: Boolean,
    /** Size on disk once completed. */
    val finalBytes: Long = 0,
    /** found | partial | missing | failed | off, or blank before the subtitle step. */
    val subtitleStatus: String = "",
    val subtitleMessage: String = "",
    /** Languages of subtitle tracks inside the video files, e.g. "English", "Swedish (image)". */
    val embeddedSubtitles: List<String> = emptyList(),
    val imdbId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    /** Name of the movie or show, without year, episode or resolution. */
    val title: String = "",
    val isSeries: Boolean = false,
    /** The title as it was found in search (poster, rating...); null for jobs that predate that. */
    val media: MediaItem? = null,
    /** Seconds since the epoch; 0 while the job is not finished. */
    val finishedAt: Double = 0.0,
    /** The videos of a completed job, with the episode each one is. */
    val files: List<JobFile> = emptyList(),
) {
    /** Waiting for the debrid service to fetch an uncached torrent. */
    val isWaiting: Boolean get() = state == JobState.Resolving && phase.startsWith("Waiting")

    /** Whole-job progress: a season pack counts finished files plus the current one. */
    val overallProgress: Float?
        get() {
            val file = progress ?: return null
            if (totalFiles <= 1) return file
            return ((completedFiles + file) / totalFiles).coerceIn(0f, 1f)
        }

    val isActive: Boolean get() = state == JobState.Queued || state == JobState.Resolving || state == JobState.Downloading
    val canRetry: Boolean get() = state == JobState.Failed || state == JobState.Cancelled
    val videoFiles: List<String>
        get() = destinations.filter { path -> VIDEO_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) } }.sorted()
    val progress: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
}

fun parseMedia(raw: String): List<MediaItem> = JSONArray(raw).objects().map(::mediaFrom)

private fun mediaFrom(obj: JSONObject) = MediaItem(
    json = obj.toString(),
    isSeries = obj.getString("media_type") == "series",
    title = obj.getString("title"),
    // The backend uses "?" for an unknown year.
    year = obj.optStringOrBlank("year").takeUnless { it == "?" }.orEmpty(),
    imdbId = obj.getString("imdb_id"),
    poster = obj.optStringOrBlank("poster"),
    rating = obj.optStringOrBlank("rating"),
    runtimeMinutes = obj.optInt("runtime_minutes", 0),
    genres = obj.optJSONArray("genres").strings(),
    seasonCount = obj.optInt("season_count", 0),
)

fun parseDetails(raw: String): MediaDetails = detailsFrom(JSONObject(raw))

/** Stored details of the library's titles, by IMDb id. */
fun parseLibraryDetails(raw: String): Map<String, MediaDetails> {
    val obj = JSONObject(raw)
    return obj.keys().asSequence().associateWith { detailsFrom(obj.getJSONObject(it)) }
}

private fun detailsFrom(obj: JSONObject): MediaDetails {
    return MediaDetails(
        rating = obj.optStringOrBlank("rating"),
        runtimeMinutes = obj.optInt("runtime_minutes", 0),
        genres = obj.optJSONArray("genres").strings(),
        description = obj.optStringOrBlank("description"),
        poster = obj.optStringOrBlank("poster"),
        background = obj.optStringOrBlank("background"),
        yearLabel = obj.optStringOrBlank("year_label"),
        episodes = (obj.optJSONArray("episodes") ?: JSONArray()).objects().map { episode ->
            Episode(
                season = episode.getInt("season"),
                episode = episode.getInt("episode"),
                title = episode.optStringOrBlank("title"),
                released = episode.optStringOrBlank("released"),
                aired = episode.optBoolean("aired", true),
            )
        },
    )
}

fun parseStreams(raw: String): List<StreamItem> = JSONArray(raw).objects().map { obj ->
    StreamItem(
        json = obj.toString(),
        // The part after /playback/ names the torrent and episode; what precedes it is the
        // account config, which changes when the token is edited in Settings.
        key = obj.optStringOrBlank("playback_url").substringAfterLast("/playback/").ifBlank { obj.toString() },
        resolution = obj.getString("resolution"),
        cached = obj.getBoolean("is_cached"),
        sizeBytes = obj.getLong("size_bytes"),
        description = obj.optStringOrBlank("description"),
        subtitleHint = obj.optBoolean("has_subtitle_hint", false),
    )
}

fun parseJobs(raw: String): List<JobItem> = JSONArray(raw).objects().map { obj ->
    val destinations = obj.optJSONArray("destinations") ?: JSONArray()
    JobItem(
        id = obj.getInt("job_id"),
        label = obj.getString("label"),
        state = when (obj.getString("status")) {
            "queued" -> JobState.Queued
            "resolving" -> JobState.Resolving
            "downloading" -> JobState.Downloading
            "completed" -> JobState.Completed
            "failed" -> JobState.Failed
            else -> JobState.Cancelled
        },
        phase = obj.optStringOrBlank("phase"),
        totalFiles = obj.optInt("total_files", 1),
        completedFiles = obj.optInt("completed_files", 0),
        currentFileIndex = obj.optInt("current_file_index", 0),
        totalBytes = obj.optLong("total_bytes", 0L),
        downloadedBytes = obj.optLong("downloaded_bytes", 0L),
        speedBytes = obj.optDouble("speed_bytes_per_second", 0.0).takeUnless { it.isNaN() } ?: 0.0,
        etaSeconds = obj.optDouble("eta_seconds", -1.0).takeUnless { it.isNaN() } ?: -1.0,
        destinations = (0 until destinations.length()).map { destinations.getString(it) },
        outputDir = obj.optStringOrBlank("output_dir"),
        errorText = obj.optStringOrBlank("error_text"),
        cancelRequested = obj.optBoolean("cancel_requested", false),
        finalBytes = obj.optLong("final_bytes", 0L),
        subtitleStatus = obj.optStringOrBlank("subtitle_status"),
        subtitleMessage = obj.optStringOrBlank("subtitle_message"),
        embeddedSubtitles = obj.optJSONArray("embedded_subtitles").strings(),
        imdbId = obj.optStringOrBlank("imdb_id"),
        season = obj.optInt("season", 0),
        episode = obj.optInt("episode", 0),
        title = obj.optStringOrBlank("title"),
        isSeries = obj.optStringOrBlank("media_type") == "series",
        media = obj.optJSONObject("media")?.let { runCatching { mediaFrom(it) }.getOrNull() },
        finishedAt = obj.optDouble("finished_at", 0.0).takeUnless { it.isNaN() } ?: 0.0,
        files = (obj.optJSONArray("files") ?: JSONArray()).objects().map { file ->
            JobFile(
                path = file.getString("path"),
                season = file.optInt("season", 0),
                episode = file.optInt("episode", 0),
                bytes = file.optLong("bytes", 0L),
            )
        },
    )
}

data class SubtitleLanguage(val code: String, val name: String, val preferred: Boolean)

/** One subtitle offered by OpenSubtitles for a video. */
data class OnlineSubtitle(
    val fileId: Int,
    val language: String,
    val languageName: String,
    val release: String,
    /** Made for exactly this video file, so its timing is right. */
    val hashMatch: Boolean,
    val hearingImpaired: Boolean,
    val machineTranslated: Boolean,
    val downloadCount: Int,
    /** Where it was saved, when it has been downloaded before; blank otherwise. */
    val path: String,
)

/** A subtitle that was just saved, and the downloads left today (-1 when unknown). */
data class SubtitleDownload(val path: String, val remaining: Int)

fun parseSubtitleLanguages(raw: String): List<SubtitleLanguage> = JSONArray(raw).objects().map { obj ->
    SubtitleLanguage(obj.getString("code"), obj.getString("name"), obj.optBoolean("preferred", false))
}

fun parseOnlineSubtitles(raw: String): List<OnlineSubtitle> = JSONArray(raw).objects().map { obj ->
    OnlineSubtitle(
        fileId = obj.getInt("file_id"),
        language = obj.getString("language"),
        languageName = obj.optStringOrBlank("language_name"),
        release = obj.optStringOrBlank("release"),
        hashMatch = obj.optBoolean("hash_match", false),
        hearingImpaired = obj.optBoolean("hearing_impaired", false),
        machineTranslated = obj.optBoolean("machine_translated", false),
        downloadCount = obj.optInt("download_count", 0),
        path = obj.optStringOrBlank("path"),
    )
}

private val VIDEO_EXTENSIONS = listOf(".mkv", ".mp4", ".avi", ".m4v", ".ts", ".mov", ".wmv", ".webm", ".mpg", ".mpeg")

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

/** optString returns the literal "null" for JSON nulls; treat those as blank. */
private fun JSONObject.optStringOrBlank(key: String): String = if (isNull(key)) "" else optString(key, "")

fun formatBytes(bytes: Long): String {
    val safe = max(bytes, 0L).toDouble()
    return when {
        safe >= GIB -> "%.1f GB".format(safe / GIB)
        safe >= MIB -> "%.0f MB".format(safe / MIB)
        safe >= KIB -> "%.0f KB".format(safe / KIB)
        else -> "${safe.toLong()} B"
    }
}

fun formatRuntime(minutes: Int): String? = when {
    minutes <= 0 -> null
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

fun formatSpeed(bytesPerSecond: Double): String {
    val safe = max(bytesPerSecond, 0.0)
    return when {
        safe >= MIB -> "%.1f MB/s".format(safe / MIB)
        safe >= KIB -> "%.0f KB/s".format(safe / KIB)
        else -> "${safe.roundToLong()} B/s"
    }
}

fun formatEta(seconds: Double): String? {
    if (seconds.isNaN() || seconds <= 0) return null
    val total = seconds.roundToLong()
    return when {
        total < 60 -> "${total}s left"
        total < 3600 -> "${total / 60} min left"
        else -> "${total / 3600} h ${(total % 3600) / 60} min left"
    }
}

fun fileName(path: String): String = File(path).name

/** "4K", "1080p"... as a release or file name states it; null when it doesn't. */
fun resolutionTag(name: String): String? = when {
    Regex("""\b(2160p|4k|uhd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "4K"
    else -> Regex("""\b(1080|720|480)[pi]\b""", RegexOption.IGNORE_CASE).find(name)?.let { "${it.groupValues[1]}p" }
}

/** Chaquopy errors look like "RuntimeError: message"; keep only the message. */
fun Throwable.friendlyMessage(fallback: String): String {
    val raw = message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    val cleaned = raw.replace(Regex("^[A-Za-z_.]*(Error|Exception):\\s*"), "")
    return cleaned.ifBlank { fallback }
}

private const val KIB = 1024.0
private const val MIB = KIB * 1024.0
private const val GIB = MIB * 1024.0


/**
 * Short labels pulled from a release title: source, HDR format, codec, audio and group.
 * "Show.S01E02.2160p.WEB-DL.DV.HDR10.H.265.DDP5.1.Atmos-FLUX" -> WEB-DL, Dolby Vision, HDR10, HEVC, DD+ 5.1, Atmos, FLUX
 */
fun releaseTags(title: String): List<String> {
    val t = title.replace('_', ' ')
    fun has(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(t)
    val tags = mutableListOf<String>()
    when {
        has("""\bremux\b""") -> tags += "REMUX"
        has("""\b(blu-?ray|bdrip|brrip|bdremux)\b""") -> tags += "BluRay"
        has("""\bweb-?dl\b""") -> tags += "WEB-DL"
        has("""\bweb-?rip\b""") -> tags += "WEBRip"
        has("""\bhdtv\b""") -> tags += "HDTV"
    }
    if (has("""\b(dv|dovi|dolby[ .]?vision)\b""")) tags += "Dolby Vision"
    when {
        has("""\bhdr10(\+|plus)""") -> tags += "HDR10+"
        has("""\bhdr(10)?\b""") -> tags += "HDR"
    }
    when {
        has("""\bav1\b""") -> tags += "AV1"
        has("""\b(x265|h[ .]?265|hevc)\b""") -> tags += "HEVC"
        has("""\b(x264|h[ .]?264|avc)\b""") -> tags += "H.264"
    }
    val channels = Regex("""(?<![0-9])([257])[ .]([01])(?![0-9])""").find(t)?.let { " ${it.groupValues[1]}.${it.groupValues[2]}" }.orEmpty()
    when {
        has("""\btrue-?hd\b""") -> tags += "TrueHD$channels"
        has("""\bdts[ -.]?(hd|x|ma)\b""") -> tags += "DTS-HD$channels"
        has("""\bdts\b""") -> tags += "DTS$channels"
        has("""\b(ddp|dd\+|e-?ac-?3)""") -> tags += "DD+$channels"
        has("""\b(dd|ac-?3)[ .]?[257]""") -> tags += "DD$channels"
        has("""\baac""") -> tags += "AAC$channels"
    }
    if (has("""\batmos\b""")) tags += "Atmos"
    Regex("""-([A-Za-z0-9]{2,15})(?:\.(?:mkv|mp4|avi))?\s*(?:\[[^]]*])?\s*$""").find(t.trim())?.let { match ->
        val group = match.groupValues[1]
        if (!group.matches(Regex("""(?i)dl|rip|hd|\d+p"""))) tags += group
    }
    return tags
}
