package com.itsluminous.clearmovie.record

import android.Manifest
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.content.ContextCompat
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
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VoiceRecordingFiles
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.ui.editor.MIC_CONTINUE_TAG
import com.itsluminous.clearmovie.ui.editor.MIC_OFF_TAG
import com.itsluminous.clearmovie.ui.editor.MIC_RATIONALE_TAG
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_PANEL_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_STOP_TAG
import com.itsluminous.clearmovie.ui.editor.RECORD_OPTION
import com.itsluminous.clearmovie.ui.editor.insertOptionTag
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_MAIN_ROW_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.rowAddTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Record voice on a voice row, in the app (cm-record-voice): with the
 * microphone refused for good the app says where to allow it and records
 * nothing; allowed, a short recording goes on the voice row at the
 * playhead, as one undo step. The emulator has nothing to speak into, so
 * the recording's sound comes from a [FakeMic].
 *
 * The refusal runs first (the method names keep the order): a permission
 * granted later in the run can't be taken back without ending the test.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecordVoiceDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val voiceRow = TrackId("voice")
    private lateinit var project: Project

    @Before
    fun setUp() {
        h.setUp()
        val ids = RandomIds
        project =
            ProjectFactory
                .blank(ProjectId(RandomIds.next()), ids)
                .addToMain(listOf(h.photo(8_000)))
                .addRow(TrackType.AUDIO, AudioRole.VOICE, id = voiceRow)
        assertThat(runBlocking { h.app.projectRepository.createDraft(project, inEditor = true) }).isTrue()
    }

    @After
    fun tearDown() {
        RecordModule.testMics = null
        h.cleanUp()
    }

    private fun waitFor(
        matcher: SemanticsMatcher,
        timeoutMs: Long = TIMEOUT_MS,
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

    private fun openRecordVoice() {
        waitFor(hasTestTag(PREVIEW_TAG), LAUNCH_TIMEOUT_MS)
        // On a small screen (CI's is 320x640) the voice row is folded away under "Show all rows": open them.
        waitFor(hasTestTag(TIMELINE_MAIN_ROW_TAG))
        Thread.sleep(500)
        if (compose.onAllNodes(hasTestTag(rowAddTag(voiceRow.value))).fetchSemanticsNodes().isEmpty()) {
            waitFor(hasTestTag(TIMELINE_ROWS_TOGGLE_TAG))
            compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).performClick()
        }
        waitFor(hasTestTag(rowAddTag(voiceRow.value)))
        // Its semantics action, not a touch: the "+" may sit under the floating preview on a small screen.
        compose.onNodeWithTag(rowAddTag(voiceRow.value)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(hasTestTag(insertOptionTag(RECORD_OPTION)))
        compose.onNodeWithTag(insertOptionTag(RECORD_OPTION)).performScrollTo().performClick()
    }

    /** Taps the system permission dialog's refusal ("Don't allow", or on Android 10 its "don't ask again" form the second time). */
    private fun refuseInSystemDialog() {
        val ids =
            listOf(
                "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button",
                "com.android.permissioncontroller:id/permission_deny_dont_ask_again_button",
                "com.android.permissioncontroller:id/permission_deny_button",
            )
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            val root = automation.rootInActiveWindow
            val button: AccessibilityNodeInfo? =
                root?.let { r ->
                    ids.firstNotNullOfOrNull { id -> r.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser } }
                }
            if (button != null) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            Thread.sleep(200)
        }
        throw AssertionError("The system permission dialog did not show")
    }

    @Test
    fun a_refusedMicrophoneRecordsNothingAndSaysWhereToAllowIt() {
        val context = h.context
        // A permission granted earlier in this run can't be taken back without ending the process.
        org.junit.Assume.assumeTrue(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED,
        )
        val mic = FakeMic()
        RecordModule.testMics = MicSources { mic }
        ActivityScenario.launch(MainActivity::class.java).use {
            var blocked = false
            // Android asks at most twice; the second refusal (or "don't ask again") is for good.
            repeat(2) {
                if (blocked) return@repeat
                openRecordVoice()
                waitFor(hasTestTag(MIC_RATIONALE_TAG))
                compose.onNodeWithTag(MIC_CONTINUE_TAG).performClick()
                refuseInSystemDialog()
                compose.waitUntil(TIMEOUT_MS) {
                    runCatching { compose.onAllNodes(hasTestTag(MIC_OFF_TAG)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) ||
                        runCatching {
                            compose
                                .onAllNodes(
                                    hasTestTag(insertOptionTag(RECORD_OPTION)),
                                ).fetchSemanticsNodes()
                                .isEmpty()
                        }.getOrDefault(false)
                }
                blocked = compose.onAllNodes(hasTestTag(MIC_OFF_TAG)).fetchSemanticsNodes().isNotEmpty()
            }
            assertThat(blocked).isTrue()
            assertThat(compose.onAllNodes(hasTestTag(RECORDING_PANEL_TAG)).fetchSemanticsNodes()).isEmpty()
            assertThat(mic.started).isFalse()
        }
        val stored = runBlocking { h.app.projectRepository.read(project.id) }!!
        assertThat(stored.requireTrack(voiceRow).clips).isEmpty()
    }

    @Test
    fun b_aShortRecordingLandsOnItsVoiceRowAtThePlayheadAsOneUndoStep() {
        automation.grantRuntimePermission(h.context.packageName, Manifest.permission.RECORD_AUDIO)
        val mic = FakeMic()
        RecordModule.testMics = MicSources { mic }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openRecordVoice()
            // The countdown, then Stop once it is recording.
            waitFor(hasTestTag(RECORDING_PANEL_TAG))
            // The screen is kept on from the countdown to the end of the recording (cm-keep-screen-on): already
            // during the countdown, while the preview is still paused and Stop is not shown yet.
            var duringCountdown = false
            compose.waitUntil(COUNTDOWN_TIMEOUT_MS) {
                val stopShown = compose.onAllNodes(hasTestTag(RECORDING_STOP_TAG)).fetchSemanticsNodes().isNotEmpty()
                if (!stopShown && screenKeptOn(scenario)) duringCountdown = true
                duringCountdown || stopShown
            }
            assertThat(duringCountdown).isTrue()
            waitFor(hasTestTag(RECORDING_STOP_TAG), COUNTDOWN_TIMEOUT_MS)
            assertThat(mic.started).isTrue()
            assertThat(screenKeptOn(scenario)).isTrue()
            Thread.sleep(RECORD_MS)
            compose.onNodeWithTag(RECORDING_STOP_TAG).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(TIMEOUT_MS) { clipOnVoiceRow() != null }
            // Saved, the preview paused: the screen may turn off again.
            compose.waitUntil(TIMEOUT_MS) { !screenKeptOn(scenario) }
            val clip = clipOnVoiceRow()!!
            Log.i(ExportHarness.TAG, "Recorded ${clip.durationMs} ms at ${clip.startMs} ms: ${clip.media}")
            assertThat(clip.startMs).isEqualTo(0)
            assertThat(clip.durationMs).isAtLeast(RECORD_MS - 500)
            assertThat(clip.durationMs).isAtMost(RECORD_MS + 1_500)
            val ref = clip.media as MediaRef.Copied
            assertThat(VoiceRecordingFiles.isRecording(ref.relPath)).isTrue()
            assertThat(
                h.app.appFiles
                    .copiedFile(project.id, ref)
                    .length(),
            ).isGreaterThan(1_000L)
            assertThat(mic.released).isTrue()

            // One Undo takes it away.
            waitFor(hasContentDescription(h.context.getString(R.string.action_undo)))
            compose.onNode(hasContentDescription(h.context.getString(R.string.action_undo))).performClick()
            compose.waitUntil(TIMEOUT_MS) { clipOnVoiceRow() == null }
        }
    }

    @Test
    fun c_turningThePhoneWhileRecordingKeepsTheRecording() {
        automation.grantRuntimePermission(h.context.packageName, Manifest.permission.RECORD_AUDIO)
        val mic = FakeMic()
        RecordModule.testMics = MicSources { mic }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openRecordVoice()
                waitFor(hasTestTag(RECORDING_STOP_TAG), COUNTDOWN_TIMEOUT_MS)
                var before: MainActivity? = null
                scenario.onActivity { before = it }
                automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                compose.waitUntil(TIMEOUT_MS) {
                    var now: MainActivity? = null
                    runCatching { scenario.onActivity { now = it } }
                    now != null && now !== before
                }
                // The new activity shows the same recording, still running.
                waitFor(hasTestTag(RECORDING_STOP_TAG))
                assertThat(mic.released).isFalse()
                // It goes on recording in the new activity.
                Thread.sleep(RECORD_MS)
                assertThat(mic.released).isFalse()
                compose.onNodeWithTag(RECORDING_STOP_TAG).performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(TIMEOUT_MS) { clipOnVoiceRow() != null }
                assertThat(clipOnVoiceRow()!!.durationMs).isAtLeast(RECORD_MS)
            }
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun screenKeptOn(scenario: ActivityScenario<MainActivity>): Boolean {
        var on = false
        scenario.onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    private fun clipOnVoiceRow(): AudioClip? =
        runBlocking { h.app.projectRepository.read(project.id) }
            ?.track(voiceRow)
            ?.clips
            ?.firstOrNull() as? AudioClip

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val LAUNCH_TIMEOUT_MS = 60_000L
        const val COUNTDOWN_TIMEOUT_MS = 15_000L
        const val RECORD_MS = 2_000L
    }
}
