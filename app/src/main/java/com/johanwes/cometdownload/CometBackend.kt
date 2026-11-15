package com.johanwes.cometdownload

import android.content.Context
import android.os.Environment
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Python side of the app (`comet.android_api`): search, the download manager, the library
 * of finished downloads and subtitles. One instance per process, shared by the activities
 * and the download service.
 *
 * Every call blocks (most do network or disk work), so call them off the main thread. Failures
 * surface as exceptions whose message is already fit to show (see [friendlyMessage]).
 */
class CometBackend private constructor(private val api: PyObject, val downloadsPath: String) {

    fun configure(settings: AppSettings) {
        api.callAttr(
            "configure",
            settings.provider.id,
            settings.token,
            settings.subtitleLanguages,
            settings.subtitlesEnabled,
        )
    }

    fun testDebrid(provider: Provider, token: String): String =
        api.callAttr("test_debrid", provider.id, token).toString()

    // Search

    fun search(query: String): List<MediaItem> = parseMedia(api.callAttr("search", query).toString())

    fun details(media: MediaItem, refresh: Boolean): MediaDetails =
        parseDetails(api.callAttr("details", media.json, refresh).toString())

    fun streams(media: MediaItem, scope: EpisodeScope): List<StreamItem> =
        parseStreams(api.callAttr("streams", media.json, media.season(scope), media.episode(scope)).toString())

    // Downloads

    /** Returns the id of the new job. */
    fun enqueue(media: MediaItem, stream: StreamItem, scope: EpisodeScope): Int =
        jobId(api.callAttr("enqueue", media.json, stream.json, media.season(scope), media.episode(scope)))

    /** Queues a failed or cancelled job again; returns the id of the job that replaces it. */
    fun retry(jobId: Int): Int = jobId(api.callAttr("retry", jobId))

    fun cancel(jobId: Int) {
        api.callAttr("cancel", jobId)
    }

    fun jobs(): List<JobItem> = parseJobs(api.callAttr("jobs").toString())

    fun activeCount(): Int = api.callAttr("active_count").toInt()

    /** Drops failed and cancelled jobs from the list (completed ones are the library). */
    fun clearFailed() {
        api.callAttr("clear_finished")
    }

    /** Takes a finished download off the list, optionally deleting its files. */
    fun removeJob(jobId: Int, deleteFiles: Boolean) {
        api.callAttr("remove_job", jobId, deleteFiles)
    }

    /** Deletes some videos of a finished download, with their subtitles (one episode, say). */
    fun deleteFiles(jobId: Int, paths: List<String>) {
        api.callAttr("delete_files", jobId, JSONArray(paths).toString())
    }

    /**
     * Plot, rating, artwork and episode titles of the titles that have downloads, by IMDb id.
     * Without [fetch] only what is stored on the device (instant, works offline); with it,
     * missing and outdated entries are fetched first.
     */
    fun libraryDetails(fetch: Boolean): Map<String, MediaDetails> =
        parseLibraryDetails(api.callAttr("library_details", fetch).toString())

    // Subtitles

    /** Looks for a subtitle again for a whole finished job (runs in the background). */
    fun refetchSubtitles(jobId: Int, tryAnother: Boolean) {
        api.callAttr("refetch_subtitles", jobId, tryAnother)
    }

    /** Languages to offer when searching, the user's preferred ones first. */
    fun subtitleLanguages(): List<SubtitleLanguage> =
        parseSubtitleLanguages(api.callAttr("subtitle_languages").toString())

    /** Subtitles found online for one downloaded video, best match first. */
    fun searchSubtitles(videoPath: String, languages: String): List<OnlineSubtitle> =
        parseOnlineSubtitles(api.callAttr("subtitle_search", videoPath, languages).toString())

    /** Saves one subtitle next to the video and returns the path of the new file. */
    fun downloadSubtitle(videoPath: String, subtitle: OnlineSubtitle): String =
        JSONObject(api.callAttr("subtitle_download", videoPath, subtitle.fileId, subtitle.language).toString())
            .getString("path")

    private fun jobId(result: PyObject): Int = JSONObject(result.toString()).getInt("job_id")

    private fun MediaItem.season(scope: EpisodeScope): Int = if (isSeries) scope.season else 0

    private fun MediaItem.episode(scope: EpisodeScope): Int = if (isSeries) scope.episode else 0

    companion object {
        private const val MAX_PARALLEL_DOWNLOADS = 2

        @Volatile
        private var instance: CometBackend? = null

        fun get(context: Context): CometBackend = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        /** Where downloads are saved. Known without starting Python. */
        fun downloadsDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        private fun create(context: Context): CometBackend {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            val downloads = downloadsDir()
            downloads.mkdirs()
            val api = Python.getInstance()
                .getModule("comet.android_api")
                .callAttr("get_api", downloads.absolutePath, MAX_PARALLEL_DOWNLOADS, context.filesDir.absolutePath)
            return CometBackend(api, downloads.absolutePath).apply {
                // Configured from the stored settings right away, whichever screen or
                // service brought the process up.
                val settings = SettingsStore(context).load()
                if (settings.isConfigured) runCatching { configure(settings) }
            }
        }
    }
}
