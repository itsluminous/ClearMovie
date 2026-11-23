package com.itsluminous.clearmovie.ui

import android.app.UiAutomation
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
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
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.ui.editor.EDITOR_POPUP_TAG
import com.itsluminous.clearmovie.ui.editor.PLAY_TAG
import com.itsluminous.clearmovie.ui.editor.TIME_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.clipTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tap on a timeline item in the real app (cm-tap-seek): while playing, a
 * tap on an earlier music item moves the preview back there and it plays on; while paused,
 * a tap on a caption or a picture in picture moves the preview into it, and a
 * tap on music moves nothing.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TapSeekDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var project: Project
    private var totalUs = 0L
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val names = TrackId("tap-names")
    private val music = TrackId("tap-music")
    private val voice = TrackId("tap-voice")
    private val pictures = TrackId("tap-pip")
    private val caption = ClipId("tap-caption")
    private val song = ClipId("tap-song")
    private val speech = ClipId("tap-speech")
    private val picture = ClipId("tap-picture")

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
                val p =
                    ProjectFactory
                        .fromPreset(ProjectId(RandomIds.next()), preset, h.app.presetStore.presetMedia(h.presetId))
                        .addToMain(items)
                        .addRow(TrackType.TEXT, id = names)
                        .addToTrack(names, TextClip(caption, 0, 3000, "Grandma and Grandpa"), 4000)
                        .addRow(TrackType.AUDIO, AudioRole.MUSIC, music)
                        .addToTrack(music, AudioClip(song, sound, 0, 3000, 3000), 2000)
                        .addRow(TrackType.AUDIO, AudioRole.VOICE, voice)
                        .addToTrack(voice, AudioClip(speech, sound, 0, 3000, 3000), 6000)
                        .addRow(TrackType.VISUAL, id = pictures)
                        .addToTrack(pictures, PhotoClip(picture, pip, 3000, pip = PipPlacement.DEFAULT), 5000)
                assertThat(h.app.projectRepository.createDraft(p, inEditor = true)).isTrue()
                p
            }
        totalUs = h.plannedUs(project)
    }

    @After
    fun tearDown() {
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        h.cleanUp()
    }

    @Test
    fun pausedATapOnACaptionOrAPictureMovesThePreviewIntoItAndOnMusicNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            this.scenario = scenario
            upright(scenario)
            rowsShown()
            assertThat(playing()).isFalse()
            // The caption runs 4-7 s: the cursor lands at the time tapped, its middle (5.5 s), give or take.
            tap(names, caption)
            waitForTime(Range.closed(5_000_000L, 6_000_000L))
            assertThat(playing()).isFalse()
            // The picture in picture runs 5-8 s: its middle, 6.5 s.
            tap(pictures, picture)
            waitForTime(Range.closed(6_000_000L, 7_000_000L))
            val before = positionUs()
            // Music (2-5 s) is not seen: the cursor stays where it was.
            tap(music, song)
            runFor(1500)
            assertThat(positionUs()).isWithin(TIME_STEP_US).of(before)
            assertThat(playing()).isFalse()
        }
    }

    @Test
    fun whilePlayingATapOnAnEarlierMusicItemMovesThePreviewBackThereAndItPlaysOn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            this.scenario = scenario
            upright(scenario)
            rowsShown()
            inView(music)
            play()
            // Past the music (2-5 s), so only the tap can bring the preview back into it.
            poll { positionUs() >= 6_000_000 }
            tap(music, song)
            waitForTime(Range.closed(2_000_000L, 5_600_000L))
            val landed = positionUs()
            Log.i(ExportHarness.TAG, "tap-seek: landed at $landed us while playing")
            assertThat(playing()).isTrue()
            // It plays on from there.
            poll { positionUs() > landed + 500_000 }
            assertThat(playing()).isTrue()
            press(PLAY_TAG)
        }
    }

    @Test
    fun afterPlayingToTheEndATapOnAPhotoMovesThePreviewThereAndItStaysPaused() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            this.scenario = scenario
            upright(scenario)
            rowsShown()
            play()
            // Played to the end: it stops there by itself.
            poll(totalUs / 1000 + TIMEOUT_MS) { !playing() && positionUs() >= totalUs - END_SLACK_US }
            // This project has no transitions, so the middle of a photo is the photo, never a transition marker.
            assertThat(project.mainClips.mapNotNull { it.transitionToNext }).isEmpty()
            val photo = project.mainClips.first { it is PhotoClip }
            val slot = requireNotNull(project.mainSlot(photo.id))
            press(clipTag(photo.id))
            waitForTime(Range.closed(slot.startMs * 1000, slot.endMs * 1000 - 1))
            val landed = positionUs()
            // It stays there, paused: the tap does not start the video again.
            runFor(1500)
            assertThat(playing()).isFalse()
            assertThat(positionUs()).isWithin(TIME_STEP_US).of(landed)
        }
    }

    /** Presses Play once the editor has its video (Play is enabled then), and waits until it plays. */
    private fun play() {
        poll { live(PLAY_TAG)?.config?.contains(SemanticsProperties.Disabled) == false && live(TIME_TAG) != null }
        runFor(500)
        press(PLAY_TAG)
        poll { playing() }
    }

    /** A real tap on the middle of item [id] of row [row] (brought into the rows' view first). */
    private fun tap(
        row: TrackId,
        id: ClipId,
    ) {
        inView(row)
        press(clipTag(id))
    }

    /**
     * A real tap on the middle of [tag], from the shell: a Compose test click waits for Compose to be idle, which it
     * never is while the preview plays, and a click tried again after a timeout would be a second tap.
     */
    private fun press(tag: String) {
        val node = live(tag) ?: error("no $tag")
        val box = node.boundsInWindow
        val origin = node.positionOnScreen - node.positionInWindow
        // On a small screen, Show all rows floats the preview in a small window over the rows' corner: a tap is
        // made on the part of the item it leaves uncovered.
        val popup = live(EDITOR_POPUP_TAG)?.boundsInWindow
        val right = if (popup != null && popup.top < box.bottom && popup.bottom > box.top) minOf(box.right, popup.left) else box.right
        check(right > box.left) { "$tag is under the small preview" }
        val x = origin.x + (box.left + right) / 2
        val y = origin.y + box.center.y
        automation.executeShellCommand("input tap ${x.toInt()} ${y.toInt()}").use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
        }
    }

    /**
     * Brings [row] into the rows' view: Show all rows when a small screen folds it away, and a scroll when it is
     * out of the rows' view. Read from the app's live semantics, which needs no idle Compose (while playing it is
     * never idle); only a scroll, done before playing where it is needed, goes through Compose.
     */
    private fun inView(row: TrackId) {
        val shown = runCatching { poll(5_000) { live(rowTag(row.value)) != null } }.isSuccess
        if (!shown) {
            press(TIMELINE_ROWS_TOGGLE_TAG)
            poll { live(rowTag(row.value)) != null }
        }

        fun within(): Boolean {
            val view = live(TIMELINE_ROWS_TAG)?.boundsInWindow ?: return false
            val r = live(rowTag(row.value))?.unclipped() ?: return false
            return r.top >= view.top - 1 && r.bottom <= view.bottom + 1
        }
        if (!within()) {
            // A scroll waits for Compose to be idle, which it never is while the preview plays: say so at once.
            check(!playing()) { "row $row is out of the rows' view while playing; bring it in before Play" }
            // Asked again until it holds: the rows bring MAIN back into view by themselves when their room changes
            // (Show all rows on a small screen), which can undo a scroll made just before.
            poll {
                if (!within()) {
                    val view = live(TIMELINE_ROWS_TAG)!!.boundsInWindow
                    val r = live(rowTag(row.value))!!.unclipped()
                    read { node(TIMELINE_ROWS_TAG).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, r.top - view.top) } }
                    runFor(600)
                }
                within()
            }
        }
    }

    /** Where the node is in the window, as laid out: a row scrolled out of the rows' view has no clipped bounds. */
    private fun SemanticsNode.unclipped(): Rect = Rect(positionInWindow, Size(size.width.toFloat(), size.height.toFloat()))

    /**
     * Where the preview is, from the play bar's time (its progress is exact; its words are to the second). Read
     * from the app's own semantics on its main thread, which needs no idle Compose: while playing it is never idle.
     */
    private fun positionUs(): Long {
        val node = live(TIME_TAG) ?: error("no play bar time")
        return (node.config[SemanticsProperties.ProgressBarRangeInfo].current.toDouble() * totalUs).toLong()
    }

    private fun playing(): Boolean {
        val pause = context.getString(R.string.action_pause)
        return live(PLAY_TAG, merged = true)?.let { n ->
            n.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .any { it.text == pause } ||
                n.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                    .any { it == pause }
        } == true
    }

    /** The node tagged [tag] as the app has it now (on its main thread, between frames), or null. */
    private fun live(
        tag: String,
        merged: Boolean = false,
    ): SemanticsNode? {
        var found: SemanticsNode? = null
        scenario.onActivity { activity ->
            val roots = ArrayDeque<View>(listOf(activity.window.decorView))
            while (roots.isNotEmpty() && found == null) {
                val v = roots.removeFirst()
                if (v is ViewRootForTest) {
                    val owner = v.semanticsOwner
                    val root = if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode
                    val nodes = ArrayDeque(listOf(root))
                    while (nodes.isNotEmpty()) {
                        val n = nodes.removeFirst()
                        if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) {
                            found = n
                            break
                        }
                        nodes.addAll(n.children)
                    }
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) roots.addLast(v.getChildAt(i))
            }
        }
        return found
    }

    private fun waitForTime(range: Range<Long>) {
        if (runCatching { poll { positionUs() in range } }.isFailure) {
            assertWithMessage("the preview's time").that(positionUs()).isIn(range)
        }
    }

    /**
     * Waits for [check], asked every 50 ms from the test thread: compose.waitUntil waits for Compose to be idle
     * between asks, which it is not while the preview plays. The test's clock drives the app's frames, so each ask
     * moves it on by those 50 ms too.
     */
    private fun poll(
        timeoutMs: Long = TIMEOUT_MS,
        check: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!runCatching(check).getOrDefault(false)) {
            check(System.currentTimeMillis() < end) { "still not so after $timeoutMs ms" }
            Thread.sleep(FRAME_MS)
            compose.mainClock.advanceTimeBy(FRAME_MS)
        }
    }

    /** Lets the app run for [ms] (its frames too). */
    private fun runFor(ms: Long) {
        repeat((ms / FRAME_MS).toInt()) {
            Thread.sleep(FRAME_MS)
            compose.mainClock.advanceTimeBy(FRAME_MS)
        }
    }

    private fun upright(scenario: ActivityScenario<MainActivity>) {
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        compose.waitUntil(LAUNCH_TIMEOUT_MS) {
            var turned = false
            runCatching {
                scenario.onActivity {
                    turned = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
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
            } catch (e: IllegalStateException) {
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

        /** The play bar's progress is reported ten times a second. */
        const val TIME_STEP_US = 150_000L
        const val FRAME_MS = 50L

        /** How near the end counts as there (the last progress report). */
        const val END_SLACK_US = 300_000L
    }
}
