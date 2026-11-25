package com.itsluminous.clearmovie

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.itsluminous.clearmovie.data.media.ShareHandoff
import com.itsluminous.clearmovie.data.media.ShareLinks
import com.itsluminous.clearmovie.ui.components.KeepScreenOn
import com.itsluminous.clearmovie.ui.components.LocalScreenAwake
import com.itsluminous.clearmovie.ui.components.ScreenAwake
import com.itsluminous.clearmovie.ui.navigation.ClearMovieApp
import com.itsluminous.clearmovie.ui.share.ShareViewModel
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import com.itsluminous.clearmovie.ui.theme.applyNightMode
import com.itsluminous.clearmovie.work.ExportWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single, edge-to-edge activity.
 *
 * It extends [AppCompatActivity] (a ComponentActivity, so Compose is
 * unaffected) because AppCompat applies the in-app language on Android 10-12
 * (AppCompatDelegate.setApplicationLocales).
 *
 * It keeps its window's screen on (FLAG_KEEP_SCREEN_ON, no wake lock) while
 * it is on screen and an export runs or a screen plays ([ScreenAwake]).
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** The share flow; the same instance the app's screens use (activity scope, default key). */
    private val share: ShareViewModel by viewModels()

    @Inject lateinit var workManager: WorkManager

    /** The screen-on flag of this window (cm-keep-screen-on); cleared the moment the activity stops. */
    private val awake =
        ScreenAwake { on ->
            if (on) window.addFlags(KEEP_SCREEN_ON) else window.clearFlags(KEEP_SCREEN_ON)
            Log.i(KEEP_SCREEN_ON_TAG, "FLAG_KEEP_SCREEN_ON ${if (on) "set" else "cleared"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: swaps Theme.ClearMovie.Starting for the app theme.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until the saved settings are known, so the content
        // never appears in the wrong theme or colours first.
        splashScreen.setKeepOnScreenCondition { viewModel.settings.value == null }
        enableEdgeToEdge()
        // A share that started the app. After a rotation or a process death the
        // share's own saved state carries on instead, so it is never taken twice.
        if (savedInstanceState == null) receiveShare(intent)
        keepScreenOnWhileNeeded()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val loaded = settings ?: return@setContent
            // A Theme change in Settings reaches the whole app (window, dialogs),
            // not only Compose; ClearMovieApplication applied the saved one at start.
            LaunchedEffect(loaded.themeMode) { applyNightMode(loaded.themeMode) }
            val darkTheme = loaded.themeMode.isDark(isSystemInDarkTheme())

            // System bar icons follow the app's theme, including the in-app
            // override, not only the phone's dark mode.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose {}
            }

            CompositionLocalProvider(LocalScreenAwake provides awake) {
                ClearMovieTheme(themeMode = loaded.themeMode, dynamicColor = loaded.dynamicColor) {
                    ClearMovieApp(launchDecision = viewModel::launchDecision, share = share)
                }
            }
        }
    }

    /**
     * Follows the lifecycle directly, not through Compose, whose recomposer is
     * paused once the activity stops: the flag goes the moment the app leaves
     * the screen. An export of any video counts while it waits or saves; the
     * export itself runs in the background as before, whatever this says.
     */
    private fun keepScreenOnWhileNeeded() {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    awake.visible = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    awake.visible = false
                }
            },
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                workManager
                    .getWorkInfosForUniqueWorkFlow(ExportWorker.UNIQUE_NAME)
                    .map { works -> KeepScreenOn.exporting(works.map { it.state.isFinished }) }
                    .distinctUntilChanged()
                    .collect { awake.exporting = it }
            }
        }
    }

    /** A share while ClearMovie is open, handed on by [ShareActivity]. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    /**
     * Only shares handed on by [ShareActivity], by a one-time token from this
     * process: a SEND, or a made-up hand-off, sent here directly (this
     * activity is exported as the launcher) is ignored, and so is one Android
     * replays from Recents (its token was used).
     */
    private fun receiveShare(intent: Intent?) {
        if (intent?.action != ShareLinks.ACTION_SHARED) return
        val token = runCatching { intent.getStringExtra(ShareHandoff.EXTRA_TOKEN) }.getOrNull()
        ShareHandoff.take(token)?.let(share::receive)
    }

    private companion object {
        const val KEEP_SCREEN_ON_TAG = "KeepScreenOn"
        const val KEEP_SCREEN_ON = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        // The scrims androidx.activity uses by default for three-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
