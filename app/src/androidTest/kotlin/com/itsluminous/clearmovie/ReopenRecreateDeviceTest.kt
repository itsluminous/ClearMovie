package com.itsluminous.clearmovie

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.data.media.ShareHandoff
import com.itsluminous.clearmovie.data.media.ShareLinks
import com.itsluminous.clearmovie.data.media.SharedLinks
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.ui.editor.EDITOR_EXPORT_TAG
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_PROGRESS_TAG
import com.itsluminous.clearmovie.ui.home.HOME_LIST_TAG
import com.itsluminous.clearmovie.work.ExportWorker
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The editor the app reopens at launch (storage D2: the app stopped with a
 * video open) must survive its activity being destroyed and created again:
 * a recreate() and a real rotation of the display, also while that video's
 * export runs and when a share arrives at the same launch, and its preview
 * must show the same picture afterwards.
 * Before the fix the first two crashed under this test harness with "State
 * must be at least 'CREATED' to be moved to 'DESTROYED'" for the editor's
 * back stack entry (the launch navigate ran off the main thread).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ReopenRecreateDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private lateinit var project: Project

    private fun s(id: Int) = h.context.getString(id)

    @Before
    fun setUp() {
        h.setUp()
        h.bindPreset()
        project = h.project(h.typicalItems(), "Reopen", inEditor = true)
    }

    @After
    fun tearDown() {
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        h.cleanUp()
    }

    /**
     * Waits for [matcher]. A moment when Compose does not go idle in time (the
     * preview's players posting frames on a loaded software-GPU emulator)
     * counts as "not yet", as in ShareDeviceTest, instead of failing the test.
     */
    private fun waitFor(
        matcher: SemanticsMatcher,
        timeoutMs: Long = TIMEOUT_MS,
    ) = compose.waitUntil(timeoutMs) { notYetIfBusy { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }

    /** Only Compose not going idle in time counts as "not yet"; anything else fails the test with its own cause. */
    private fun notYetIfBusy(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (e: ComposeNotIdleException) {
            Log.i(ExportHarness.TAG, "Compose busy, trying again: ${e.message?.lineSequence()?.firstOrNull()}")
            false
        }

    /** A picture of the preview, trying again while Compose is busy. */
    private fun capture(): Bitmap {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            try {
                return compose.onNodeWithTag(PREVIEW_TAG).captureToImage().asAndroidBitmap()
            } catch (e: ComposeNotIdleException) {
                if (System.currentTimeMillis() > end) throw e
            } catch (e: AssertionError) {
                // "Failed waiting for PixelCopy!": the window copy timed out on a busy GPU; nothing else.
                if (e.message?.contains("PixelCopy") != true || System.currentTimeMillis() > end) throw e
            }
        }
    }

    /**
     * The editor with its preview loaded: the players know the length (the
     * label no longer reads "0:00 of 0:00") and Play takes a tap. Nothing here
     * parses the label, so it holds in every app language.
     */
    private fun editorShown() {
        // The longer wait: a launch or a recreate while a software export runs can take a while on CI.
        waitFor(hasTestTag(EDITOR_EXPORT_TAG), LAUNCH_TIMEOUT_MS)
        waitFor(hasTestTag(PREVIEW_TAG))
        val unloaded = h.context.getString(R.string.editor_time, "0:00", "0:00")
        compose.waitUntil(LAUNCH_TIMEOUT_MS) { notYetIfBusy { timeLabel() != unloaded } }
        waitFor(hasText(s(R.string.action_play)).and(isEnabled()).or(hasText(s(R.string.action_pause)).and(isEnabled())), LAUNCH_TIMEOUT_MS)
    }

    /** Where the playhead is, 0 to 1 of the length (the label's progress semantics). */
    private fun progress(): Float =
        compose
            .onAllNodes(hasContentDescription(s(R.string.editor_seek)))
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrElseNullable(SemanticsProperties.ProgressBarRangeInfo) { null }
            ?.current ?: 0f

    /** The playhead label, "0:05 of 0:14" (the text of the node TalkBack calls Playback position). */
    private fun timeLabel(): String =
        compose
            .onAllNodes(hasContentDescription(s(R.string.editor_seek)))
            .fetchSemanticsNodes()
            .first()
            .config
            .getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString("") { it.text }

    /**
     * Play moves the playhead on. It is not paused again: Play and Pause swap
     * at every junction, so a Pause button found a moment ago may be gone.
     */
    private fun previewPlays() {
        val atStart = progress()
        compose.onNodeWithText(s(R.string.action_play)).performClick()
        // While it plays Compose may not go idle; the whole 14 s video plays out at most.
        compose.waitUntil(PLAY_TIMEOUT_MS) { notYetIfBusy { progress() > atStart + 0.02f } }
    }

    /** Turns the display and waits until the activity has been created again for it. */
    private fun rotate(
        scenario: ActivityScenario<MainActivity>,
        rotation: Int,
    ) {
        var before: MainActivity? = null
        scenario.onActivity { before = it }
        automation.setRotation(rotation)
        compose.waitUntil(TIMEOUT_MS) {
            var now: MainActivity? = null
            runCatching { scenario.onActivity { now = it } }
            now != null && now !== before
        }
    }

    @Test
    fun aReopenedEditorSurvivesRecreate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            editorShown()
            scenario.recreate()
            editorShown()
            scenario.recreate()
            editorShown()
            previewPlays()

            // Back from the reopened editor goes Home.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(hasTestTag(HOME_LIST_TAG))
        }
    }

    @Test
    fun aReopenedEditorSurvivesARotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            editorShown()
            rotate(scenario, UiAutomation.ROTATION_FREEZE_90)
            editorShown()
            rotate(scenario, UiAutomation.ROTATION_FREEZE_0)
            editorShown()
            previewPlays()
        }
    }

    @Test
    fun aReopenedEditorSurvivesRecreateAndRotationWhileItsExportRuns() {
        val workManager = WorkManager.getInstance(h.context)
        val id = ExportWorker.enqueue(workManager, project.id, ExportQuality.BEST, "Reopen", replace = true)
        compose.waitUntil(TIMEOUT_MS) { workManager.getWorkInfoById(id).get()?.state == WorkInfo.State.RUNNING }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            editorShown()
            scenario.recreate()
            editorShown()
            rotate(scenario, UiAutomation.ROTATION_FREEZE_90)
            editorShown()
            rotate(scenario, UiAutomation.ROTATION_FREEZE_0)
            editorShown()

            // The export carried on, and the editor still shows it.
            assertThat(workManager.getWorkInfoById(id).get()?.state).isAnyOf(WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED)
            compose.onNodeWithTag(EDITOR_EXPORT_TAG).performClick()
            waitFor(hasTestTag(EXPORT_PROGRESS_TAG).or(hasText(s(R.string.export_done_title))))
        }
        // Stop it and wait until it has ended, so a video it finished meanwhile is removed too.
        workManager.cancelWorkById(id).result.get()
        compose.waitUntil(TIMEOUT_MS) {
            workManager
                .getWorkInfoById(id)
                .get()
                ?.state
                ?.isFinished != false
        }
        workManager
            .getWorkInfoById(id)
            .get()
            ?.outputData
            ?.getString(ExportWorker.KEY_URI)
            ?.let { h.exported(android.net.Uri.parse(it)) }
    }

    @Test
    fun aShareArrivingAtLaunchOnAReopenedEditorSurvivesRecreateAndRotationAndAdds() {
        // A share that starts the app while a video is open (as ShareActivity hands it on).
        val item = h.insert("fixtures/photo_blue.png", "image/png")
        val token = ShareHandoff.put(SharedLinks(arrayListOf(item)))
        val intent =
            Intent(h.context, MainActivity::class.java).setAction(ShareLinks.ACTION_SHARED).putExtra(ShareHandoff.EXTRA_TOKEN, token)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            editorShown()
            waitFor(hasText(s(R.string.share_add_title)))
            scenario.recreate()
            editorShown()
            waitFor(hasText(s(R.string.share_add_title)))
            rotate(scenario, UiAutomation.ROTATION_FREEZE_90)
            editorShown()
            rotate(scenario, UiAutomation.ROTATION_FREEZE_0)
            editorShown()
            waitFor(hasText(s(R.string.share_add_title)))
            compose.onNodeWithText(s(R.string.share_add_here)).performClick()

            // The shared photo lands on the reopened video, once.
            val items = {
                h.app.projectRepository.open.value?.takeIf { it.id == project.id }?.mainClips?.count {
                    it is PhotoClip ||
                        it is VideoClip
                }
            }
            compose.waitUntil(TIMEOUT_MS) { items() == 5 }
            scenario.recreate()
            editorShown()
            assertThat(items()).isEqualTo(5)
            assertThat(compose.onAllNodes(hasText(s(R.string.share_add_title))).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun aReopenedEditorShowsTheSamePreviewFrameAfterRecreate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            editorShown()
            // Each probe: seek paused, capture, recreate, capture again. All are
            // measured and logged before any is asserted, so a failing run says
            // which picture changed and how.
            val results = KNOWN_TIMES_US.map { probe(scenario, it) }
            results.forEach { (at, before, diff) ->
                // A picture, not a blank surface, and the same one after the recreate.
                assertWithMessage("picture at $at").that(spread(before)).isGreaterThan(MIN_SPREAD)
                assertWithMessage("change at $at after recreate").that(diff).isLessThan(MAX_DIFF)
            }
        }
    }

    /** Seeks to [us] paused, then compares the preview before and after a recreate. */
    private fun probe(
        scenario: ActivityScenario<MainActivity>,
        us: Long,
    ): Triple<String, Bitmap, Double> {
        val fraction = us.toFloat() / h.plannedUs(project)
        val earlier = settledFrame()
        compose.onNodeWithContentDescription(s(R.string.editor_seek)).performSemanticsAction(SemanticsActions.SetProgress) { it(fraction) }
        compose.waitUntil(TIMEOUT_MS) { notYetIfBusy { abs(progress() - fraction) < 0.01f } }
        val at = timeLabel()
        val position = progress()
        // The label moves at once, the picture only once the players have landed
        // the seek: on a slow CI emulator the old picture stays steady for over a
        // second. So the picture before the recreate is the first steady one that
        // is no longer the picture from before the seek.
        val before = settledFrame(unlike = earlier, windowShowsVideo = true)

        scenario.recreate()
        editorShown()
        // The restored playhead, and then its picture, which the players land after it.
        compose.waitUntil(TIMEOUT_MS) { notYetIfBusy { timeLabel() == at } }
        // The same playhead to the frame, not only to the second the label shows.
        assertWithMessage("playhead at $us after recreate").that(abs(progress() - position)).isLessThan(POSITION_SLACK)
        val after = settledFrame(like = before, windowShowsVideo = true)

        save(before, "reopen-preview-$us-before-recreate.png")
        save(after, "reopen-preview-$us-after-recreate.png")
        val diff = meanAbsDiff(before, after)
        Log.i(
            ExportHarness.TAG,
            "Preview at $us us ($at): before spread ${spread(before)}, after spread ${spread(after)}, mean abs diff $diff",
        )
        // A coarse picture of each capture in the log, for a run whose PNGs stay on its device (CI).
        Log.i(ExportHarness.TAG, "Preview at $us us before (3x3 mean RGB): ${grid(before)}")
        Log.i(ExportHarness.TAG, "Preview at $us us after (3x3 mean RGB): ${grid(after)}")
        // The frame's window shows the video itself (not black, not the photo before it), before and after.
        assertWithMessage("window at $us before recreate: ${grid(before)}").that(windowShowsVideo(before)).isTrue()
        assertWithMessage("window at $us after recreate: ${grid(after)}").that(windowShowsVideo(after)).isTrue()
        return Triple(at, before, diff)
    }

    /**
     * The preview once its picture has stopped changing: three captures in a
     * row, 700 ms apart, agree (a slow software GPU lands a paused seek late).
     * With [like], it also waits (up to the timeout) for a settled picture
     * that matches [like]: right after a recreate the new surfaces can hold an
     * earlier frame steady for a while before the restored seek lands. A
     * preview that never shows [like] again still fails the test. With
     * [unlike], it waits for a steady picture that differs from [unlike] (a
     * seek that has landed). With [windowShowsVideo], it also waits for the
     * frame's window to show the video (every layer has landed).
     */
    private fun settledFrame(
        like: Bitmap? = null,
        unlike: Bitmap? = null,
        windowShowsVideo: Boolean = false,
    ): Bitmap {
        var last = capture()
        var same = 0
        var settled = 0
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            Thread.sleep(700)
            val now = capture()
            same = if (spread(now) > MIN_SPREAD && meanAbsDiff(last, now) < 1.0) same + 1 else 0
            last = now
            if (same >= 2) {
                settled++
                val moved = unlike == null || meanAbsDiff(unlike, now) >= 1.0
                val window = !windowShowsVideo || windowShowsVideo(now)
                if (moved && window && (like == null || meanAbsDiff(like, now) < MAX_DIFF)) {
                    Log.i(ExportHarness.TAG, "Preview settled after $settled steady picture(s)")
                    return now
                }
                Log.i(ExportHarness.TAG, "Preview steady but not yet the frame wanted: ${grid(now)}")
            }
        }
        return last
    }

    private fun save(
        bitmap: Bitmap,
        name: String,
    ) {
        val dir = File(checkNotNull(h.context.getExternalFilesDir(null)), "render-tests").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L

        const val LAUNCH_TIMEOUT_MS = 60_000L

        /** 0.5 % of the 14 s video, about 70 ms: two frames at 30 fps. */
        const val POSITION_SLACK = 0.005f

        val CLIP_GREY = intArrayOf(54, 56, 56)

        /** Per channel, 0-255. */
        const val WINDOW_SLACK = 12

        /** Longer than the whole test video, so its end, when the preview stops, is inside the wait. */
        const val PLAY_TIMEOUT_MS = 40_000L

        /**
         * The first video on MAIN plays from about 0:04 to 0:07. 4.2 s is just
         * after its cut-in from the photo (where the old CI failure was seen, the
         * spot a tap on its tile lands near); 6.0 s is inside it, away from the
         * crossfades and from the frame fixture's 4 s loop point (near 5.4 s
         * here), where one frame of difference moves its white square across.
         */
        val KNOWN_TIMES_US = listOf(4_200_000L, 6_000_000L)

        /** Brightest minus darkest luma: a blank or single-colour surface is near 0. */
        const val MIN_SPREAD = 40

        /** Mean absolute difference per channel (0-255) allowed between the two captures. */
        const val MAX_DIFF = 6.0

        fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

        fun luma(p: Int): Int = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000

        fun grid(b: Bitmap): String {
            val p = pixels(b)
            return (0 until 9).joinToString(" ") { cell ->
                val x0 = (cell % 3) * b.width / 3
                val y0 = (cell / 3) * b.height / 3
                var r = 0L
                var g = 0L
                var bl = 0L
                var n = 0
                for (y in y0 until y0 + b.height / 3) {
                    for (x in x0 until x0 + b.width / 3) {
                        val c = p[y * b.width + x]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        bl += c and 0xFF
                        n++
                    }
                }
                "${r / n},${g / n},${bl / n}"
            }
        }

        /**
         * The centre ninth of the preview is inside the frame's keyed window,
         * which here shows clip_dark: a flat grey near (54, 56, 56) (ffmpeg,
         * frame 0; the preview shows (56-57, 58-59, 58-59)). A black surface,
         * the green key or the photo before it are far from that.
         */
        fun windowShowsVideo(b: Bitmap): Boolean {
            val (r, g, bl) = cellMean(b, 4)
            return abs(r - CLIP_GREY[0]) <= WINDOW_SLACK && abs(g - CLIP_GREY[1]) <= WINDOW_SLACK && abs(bl - CLIP_GREY[2]) <= WINDOW_SLACK
        }

        fun cellMean(
            b: Bitmap,
            cell: Int,
        ): Triple<Int, Int, Int> {
            val p = pixels(b)
            val x0 = (cell % 3) * b.width / 3
            val y0 = (cell / 3) * b.height / 3
            var r = 0L
            var g = 0L
            var bl = 0L
            var n = 0
            for (y in y0 until y0 + b.height / 3) {
                for (x in x0 until x0 + b.width / 3) {
                    val c = p[y * b.width + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    bl += c and 0xFF
                    n++
                }
            }
            return Triple((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }

        fun spread(b: Bitmap): Int = pixels(b).map(::luma).let { it.max() - it.min() }

        fun meanAbsDiff(
            a: Bitmap,
            b: Bitmap,
        ): Double {
            if (a.width != b.width || a.height != b.height) return Double.MAX_VALUE
            val pa = pixels(a)
            val pb = pixels(b)
            var sum = 0L
            for (i in pa.indices) {
                for (shift in intArrayOf(16, 8, 0)) sum += abs(((pa[i] shr shift) and 0xFF) - ((pb[i] shr shift) and 0xFF))
            }
            return sum.toDouble() / (pa.size * 3)
        }
    }
}
