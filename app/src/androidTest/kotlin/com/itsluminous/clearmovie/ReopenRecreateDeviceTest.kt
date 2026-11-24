package com.itsluminous.clearmovie

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
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
import androidx.compose.ui.test.performTextReplacement
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.AppNotIdleException
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.IdlingResourceTimeoutException
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
import com.itsluminous.clearmovie.ui.editor.EDITOR_MENU_SETTINGS_TAG
import com.itsluminous.clearmovie.ui.editor.EDITOR_MORE_TAG
import com.itsluminous.clearmovie.ui.editor.EDITOR_TITLE_TAG
import com.itsluminous.clearmovie.ui.editor.PLAY_TAG
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.RENAME_CONFIRM_TAG
import com.itsluminous.clearmovie.ui.editor.RENAME_FIELD_TAG
import com.itsluminous.clearmovie.ui.editor.UNDO_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_PROGRESS_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_SAVE_TAG
import com.itsluminous.clearmovie.ui.home.HOME_LIST_TAG
import com.itsluminous.clearmovie.work.ExportFailure
import com.itsluminous.clearmovie.work.ExportWorker
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The editor the app reopens at launch (storage D2: the app stopped with a
 * video open) must survive its activity being destroyed and created again:
 * a recreate() and a real rotation of the display, also while that video's
 * export runs and when a share arrives at the same launch, and its preview
 * must show the same picture after a recreate (a rotation keeps the
 * playing state, a recreate pauses it first, so the frame check uses
 * recreate, the stricter of the two).
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

    /** True while previewPlays has Espresso's idle timeouts at [BUSY_READ_S]. */
    private var shortIdleTimeouts = false

    /** The last busy read notYetIfBusy counted as "not yet" in previewPlays. */
    private var lastBusy: Throwable? = null

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

    /**
     * Only two passing states count as "not yet": the app not going idle in
     * time, and no Compose content yet (the new activity still behind its
     * splash screen, which waits for the settings). Anything else fails the
     * test with its own cause.
     *
     * The app not going idle shows up as Compose's ComposeNotIdleException.
     * While previewPlays has Espresso's idle timeouts at [BUSY_READ_S]
     * ([shortIdleTimeouts]), Espresso's own timeout can run out first; only
     * then does that count as busy too (see [isEspressoIdleTimeout]), and the
     * last busy read is kept in [lastBusy] for the failure if the wait runs
     * out.
     * With Espresso's default timeouts it still fails the read, naming the
     * main-looper message that kept the app busy.
     */
    private fun notYetIfBusy(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (e: ComposeNotIdleException) {
            if (shortIdleTimeouts) lastBusy = e
            Log.i(ExportHarness.TAG, "Compose busy, trying again: ${e.message?.lineSequence()?.firstOrNull()}")
            false
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("No compose hierarchies found") != true) throw e
            Log.i(ExportHarness.TAG, "No Compose content yet, trying again")
            false
        } catch (e: RuntimeException) {
            if (!shortIdleTimeouts || !isEspressoIdleTimeout(e)) throw e
            lastBusy = e
            Log.i(ExportHarness.TAG, "Main thread busy, trying again: ${e.message?.lineSequence()?.firstOrNull()}")
            false
        }

    /**
     * Espresso's idle timeout, wrapped or not: AppNotIdleException (the main
     * looper never idled, as while the preview's players and the playhead post
     * to it every frame), IdlingResourceTimeoutException (the Compose-Espresso
     * link stayed busy), or the NullPointerException Espresso 3.7.0 throws from
     * StringJoinerKt.joinToString while it builds the message for that same
     * timeout (UiControllerImpl.loopUntil).
     */
    private fun isEspressoIdleTimeout(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(MAX_CAUSES).any {
            it is AppNotIdleException ||
                it is IdlingResourceTimeoutException ||
                (it is NullPointerException && it.isEspressoTimeoutMessageNpe())
        }

    private fun NullPointerException.isEspressoTimeoutMessageNpe(): Boolean =
        stackTrace.firstOrNull()?.className == ESPRESSO_JOINER &&
            stackTrace.any { it.className == ESPRESSO_UI_CONTROLLER && it.methodName == "loopUntil" }

    /** A picture of the preview, trying again while Compose is busy. */
    private fun capture(): Bitmap {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            try {
                return compose.onNodeWithTag(PREVIEW_TAG).captureToImage().asAndroidBitmap()
            } catch (e: ComposeNotIdleException) {
                if (System.currentTimeMillis() > end) throw e
                Thread.sleep(200)
            } catch (e: ComposeTimeoutException) {
                // captureToImage forces a redraw and waits 2 s for the frame to commit; a busy
                // emulator can miss that. The capture is tried again, not the picture accepted.
                if (System.currentTimeMillis() > end) throw e
                Thread.sleep(200)
            } catch (e: AssertionError) {
                // "Failed waiting for PixelCopy!": the window copy timed out on a busy GPU; nothing else.
                if (e.message?.contains("PixelCopy") != true || System.currentTimeMillis() > end) throw e
                Thread.sleep(200)
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
        // By its tag: Play may show only its icon on a narrow window or at large text.
        waitFor(hasTestTag(PLAY_TAG).and(isEnabled()), LAUNCH_TIMEOUT_MS)
    }

    /** The editor is back on screen (not that its preview has loaded). */
    private fun editorUp() {
        waitFor(hasTestTag(EDITOR_EXPORT_TAG), LAUNCH_TIMEOUT_MS)
        waitFor(hasTestTag(PREVIEW_TAG), LAUNCH_TIMEOUT_MS)
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
        compose.onNodeWithTag(PLAY_TAG).performClick()
        // While it plays Compose redraws the playhead every frame and may not go idle, and a
        // Compose read then waits out Espresso's idle timeout (seen: about 35 s per read), so
        // two reads could outlast the wait. With a 2 s timeout a busy read fails fast and is
        // tried again; at the latest when the 14 s video ends and the preview stops.
        val master = IdlingPolicies.getMasterIdlingPolicy()
        val resource = IdlingPolicies.getDynamicIdlingResourceErrorPolicy()
        try {
            IdlingPolicies.setMasterPolicyTimeout(BUSY_READ_S, TimeUnit.SECONDS)
            IdlingPolicies.setIdlingResourceTimeout(BUSY_READ_S, TimeUnit.SECONDS)
            shortIdleTimeouts = true
            lastBusy = null
            compose.waitUntil(PLAY_TIMEOUT_MS) { notYetIfBusy { progress() > atStart + 0.02f } }
        } catch (e: ComposeTimeoutException) {
            lastBusy?.let(e::addSuppressed)
            throw e
        } finally {
            shortIdleTimeouts = false
            IdlingPolicies.setMasterPolicyTimeout(master.idleTimeout, master.idleTimeoutUnit)
            IdlingPolicies.setIdlingResourceTimeout(resource.idleTimeout, resource.idleTimeoutUnit)
        }
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
            // Rename from the title, then More options > Settings (while the preview is still, so Compose
            // goes idle for the clicks): Back brings the same editor back, its name and undo history kept.
            compose.onNodeWithTag(EDITOR_TITLE_TAG).performClick()
            waitFor(hasTestTag(RENAME_FIELD_TAG))
            compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement(RENAMED)
            compose.onNodeWithTag(RENAME_CONFIRM_TAG).performClick()
            waitFor(hasTestTag(EDITOR_TITLE_TAG).and(hasText(RENAMED)))
            compose.onNodeWithTag(EDITOR_MORE_TAG).performClick()
            waitFor(hasTestTag(EDITOR_MENU_SETTINGS_TAG))
            compose.onNodeWithTag(EDITOR_MENU_SETTINGS_TAG).performClick()
            waitFor(hasText(s(R.string.settings_title)))
            compose.waitUntil(
                TIMEOUT_MS,
            ) { notYetIfBusy { compose.onAllNodes(hasTestTag(EDITOR_EXPORT_TAG)).fetchSemanticsNodes().isEmpty() } }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            editorShown()
            waitFor(hasTestTag(EDITOR_TITLE_TAG).and(hasText(RENAMED)))
            waitFor(hasTestTag(UNDO_TAG).and(isEnabled()))
            previewPlays()

            // Back from the reopened editor goes Home. The preview may still be playing when Back
            // is pressed, and one Compose read with Espresso's default timeouts then waited
            // 30 s and more for idle on the emulator, longer than TIMEOUT_MS, so this wait is the longer one.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(hasTestTag(HOME_LIST_TAG), LAUNCH_TIMEOUT_MS)
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
        // What is tested is the editor beside a running export, not the export: on a loaded
        // emulator the preview's players and the export share its few decoders, and either
        // can fail there (ERROR_CODE_MUXING_TIMEOUT, ERROR_CODE_DECODING_FAILED, a preview
        // that never loads). So this test needs the editor back after each recreate and the
        // export not cancelled by them; a codec failure (ExportFailure.FAILED) is allowed,
        // anything else is not. Whether the preview loads while an export runs is not covered
        // here (the other tests cover the preview with no export).
        val id = ExportWorker.enqueue(workManager, project.id, ExportQuality.SMALL, "Reopen", replace = true)
        compose.waitUntil(TIMEOUT_MS) { workManager.getWorkInfoById(id).get()?.state == WorkInfo.State.RUNNING }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            editorUp()
            // The recreate below happens while the export runs (Small takes far longer than this on an emulator).
            assertThat(workManager.getWorkInfoById(id).get()?.state).isEqualTo(WorkInfo.State.RUNNING)
            scenario.recreate()
            editorUp()
            rotate(scenario, UiAutomation.ROTATION_FREEZE_90)
            editorUp()
            rotate(scenario, UiAutomation.ROTATION_FREEZE_0)
            editorUp()

            // The recreates did not stop the export, and the editor still shows its state.
            val info = workManager.getWorkInfoById(id).get()
            Log.i(ExportHarness.TAG, "Export beside the recreates: ${info?.state} ${info?.outputData?.getString(ExportWorker.KEY_FAILURE)}")
            assertThat(info?.state).isAnyOf(WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED)
            if (info?.state == WorkInfo.State.FAILED) {
                // Only the codec failure the shared decoders cause; a worker that threw (no reason) or
                // lost its media would point at the recreate.
                assertThat(info.outputData.getString(ExportWorker.KEY_FAILURE)).isEqualTo(ExportFailure.FAILED.name)
            }
            compose.onNodeWithTag(EDITOR_EXPORT_TAG).performClick()
            // Running: its progress. Ended before the sheet ever watched it (ExportSteps): the
            // sizes to choose from, as for any export the sheet did not start; or its result.
            val shown =
                hasTestTag(EXPORT_PROGRESS_TAG).or(hasText(s(R.string.export_done_title))).or(hasText(s(R.string.export_failed_title)))
            waitFor(if (info?.state?.isFinished == true) shown.or(hasTestTag(EXPORT_SAVE_TAG)) else shown, LAUNCH_TIMEOUT_MS)
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
            compose.waitUntil(LAUNCH_TIMEOUT_MS) { items() == 5 }
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

    /** Seeks the paused preview to [fraction] of the length and waits for the playhead to get there. */
    private fun seekTo(fraction: Float) {
        compose.onNodeWithContentDescription(s(R.string.editor_seek)).performSemanticsAction(SemanticsActions.SetProgress) { it(fraction) }
        compose.waitUntil(TIMEOUT_MS) { notYetIfBusy { abs(progress() - fraction) < 0.01f } }
    }

    /** Seeks to [us] paused, then compares the preview before and after a recreate. */
    private fun probe(
        scenario: ActivityScenario<MainActivity>,
        us: Long,
    ): Triple<String, Bitmap, Double> {
        val fraction = us.toFloat() / h.plannedUs(project)
        // From the intro first, whose orange fills the window: the video's grey there
        // afterwards proves the MAIN player itself landed the seek, not only the overlay.
        seekTo(0f)
        val earlier = settledFrame(window = WindowWanted.NOT_VIDEO)
        assertWithMessage("window at the intro: ${grid(earlier)}").that(windowShowsVideo(earlier)).isFalse()
        seekTo(fraction)
        val at = timeLabel()
        val position = progress()
        // The label moves at once, the picture only once the players have landed
        // the seek: on a slow CI emulator the old picture stays steady for over a
        // second. So the picture before the recreate is the first steady one that
        // is no longer the picture from before the seek.
        val before = settledFrame(unlike = earlier, window = WindowWanted.VIDEO)

        scenario.recreate()
        editorShown()
        // The restored playhead, and then its picture, which the players land after it.
        compose.waitUntil(TIMEOUT_MS) { notYetIfBusy { timeLabel() == at } }
        // The same playhead to the frame, not only to the second the label shows.
        assertWithMessage("playhead at $us after recreate").that(abs(progress() - position)).isLessThan(POSITION_SLACK)
        val after = settledFrame(like = before, window = WindowWanted.VIDEO)

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
     * seek that has landed). With [window], it also waits for the frame's
     * window to show the video, or anything but the video (the MAIN layer has
     * landed its seek; a stale picture from the previous probe is not taken).
     */
    private fun settledFrame(
        like: Bitmap? = null,
        unlike: Bitmap? = null,
        window: WindowWanted = WindowWanted.ANY,
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
                val windowOk =
                    when (window) {
                        WindowWanted.ANY -> true
                        WindowWanted.VIDEO -> windowShowsVideo(now)
                        WindowWanted.NOT_VIDEO -> !windowShowsVideo(now)
                    }
                if (moved && windowOk && (like == null || meanAbsDiff(like, now) < MAX_DIFF)) {
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

    private enum class WindowWanted { ANY, VIDEO, NOT_VIDEO }

    private companion object {
        const val TIMEOUT_MS = 20_000L

        /** The name the Settings round trip gives the video first. */
        const val RENAMED = "Asha's birthday"

        const val LAUNCH_TIMEOUT_MS = 60_000L

        /** 0.5 % of the 14 s video, about 70 ms: two frames at 30 fps. */
        const val POSITION_SLACK = 0.005f

        val CLIP_GREY = intArrayOf(54, 56, 56)

        /** Per channel, 0-255. */
        const val WINDOW_SLACK = 12

        /** Longer than the whole test video, so its end, when the preview stops, is inside the wait. */
        const val PLAY_TIMEOUT_MS = 40_000L

        /** How long one Compose read may wait for idle while the preview plays. */
        const val BUSY_READ_S = 2L

        /** How deep [isEspressoIdleTimeout] looks for the timeout among an exception's causes. */
        const val MAX_CAUSES = 8

        /** Where Espresso 3.7.0's NullPointerException on its idle timeout is thrown, and from where. */
        const val ESPRESSO_JOINER = "androidx.test.espresso.util.StringJoinerKt"
        const val ESPRESSO_UI_CONTROLLER = "androidx.test.espresso.base.UiControllerImpl"

        /**
         * MAIN here: intro 0-2 s, a captioned photo, then clip_dark from about
         * 3.8 s to 6.8 s (each junction a 0.6 s crossfade, which the preview
         * blends as the export does since cm-effects, so the photo shows in
         * the window until 4.4 s). 4.5 s is just after that cut-in, where the
         * old CI failure was seen (a tap on the video's tile seeks near it); 6.0 s is inside the
         * video, away from the frame fixture's 4 s loop point (near 5.4 s here),
         * where one frame of difference moves its white square across.
         */
        val KNOWN_TIMES_US = listOf(4_500_000L, 6_000_000L)

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
