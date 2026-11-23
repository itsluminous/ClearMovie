package com.itsluminous.clearmovie.ui

import android.app.UiAutomation
import android.content.res.Configuration
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
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
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.addToTrack
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.ui.editor.PANEL_DONE_TAG
import com.itsluminous.clearmovie.ui.editor.UNDO_TAG
import com.itsluminous.clearmovie.ui.editor.insertOptionTag
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_PLAYHEAD_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_RULER_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TimelineGeometry
import com.itsluminous.clearmovie.ui.editor.timeline.rowAddTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowEndPlusTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowHeaderTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every row's own "+" in the real app (cm-plus-position), on a video whose
 * rows end at different times: upright and sideways each row in view has its
 * "+" floating at one place at the timeline's right edge, and a tap on a row's "+"
 * with the cursor in a gap adds there, which one Undo takes away.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RowEndPlusDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private lateinit var project: Project
    private val names = TrackId("plus-names")

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
                val music = TrackId("plus-music")
                val pictures = TrackId("plus-pip")
                val p =
                    ProjectFactory
                        .fromPreset(ProjectId(RandomIds.next()), preset, h.app.presetStore.presetMedia(h.presetId))
                        .addToMain(items)
                        .addRow(TrackType.TEXT, id = names)
                        .addToTrack(names, TextClip(ClipId(RandomIds.next()), 0, 3000, "Grandma and Grandpa"), 4000)
                        .addRow(TrackType.AUDIO, AudioRole.MUSIC, music)
                        .addToTrack(music, AudioClip(ClipId(RandomIds.next()), sound, 0, 3000, 3000), 2000)
                        .addRow(TrackType.VISUAL, id = pictures)
                        .addToTrack(pictures, PhotoClip(ClipId(RandomIds.next()), pip, 3000, pip = PipPlacement.DEFAULT), 5000)
                assertThat(h.app.projectRepository.createDraft(p, inEditor = true)).isTrue()
                p
            }
    }

    @After
    fun tearDown() {
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        h.cleanUp()
    }

    @Test
    fun uprightEveryRowsPlusIsInOneColumnAtTheRightEnd() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            turn(scenario, UiAutomation.ROTATION_FREEZE_0, landscape = false)
            oneColumn()
        }
    }

    @Test
    fun sidewaysEveryRowsPlusIsInOneColumnAtTheRightEnd() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            turn(scenario, UiAutomation.ROTATION_FREEZE_90, landscape = true)
            oneColumn()
        }
    }

    @Test
    fun aTapWithTheCursorInAGapAddsAtTheCursorAndUndoTakesItAway() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            turn(scenario, UiAutomation.ROTATION_FREEZE_0, landscape = false)
            rowsShown()
            // The cursor to the very start: a tap on the ruler where the time span begins.
            val ruler = read { bounds(TIMELINE_RULER_TAG) }
            val timeline = read { bounds(TIMELINE_TAG) }
            compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
                click(Offset((48 + TimelineGeometry.START_ROOM_DP + 1).dp.toPx(), ((ruler.top + ruler.bottom) / 2 - timeline.top).toPx()))
            }
            // A small screen (CI's) folds rows away behind Show all rows: open them so the text row is there.
            if (!read { exists(rowTag(names.value)) }) read { node(TIMELINE_ROWS_TOGGLE_TAG).performClick() }
            // ...and the rows scrolled to the top, where that row is (a row under the ruler has no "+").
            read { node(TIMELINE_ROWS_TAG).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) } }
            compose.waitUntil(TIMEOUT_MS) {
                notYetIfBusy {
                    node(rowEndPlusTag(names.value))
                        .fetchSemanticsNode()
                        .config
                        .toString()
                        .contains("0:00")
                }
            }
            // Through its accessibility action: the row may be scrolled below the rows' view on a small screen.
            read { node(rowEndPlusTag(names.value)).performSemanticsAction(SemanticsActions.OnClick) }
            read { node(insertOptionTag("text")).performClick() }
            read { node(PANEL_DONE_TAG).performClick() }
            val atCursor = hasAnyAncestor(hasTestTag(rowTag(names.value))) and hasContentDescription("from 0:00 to 0:03", substring = true)
            compose.waitUntil(TIMEOUT_MS) {
                notYetIfBusy {
                    compose.onAllNodes(atCursor, useUnmergedTree = true).fetchSemanticsNodes().size ==
                        1
                }
            }
            read { node(UNDO_TAG).performClick() }
            compose.waitUntil(
                TIMEOUT_MS,
            ) { notYetIfBusy { compose.onAllNodes(atCursor, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
            // The row's first caption is still there: one Undo took away only the new one.
            assertThat(
                compose
                    .onAllNodes(
                        hasContentDescription("Grandma and Grandpa", substring = true),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes(),
            ).isNotEmpty()
        }
    }

    @Test
    fun uprightTheVideoStartsRightAfterTheRowIcons() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            turn(scenario, UiAutomation.ROTATION_FREEZE_0, landscape = false)
            startInset()
        }
    }

    @Test
    fun sidewaysTheVideoStartsRightAfterTheRowIcons() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            turn(scenario, UiAutomation.ROTATION_FREEZE_90, landscape = true)
            startInset()
        }
    }

    /**
     * cm-timeline-start-gap: between the row icons and 0:00 there is only the playhead's dot (6 dp), not the
     * 28 dp of empty track there was; the first item starts there and the playhead's dot is whole beside the icons.
     */
    private fun startInset() {
        rowsShown()
        val main =
            project.tracks
                .first { it.type == TrackType.MAIN }
                .id.value
        val intro = hasAnyAncestor(hasTestTag(rowTag(main))) and hasContentDescription("Intro,", substring = true)
        compose.waitUntil(
            TIMEOUT_MS,
        ) { notYetIfBusy { compose.onAllNodes(intro, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
        // The cursor at 0:00, through the ruler's accessibility action.
        read { node(TIMELINE_RULER_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) } }
        // Retried while Compose settles (the seek, the rows' layout).
        read {
            val header = bounds(rowHeaderTag(main))
            val first = compose.onAllNodes(intro, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()
            val playhead = bounds(TIMELINE_PLAYHEAD_TAG)
            Log.i(
                ExportHarness.TAG,
                "start inset: header right ${header.right} first item left ${first.left} playhead ${playhead.left}-${playhead.right}",
            )
            assertWithMessage("gap from the row icons to the first item")
                .that((first.left - header.right).value)
                .isWithin(1f)
                .of(TimelineGeometry.START_ROOM_DP.toFloat())
            // The playhead at 0:00: its line on the first item's edge, its 12 dp dot whole to the right of the icons.
            val line = (playhead.left + playhead.right) / 2
            assertWithMessage("playhead at 0:00").that(line.value).isWithin(1f).of(first.left.value)
            assertWithMessage("playhead dot clear of the icons").that((line - 6.dp - header.right).value).isAtLeast(-0.5f)
        }
    }

    /**
     * Each row in view has its "+" (or, empty, its Add here "+") at the same x, flush with the timeline's right end,
     * the preset's rows included.
     */
    private fun oneColumn() {
        rowsShown()
        // Laid out: at least two whole rows in the rows' view (the editor opens, then turns).
        compose.waitUntil(TIMEOUT_MS) {
            notYetIfBusy {
                val v = bounds(TIMELINE_ROWS_TAG)
                v.bottom - v.top > 40.dp &&
                    project.tracks.count { t ->
                        val r = bounds(rowTag(t.id.value))
                        r.top >= v.top && r.bottom <= v.bottom && r.bottom > r.top
                    } >= 2
            }
        }
        val timeline = read { bounds(TIMELINE_TAG) }
        val view = read { bounds(TIMELINE_ROWS_TAG) }
        val boxes =
            read {
                project.tracks.mapNotNull { t ->
                    val key = t.id.value
                    val r = bounds(rowTag(key))
                    if (r.top < view.top || r.bottom > view.bottom) return@mapNotNull null
                    val tag = listOf(rowEndPlusTag(key), rowAddTag(key)).firstOrNull { exists(it) }
                    // Every row has one, the preset's frame and music included.
                    assertWithMessage("the plus of row $key").that(tag).isNotNull()
                    key to bounds(tag!!)
                }
            }
        Log.i(
            ExportHarness.TAG,
            "end plus: ${boxes.map { (k, b) ->
                "$k ${b.left}-${b.right}"
            }} timeline ${timeline.left}-${timeline.right}",
        )
        assertWithMessage("rows with a plus in view").that(boxes.size).isAtLeast(2)
        for ((key, b) in boxes) {
            assertWithMessage("left of $key").that(b.left.value).isWithin(1f).of(
                boxes
                    .first()
                    .second.left.value,
            )
            assertWithMessage("right of $key").that(b.right.value).isWithin(1f).of(timeline.right.value)
            assertWithMessage("width of $key").that((b.right - b.left).value).isWithin(1f).of(48f)
        }
    }

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

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun bounds(tag: String): DpRect = node(tag).getUnclippedBoundsInRoot()

    private fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** [block], tried again while Compose is busy (the preview's players post frames on a software-GPU emulator). */
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
                if (System.currentTimeMillis() > end) throw e
            }
            Thread.sleep(200)
        }
    }

    private fun notYetIfBusy(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (e: ComposeNotIdleException) {
            false
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("No compose hierarchies found") != true) throw e
            false
        } catch (e: AssertionError) {
            false
        }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val LAUNCH_TIMEOUT_MS = 60_000L
    }
}
