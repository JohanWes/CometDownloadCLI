package com.johanwes.cometdownload.player

import android.content.Context
import org.json.JSONObject
import java.io.File

/** What is remembered about one video between plays. */
data class Playback(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Played to the end (the position is back at the start then). */
    val watched: Boolean = false,
    /** The subtitle choice, as [SubtitleSelection.key]; null when never chosen by hand. */
    val subtitle: String? = null,
    /** Position of the chosen audio track in the file's audio list; -1 for the default. */
    val audio: Int = -1,
    val subtitleDelayMs: Long = 0,
    /** When it was last played (epoch ms); 0 when never, or before this was recorded. */
    val updatedAt: Long = 0,
) {
    /** How far into the video playback stopped, or null when there is nothing to resume. */
    val progress: Float?
        get() = if (positionMs > 0 && durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
}

/** Resume positions and per-video choices, kept in plain preferences keyed by file path. */
class PlaybackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("comet_playback", Context.MODE_PRIVATE)

    fun get(path: String): Playback {
        val raw = prefs.getString(path, null) ?: return Playback()
        return runCatching {
            val obj = JSONObject(raw)
            Playback(
                positionMs = obj.optLong("position", 0),
                durationMs = obj.optLong("duration", 0),
                watched = obj.optBoolean("watched", false),
                subtitle = if (obj.has("subtitle")) obj.getString("subtitle") else null,
                audio = obj.optInt("audio", -1),
                subtitleDelayMs = obj.optLong("delay", 0),
                updatedAt = obj.optLong("played", 0),
            )
        }.getOrDefault(Playback())
    }

    fun update(path: String, change: (Playback) -> Playback) {
        val value = change(get(path))
        val obj = JSONObject()
            .put("position", value.positionMs)
            .put("duration", value.durationMs)
            .put("watched", value.watched)
            .put("audio", value.audio)
            .put("delay", value.subtitleDelayMs)
            .put("played", value.updatedAt)
        if (value.subtitle != null) obj.put("subtitle", value.subtitle)
        prefs.edit().putString(path, obj.toString()).apply()
    }

    /** Stores where playback is; near the end counts as watched and resets the position. */
    fun savePosition(path: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val finished = positionMs >= durationMs * WATCHED_FRACTION || durationMs - positionMs < WATCHED_TAIL_MS
        val now = System.currentTimeMillis()
        update(path) {
            when {
                finished -> it.copy(positionMs = 0, durationMs = durationMs, watched = true, updatedAt = now)
                // The first seconds aren't worth a "resume" prompt.
                positionMs < MIN_RESUME_MS -> it.copy(positionMs = 0, durationMs = durationMs)
                else -> it.copy(positionMs = positionMs, durationMs = durationMs, updatedAt = now)
            }
        }
    }

    /** Marks a video as watched or not by hand; either way there is nothing left to resume. */
    fun setWatched(path: String, watched: Boolean) {
        update(path) { it.copy(positionMs = 0, watched = watched) }
    }

    /** Text size of subtitles relative to the default, shared by all videos. */
    var subtitleScale: Float
        get() = prefs.getFloat(KEY_SUBTITLE_SCALE, 1f)
        set(value) = prefs.edit().putFloat(KEY_SUBTITLE_SCALE, value).apply()

    /** How subtitle text looks, shared by all videos. */
    var subtitleStyle: SubtitleStyle
        get() = SubtitleStyle().let { default ->
            SubtitleStyle(
                textColor = prefs.getInt(KEY_SUBTITLE_TEXT_COLOR, default.textColor),
                borderColor = prefs.getInt(KEY_SUBTITLE_BORDER_COLOR, default.borderColor),
                border = prefs.getInt(KEY_SUBTITLE_BORDER, default.border),
            )
        }
        set(value) = prefs.edit()
            .putInt(KEY_SUBTITLE_TEXT_COLOR, value.textColor)
            .putInt(KEY_SUBTITLE_BORDER_COLOR, value.borderColor)
            .putInt(KEY_SUBTITLE_BORDER, value.border)
            .apply()

    /** Forgets videos that no longer exist, so the store doesn't grow forever. */
    fun prune() {
        val gone = prefs.all.keys.filter { it.startsWith("/") && !File(it).exists() }
        if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach(::remove) }.apply()
    }

    private companion object {
        const val KEY_SUBTITLE_SCALE = "#subtitle_scale"
        const val KEY_SUBTITLE_TEXT_COLOR = "#subtitle_text_color"
        const val KEY_SUBTITLE_BORDER_COLOR = "#subtitle_border_color"
        const val KEY_SUBTITLE_BORDER = "#subtitle_border"
        const val WATCHED_FRACTION = 0.95
        const val WATCHED_TAIL_MS = 30_000L
        const val MIN_RESUME_MS = 15_000L
    }
}
