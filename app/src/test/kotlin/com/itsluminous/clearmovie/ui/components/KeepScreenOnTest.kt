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
        // Paused while loading, ended or failed (playWhenReady stays set there), or held back by the system.
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
    fun goingToTheBackgroundClearsItAtOnceAndComingBackSetsItAgain() {
        awake.visible = true
        awake.hold()
        awake.visible = false
        assertThat(applied).containsExactly(true, false).inOrder()
        awake.visible = true
        assertThat(applied).containsExactly(true, false, true).inOrder()
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
