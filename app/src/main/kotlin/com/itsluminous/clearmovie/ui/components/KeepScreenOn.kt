package com.itsluminous.clearmovie.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When the screen is kept on (cm-keep-screen-on): while ClearMovie is on
 * screen and an export of any video is waiting or saving, or something is
 * playing on the screen that shows (the editor's preview, a voice being
 * recorded, the app's own player). Paused, ended, failed, stopped, left or
 * in the background, the phone's own screen timeout applies again.
 */
object KeepScreenOn {
    /** Whether to keep the screen on: [visible] is the activity started, [holds] the screens asking for it now. */
    fun shouldKeepOn(
        visible: Boolean,
        exporting: Boolean,
        holds: Int,
    ): Boolean = visible && (exporting || holds > 0)

    /** Whether an export is waiting or saving, from the export works' finished flags (any video). */
    fun exporting(finished: List<Boolean>): Boolean = finished.any { !it }

    /**
     * Whether a player counts as playing: playing now, or asked to play and
     * still loading. Paused, ended, failed (idle) or held back by the system
     * (lost audio focus) does not count.
     */
    fun playing(
        isPlaying: Boolean,
        playWhenReady: Boolean,
        buffering: Boolean,
    ): Boolean = isPlaying || (playWhenReady && buffering)
}

/**
 * Keeps the activity window's screen-on flag in step with [KeepScreenOn]:
 * [apply] gets true to set FLAG_KEEP_SCREEN_ON and false to clear it, only
 * when the answer changes. Main thread only.
 */
class ScreenAwake(
    private val apply: (Boolean) -> Unit,
) {
    private var holds = 0
    private var applied = false

    /** Whether the activity is started (on screen); set by its lifecycle. */
    var visible = false
        set(value) {
            field = value
            update()
        }

    /** Whether an export of any video is waiting or saving. */
    var exporting = false
        set(value) {
            field = value
            update()
        }

    /** Whether the flag is set now. */
    val on: Boolean get() = applied

    /** One screen asking to keep the screen on, until [Hold.release]. */
    fun hold(): Hold {
        holds++
        update()
        return Hold()
    }

    private fun update() {
        val wanted = KeepScreenOn.shouldKeepOn(visible, exporting, holds)
        if (wanted == applied) return
        applied = wanted
        apply(wanted)
    }

    /** A screen's request; releasing it twice counts once. */
    inner class Hold internal constructor() {
        private var released = false

        fun release() {
            if (released) return
            released = true
            holds--
            update()
        }
    }
}

/** The activity's [ScreenAwake]; null outside it (previews, tests of one screen), where nothing is kept on. */
val LocalScreenAwake = staticCompositionLocalOf<ScreenAwake?> { null }

/** Keeps the screen on while [active] and this is in the composition (the screen is showing). */
@Composable
fun KeepScreenOnWhile(active: Boolean) {
    val awake = LocalScreenAwake.current
    DisposableEffect(awake, active) {
        val hold = if (active) awake?.hold() else null
        onDispose { hold?.release() }
    }
}
