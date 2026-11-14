package com.johanwes.cometdownload

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process (and Wi-Fi) awake while downloads run, and shows their progress.
 * Started on every enqueue/retry; stops itself once nothing is queued or downloading.
 */
class DownloadForegroundService : Service() {
    private var backend: CometBackend? = null
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var lastStartId = 0

    /** Jobs seen active during this run, for the summary notice when everything is done. */
    private val seenJobs = mutableSetOf<Int>()
    private var idlePolls = 0
    private var readFailures = 0

    @Volatile
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        worker = HandlerThread("comet-download-service").apply { start() }
        handler = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wasRunning = lastStartId != 0
        // Every start counts: stopSelf(lastStartId) must refer to the newest one.
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            // From the notification's Cancel button (a plain start, so no startForeground needed).
            val ids = intent.getIntArrayExtra(EXTRA_JOB_IDS) ?: intArrayOf()
            handler.post {
                ids.forEach { id -> runCatching { ensureBackend().cancel(id) } }
                if (wasRunning) {
                    handler.removeCallbacks(poll)
                    handler.post(poll)
                } else {
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }
        // A refused re-entry while an earlier start already holds the foreground must not
        // take the running service (and its wake lock) away from active downloads.
        if (!enterForeground(progressNotification(null)) && !inForeground) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireLocks()
        handler.removeCallbacks(poll)
        idlePolls = 0
        handler.post(poll)
        // Jobs live in memory only, so restarting after the process dies would have nothing to do.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Only reached if the system enforces a time limit on this service type.
        Log.w(TAG, "Foreground service timed out (type $fgsType)")
        shutDown(startId)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
        releaseLocks()
        super.onDestroy()
    }

    private val poll = object : Runnable {
        override fun run() {
            // Read before the job list: a start that lands after this point (a new download
            // this poll can't see yet) then makes stopSelfResult refuse to stop the service.
            val startId = lastStartId
            val read = runCatching { ensureBackend().jobs() }
                .onFailure { Log.w(TAG, "Could not read jobs", it) }
                .getOrNull()
            if (read == null) {
                // Not knowing isn't "nothing running": stay up and ask again, within reason.
                if (++readFailures < MAX_READ_FAILURES) {
                    reschedule()
                    return
                }
            } else {
                readFailures = 0
            }
            val jobs = read.orEmpty()
            val active = jobs.filter { it.isActive }
            active.forEach { seenJobs += it.id }
            if (active.isEmpty()) {
                // A short grace period covers the gap between a finished job and the next enqueue.
                if (++idlePolls >= 2) {
                    postSummary(jobs.filter { it.id in seenJobs })
                    shutDown(startId)
                    return
                }
            } else {
                idlePolls = 0
                notificationManager().notify(NOTIFICATION_ID, progressNotification(active))
            }
            reschedule()
        }

        /** A start that arrived mid-run has queued its own poll; keep it to a single chain. */
        private fun reschedule() {
            handler.removeCallbacks(this)
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun shutDown(startId: Int) {
        handler.removeCallbacks(poll)
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        // stopSelf(startId) is a no-op if a newer start arrived meanwhile.
        if (!stopSelfResult(startId)) {
            enterForeground(progressNotification(null))
            acquireLocks()
            idlePolls = 0
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        inForeground = true
        true
    } catch (error: Exception) {
        // E.g. ForegroundServiceStartNotAllowedException when started from the background.
        Log.w(TAG, "Could not enter the foreground", error)
        false
    }

    private fun ensureBackend(): CometBackend = backend ?: CometBackend.get(this).also { backend = it }

    // Called from both the main thread (starts) and the poll thread (shutdown), so guarded:
    // an unsynchronised release could drop the reference to a lock that was just acquired.
    @Synchronized
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CometDownload:downloads").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock?.isHeld != true) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CometDownload:downloads").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    @Synchronized
    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun progressNotification(active: List<JobItem>?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(openDownloads())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (active.isNullOrEmpty()) {
            return builder.setContentTitle("Starting download…").setProgress(0, 0, true).build()
        }
        val downloading = active.filter { it.state == JobState.Downloading && it.overallProgress != null }
        val lead = downloading.firstOrNull() ?: active.first()
        val title = if (active.size == 1) lead.label else "Downloading ${active.size} items"
        // Average of whole-job progress, so season packs don't restart at 0% for every file.
        val progress = downloading.map { it.overallProgress ?: 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val speed = downloading.sumOf { it.speedBytes }
        val detail = when {
            downloading.isEmpty() -> when {
                lead.isWaiting -> lead.phase
                lead.state == JobState.Queued -> "Waiting in queue"
                // Past the byte transfer: "Finalizing files", "Downloading subtitles".
                lead.state == JobState.Downloading -> lead.phase.ifBlank { "Downloading" }
                else -> "Preparing links"
            }
            else -> listOfNotNull(
                "${((progress ?: 0f) * 100).toInt()}%",
                formatSpeed(speed),
                lead.takeIf { active.size == 1 && it.totalFiles > 1 }?.let { "file ${it.completedFiles + 1} of ${it.totalFiles}" },
                formatEta(downloading.maxOf { it.etaSeconds }),
            ).joinToString(" · ")
        }
        builder.setContentTitle(title).setContentText(detail)
        if (progress != null) {
            builder.setProgress(1000, (progress * 1000).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val cancelIds = active.filterNot { it.cancelRequested }.map { it.id }
        if (cancelIds.isNotEmpty()) {
            val cancel = Intent(this, DownloadForegroundService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_JOB_IDS, cancelIds.toIntArray())
            val pending = PendingIntent.getService(
                this,
                1,
                cancel,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, if (cancelIds.size == 1) "Cancel" else "Cancel all", pending)
        }
        return builder.build()
    }

    /** One "done" notice per run, only when the app isn't on screen to show it already. */
    private fun postSummary(finished: List<JobItem>) {
        seenJobs.clear()
        if (finished.isEmpty() || AppVisibility.visible) return
        val completed = finished.filter { it.state == JobState.Completed }
        val failed = finished.filter { it.state == JobState.Failed }
        if (completed.isEmpty() && failed.isEmpty()) return
        val title = when {
            failed.isEmpty() && completed.size == 1 -> "Downloaded"
            failed.isEmpty() -> "${completed.size} downloads finished"
            completed.isEmpty() && failed.size == 1 -> "Download failed"
            completed.isEmpty() -> "${failed.size} downloads failed"
            else -> "${completed.size} finished, ${failed.size} failed"
        }
        val text = when {
            finished.size == 1 && failed.size == 1 -> "${failed[0].label}: ${failed[0].errorText}"
            finished.size == 1 -> completed[0].label
            else -> (completed + failed).joinToString(", ") { it.label }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openDownloads())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager().notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun openDownloads(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while downloads run, so they keep going with the screen off."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when downloads finish or fail."
            },
        )
        // The single channel from older versions.
        manager.deleteNotificationChannel("comet_downloads")
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "CometDownloadService"
        private const val CHANNEL_PROGRESS = "comet_progress"
        private const val CHANNEL_DONE = "comet_done"
        private const val NOTIFICATION_ID = 100
        private const val DONE_NOTIFICATION_ID = 101
        private const val POLL_MS = 2000L
        private const val MAX_READ_FAILURES = 15
        private const val ACTION_CANCEL = "com.johanwes.cometdownload.CANCEL"
        private const val EXTRA_JOB_IDS = "job_ids"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadForegroundService::class.java))
            }.onFailure { Log.w(TAG, "Could not start the download service", it) }
        }
    }
}
