package com.johanwes.cometdownload.player

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** A subtitle file saved next to a video, e.g. `Movie.en.srt` for `Movie.mkv`. */
data class Sidecar(val path: String, val language: String, val label: String)

/**
 * Subtitle files that belong to [videoPath]: same name with `.srt`, optionally with a language
 * (`.en.srt`) and a counter (`.en (1).srt`), which is how the downloader names them.
 */
fun findSidecars(videoPath: String): List<Sidecar> {
    val video = File(videoPath)
    val stem = video.nameWithoutExtension
    val files = video.parentFile?.listFiles { file ->
        file.isFile && file.name.startsWith(stem) && file.extension.equals("srt", ignoreCase = true)
    }.orEmpty()
    return files.sortedBy { it.name }.mapNotNull { file ->
        // What sits between the video's name and ".srt": "", ".en", ".en (1)", ".sv.forced"...
        val middle = file.nameWithoutExtension.removePrefix(stem)
        if (middle.isNotEmpty() && !middle.startsWith(".")) return@mapNotNull null // another video's file
        val match = SIDECAR_SUFFIX.matchEntire(middle.removePrefix("."))
        val code = match?.groupValues?.get(1).orEmpty().lowercase()
        val counter = match?.groupValues?.get(2).orEmpty()
        val name = languageName(code).ifBlank { middle.removePrefix(".").ifBlank { "Subtitles" } }
        Sidecar(file.absolutePath, code, if (counter.isEmpty()) name else "$name ${counter.toInt() + 1}")
    }
}

private val SIDECAR_SUFFIX = Regex("""([A-Za-z]{2,3}(?:-[A-Za-z]{2})?)(?: \((\d+)\))?""")

/** "en" -> "English"; blank for an unknown or missing code. */
fun languageName(code: String?): String {
    if (code.isNullOrBlank() || code == "und") return ""
    val name = Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)
    return if (name.isBlank() || name.equals(code, ignoreCase = true)) code.uppercase() else name
}

/** A loaded subtitle file: finds what should be on screen at a given moment. */
class SubtitleTrack(private val starts: LongArray, private val ends: LongArray, private val cues: List<Cue>) {
    val isEmpty: Boolean get() = cues.isEmpty()

    /** Indexes of the cues visible at [positionMs] (usually none or one). */
    fun activeAt(positionMs: Long): List<Int> {
        var low = 0
        var high = starts.size - 1
        var last = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= positionMs) {
                last = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (last < 0) return emptyList()
        // Cues can overlap (a sign over dialogue), so look a few entries back as well.
        val active = ArrayList<Int>(2)
        for (index in maxOf(0, last - OVERLAP_LOOKBACK)..last) {
            if (ends[index] > positionMs) active += index
        }
        return active
    }

    fun cuesAt(indexes: List<Int>): List<Cue> = indexes.map { cues[it] }

    private companion object {
        const val OVERLAP_LOOKBACK = 6
    }
}

fun loadSubtitleTrack(path: String): SubtitleTrack = parseSrt(decodeText(File(path).readBytes()))

/** UTF-8 (with or without BOM) or UTF-16 by BOM; anything else is read as Windows-1252. */
private fun decodeText(bytes: ByteArray): String {
    fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
    if (startsWith(0xFF, 0xFE) || startsWith(0xFE, 0xFF)) return String(bytes, Charsets.UTF_16)
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
}

private val SRT_TIMING = Regex(
    """(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""",
)
private val SRT_TAG = Regex("""<(/?)([a-zA-Z]+)[^>]*>|\{\\[^}]*\}""")

fun parseSrt(text: String): SubtitleTrack {
    class Entry(val start: Long, val end: Long, val cue: Cue)

    val entries = ArrayList<Entry>()
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var index = 0
    while (index < lines.size) {
        val timing = SRT_TIMING.find(lines[index])
        index++
        if (timing == null) continue
        val (h1, m1, s1, f1, h2, m2, s2, f2) = timing.destructured
        val start = timeMs(h1, m1, s1, f1)
        val end = timeMs(h2, m2, s2, f2)
        val body = StringBuilder()
        while (index < lines.size && lines[index].isNotBlank()) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(lines[index].trimEnd())
            index++
        }
        if (end > start && body.isNotBlank()) entries += Entry(start, end, buildCue(body.toString()))
    }
    entries.sortBy { it.start }
    return SubtitleTrack(
        starts = LongArray(entries.size) { entries[it].start },
        ends = LongArray(entries.size) { entries[it].end },
        cues = entries.map { it.cue },
    )
}

private fun timeMs(hours: String, minutes: String, seconds: String, fraction: String): Long =
    hours.toLong() * 3_600_000 + minutes.toLong() * 60_000 + seconds.toLong() * 1000 +
        fraction.padEnd(3, '0').toLong()

/** Turns `<i>`, `<b>`, `<u>` into styling, drops other tags; `{\an8}` moves the line to the top. */
@OptIn(UnstableApi::class)
private fun buildCue(raw: String): Cue {
    val text = SpannableStringBuilder()
    val open = HashMap<String, Int>()
    var top = false
    var cursor = 0
    for (match in SRT_TAG.findAll(raw)) {
        text.append(raw, cursor, match.range.first)
        cursor = match.range.last + 1
        if (match.value.startsWith("{")) {
            if (Regex("""\\an[789]""").containsMatchIn(match.value)) top = true
            continue
        }
        val name = match.groupValues[2].lowercase()
        if (name !in setOf("i", "b", "u")) continue
        if (match.groupValues[1].isEmpty()) {
            open[name] = text.length
        } else {
            val from = open.remove(name) ?: continue
            if (from < text.length) text.setSpan(spanFor(name), from, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    text.append(raw, cursor, raw.length)
    // Tags left open run to the end of the cue.
    open.forEach { (name, from) ->
        if (from < text.length) text.setSpan(spanFor(name), from, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val builder = Cue.Builder().setText(text)
    if (top) builder.setLine(0f, Cue.LINE_TYPE_NUMBER).setLineAnchor(Cue.ANCHOR_TYPE_START)
    return builder.build()
}

private fun spanFor(tag: String): Any = when (tag) {
    "i" -> StyleSpan(Typeface.ITALIC)
    "b" -> StyleSpan(Typeface.BOLD)
    else -> UnderlineSpan()
}
