package com.itsluminous.clearmovie.export

import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.ui.components.PLAY_FILE_CLOSE_TAG
import com.itsluminous.clearmovie.ui.components.PLAY_FILE_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.EDITOR_EXPORT_TAG
import com.itsluminous.clearmovie.ui.editor.PLAY_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_CLOSE_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_PLAY_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_PROGRESS_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_SAVE_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_STOP_TAG
import com.itsluminous.clearmovie.ui.export.exportQualityTag
import com.itsluminous.clearmovie.ui.home.HOME_LIST_TAG
import com.itsluminous.clearmovie.ui.home.presetEditTag
import com.itsluminous.clearmovie.work.ExportWorker
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * cm-keep-screen-on: the activity window's FLAG_KEEP_SCREEN_ON while the
 * editor preview plays, while an export waits or saves, and while the
 * app's own player plays the result; cleared on pause, at the end, when
 * the editor is left, when the export ends or is stopped, when the player
 * closes, and the moment the app goes to the background. Each step is also logged under the tag KeepScreenOnTest.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class KeepScreenOnDeviceTest {
    private companion object {
        const val TAG = "KeepScreenOnTest"
        const val FLAG_TIMEOUT_MS = 10_000L

        /** Longer than the whole video plays. */
        const val END_TIMEOUT_MS = 90_000L
        const val NO_NODE = "could not find any node"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()

    private fun s(id: Int) = h.context.getString(id)

    @Before
    fun setUp() {
        h.setUp()
        h.bindPreset()
    }

    @After
    fun tearDown() = h.cleanUp()

    private fun waitFor(
        matcher: SemanticsMatcher,
        timeoutMs: Long = 20_000,
    ) = compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun flag(scenario: ActivityScenario<MainActivity>): Boolean {
        var on = false
        scenario.onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    /** Waits until the flag is [on], logs the step, and fails if it never gets there. */
    private fun expectFlag(
        scenario: ActivityScenario<MainActivity>,
        on: Boolean,
        step: String,
    ) {
        // Through Compose's test clock, so the app's frames (and its effects) go on while waiting.
        runCatching { compose.waitUntil(FLAG_TIMEOUT_MS) { flag(scenario) == on } }
        val now = flag(scenario)
        Log.i(TAG, "$step: FLAG_KEEP_SCREEN_ON=${if (now) "set" else "clear"}")
        assertWithMessage(step).that(now).isEqualTo(on)
    }

    /**
     * While the export still saves the flag is set; an export that ends first
     * (a fast phone) only has to have set it while it ran: the app's own
     * KeepScreenOn log line shows that, not this test's.
     */
    private fun expectFlagWhileSaving(
        scenario: ActivityScenario<MainActivity>,
        step: String,
    ) {
        runCatching { compose.waitUntil(FLAG_TIMEOUT_MS) { flag(scenario) || exportState()?.isFinished == true } }
        val on = flag(scenario)
        val finished = exportState()?.isFinished == true
        Log.i(TAG, "$step: FLAG_KEEP_SCREEN_ON=${if (on) "set" else "clear"}${if (finished) ", export already finished" else ""}")
        // Read in this order, a flag that is clear while the export is not finished is a failure.
        if (!finished) assertWithMessage(step).that(on || exportState()?.isFinished == true).isTrue()
    }

    /** The flag stays [on] for [ms]: nothing sets it, or clears it, a moment later. */
    private fun holdsFlag(
        scenario: ActivityScenario<MainActivity>,
        on: Boolean,
        step: String,
        ms: Long = 1_500,
    ) {
        val end = System.currentTimeMillis() + ms
        compose.waitUntil(ms + FLAG_TIMEOUT_MS) {
            assertWithMessage(step).that(flag(scenario)).isEqualTo(on)
            System.currentTimeMillis() >= end
        }
        Log.i(TAG, "$step: FLAG_KEEP_SCREEN_ON=${if (on) "set" else "clear"} for $ms ms")
    }

    /** The export the test started last, by its id: rows of earlier exports (finished, cancelled) stay in WorkManager. */
    private var current: java.util.UUID? = null

    private fun exportWorks(): List<WorkInfo> =
        WorkManager
            .getInstance(h.context)
            .getWorkInfosForUniqueWork(ExportWorker.UNIQUE_NAME)
            .get()

    private fun currentWork(): WorkInfo? = current?.let { id -> exportWorks().firstOrNull { it.id == id } }

    private fun exportState(): WorkInfo.State? = currentWork()?.state

    private fun exportUri(): android.net.Uri? =
        currentWork()
            ?.outputData
            ?.getString(ExportWorker.KEY_URI)
            ?.let(android.net.Uri::parse)

    /**
     * Opens [name] from My videos. The first time, Home is waited on until it has read the presets (their
     * cards go in above My videos); back on Home later they are read, and Home keeps where it was scrolled
     * to, so on a small screen the preset card is not even composed.
     */
    private fun openFromMyVideos(
        name: String,
        firstTime: Boolean = true,
    ) {
        waitFor(hasTestTag(HOME_LIST_TAG))
        if (firstTime) waitFor(hasTestTag(presetEditTag(h.presetId)))
        val card = hasText(name) and hasClickAction()
        var missed: AssertionError? = null
        try {
            compose.waitUntil(10_000) {
                try {
                    compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(card)
                    compose.onNode(card).performClick()
                    true
                } catch (e: AssertionError) {
                    if (e.message?.contains(NO_NODE) != true) throw e
                    missed = e
                    false
                }
            }
        } catch (e: ComposeTimeoutException) {
            throw missed ?: e
        }
        waitFor(hasTestTag(EDITOR_EXPORT_TAG))
    }

    private fun startExport(quality: ExportQuality) {
        val before = exportWorks().map { it.id }.toSet()
        compose.onNodeWithTag(EDITOR_EXPORT_TAG).performClick()
        waitFor(hasTestTag(EXPORT_SAVE_TAG))
        compose.onNodeWithTag(exportQualityTag(quality)).performScrollTo().performClick()
        compose.onNodeWithTag(EXPORT_SAVE_TAG).performClick()
        waitFor(hasTestTag(EXPORT_PROGRESS_TAG) or hasText(s(R.string.export_done_title)))
        // The sheet shows a new export at 0% before WorkManager has written it: wait for its row.
        compose.waitUntil(20_000) { exportWorks().any { it.id !in before } }
        current = exportWorks().first { it.id !in before }.id
    }

    @Test
    fun theScreenStaysOnOnlyWhilePlayingOrExportingInTheForeground() {
        h.project(h.typicalItems(), "Keep it on", inEditor = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFromMyVideos("Keep it on")
            // Play is enabled once the preview has loaded the video.
            waitFor(hasTestTag(PLAY_TAG) and isEnabled(), timeoutMs = 30_000)
            holdsFlag(scenario, on = false, step = "editor open, paused")

            // The editor preview.
            compose.onNodeWithTag(PLAY_TAG).performClick()
            expectFlag(scenario, on = true, step = "editor preview playing")
            compose.onNodeWithTag(PLAY_TAG).performClick()
            expectFlag(scenario, on = false, step = "editor preview paused")
            holdsFlag(scenario, on = false, step = "editor stays paused")

            // Played to the end, it stops there by itself, and so does the flag.
            compose.onNodeWithTag(PLAY_TAG).performClick()
            expectFlag(scenario, on = true, step = "editor preview playing to the end")
            compose.waitUntil(END_TIMEOUT_MS) { !flag(scenario) }
            expectFlag(scenario, on = false, step = "editor preview reached the end")

            // Leaving the editor while it plays: the screen it showed on is gone.
            compose.onNodeWithTag(PLAY_TAG).performClick()
            expectFlag(scenario, on = true, step = "editor preview playing before Back")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(hasTestTag(HOME_LIST_TAG))
            expectFlag(scenario, on = false, step = "left the editor for Home while it played")
            openFromMyVideos("Keep it on", firstTime = false)
            waitFor(hasTestTag(PLAY_TAG) and isEnabled(), timeoutMs = 30_000)
            holdsFlag(scenario, on = false, step = "editor open again, paused")

            // Playing, then the app goes to the background: cleared at once, not when Compose next runs.
            compose.onNodeWithTag(PLAY_TAG).performClick()
            expectFlag(scenario, on = true, step = "editor preview playing again")
            scenario.moveToState(Lifecycle.State.CREATED)
            assertWithMessage("background while playing").that(flag(scenario)).isFalse()
            Log.i(TAG, "background while playing: FLAG_KEEP_SCREEN_ON=clear")
            scenario.moveToState(Lifecycle.State.RESUMED)
            // Going to the background paused the editor, so back on screen it may go dark.
            // Not even for a moment after the return (a hold from before the background must not count).
            holdsFlag(scenario, on = false, step = "back from the background, editor paused")

            // An export: on while it waits or saves, off in the background, on again when the app is back.
            startExport(ExportQuality.SMALL)
            expectFlagWhileSaving(scenario, step = "export saving")
            scenario.moveToState(Lifecycle.State.CREATED)
            assertWithMessage("background while exporting").that(flag(scenario)).isFalse()
            Log.i(TAG, "background while exporting: FLAG_KEEP_SCREEN_ON=clear")
            Thread.sleep(1_000)
            // The export itself carries on in the background, as before.
            assertThat(exportState()).isAnyOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            // Back on screen while it still saves, it is set again (a fast export may have finished meanwhile).
            expectFlagWhileSaving(scenario, step = "back while still exporting")

            // Done: cleared.
            waitFor(hasText(s(R.string.export_done_title)), timeoutMs = 600_000)
            exportUri()?.let(h::exported) // removed from the gallery after the test
            expectFlag(scenario, on = false, step = "export finished")
            holdsFlag(scenario, on = false, step = "export finished, nothing playing")

            // The app's own player, after the export: on while it plays, off when paused and when closed.
            compose.onNodeWithTag(EXPORT_PLAY_TAG).performClick()
            waitFor(hasTestTag(PLAY_FILE_TOGGLE_TAG))
            expectFlag(scenario, on = true, step = "exported video playing")
            compose.onNodeWithTag(PLAY_FILE_TOGGLE_TAG).performClick()
            expectFlag(scenario, on = false, step = "exported video paused")
            compose.onNodeWithTag(PLAY_FILE_TOGGLE_TAG).performClick()
            expectFlag(scenario, on = true, step = "exported video playing again")
            compose.onNodeWithTag(PLAY_FILE_CLOSE_TAG).performClick()
            expectFlag(scenario, on = false, step = "player closed")

            // A stopped export clears it too.
            compose.onNodeWithTag(EXPORT_CLOSE_TAG).performClick()
            startExport(ExportQuality.BEST)
            // A Best export of this video takes minutes on an emulator; Stop is pressed within seconds.
            assertWithMessage("the second export ended before it could be stopped").that(exportState()?.isFinished).isNotEqualTo(true)
            expectFlag(scenario, on = true, step = "second export saving")
            compose.onNodeWithTag(EXPORT_STOP_TAG).performClick()
            val confirm = hasText(s(R.string.export_stop)) and hasClickAction() and !hasTestTag(EXPORT_STOP_TAG)
            waitFor(confirm)
            holdsFlag(scenario, on = true, step = "asked whether to stop, still saving", ms = 500)
            compose.onNode(confirm).performClick()
            expectFlag(scenario, on = false, step = "export stopped")
            assertThat(exportState()).isEqualTo(WorkInfo.State.CANCELLED)
            holdsFlag(scenario, on = false, step = "after the stop")
        }
    }
}
