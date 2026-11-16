package com.itsluminous.clearmovie

import android.app.UiAutomation
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.Project
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
 * export runs, and its preview must show the same picture afterwards.
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

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    private fun editorShown() {
        waitFor(hasTestTag(EDITOR_EXPORT_TAG))
        waitFor(hasTestTag(PREVIEW_TAG))
        compose.waitForIdle()
    }

    /** The playhead label, "0:05 of 0:14". */
    private fun timeLabel(): String {
        val of = h.context.getString(R.string.editor_time, "", "")
        return compose
            .onAllNodes(hasText(of, substring = true))
            .fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }
            .first { of in it }
    }

    private fun previewPlays() {
        val atStart = timeLabel()
        compose.onNodeWithText(s(R.string.action_play)).performClick()
        compose.waitUntil(TIMEOUT_MS) { timeLabel() != atStart }
        compose.onNodeWithText(s(R.string.action_pause)).performClick()
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
    fun aReopenedEditorShowsTheSamePreviewFrameAfterRecreate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            editorShown()
            // A known time: the start of the first video on MAIN (tapping a tile seeks there, paused).
            compose.onAllNodes(hasContentDescription(s(R.string.editor_item_video), substring = true)).onFirst().performClick()
            val at = timeLabel()
            val before = settledFrame()

            scenario.recreate()
            editorShown()
            assertThat(timeLabel()).isEqualTo(at)
            val after = settledFrame()

            save(before, "reopen-preview-before-recreate.png")
            save(after, "reopen-preview-after-recreate.png")
            val diff = meanAbsDiff(before, after)
            Log.i(ExportHarness.TAG, "Preview at $at: before spread ${spread(before)}, after spread ${spread(after)}, mean abs diff $diff")
            // A picture, not a blank surface, and the same one after the recreate.
            assertThat(spread(before)).isGreaterThan(MIN_SPREAD)
            assertThat(diff).isLessThan(MAX_DIFF)
        }
    }

    /** The preview, once two captures a moment apart agree (the frame has landed). */
    private fun settledFrame(): Bitmap {
        var last = compose.onNodeWithTag(PREVIEW_TAG).captureToImage().asAndroidBitmap()
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500)
            val now = compose.onNodeWithTag(PREVIEW_TAG).captureToImage().asAndroidBitmap()
            if (spread(now) > MIN_SPREAD && meanAbsDiff(last, now) < 1.0) return now
            last = now
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

        /** Brightest minus darkest luma: a blank or single-colour surface is near 0. */
        const val MIN_SPREAD = 40

        /** Mean absolute difference per channel (0-255) allowed between the two captures. */
        const val MAX_DIFF = 6.0

        fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

        fun luma(p: Int): Int = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000

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
