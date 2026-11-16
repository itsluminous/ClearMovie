package com.itsluminous.clearmovie

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.clearmovie.data.media.ShareHandoff
import com.itsluminous.clearmovie.data.media.ShareLinks
import com.itsluminous.clearmovie.ui.navigation.ClearMovieApp
import com.itsluminous.clearmovie.ui.share.ShareViewModel
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import com.itsluminous.clearmovie.ui.theme.applyNightMode
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single, edge-to-edge activity.
 *
 * It extends [AppCompatActivity] (a ComponentActivity, so Compose is
 * unaffected) because AppCompat applies the in-app language on Android 10-12
 * (AppCompatDelegate.setApplicationLocales).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** The share flow; the same instance the app's screens use (activity scope, default key). */
    private val share: ShareViewModel by viewModels()

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

            ClearMovieTheme(themeMode = loaded.themeMode, dynamicColor = loaded.dynamicColor) {
                ClearMovieApp(launchDecision = viewModel::launchDecision, share = share)
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
        // The scrims androidx.activity uses by default for three-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
