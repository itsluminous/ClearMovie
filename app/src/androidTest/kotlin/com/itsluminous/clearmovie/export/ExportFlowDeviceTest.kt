package com.itsluminous.clearmovie.export

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.ui.editor.EDITOR_EXPORT_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_CLOSE_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_PLAY_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_PROGRESS_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_SAVE_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_SHARE_TAG
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
 * The export from the editor's Export button, through the real worker:
 * it keeps going through two rotations and a trip to the background, the
 * sheet shows its progress again after each, and it ends in the gallery
 * with Play and Share. A recreation while choosing keeps the choice.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExportFlowDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()

    private fun s(id: Int) = h.context.getString(id)

    @Before
    fun setUp() {
        h.setUp()
        h.bindPreset()
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
        h.cleanUp()
    }

    private fun waitFor(
        matcher: SemanticsMatcher,
        timeoutMs: Long = 20_000,
    ) = compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Opens [name] from My videos. (An editor the app reopened by itself at launch
     * crashes when its activity is destroyed, on main today: a navigation entry
     * stays INITIALIZED. Reported with a reproduction; not in this change.)
     */
    private fun openFromMyVideos(name: String) {
        waitFor(hasTestTag(HOME_LIST_TAG))
        // Home reads the presets after the videos, and their cards then go in above My videos. Wait for them,
        // or the video scrolled to can be pushed off the screen before the click.
        waitFor(hasTestTag(presetEditTag(PresetId("event-frame"))))
        compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performClick()
        waitFor(hasTestTag(EDITOR_EXPORT_TAG))
    }

    /**
     * Turns the screen and waits for the new activity to be up and drawn.
     */
    private fun rotate(
        scenario: ActivityScenario<MainActivity>,
        orientation: Int,
    ) {
        var before = 0
        scenario.onActivity {
            before = System.identityHashCode(it)
            it.requestedOrientation = orientation
        }
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            var now = before
            runCatching { scenario.onActivity { now = System.identityHashCode(it) } }
            if (now != before && scenario.state == Lifecycle.State.RESUMED) break
            Thread.sleep(100)
        }
        compose.waitForIdle()
        Thread.sleep(1_000)
    }

    private fun exportWork(): WorkInfo? =
        WorkManager
            .getInstance(h.context)
            .getWorkInfosForUniqueWork(ExportWorker.UNIQUE_NAME)
            .get()
            .lastOrNull()

    @Test
    fun anExportCarriesOnThroughRotationsAndTheBackgroundAndEndsWithPlayAndShare() {
        val project = h.project(h.typicalItems(photoMs = 8000), "Rotate and leave", inEditor = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFromMyVideos("Rotate and leave")
            compose.onNodeWithTag(EDITOR_EXPORT_TAG).performClick()
            waitFor(hasTestTag(EXPORT_SAVE_TAG))
            compose.onNodeWithTag(exportQualityTag(ExportQuality.BEST)).performScrollTo().performClick()
            compose.onNodeWithTag(EXPORT_SAVE_TAG).assertIsDisplayed().performClick()
            waitFor(hasTestTag(EXPORT_PROGRESS_TAG))

            rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            waitFor(hasTestTag(EXPORT_PROGRESS_TAG).or(hasText(s(R.string.export_done_title))))
            rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            waitFor(hasTestTag(EXPORT_PROGRESS_TAG).or(hasText(s(R.string.export_done_title))))

            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(3_000)
            val meanwhile = exportWork()?.state
            assertThat(meanwhile).isAnyOf(WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            waitFor(hasText(s(R.string.export_done_title)), timeoutMs = 600_000)
            val info = requireNotNull(exportWork())
            assertThat(info.state).isEqualTo(WorkInfo.State.SUCCEEDED)
            val uri = h.exported(android.net.Uri.parse(info.outputData.getString(ExportWorker.KEY_URI)))
            val probe = h.probe(uri)
            assertThat(probe.height).isEqualTo(1080)
            assertThat(probe.videoDurationUs).isWithin(1_000_000L / 30 + 1).of(h.plannedUs(project))
            h.keep(uri, "export-flow-best.mp4")

            // Share hands the video to the phone's share sheet.
            intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            compose.onNodeWithTag(EXPORT_SHARE_TAG).assertIsDisplayed().performClick()
            intended(hasAction(Intent.ACTION_CHOOSER))

            // Play opens it in the app's own player.
            compose.onNodeWithTag(EXPORT_PLAY_TAG).assertIsDisplayed().performClick()
            waitFor(hasText(s(R.string.action_close)))
            compose.onAllNodes(hasText(s(R.string.action_close))).fetchSemanticsNodes().let { assertThat(it).isNotEmpty() }
        }
    }

    @Test
    fun aRecreationWhileChoosingKeepsTheSheetAndTheChoice() {
        h.project(h.typicalItems(), "Recreate this", inEditor = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFromMyVideos("Recreate this")
            compose.onNodeWithTag(EDITOR_EXPORT_TAG).performClick()
            waitFor(hasTestTag(EXPORT_SAVE_TAG))
            compose.onNodeWithTag(exportQualityTag(ExportQuality.SMALL)).performScrollTo().performClick()
            scenario.recreate()
            waitFor(hasTestTag(EXPORT_SAVE_TAG))
            compose.onNodeWithTag(exportQualityTag(ExportQuality.SMALL)).assertIsSelected()
            compose.onNodeWithTag(EXPORT_CLOSE_TAG).assertIsDisplayed().performClick()
            compose.onNodeWithText(s(R.string.export_title)).assertDoesNotExist()
            assertThat(exportWork()?.state?.isFinished ?: true).isTrue()
        }
    }
}
