package com.johanwes.cometdownload

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.johanwes.cometdownload.ui.CometApp

class MainActivity : ComponentActivity() {
    private val viewModel: CometViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        requestLegacyStoragePermission()
        handleIntent(intent)

        // Downloads live in the process-wide Python manager and keep running in the
        // foreground service, so leaving the activity must not shut them down.
        setContent {
            CometApp(viewModel = viewModel, onExit = { moveTaskToBack(true) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The user may have just come back from the "All files access" settings page.
        viewModel.refreshStorageAccess()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) {
            viewModel.showDownloads()
            intent.removeExtra(EXTRA_OPEN_DOWNLOADS)
        }
    }

    private fun requestLegacyStoragePermission() {
        // Android 11+ uses "All files access" instead (asked for when it is first needed).
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_STORAGE,
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        private const val REQUEST_STORAGE = 1001
        private const val REQUEST_NOTIFICATIONS = 1002

        /**
         * Opens the system "All files access" page for this app (Android 11+). On older
         * versions it opens the app's settings page, where the Storage permission lives.
         */
        fun openStorageAccessSettings(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                val details = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                )
                runCatching { context.startActivity(details) }
                return
            }
            val appPage = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { context.startActivity(appPage) }
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
        }
    }
}
