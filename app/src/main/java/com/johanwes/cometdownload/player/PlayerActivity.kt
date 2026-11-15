package com.johanwes.cometdownload.player

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.johanwes.cometdownload.player.ui.PlayerScreen
import com.johanwes.cometdownload.ui.CometTheme

/** Full-screen playback of downloaded videos. Playback itself lives in [PlayerViewModel]. */
class PlayerActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Leaving fades back to the app, as arriving faded out of it.
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Use the whole screen, also beside a camera cut-out; controls keep clear of it.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        applyOrientation(resources.configuration)

        viewModel.open(
            paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty(),
            labels = intent.getStringArrayListExtra(EXTRA_LABELS).orEmpty(),
            startIndex = intent.getIntExtra(EXTRA_INDEX, 0),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
        )

        setContent {
            CometTheme {
                // The screen stays on only while something is actually playing.
                LaunchedEffect(viewModel.isPlaying) {
                    if (viewModel.isPlaying) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                PlayerScreen(viewModel = viewModel, onClose = ::finish)
            }
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding or unfolding changes which rule applies.
        applyOrientation(newConfig)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onLeaving()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onBackground()
    }

    /** Phones play in landscape; tablets and unfolded foldables rotate freely. */
    private fun applyOrientation(configuration: Configuration) {
        requestedOrientation = if (configuration.smallestScreenWidthDp < LARGE_SCREEN_MIN_WIDTH_DP) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    companion object {
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_TITLE = "title"
        private const val LARGE_SCREEN_MIN_WIDTH_DP = 600

        /**
         * Plays [paths] (the episodes of one title) starting with the one at [index]. [labels]
         * says what each one is ("S1 E2 · Cat's in the Bag..."), blank where that is unknown.
         *
         * [covered] says the screen has been flooded black already (see PlayFlood): the player,
         * black itself, then simply takes over. Otherwise it fades in.
         */
        fun start(
            context: Context,
            paths: List<String>,
            labels: List<String>,
            index: Int,
            title: String,
            covered: Boolean = false,
        ) {
            val transition = if (covered) {
                ActivityOptions.makeCustomAnimation(context, 0, 0)
            } else {
                ActivityOptions.makeCustomAnimation(context, android.R.anim.fade_in, android.R.anim.fade_out)
            }
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
                    .putStringArrayListExtra(EXTRA_LABELS, ArrayList(labels))
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_TITLE, title),
                transition.toBundle(),
            )
        }
    }
}
