package com.itsluminous.clearmovie.ui.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaPermissions

/** The current photo and video access, and the ways to change it. */
@Stable
class MediaAccessController internal constructor(
    state: MediaAccessState,
    private val launch: () -> Unit,
    private val settings: () -> Unit,
    private val answerCount: () -> Int = { 0 },
) {
    var state: MediaAccessState by mutableStateOf(state)
        internal set

    /**
     * How many answers to [request] have arrived, kept across rotation and
     * process death. A screen that must act on the answer (Allow all photos,
     * then take the items in again) notes this before it asks and acts when
     * it changes: a resume alone can come before the answer, with the old
     * [state].
     */
    val answers: Int get() = answerCount()

    /** Shows Android's own request (also "Allow all photos" under partial access). */
    fun request() = launch()

    /** Opens the app's page in Android Settings, for [MediaAccessState.DeniedForGood]. */
    fun openSettings() = settings()
}

/**
 * Tracks [MediaAccessState] for the current activity, re-reading it whenever
 * the screen resumes (the user may have changed it in Android Settings), and
 * records that the app has asked, through [onAsked], the first time it does.
 */
@Composable
fun rememberMediaAccessController(
    askedBefore: Boolean,
    onAsked: () -> Unit,
): MediaAccessController {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(askedBefore) }
    if (askedBefore) asked = true
    var answers by rememberSaveable { mutableIntStateOf(0) }
    val holder = remember { arrayOfNulls<MediaAccessController>(1) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            holder[0]?.state = readState(context, asked = true)
            answers++
        }
    val controller =
        remember {
            MediaAccessController(
                state = readState(context, asked),
                launch = {
                    if (!asked) {
                        asked = true
                        onAsked()
                    }
                    launcher.launch(MediaPermissions.toRequest(Build.VERSION.SDK_INT).toTypedArray())
                },
                settings = {
                    val intent =
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                answerCount = { answers },
            ).also { holder[0] = it }
        }
    LifecycleResumeEffect(asked) {
        controller.state = readState(context, asked)
        onPauseOrDispose {}
    }
    return controller
}

private fun readState(
    context: Context,
    asked: Boolean,
): MediaAccessState {
    val sdk = Build.VERSION.SDK_INT
    val requested = MediaPermissions.toRequest(sdk)
    val granted = requested.filterTo(mutableSetOf()) { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val activity = context.findActivity()
    val rationale = activity != null && requested.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    return MediaPermissions.state(sdk, granted, asked, rationale)
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
