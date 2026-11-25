package com.itsluminous.clearmovie.voice

import android.Manifest
import android.app.UiAutomation
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.data.record.MicSources
import com.itsluminous.clearmovie.di.RecordModule
import com.itsluminous.clearmovie.domain.model.AudioRole
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
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_PANEL_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_STOP_TAG
import com.itsluminous.clearmovie.ui.editor.RECORD_OPTION
import com.itsluminous.clearmovie.ui.editor.VOICE_MAKE_TAG
import com.itsluminous.clearmovie.ui.editor.insertOptionTag
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_MAIN_ROW_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.rowAddTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Evidence screenshots of the voice status (cm-voice-progress), by hand only
 * (`-e voiceprogressshots <prefix>`): the same steps of recording a voice,
 * finding its words and making its voice, with a [HeldVoiceTools] engine
 * held in each state, so a build without the status (the "before") is
 * photographed at the same moments. It uses only what both builds have.
 * Theme and language are set on the device beforehand; the pictures go to
 * the app's external files, `screens/`.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VoiceProgressScreenshots {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val voiceRow = TrackId("voice")
    private val words = listOf(VoicePhrase(200, "welcome everyone"), VoicePhrase(1_200, "to the party"))
    private var tools: HeldVoiceTools? = null

    @After
    fun tearDown() {
        tools?.let { t -> repeat(4) { t.steps += HeldVoiceTools.Step.Fail(IllegalStateException("test over")) } }
        RecordModule.testVoiceTools = null
        RecordModule.testMics = null
        h.cleanUp()
    }

    private fun present(matcher: SemanticsMatcher): Boolean =
        try {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        } catch (e: ComposeNotIdleException) {
            false
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("No compose hierarchies found") != true) throw e
            false
        }

    private fun waitFor(
        matcher: SemanticsMatcher,
        timeoutMs: Long = 20_000,
    ) = compose.waitUntil(timeoutMs) { present(matcher) }

    private fun shot(
        prefix: String,
        name: String,
    ) {
        Thread.sleep(900)
        val dir = File(checkNotNull(h.context.getExternalFilesDir(null)), "screens").apply { mkdirs() }
        val bitmap = checkNotNull(automation.takeScreenshot())
        FileOutputStream(File(dir, "$prefix-$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(ExportHarness.TAG, "voice-progress shot $prefix-$name")
    }

    /** Lets the engine report [done], when the build reports progress at all (a step it never takes is not waited for). */
    private fun progress(
        t: HeldVoiceTools,
        done: Float,
    ) {
        t.steps += HeldVoiceTools.Step.Progress(done)
        t.took.poll(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun closePanel() {
        if (present(hasTestTag(PANEL_CANCEL_TAG))) compose.onNodeWithTag(PANEL_CANCEL_TAG).performClick()
    }

    @Test
    fun screenshots() {
        val prefix = InstrumentationRegistry.getArguments().getString("voiceprogressshots")
        assumeTrue("screenshots run only when asked", prefix != null)
        prefix!!
        h.setUp()
        automation.grantRuntimePermission(h.context.packageName, Manifest.permission.RECORD_AUDIO)
        val project =
            ProjectFactory
                .blank(ProjectId(RandomIds.next()), RandomIds)
                .addToMain(listOf(h.photo(8_000)))
                .addRow(TrackType.AUDIO, AudioRole.VOICE, id = voiceRow)
                .copy(defaultVoice = VoiceChoice.INDIAN_FEMALE)
        assertThat(runBlocking { h.app.projectRepository.createDraft(project, inEditor = true) }).isTrue()
        val t = HeldVoiceTools(h.app.appFiles, words).also { tools = it }
        RecordModule.testVoiceTools = t
        val mic = FakeMic()
        RecordModule.testMics = MicSources { mic }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(hasTestTag(PREVIEW_TAG), 60_000)
            waitFor(hasTestTag(TIMELINE_MAIN_ROW_TAG))
            Thread.sleep(500)
            if (!present(hasTestTag(rowAddTag(voiceRow.value)))) compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).performClick()
            waitFor(hasTestTag(rowAddTag(voiceRow.value)))
            compose.onNodeWithTag(rowAddTag(voiceRow.value)).performSemanticsAction(SemanticsActions.OnClick)
            waitFor(hasTestTag(insertOptionTag(RECORD_OPTION)))
            compose.onNodeWithTag(insertOptionTag(RECORD_OPTION)).performScrollTo().performClick()
            waitFor(hasTestTag(RECORDING_STOP_TAG), 15_000)
            Thread.sleep(1_500)
            compose.onNodeWithTag(RECORDING_STOP_TAG).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(20_000) { !present(hasTestTag(RECORDING_PANEL_TAG)) }
            // Finding the words: before the engine reports anything, then halfway, then with the panel closed.
            shot(prefix, "1-listening")
            progress(t, 0f)
            progress(t, 0.45f)
            shot(prefix, "2-listening-progress")
            closePanel()
            shot(prefix, "3-listening-panel-closed")
            t.steps += HeldVoiceTools.Step.Finish
            waitFor(hasTestTag("voice_phrase_0"))
            shot(prefix, "4-words-to-check")
            // Making the voice: loading the voices, then halfway.
            compose.onNodeWithTag(VOICE_MAKE_TAG).performScrollTo().performClick()
            shot(prefix, "5-making-voice-loading")
            progress(t, 0f)
            progress(t, 0.5f)
            shot(prefix, "6-making-voice")
            // It fails: the panel's error and Try again (where the build has them).
            t.steps += HeldVoiceTools.Step.Fail(RuntimeException("encoder"))
            shot(prefix, "7-failed")
            if (present(hasTestTag(TRY_AGAIN_TAG))) {
                compose.onNodeWithTag(TRY_AGAIN_TAG).performClick()
            } else {
                compose.onNodeWithTag(VOICE_MAKE_TAG).performScrollTo().performClick()
            }
            progress(t, 0f)
            progress(t, 0.7f)
            closePanel()
            shot(prefix, "8-making-voice-panel-closed")
            t.steps += HeldVoiceTools.Step.Fail(RuntimeException("encoder"))
            shot(prefix, "9-failed-panel-closed")
        }
    }

    private companion object {
        /** Try again's tag, written out: the build without the status has no such constant. */
        const val TRY_AGAIN_TAG = "voice_try_again"
    }
}
