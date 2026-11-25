package com.itsluminous.clearmovie.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** cm-keep-screen-on: when the screen is kept on, and that the window flag follows it exactly. */
class KeepScreenOnTest {
    private val applied = mutableListOf<Boolean>()
    private val awake = ScreenAwake { applied += it }

    @Test
    fun onlyWhileVisibleAndExportingOrHeld() {
        assertThat(KeepScreenOn.shouldKeepOn(visible = false, exporting = false, holds = 0)).isFalse()
        assertThat(KeepScreenOn.shouldKeepOn(visible = true, exporting = false, holds = 0)).isFalse()
        assertThat(KeepScreenOn.shouldKeepOn(visible = true, exporting = true, holds = 0)).isTrue()
        assertThat(KeepScreenOn.shouldKeepOn(visible = true, exporting = false, holds = 2)).isTrue()
        // In the background nothing keeps it on, an export and a playing screen included.
        assertThat(KeepScreenOn.shouldKeepOn(visible = false, exporting = true, holds = 1)).isFalse()
    }

    @Test
    fun anUnfinishedExportOfAnyVideoCounts() {
        assertThat(KeepScreenOn.exporting(emptyList())).isFalse()
        assertThat(KeepScreenOn.exporting(listOf(true, true))).isFalse()
        assertThat(KeepScreenOn.exporting(listOf(true, false))).isTrue()
        assertThat(KeepScreenOn.exporting(listOf(false))).isTrue()
    }

    @Test
    fun playingIsPlayingOrLoadingToPlay() {
        assertThat(KeepScreenOn.playing(isPlaying = true, playWhenReady = true, buffering = false)).isTrue()
        assertThat(KeepScreenOn.playing(isPlaying = false, playWhenReady = true, buffering = true)).isTrue()
        // Paused while loading; ended, failed or held back by the system (playWhenReady stays set there); paused.
        assertThat(KeepScreenOn.playing(isPlaying = false, playWhenReady = false, buffering = true)).isFalse()
        assertThat(KeepScreenOn.playing(isPlaying = false, playWhenReady = true, buffering = false)).isFalse()
        assertThat(KeepScreenOn.playing(isPlaying = false, playWhenReady = false, buffering = false)).isFalse()
    }

    @Test
    fun theFlagIsSetAndClearedOnlyWhenTheAnswerChanges() {
        val hold = awake.hold()
        assertThat(applied).isEmpty() // not on screen yet
        awake.visible = true
        assertThat(applied).containsExactly(true)
        awake.exporting = true
        hold.release()
        assertThat(applied).containsExactly(true) // the export still keeps it on
        awake.exporting = false
        assertThat(applied).containsExactly(true, false).inOrder()
        assertThat(awake.on).isFalse()
    }

    @Test
    fun goingToTheBackgroundClearsItAtOnce() {
        awake.visible = true
        awake.hold()
        awake.exporting = true
        awake.visible = false
        assertThat(applied).containsExactly(true, false).inOrder()
    }

    @Test
    fun backOnScreenNothingStaleSetsItUntilTheScreensAndTheExportAnswerAgain() {
        awake.visible = true
        val stale = awake.hold()
        awake.exporting = true
        val before = awake.generation
        awake.visible = false
        // A preview paused by going to the background still holds, and the export may have ended meanwhile.
        awake.visible = true
        assertThat(applied).containsExactly(true, false).inOrder()
        assertThat(awake.generation).isNotEqualTo(before)
        // The old hold goes as the screen asks again; releasing it changes nothing.
        stale.release()
        assertThat(awake.on).isFalse()
        // The screen asks again, still playing: on.
        val fresh = awake.hold()
        assertThat(awake.on).isTrue()
        fresh.release()
        assertThat(awake.on).isFalse()
        // The export answers again, still saving: on.
        awake.exporting = true
        assertThat(applied).containsExactly(true, false, true, false, true).inOrder()
    }

    @Test
    fun everyHoldMustBeReleasedAndADoubleReleaseCountsOnce() {
        awake.visible = true
        val a = awake.hold()
        val b = awake.hold()
        a.release()
        a.release()
        assertThat(awake.on).isTrue() // b still holds it
        b.release()
        assertThat(awake.on).isFalse()
        assertThat(applied).containsExactly(true, false).inOrder()
        // A stray release never takes the count below zero, so the next hold still sets the flag.
        a.release()
        awake.hold()
        assertThat(awake.on).isTrue()
    }
}
