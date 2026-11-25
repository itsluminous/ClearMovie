package com.itsluminous.clearmovie.voice

import android.Manifest
import android.app.UiAutomation
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToLog
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.record.MicSources
import com.itsluminous.clearmovie.di.RecordModule
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.record.FakeMic
import com.itsluminous.clearmovie.ui.editor.PANEL_CANCEL_TAG
import com.itsluminous.clearmovie.ui.editor.PANEL_TAG
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_PANEL_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_STOP_TAG
import com.itsluminous.clearmovie.ui.editor.RECORD_OPTION
import com.itsluminous.clearmovie.ui.editor.VOICE_MAKE_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_STATUS_BAR_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_STATUS_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_STOP_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_TRY_AGAIN_TAG
import com.itsluminous.clearmovie.ui.editor.insertOptionTag
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_BUSY_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_MAIN_ROW_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.rowAddTag
import com.itsluminous.clearmovie.ui.editor.timeline.timedPlusTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the editor shows while a recording's words are found and a voice is
 * made (cm-voice-progress), in the app, with a [HeldVoiceTools] engine that
 * waits in each state until the test moves it on, and a [FakeMic] (the
 * emulator has nothing to speak into). The status shows in the voice item's
 * panel as soon as the recording stops, on the item, and above the bar when
 * the panel is closed; it stays through a rotation and the app going to the
 * background, Stop ends it, a failure offers Try again, and it clears when
 * the work ends. The screenshots are taken by [VoiceProgressScreenshots].
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VoiceProgressDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val voiceRow = TrackId("voice")
    private val words = listOf(VoicePhrase(200, "welcome everyone"), VoicePhrase(1_200, "to the party"))
    private lateinit var project: Project
    private lateinit var tools: HeldVoiceTools

    @Before
    fun setUp() {
        h.setUp()
        automation.grantRuntimePermission(h.context.packageName, Manifest.permission.RECORD_AUDIO)
        project =
            ProjectFactory
                .blank(ProjectId(RandomIds.next()), RandomIds)
                .addToMain(listOf(h.photo(8_000)))
                .addRow(TrackType.AUDIO, AudioRole.VOICE, id = voiceRow)
                // A new recording starts in this voice, so its words are found when it stops.
                .copy(defaultVoice = VoiceChoice.INDIAN_FEMALE)
        assertThat(runBlocking { h.app.projectRepository.createDraft(project, inEditor = true) }).isTrue()
        tools = HeldVoiceTools(h.app.appFiles, words)
        RecordModule.testVoiceTools = tools
        val mic = FakeMic()
        RecordModule.testMics = MicSources { mic }
    }

    @After
    fun tearDown() {
        // A job still waiting ends at once.
        repeat(4) { tools.steps += HeldVoiceTools.Step.Fail(IllegalStateException("test over")) }
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        RecordModule.testVoiceTools = null
        RecordModule.testMics = null
        h.cleanUp()
    }

    private fun present(
        matcher: SemanticsMatcher,
        unmerged: Boolean = false,
    ): Boolean =
        try {
            compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
        } catch (e: ComposeNotIdleException) {
            false
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("No compose hierarchies found") != true) throw e
            false
        }

    private fun waitFor(
        matcher: SemanticsMatcher,
        timeoutMs: Long = TIMEOUT_MS,
        unmerged: Boolean = false,
    ) = try {
        compose.waitUntil(timeoutMs) { present(matcher, unmerged) }
    } catch (e: Throwable) {
        // What the screen showed instead, for the log.
        runCatching { compose.onAllNodes(isRoot()).onFirst().printToLog(TAG) }
        throw e
    }

    private fun waitGone(
        matcher: SemanticsMatcher,
        unmerged: Boolean = false,
    ) = compose.waitUntil(TIMEOUT_MS) { !present(matcher, unmerged) }

    private fun text(id: Int) = hasText(h.context.getString(id))

    /** A determinate progress bar at [done]. */
    private fun progressAt(done: Float) =
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(done, 0f..1f))

    private fun openVoiceRowSheet() {
        waitFor(hasTestTag(PREVIEW_TAG), LAUNCH_TIMEOUT_MS)
        // On a small screen (CI's is 320x640) the voice row is folded away under "Show all rows": open them.
        waitFor(hasTestTag(TIMELINE_MAIN_ROW_TAG))
        Thread.sleep(500)
        if (!present(hasTestTag(rowAddTag(voiceRow.value)))) {
            waitFor(hasTestTag(TIMELINE_ROWS_TOGGLE_TAG))
            compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).performClick()
        }
        waitFor(hasTestTag(rowAddTag(voiceRow.value)))
        // Its semantics action, not a touch: the "+" may sit under the floating preview on a small screen.
        compose.onNodeWithTag(rowAddTag(voiceRow.value)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(hasTestTag(insertOptionTag(RECORD_OPTION)))
    }

    /** Records a short voice from the row's sheet and stops it. */
    private fun recordAndStop() {
        openVoiceRowSheet()
        compose.onNodeWithTag(insertOptionTag(RECORD_OPTION)).performScrollTo().performClick()
        waitFor(hasTestTag(RECORDING_PANEL_TAG))
        waitFor(hasTestTag(RECORDING_STOP_TAG), COUNTDOWN_TIMEOUT_MS)
        Thread.sleep(RECORD_MS)
        compose.onNodeWithTag(RECORDING_STOP_TAG).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun voiceItem(): AudioClip? =
        runBlocking { h.app.projectRepository.read(project.id) }
            ?.track(voiceRow)
            ?.clips
            ?.firstOrNull() as? AudioClip

    @Test
    fun theStatusShowsWhileWordsAreFoundAndAVoiceIsMadeAndClearsWhenDone() {
        val panel = hasTestTag(PANEL_TAG)
        val inPanel = hasTestTag(VOICE_STATUS_TAG) and hasAnyAncestor(panel)
        val bar = hasTestTag(VOICE_STATUS_BAR_TAG)
        val loading = text(R.string.voice_engine_loading)
        val listening = text(R.string.voice_working_words)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            recordAndStop()
            // 1. Right after Stop, the item's panel opens with the words being found, and the item shows it too.
            waitFor(inPanel)
            assertThat(present(loading)).isTrue()
            // Stop from the start: the engine checks for it between each step of getting ready.
            assertThat(present(hasTestTag(VOICE_STOP_TAG))).isTrue()
            waitFor(hasTestTag(TIMELINE_BUSY_TAG), unmerged = true)
            assertThat(present(bar)).isFalse()
            // Progress once the engine reports it.
            tools.step(HeldVoiceTools.Step.Progress(0f))
            tools.step(HeldVoiceTools.Step.Progress(0.5f))
            waitFor(progressAt(0.5f), unmerged = true)
            assertThat(present(listening)).isTrue()

            // 2. The panel closed: the status moves above the bar, with Stop, and Record voice waits for it.
            compose.onNodeWithTag(PANEL_CANCEL_TAG).performSemanticsAction(SemanticsActions.OnClick)
            waitFor(bar)
            assertThat(present(hasTestTag(VOICE_STOP_TAG) and hasAnyAncestor(bar))).isTrue()
            // The item is selected, so its row's sheet opens from the "+" after it.
            compose.onNodeWithTag(timedPlusTag(voiceItem()!!.id, InsertSide.AFTER)).performSemanticsAction(SemanticsActions.OnClick)
            waitFor(hasTestTag(insertOptionTag(RECORD_OPTION)))
            compose.onNodeWithTag(insertOptionTag(RECORD_OPTION)).assertIsNotEnabled()
            assertThat(present(bar)).isTrue()
            compose.onNodeWithTag(PANEL_CANCEL_TAG).performSemanticsAction(SemanticsActions.OnClick)

            // 3. A rotation and the app going to the background keep it.
            var before: MainActivity? = null
            scenario.onActivity { before = it }
            automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            compose.waitUntil(TIMEOUT_MS) {
                var now: MainActivity? = null
                runCatching { scenario.onActivity { now = it } }
                now != null && now !== before
            }
            waitFor(bar)
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(1_000)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor(bar)
            assertThat(present(listening)).isTrue()

            // 4. The words are in: the status clears and the panel opens to check them.
            tools.steps += HeldVoiceTools.Step.Finish
            waitFor(hasTestTag("voice_phrase_0"))
            waitGone(bar)
            waitGone(hasTestTag(VOICE_STATUS_TAG))
            waitGone(hasTestTag(TIMELINE_BUSY_TAG), unmerged = true)
            assertThat(voiceItem()?.voiceOver?.phrases).isEqualTo(words)

            // 5. Make voice, then Stop: "Stopping" until the engine's next step, and the item is left as it was.
            compose.onNodeWithTag(VOICE_MAKE_TAG).performScrollTo().performClick()
            waitFor(loading)
            tools.step(HeldVoiceTools.Step.Progress(0f))
            waitFor(text(R.string.voice_working_voice))
            waitFor(progressAt(0f), unmerged = true)
            compose.onNodeWithTag(VOICE_STOP_TAG).performClick()
            waitFor(text(R.string.voice_stopping))
            tools.steps += HeldVoiceTools.Step.Progress(0.5f)
            waitGone(hasTestTag(VOICE_STATUS_TAG))
            assertThat(voiceItem()?.voiceOver?.generated).isNull()

            // 6. A voice that could not be made: the panel says so, and its own Make voice is the retry. Closed, the
            // bar keeps the error with Try again, which makes it.
            compose.onNodeWithTag(VOICE_MAKE_TAG).performScrollTo().performClick()
            waitFor(loading)
            tools.steps += HeldVoiceTools.Step.Fail(RuntimeException("encoder"))
            waitFor(text(R.string.voice_failed))
            assertThat(present(hasTestTag(VOICE_TRY_AGAIN_TAG))).isFalse()
            compose.onNodeWithTag(PANEL_CANCEL_TAG).performSemanticsAction(SemanticsActions.OnClick)
            waitFor(hasTestTag(VOICE_TRY_AGAIN_TAG) and hasAnyAncestor(bar))
            compose.onNodeWithTag(VOICE_TRY_AGAIN_TAG).performClick()
            waitFor(loading)
            tools.steps += HeldVoiceTools.Step.Finish
            compose.waitUntil(TIMEOUT_MS) { voiceItem()?.voiceOver?.generated != null }
            waitGone(hasTestTag(VOICE_STATUS_TAG))
            waitGone(bar)
            assertThat(voiceItem()?.voiceOver?.voice).isEqualTo(VoiceChoice.INDIAN_FEMALE)
        }
    }

    private companion object {
        const val TAG = "VoiceProgressTest"
        const val TIMEOUT_MS = 20_000L
        const val LAUNCH_TIMEOUT_MS = 60_000L
        const val COUNTDOWN_TIMEOUT_MS = 15_000L
        const val RECORD_MS = 1_500L
    }
}
