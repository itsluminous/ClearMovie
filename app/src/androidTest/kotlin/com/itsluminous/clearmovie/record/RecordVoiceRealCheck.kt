package com.itsluminous.clearmovie.record

import android.Manifest
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.record.MicSources
import com.itsluminous.clearmovie.di.RecordModule
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.validate
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_STOP_TAG
import com.itsluminous.clearmovie.ui.editor.RECORD_OPTION
import com.itsluminous.clearmovie.ui.editor.VoiceRecordingController
import com.itsluminous.clearmovie.ui.editor.insertOptionTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowAddTag
import com.itsluminous.clearmovie.work.ExportOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The by-hand sync check for Record voice (cm-record-voice), run only with
 * `-e cm.recordsync true`: a video made from the clips in the app's external
 * files folder under `realmedia/demo/`, a commentary recorded from the
 * playhead at 3 s through the editor itself, and the export. The emulator has
 * no microphone to speak into, so a [FakeMic] "speaks" a click at each
 * moment the preview shows a mark (5 s and 8 s of the video), found from the
 * preview's own clock while it plays. The export lands in `render-tests/`
 * with `record-sync-marks.txt`, for ffmpeg to measure where the clicks are.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RecordVoiceRealCheck {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()

    @After
    fun tearDown() {
        RecordModule.testMics = null
        VoiceRecordingController.onSample = null
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

    @Test
    fun commentaryOverDemoClipsExportsInSync() {
        assumeTrue("by hand only", InstrumentationRegistry.getArguments().getString("cm.recordsync") == "true")
        h.setUp()
        val demo =
            File(checkNotNull(h.context.getExternalFilesDir(null)), "realmedia/demo")
                .listFiles { f ->
                    f.name.endsWith(".mp4")
                }?.sorted()
                .orEmpty()
        assumeTrue("put the demo clips in realmedia/demo first", demo.isNotEmpty())
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            h.context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        val id = ProjectId(RandomIds.next())
        val media =
            h.app.appFiles
                .projectMediaDir(id)
                .apply { mkdirs() }
        // A clip whose length Android can't read is left out (it would not be taken in by the app either).
        val clips =
            demo.mapIndexedNotNull { i, f ->
                val name = "demo$i.mp4"
                f.copyTo(File(media, name), overwrite = true)
                val ms =
                    android.media.MediaMetadataRetriever().use { r ->
                        r.setDataSource(f.path)
                        requireNotNull(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                    }
                Log.i(ExportHarness.TAG, "Demo clip ${f.name}: $ms ms")
                if (ms < Limits.MIN_CLIP_MS) return@mapIndexedNotNull null
                VideoClip(ClipId(RandomIds.next()), MediaRef.Copied(name), sourceDurationMs = ms)
            }
        val voice = TrackId("voice")
        val project = ProjectFactory.blank(id).addToMain(clips).addRow(TrackType.AUDIO, AudioRole.VOICE, id = voice)
        assertThat(project.validate()).isEmpty()
        assertThat(runBlocking { h.app.projectRepository.createDraft(project, inEditor = true) }).isTrue()
        val totalMs = clips.sumOf { it.durationMs }

        // A slow microphone (120 ms from sound to app), so the clicks can still be placed after the preview is read.
        val mic = FakeMic(latencyNs = 120_000_000)
        RecordModule.testMics = MicSources { mic }
        val marksUs = listOf(5_000_000L, 8_000_000L)
        val clicked = mutableListOf<Long>()
        var last: Pair<Long, Long>? = null
        VoiceRecordingController.onSample = { wall, pos ->
            val before = last
            last = wall to pos
            if (before != null) {
                for (m in marksUs) {
                    if (m !in clicked && before.second < m && pos >= m && pos > before.second) {
                        // The moment the preview showed the mark, between the two readings.
                        val at = before.first + (wall - before.first) * (m - before.second) / (pos - before.second)
                        mic.clicksAtNs += at
                        clicked += m
                        Log.i(ExportHarness.TAG, "Mark ${m / 1000} ms shown at $at ns (read at $wall ns)")
                    }
                }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(hasTestTag(PREVIEW_TAG))
            val seek = hasContentDescription(h.context.getString(R.string.editor_seek))
            waitFor(seek)
            // The playhead to 3 s.
            compose.onNode(seek).performSemanticsAction(SemanticsActions.SetProgress) { it(START_MS.toFloat() / totalMs) }
            Thread.sleep(1_000)
            compose.onNodeWithTag(rowAddTag(voice.value)).performClick()
            waitFor(hasTestTag(insertOptionTag(RECORD_OPTION)))
            compose.onNodeWithTag(insertOptionTag(RECORD_OPTION)).performClick()
            waitFor(hasTestTag(RECORDING_STOP_TAG), 20_000)
            compose.waitUntil(30_000) { clicked.size == marksUs.size }
            Thread.sleep(1_000)
            compose.onNodeWithTag(RECORDING_STOP_TAG).performClick()
            compose.waitUntil(20_000) { runBlocking { h.app.projectRepository.read(id) }?.track(voice)?.clips?.isNotEmpty() == true }
        }
        val stored = requireNotNull(runBlocking { h.app.projectRepository.read(id) })
        val clip = stored.requireTrack(voice).clips.single() as AudioClip
        val outcome = runBlocking { h.pipeline().run(id, ExportQuality.SMALL, "Record sync") {} }
        val done = (outcome as ExportOutcome.Done).done
        h.exported(done.uri)
        val kept = h.keep(done.uri, "record-sync-export.mp4")
        val note = "clip_start_ms=${clip.startMs} clip_ms=${clip.durationMs} marks_ms=${marksUs.map { it / 1000 }} export=${kept.name}"
        Log.i(ExportHarness.TAG, note)
        File(RenderHarness.outDir, "record-sync-marks.txt").writeText(note + "\n")
    }

    private companion object {
        const val START_MS = 3_000L
    }
}
