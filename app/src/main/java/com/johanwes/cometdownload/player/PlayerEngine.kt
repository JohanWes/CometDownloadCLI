package com.johanwes.cometdownload.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleExtractorsFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.withFfmpegSubtitles
import java.io.File

const val SEEK_STEP_MS = 10_000L

/**
 * ExoPlayer set up for local files: the device's own (hardware) decoders first, with the
 * bundled FFmpeg ones stepping in for formats phones rarely ship (DTS, TrueHD...), and
 * libass/FFmpeg for styled and picture-based subtitles.
 */
@OptIn(UnstableApi::class)
fun buildPlayer(context: Context): ExoPlayer {
    val renderers = NextRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    val dataSources = DefaultDataSource.Factory(context)
    val sources = DefaultMediaSourceFactory(context, FfmpegSubtitleExtractorsFactory())
        .setDataSourceFactory(dataSources)
        .withFfmpegSubtitles(dataSources)
    return ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(sources)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .build()
}

/** A short reason the user can act on. */
fun playbackErrorMessage(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "That file is gone. It may have been moved or deleted."
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Comet isn't allowed to read this file."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    -> "This device can't decode this video."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    -> "This file is damaged or in a format that isn't supported."
    else -> "Playback stopped unexpectedly."
}

/** Hands the file to another video app; returns an error message if that isn't possible. */
fun openInOtherApp(context: Context, path: String): String? {
    val file = File(path)
    if (!file.exists()) return "That file is gone. It may have been moved or deleted."
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val extension = file.extension.lowercase()
        val mime = when (extension) {
            "mkv" -> "video/x-matroska"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/*"
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        null
    } catch (_: ActivityNotFoundException) {
        "No video player installed that can open this file."
    } catch (_: IllegalArgumentException) {
        "Can't share this file with a player."
    }
}
