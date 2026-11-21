package com.itsluminous.clearmovie.ui

import android.app.UiAutomation
import android.content.res.Configuration
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.addToTrack
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.ui.editor.BAR_TAG
import com.itsluminous.clearmovie.ui.editor.EDITOR_TOP_BAR_TAG
import com.itsluminous.clearmovie.ui.editor.TRANSPORT_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.rowTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's rows scroll up and down in the real app (cm-timeline-scroll),
 * on a video with nine rows that the app reopens at launch: held upright a
 * swipe scrolls them and Show all rows stays; held sideways at least four
 * whole rows show (on a phone at least 400 dp tall that way), there is no
 * Show all rows, the top bar is 56 dp and the bottom bar is as tall as the
 * Play bar beside it, and a swipe scrolls the rows.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TimelineScrollDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private lateinit var project: Project

    @Before
    fun setUp() {
        h.setUp()
        h.bindPreset()
        val items = h.typicalItems()
        val sound = MediaRef.Store(h.insert("fixtures/speech.m4a", "audio/mp4").toString())
        val pip = MediaRef.Store(h.insert("fixtures/photo_blue.png", "image/png").toString())
        project =
            runBlocking {
                val preset = requireNotNull(h.app.presetStore.effectivePreset(h.presetId))
                val names = TrackId("scroll-names")
                val music = TrackId("scroll-music")
                val voice = TrackId("scroll-voice")
                val pictures = TrackId("scroll-pip")
                val p =
                    ProjectFactory
                        .fromPreset(ProjectId(RandomIds.next()), preset, h.app.presetStore.presetMedia(h.presetId))
                        .addToMain(items)
                        .addCaptionFor(items.first().id, "Happy birthday")
                        .addRow(TrackType.TEXT, id = names)
                        .addToTrack(names, TextClip(ClipId(RandomIds.next()), 0, 3000, "Grandma and Grandpa"), 4000)
                        .addRow(TrackType.AUDIO, AudioRole.MUSIC, music)
                        .addToTrack(music, AudioClip(ClipId(RandomIds.next()), sound, 0, 3000, 3000), 2000)
                        .addRow(TrackType.AUDIO, AudioRole.VOICE, voice)
                        .addToTrack(voice, AudioClip(ClipId(RandomIds.next()), sound, 0, 3000, 3000), 6000)
                        .addRow(TrackType.VISUAL, id = pictures)
                        .addToTrack(pictures, PhotoClip(ClipId(RandomIds.next()), pip, 3000, pip = PipPlacement.DEFAULT), 5000)
                assertThat(h.app.projectRepository.createDraft(p, inEditor = true)).isTrue()
                p
            }
        assertThat(project.tracks.size).isAtLeast(7)
    }

    @After
    fun tearDown() {
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        h.cleanUp()
    }

    @Test
    fun uprightTheRowsScrollAndShowAllRowsStays() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            turn(scenario, UiAutomation.ROTATION_FREEZE_0, landscape = false)
            rowsShown()
            val keys = project.tracks.map { it.id.value }
            val whole = read { wholeRows(keys) }
            Log.i(ExportHarness.TAG, "upright: ${whole.size} of ${keys.size} rows whole, window ${read { window() }}")
            if (whole.size < keys.size) assertThat(read { exists(TIMELINE_ROWS_TOGGLE_TAG) }).isTrue()
            swipeUpKeepsTheTimeAndMovesTheRows(keys)
        }
    }

    @Test
    fun sidewaysAtLeastFourRowsShowWithSlimBarsAndTheRowsScroll() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            turn(scenario, UiAutomation.ROTATION_FREEZE_90, landscape = true)
            rowsShown()
            val window = read { window() }
            assertThat(window.right - window.left).isGreaterThan(window.bottom - window.top)
            val keys = project.tracks.map { it.id.value }
            val whole = read { wholeRows(keys) }
            Log.i(ExportHarness.TAG, "sideways: ${whole.size} of ${keys.size} rows whole, window $window")
            // A phone at least 400 dp tall sideways (a 411 dp phone less its bars) shows four; a smaller one three.
            val wanted = if (window.bottom - window.top >= 400.dp) 4 else 3
            assertWithMessage("whole rows sideways in $window").that(whole.size).isAtLeast(wanted)
            assertThat(read { exists(TIMELINE_ROWS_TOGGLE_TAG) }).isFalse()
            val top = read { bounds(EDITOR_TOP_BAR_TAG) }
            assertThat((top.bottom - top.top).value).isWithin(0.5f).of(56f)
            val bar = read { bounds(BAR_TAG) }
            val play = read { bounds(TRANSPORT_TAG) }
            assertThat((bar.bottom - bar.top).value).isWithin(0.5f).of((play.bottom - play.top).value)
            swipeUpKeepsTheTimeAndMovesTheRows(keys)
        }
    }

    /** A swipe up the right-hand end of the rows (clear of the items) scrolls them, and the first row moves up. */
    private fun swipeUpKeepsTheTimeAndMovesTheRows(keys: List<String>) {
        val first = keys.first()
        val before = read { bounds(rowTag(first)).top }
        compose.onNodeWithTag(TIMELINE_ROWS_TAG).performTouchInput {
            swipe(Offset(width - 12f, height - 12f), Offset(width - 16f, 12f), SWIPE_MS)
        }
        compose.waitUntil(TIMEOUT_MS) { notYetIfBusy { bounds(rowTag(first)).top < before - 8.dp } }
    }

    /** Turns the display to [rotation] and waits until the app is laid out that way. */
    private fun turn(
        scenario: ActivityScenario<MainActivity>,
        rotation: Int,
        landscape: Boolean,
    ) {
        automation.setRotation(rotation)
        compose.waitUntil(LAUNCH_TIMEOUT_MS) {
            var turned = false
            runCatching {
                scenario.onActivity {
                    val orientation = it.resources.configuration.orientation
                    turned = (orientation == Configuration.ORIENTATION_LANDSCAPE) == landscape
                }
            }
            turned
        }
    }

    private fun rowsShown() =
        compose.waitUntil(LAUNCH_TIMEOUT_MS) {
            notYetIfBusy { compose.onAllNodes(hasTestTag(TIMELINE_ROWS_TAG)).fetchSemanticsNodes().isNotEmpty() }
        }

    private fun window(): DpRect = compose.onRoot().getUnclippedBoundsInRoot()

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** The rows wholly inside the rows' view. */
    private fun wholeRows(keys: List<String>): List<String> {
        val view = bounds(TIMELINE_ROWS_TAG)
        return keys.filter { k ->
            val r = bounds(rowTag(k))
            r.top >= view.top - 1.dp && r.bottom <= view.bottom + 1.dp
        }
    }

    /**
     * [block], tried again while Compose is busy (the preview's players post
     * frames on a software-GPU emulator) or a node it needs is not there yet.
     */
    private fun <T> read(block: () -> T): T {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            try {
                return block()
            } catch (e: ComposeNotIdleException) {
                if (System.currentTimeMillis() > end) throw e
            } catch (e: ComposeTimeoutException) {
                if (System.currentTimeMillis() > end) throw e
            } catch (e: AssertionError) {
                // A node not there yet: the editor still opening, or laid out again after the turn.
                if (System.currentTimeMillis() > end) throw e
            }
            Thread.sleep(200)
        }
    }

    private fun notYetIfBusy(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (e: ComposeNotIdleException) {
            Log.i(ExportHarness.TAG, "Compose busy, trying again: ${e.message?.lineSequence()?.firstOrNull()}")
            false
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("No compose hierarchies found") != true) throw e
            false
        } catch (e: AssertionError) {
            // The node is not there yet (the editor still opening).
            false
        }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val LAUNCH_TIMEOUT_MS = 60_000L
        const val SWIPE_MS = 400L
    }
}
