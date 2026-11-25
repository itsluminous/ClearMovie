package com.itsluminous.clearmovie.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** cm-keep-screen-on: [KeepScreenOnWhile] holds the screen exactly while it is active and in the composition. */
@RunWith(RobolectricTestRunner::class)
class KeepScreenOnWhileTest {
    @get:Rule
    val compose = createComposeRule()

    private val applied = mutableListOf<Boolean>()
    private val awake = ScreenAwake { applied += it }.apply { visible = true }

    @Test
    fun holdsWhileActiveAndShownAndLetsGoWhenPausedOrLeft() {
        var playing by mutableStateOf(false)
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalScreenAwake provides awake) {
                if (shown) KeepScreenOnWhile(playing)
            }
        }
        compose.waitForIdle()
        assertThat(awake.on).isFalse()
        playing = true
        compose.waitForIdle()
        assertThat(awake.on).isTrue()
        playing = false
        compose.waitForIdle()
        assertThat(awake.on).isFalse()
        playing = true
        compose.waitForIdle()
        assertThat(awake.on).isTrue()
        // The screen is left while it plays.
        shown = false
        compose.waitForIdle()
        assertThat(awake.on).isFalse()
        assertThat(applied).containsExactly(true, false, true, false).inOrder()
    }

    @Test
    fun twoScreensHoldingKeepItOnUntilBothLetGo() {
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalScreenAwake provides awake) {
                KeepScreenOnWhile(first)
                KeepScreenOnWhile(second)
            }
        }
        compose.waitForIdle()
        assertThat(awake.on).isTrue()
        first = false
        compose.waitForIdle()
        assertThat(awake.on).isTrue()
        second = false
        compose.waitForIdle()
        assertThat(awake.on).isFalse()
    }

    @Test
    fun afterTheActivityStopsAndStartsTheScreenAsksAgain() {
        var playing by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalScreenAwake provides awake) {
                KeepScreenOnWhile(playing)
            }
        }
        compose.waitForIdle()
        assertThat(awake.on).isTrue()
        awake.visible = false
        assertThat(awake.on).isFalse()
        awake.visible = true
        // Nothing stale sets it at the start: the screen's hold is asked for again on the next composition.
        assertThat(awake.on).isFalse()
        compose.waitForIdle()
        assertThat(awake.on).isTrue()
        // Paused while it was in the background: it stays off.
        awake.visible = false
        playing = false
        awake.visible = true
        compose.waitForIdle()
        assertThat(awake.on).isFalse()
    }

    @Test
    fun withoutTheActivityNothingIsHeld() {
        compose.setContent { KeepScreenOnWhile(true) }
        compose.waitForIdle()
        assertThat(applied).isEmpty()
    }
}
