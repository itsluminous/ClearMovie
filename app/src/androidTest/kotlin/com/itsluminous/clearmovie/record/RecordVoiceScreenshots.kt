package com.itsluminous.clearmovie.record

import android.Manifest
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.data.record.MicSources
import com.itsluminous.clearmovie.di.RecordModule
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.ui.editor.MIC_CONTINUE_TAG
import com.itsluminous.clearmovie.ui.editor.MIC_RATIONALE_TAG
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_PANEL_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_STOP_TAG
import com.itsluminous.clearmovie.ui.editor.RECORD_OPTION
import com.itsluminous.clearmovie.ui.editor.insertOptionTag
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.clipTag
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
 * Evidence screenshots of Record voice (cm-record-voice), by hand only
 * (`-e screenshots <prefix>`, optional `-e landscape true`): the voice row's
 * add menu, the microphone explanation (when the permission is not granted
 * yet), the countdown, the recording panel and the placed clip. Theme,
 * language and font size are set on the device beforehand. Pictures go to
 * the app's files/screens folder.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RecordVoiceScreenshots {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val voiceRow = TrackId("voice")

    @After
    fun tearDown() {
        RecordModule.testMics = null
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        h.cleanUp()
    }

    private fun waitFor(
        matcher: SemanticsMatcher,
        timeoutMs: Long = 60_000,
    ) = compose.waitUntil(timeoutMs) {
        try {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        } catch (e: ComposeNotIdleException) {
            false
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("No compose hierarchies found") != true) throw e
            false
        }
    }

    private fun shot(name: String) {
        Thread.sleep(700)
        val dir = File(checkNotNull(h.context.getExternalFilesDir(null)), "screens").apply { mkdirs() }
        val bitmap = checkNotNull(automation.takeScreenshot())
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun recordVoice() {
        val args = InstrumentationRegistry.getArguments()
        val prefix = args.getString("screenshots")
        assumeTrue("screenshots run only when asked", prefix != null)
        h.setUp()
        if (args.getString("landscape") == "true") automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        // A short video: a photo and two videos, an earlier voice clip, and an empty voice row to record onto.
        val earlier = AudioClip(ClipId("earlier"), h.video().media, startMs = 600, durationMs = 2_000, sourceDurationMs = 3_000)
        val project =
            ProjectFactory
                .blank(ProjectId(RandomIds.next()))
                .addToMain(listOf(h.photo(30_000), h.video(), h.video("fixtures/clip_light.mp4")))
                .addRow(TrackType.AUDIO, AudioRole.VOICE, id = TrackId("talk"))
                .let { it.copy(tracks = it.tracks.map { t -> if (t.id == TrackId("talk")) t.copy(clips = listOf(earlier)) else t }) }
                .addRow(TrackType.AUDIO, AudioRole.VOICE, id = voiceRow)
        assertThat(runBlocking { h.app.projectRepository.createDraft(project, inEditor = true) }).isTrue()
        val mic = FakeMic()
        RecordModule.testMics = MicSources { mic }
        val askFirst = ContextCompat.checkSelfPermission(h.context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(hasTestTag(PREVIEW_TAG))
            // Sideways or with large text the rows fold away: show them all first.
            Thread.sleep(1_500)
            if (compose.onAllNodes(hasTestTag(TIMELINE_ROWS_TOGGLE_TAG)).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).performClick()
                Thread.sleep(1_000)
            }
            waitFor(hasTestTag(rowAddTag(voiceRow.value)))
            compose.onNodeWithTag(rowAddTag(voiceRow.value)).performClick()
            waitFor(hasTestTag(insertOptionTag(RECORD_OPTION)))
            shot("$prefix-1-add-menu")
            compose.onNodeWithTag(insertOptionTag(RECORD_OPTION)).performClick()
            if (askFirst) {
                waitFor(hasTestTag(MIC_RATIONALE_TAG))
                shot("$prefix-2-mic-rationale")
                compose.onNodeWithTag(MIC_CONTINUE_TAG).performClick()
                allowInSystemDialog()
            }
            waitFor(hasTestTag(RECORDING_PANEL_TAG))
            shot("$prefix-3-countdown")
            waitFor(hasTestTag(RECORDING_STOP_TAG), 20_000)
            Thread.sleep(2_500)
            shot("$prefix-4-recording")
            // Stop (unless the video already ended, which stops it too).
            runCatching { compose.onNodeWithTag(RECORDING_STOP_TAG).performClick() }
            compose.waitUntil(20_000) {
                runBlocking { h.app.projectRepository.read(project.id) }?.tracks?.flatMap { t -> t.clips }?.any { c ->
                    c is AudioClip &&
                        c.id != earlier.id
                } ==
                    true
            }
            val placed =
                runBlocking { h.app.projectRepository.read(project.id) }!!.tracks.flatMap { it.clips }.first {
                    it is AudioClip &&
                        it.id != earlier.id
                }
            waitFor(hasTestTag(clipTag(placed.id)))
            shot("$prefix-5-placed")
        }
    }

    private fun allowInSystemDialog() {
        val ids =
            listOf(
                "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
                "com.android.permissioncontroller:id/permission_allow_button",
            )
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            val root = automation.rootInActiveWindow
            val button: AccessibilityNodeInfo? =
                root?.let { r ->
                    ids.firstNotNullOfOrNull { id -> r.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser } }
                }
            if (button != null) {
                shot("${InstrumentationRegistry.getArguments().getString("screenshots")}-2b-system-question")
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            Thread.sleep(200)
        }
        throw AssertionError("The system permission dialog did not show")
    }
}
