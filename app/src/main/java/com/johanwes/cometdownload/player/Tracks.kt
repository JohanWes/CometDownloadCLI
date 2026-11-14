package com.johanwes.cometdownload.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter

/** One audio track of the playing file. */
data class AudioOption(
    val group: TrackGroup,
    val trackIndex: Int,
    val title: String,
    val detail: String,
    /** False when neither the device nor the bundled decoders can play it. */
    val supported: Boolean,
    val selected: Boolean,
)

/** One subtitle track inside the playing file. */
data class TextOption(
    val group: TrackGroup,
    val trackIndex: Int,
    /** ISO 639-1 code where known ("en"), else blank. */
    val language: String,
    val title: String,
    val detail: String,
    /** Picture-based (PGS, VobSub): can't be restyled or resized. */
    val image: Boolean,
    val forced: Boolean,
    val supported: Boolean,
    val selected: Boolean,
)

data class ChapterMark(val startMs: Long, val title: String)

/** Which subtitles are showing. */
sealed interface SubtitleSelection {
    /** Stable text form for remembering the choice with the video. */
    val key: String

    data object Off : SubtitleSelection {
        override val key = "off"
    }

    /** The [ordinal]-th subtitle track inside the file. */
    data class Embedded(val ordinal: Int) : SubtitleSelection {
        override val key = "track:$ordinal"
    }

    data class File(val path: String) : SubtitleSelection {
        override val key = "file:${java.io.File(path).name}"
    }
}

fun audioOptions(tracks: Tracks): List<AudioOption> = tracks.groups
    .filter { it.type == C.TRACK_TYPE_AUDIO }
    .flatMap { group ->
        (0 until group.length).map { index ->
            val format = group.getTrackFormat(index)
            val language = languageName(format.language)
            val codec = audioCodecName(format)
            val channels = channelLayout(format.channelCount)
            // Track names often just repeat what is already shown ("English", "Stereo").
            val label = format.label?.takeIf { name ->
                name.isNotBlank() && listOf(language, codec, channels).none { name.equals(it, ignoreCase = true) }
            }
            AudioOption(
                group = group.mediaTrackGroup,
                trackIndex = index,
                title = language.ifBlank { label ?: "Unknown language" },
                detail = listOfNotNull(codec, channels, label.takeIf { language.isNotBlank() }).joinToString(" · "),
                supported = group.isTrackSupported(index),
                selected = group.isTrackSelected(index),
            )
        }
    }

fun textOptions(tracks: Tracks): List<TextOption> = tracks.groups
    .filter { it.type == C.TRACK_TYPE_TEXT }
    // The player groups tracks by how they are decoded; list them in the file's own order.
    .sortedBy { it.getTrackFormat(0).id?.toIntOrNull() ?: Int.MAX_VALUE }
    .flatMap { group ->
        (0 until group.length).map { index ->
            val format = group.getTrackFormat(index)
            val language = languageName(format.language)
            val label = format.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
            val forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
            val hearingImpaired = format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0
            val kind = subtitleFormatName(format)
            TextOption(
                group = group.mediaTrackGroup,
                trackIndex = index,
                language = format.language?.substringBefore('-')?.lowercase()?.takeUnless { it == "und" }.orEmpty(),
                title = language.ifBlank { label ?: "Unknown language" },
                detail = listOfNotNull(
                    label.takeIf { language.isNotBlank() },
                    "Forced".takeIf { forced },
                    "SDH".takeIf { hearingImpaired && label?.contains("SDH", ignoreCase = true) != true },
                    kind.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                image = kind in IMAGE_SUBTITLE_FORMATS,
                forced = forced,
                supported = group.isTrackSupported(index),
                selected = group.isTrackSelected(index),
            )
        }
    }

/** Chapters from the container (Matroska, MP4), in order, without hidden ones. */
@OptIn(UnstableApi::class)
fun chapterMarks(tracks: Tracks): List<ChapterMark> {
    val marks = sortedMapOf<Long, String>()
    for (group in tracks.groups) {
        for (index in 0 until group.length) {
            val metadata = group.getTrackFormat(index).metadata ?: continue
            for (entry in 0 until metadata.length()) {
                val chapter = metadata[entry] as? Chapter ?: continue
                if (chapter.isHidden) continue
                marks.getOrPut(chapter.startTimeMs) { chapter.title?.value.orEmpty() }
            }
        }
    }
    // A single chapter covering the whole video is no help for navigation.
    if (marks.size < 2) return emptyList()
    return marks.entries.mapIndexed { index, (start, title) ->
        ChapterMark(start, title.ifBlank { "Chapter ${index + 1}" })
    }
}

private fun audioCodecName(format: Format): String? = when (format.sampleMimeType) {
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+ Atmos"
    MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS-HD"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP3"
    MimeTypes.AUDIO_RAW -> "PCM"
    MimeTypes.AUDIO_ALAC -> "ALAC"
    else -> null
}

private fun channelLayout(channels: Int): String? = when (channels) {
    Format.NO_VALUE, 0 -> null
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "$channels channels"
}

private val IMAGE_SUBTITLE_FORMATS = setOf("PGS", "VobSub", "DVB")

/** Text tracks are often converted to a common cue format on the way in; then the original
 * type is kept in [Format.codecs]. */
@OptIn(UnstableApi::class)
private fun subtitleFormatName(format: Format): String {
    val types = listOfNotNull(format.codecs, format.sampleMimeType, format.containerMimeType)
        .joinToString(" ")
        .lowercase()
    return when {
        "pgs" in types -> "PGS"
        "vobsub" in types -> "VobSub"
        "dvbsub" in types -> "DVB"
        "ssa" in types || "ass" in types -> "ASS"
        "subrip" in types -> "SRT"
        "vtt" in types -> "WebVTT"
        "ttml" in types -> "TTML"
        "tx3g" in types || "quicktime" in types -> "Text"
        else -> ""
    }
}
