package com.johanwes.cometdownload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import kotlin.concurrent.thread

class DownloadForegroundService : Service() {
    private var running = false
    private var api: PyObject? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ready", "Downloads can continue while locked."))
        running = true
        ensurePythonApi()
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensurePythonApi()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun ensurePythonApi() {
        if (api != null) {
            return
        }
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloads.mkdirs()
        api = Python.getInstance()
            .getModule("comet.android_api")
            .callAttr("get_api", downloads.absolutePath, 2)
    }

    private fun startPolling() {
        thread(name = "comet-download-notification", isDaemon = true) {
            while (running) {
                val text = runCatching { notificationText() }
                    .getOrDefault("Downloads can continue while locked.")
                val title = if (text.startsWith("Downloading")) "Comet Download" else "Comet Download"
                notificationManager().notify(NOTIFICATION_ID, buildNotification(title, text))
                Thread.sleep(2500)
            }
        }
    }

    private fun notificationText(): String {
        val raw = api?.callAttr("jobs")?.toString() ?: return "Downloads can continue while locked."
        val jobs = JSONArray(raw)
        var active = 0
        var completed = 0
        var failed = 0
        var activeLabel = ""
        for (index in 0 until jobs.length()) {
            val job = jobs.getJSONObject(index)
            when (job.optString("status")) {
                "queued", "resolving", "downloading" -> {
                    active += 1
                    if (activeLabel.isBlank()) {
                        activeLabel = job.optString("label", "")
                    }
                }
                "completed" -> completed += 1
                "failed" -> failed += 1
            }
        }
        return when {
            active > 1 -> "Downloading $active items"
            active == 1 && activeLabel.isNotBlank() -> "Downloading $activeLabel"
            completed > 0 && failed > 0 -> "$completed complete, $failed failed"
            completed > 0 -> "$completed complete"
            failed > 0 -> "$failed failed"
            else -> "Ready. Downloads can continue while locked."
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Comet downloads",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Keeps Comet downloads running while the tablet is locked."
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val CHANNEL_ID = "comet_downloads"
        private const val NOTIFICATION_ID = 100
    }
}
