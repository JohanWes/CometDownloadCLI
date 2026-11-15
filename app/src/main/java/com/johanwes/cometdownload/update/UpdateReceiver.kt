package com.johanwes.cometdownload.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.johanwes.cometdownload.MainActivity
import com.johanwes.cometdownload.R

/**
 * Follows an update started by [AppUpdater]: shows Android's confirmation when it asks for
 * one, reports failures, and opens the app again once the new version is installed.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INSTALL_STATUS -> onInstallStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> reopen(context)
        }
    }

    private fun onInstallStatus(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                val shown = confirm != null &&
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (!shown) fail(context, PackageInstaller.STATUS_FAILURE, "the confirmation could not be shown")
            }
            // Normally this process is replaced before the answer arrives; MY_PACKAGE_REPLACED
            // then does the reopening in the new version.
            PackageInstaller.STATUS_SUCCESS -> reopen(context)
            else -> fail(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    private fun fail(context: Context, status: Int, message: String?) {
        AppUpdater.prefs(context).edit().remove(AppUpdater.KEY_REOPEN).apply()
        AppUpdater.installFailed(status, message)
    }

    /** Only after an update the user started here; other installs leave the app closed. */
    private fun reopen(context: Context) {
        val prefs = AppUpdater.prefs(context)
        if (!prefs.getBoolean(AppUpdater.KEY_REOPEN, false)) return
        prefs.edit().remove(AppUpdater.KEY_REOPEN).apply()
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Android 10 and later don't let an app open itself from the background, so there is
        // also a notification to tap. MainActivity removes it when it does come up.
        notifyUpdated(context, open)
        runCatching { context.startActivity(open) }
    }

    private fun notifyUpdated(context: Context, open: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_HIGH),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Comet Download was updated")
            .setContentText("Version ${AppUpdater.installedVersion(context)} is installed. Tap to open.")
            .setContentIntent(
                PendingIntent.getActivity(
                    context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.johanwes.cometdownload.update.INSTALL_STATUS"
        const val NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "app_updates"

        fun dismissUpdatedNotice(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
