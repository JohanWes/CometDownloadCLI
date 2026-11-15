package com.johanwes.cometdownload.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A published version of the app and where its APK is. */
data class AppRelease(
    val version: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkName: String,
    val size: Long,
    /** SHA-256 of the APK as published by GitHub; blank when it doesn't say. */
    val sha256: String,
)

sealed interface UpdateState {
    /** Not checked yet in this run and nothing remembered from an earlier one. */
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class CheckFailed(val message: String) : UpdateState
    /** [problem] says why the last attempt to install it didn't work, if one was made. */
    data class Available(val release: AppRelease, val problem: String? = null) : UpdateState
    /** [progress] is 0..1, or null while the size isn't known. */
    data class Downloading(val release: AppRelease, val progress: Float?) : UpdateState
    /** Handed to Android, which closes the app while it replaces it. */
    data class Installing(val release: AppRelease) : UpdateState
}

/**
 * Self-update from the project's GitHub releases: looks up the latest release, downloads its
 * APK, checks it is really this app from the same signer and hands it to Android's installer.
 * Android installs it over the running version (settings and library stay). The app is
 * closed by that; [UpdateReceiver] brings it back, or offers to where Android won't allow it.
 */
object AppUpdater {
    private const val FEED_URL = "https://api.github.com/repos/JohanWes/CometDownloadCLI/releases/latest"
    private const val PREFS = "app_update"
    private const val KEY_RELEASE = "latest_release"
    private const val KEY_CHECKED_AT = "checked_at"
    const val KEY_REOPEN = "reopen_after_update"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    /** Debug builds only: a file with a feed URL to use instead of GitHub (for testing). */
    private const val DEBUG_FEED_FILE = "update_feed_url"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var state by mutableStateOf<UpdateState>(UpdateState.Unknown)
        private set

    /** True while a newer version is waiting (drives the dot on the Update tab). */
    val hasUpdate: Boolean
        get() = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Installing

    fun installedVersion(context: Context): String =
        installedInfo(context).versionName.orEmpty().substringBefore('-')

    /** Looks for a new version unless that was done recently; [force] always asks GitHub. */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing) return
        val prefs = prefs(app)
        if (state is UpdateState.Unknown) {
            remembered(app)?.let { state = it }
            // The APK of an update that has been installed (or given up on) is no longer needed.
            scope.launch(Dispatchers.IO) { File(app.cacheDir, "updates").deleteRecursively() }
        }
        val checkedAt = prefs.getLong(KEY_CHECKED_AT, 0)
        val fresh = System.currentTimeMillis() - checkedAt in 0 until CHECK_INTERVAL_MS
        if (!force && fresh && state !is UpdateState.Unknown) return
        val before = state
        if (force || before is UpdateState.Unknown) state = UpdateState.Checking
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetchLatest(app) } }
            if (state is UpdateState.Downloading || state is UpdateState.Installing) return@launch
            state = result.fold(
                onSuccess = { release ->
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong(KEY_CHECKED_AT, now).putString(KEY_RELEASE, release.toJson()).apply()
                    if (isNewer(release.version, installedVersion(app))) UpdateState.Available(release) else UpdateState.UpToDate(now)
                },
                onFailure = { error ->
                    // A failed background check keeps showing what was known before it.
                    if (!force && before !is UpdateState.Unknown) before else UpdateState.CheckFailed(describe(error))
                },
            )
        }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the user allows this app to install its own updates. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Downloads the waiting release and starts its installation. */
    fun downloadAndInstall(context: Context) {
        val app = context.applicationContext
        val release = (state as? UpdateState.Available)?.release ?: return
        state = UpdateState.Downloading(release, 0f)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val apk = download(app, release) { progress ->
                        scope.launch { if (state is UpdateState.Downloading) state = UpdateState.Downloading(release, progress) }
                    }
                    verify(app, apk, release)
                    apk
                }
            }
            val apk = result.getOrElse {
                state = UpdateState.Available(release, describe(it))
                return@launch
            }
            state = UpdateState.Installing(release)
            runCatching { withContext(Dispatchers.IO) { install(app, apk) } }
                .onFailure { state = UpdateState.Available(release, describe(it)) }
        }
    }

    /** Called by [UpdateReceiver] when Android gave up on the installation. */
    fun installFailed(status: Int, message: String?) {
        val release = (state as? UpdateState.Installing)?.release ?: return
        state = UpdateState.Available(
            release,
            when {
                status == PackageInstaller.STATUS_FAILURE_ABORTED -> "The installation was cancelled."
                status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free space to install the update."
                status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    "Android refused the update: it doesn't match the installed app."
                else -> "Android could not install the update" + (message?.let { " ($it)." } ?: ".")
            },
        )
    }

    // --- Looking up the latest release ---------------------------------------------------

    private fun feedUrl(context: Context): String {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val override = File(context.filesDir, DEBUG_FEED_FILE)
            if (override.exists()) override.readText().trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return FEED_URL
    }

    private fun fetchLatest(context: Context): AppRelease {
        val connection = URL(feedUrl(context)).openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CometDownload/${installedVersion(context)}")
        }
        if (connection is HttpURLConnection && connection.responseCode != 200) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException(if (code == 403 || code == 429) "GitHub is limiting requests right now. Try again later." else "GitHub answered with HTTP $code.")
        }
        val json = JSONObject(connection.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) })
        val assets = json.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: throw IOException("The latest release has no APK attached.")
        return AppRelease(
            version = json.getString("tag_name").removePrefix("v"),
            pageUrl = json.optString("html_url"),
            apkUrl = apk.getString("browser_download_url"),
            apkName = apk.getString("name").substringAfterLast('/'),
            size = apk.optLong("size", -1),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:").orEmpty(),
        )
    }

    private fun remembered(context: Context): UpdateState? {
        val prefs = prefs(context)
        val release = prefs.getString(KEY_RELEASE, null)?.let { runCatching { releaseFromJson(it) }.getOrNull() } ?: return null
        return if (isNewer(release.version, installedVersion(context))) {
            UpdateState.Available(release)
        } else {
            UpdateState.UpToDate(prefs.getLong(KEY_CHECKED_AT, 0))
        }
    }

    /** Compares dotted version numbers: "1.0.10" is newer than "1.0.9". */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(installed)
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
            if (difference != 0) return difference > 0
        }
        return false
    }

    // --- Download, checks and installation -----------------------------------------------

    private fun download(context: Context, release: AppRelease, onProgress: (Float?) -> Unit): File {
        val dir = File(context.cacheDir, "updates")
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val target = File(dir, release.apkName)
        val connection = URL(release.apkUrl).openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        if (connection is HttpURLConnection && connection.responseCode != 200) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("The download failed (HTTP $code).")
        }
        val total = release.size.takeIf { it > 0 } ?: connection.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var reported = -1
        connection.getInputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    done += read
                    val percent = if (total > 0) (done * 100 / total).toInt() else -1
                    if (percent != reported) {
                        reported = percent
                        onProgress(if (percent >= 0) percent / 100f else null)
                    }
                }
            }
        }
        if (release.size > 0 && done != release.size) {
            target.delete()
            throw IOException("The download was cut short. Try again.")
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256.isNotEmpty() && !release.sha256.equals(sha256, ignoreCase = true)) {
            target.delete()
            throw IOException("The downloaded file doesn't match the published one. Try again.")
        }
        return target
    }

    /** The APK must be this app, not older than the installed one, and from the same signer. */
    private fun verify(context: Context, apk: File, release: AppRelease) {
        try {
            val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw IOException("The downloaded file is not a valid APK.")
            if (info.packageName != context.packageName) {
                throw IOException("Version ${release.version} is a different app (${info.packageName}) and can't replace this one.")
            }
            if (info.longVersionCode < installedInfo(context).longVersionCode) {
                throw IOException("Version ${release.version} is older than the installed one.")
            }
            // Read from the file, the signing details need the archive paths to be set.
            info.applicationInfo?.apply {
                sourceDir = apk.absolutePath
                publicSourceDir = apk.absolutePath
            }
            val theirs = signers(info)
            val ours = signers(installedInfo(context, PackageManager.GET_SIGNING_CERTIFICATES))
            if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs.intersect(ours).isEmpty()) {
                throw IOException("Version ${release.version} is signed with a different key than the installed app.")
            }
        } catch (error: IOException) {
            apk.delete()
            throw error
        }
    }

    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Lets later updates install without the system's confirmation dialog.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output, 1 shl 16)
                    session.fsync(output)
                }
            }
            val status = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            // Remembered on disk: this process is gone by the time the new version is in place.
            prefs(context).edit().putBoolean(KEY_REOPEN, true).commit()
            session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
        }
    }

    // --- Helpers ---------------------------------------------------------------------------

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installedInfo(context: Context, flags: Int = 0): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, flags)

    private fun describe(error: Throwable): String = when (error) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "Could not reach GitHub. Check your connection."
        is org.json.JSONException -> "GitHub sent an answer the app couldn't read."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong (${error.javaClass.simpleName})."
    }

    private fun AppRelease.toJson(): String = JSONObject()
        .put("version", version).put("pageUrl", pageUrl).put("apkUrl", apkUrl)
        .put("apkName", apkName).put("size", size).put("sha256", sha256).toString()

    private fun releaseFromJson(raw: String): AppRelease = JSONObject(raw).let {
        AppRelease(
            it.getString("version"), it.optString("pageUrl"), it.getString("apkUrl"),
            it.getString("apkName"), it.optLong("size", -1), it.optString("sha256"),
        )
    }
}
