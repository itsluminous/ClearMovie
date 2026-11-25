package com.itsluminous.clearmovie.voice

import android.Manifest
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.preset.PresetParts
import com.itsluminous.clearmovie.data.preset.PresetSaveResult
import com.itsluminous.clearmovie.data.preset.SlotBindResult
import com.itsluminous.clearmovie.data.record.MicSources
import com.itsluminous.clearmovie.data.record.VoiceRecordings
import com.itsluminous.clearmovie.data.voice.PackVoiceTools
import com.itsluminous.clearmovie.data.voice.VoicePackStore
import com.itsluminous.clearmovie.di.RecordModule
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.playedMedia
import com.itsluminous.clearmovie.domain.model.rename
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.record.PcmMic
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.RECORDING_STOP_TAG
import com.itsluminous.clearmovie.ui.editor.RECORD_OPTION
import com.itsluminous.clearmovie.ui.editor.VOICE_CHOICE_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_MAKE_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_PACK_DIALOG_TAG
import com.itsluminous.clearmovie.ui.editor.insertOptionTag
import com.itsluminous.clearmovie.ui.editor.labelRes
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.rowAddTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowEndPlusTag
import com.itsluminous.clearmovie.ui.home.presetEditTag
import com.itsluminous.clearmovie.ui.preset.PRESET_FILES_TAG
import com.itsluminous.clearmovie.ui.preset.PRESET_VOICE_CHOICE_TAG
import com.itsluminous.clearmovie.ui.preset.PRESET_VOICE_TAG
import com.itsluminous.clearmovie.work.ExportOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * A preset's default voice on a device (cm-preset-voice-default), by hand
 * only. `-e presetvoiceshots <prefix>` takes the screenshots (Edit preset's
 * Default voice, and a new video's Record voice sheet with it preselected)
 * into `screens/`. `-e cm.voicepack true` records a person's speech through
 * the editor's own Record voice in a video made from a preset with a default
 * voice, and in one made from a preset with No change, and exports both into
 * `render-tests/`: put the pack's zips in `voicepack/` and the speech as
 * `voicetest/recording-en.pcm` (mono 16-bit little-endian, 44.1 kHz) under
 * the app's external files first.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PresetVoiceDefaultDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val args get() = InstrumentationRegistry.getArguments()
    private val external: File get() = checkNotNull(h.context.getExternalFilesDir(null))
    private val voiceRow = TrackId("voice")

    private val manifest: VoicePackManifest by lazy {
        requireNotNull(
            h.context.assets
                .open(VoicePackStore.MANIFEST_ASSET)
                .bufferedReader()
                .use { VoicePackManifest.parse(it.readText()) },
        )
    }
    private val abi: String by lazy {
        (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).first { manifest.supports(it) }
    }
    private val store by lazy { VoicePackStore(h.context.filesDir, manifest, abi) { Long.MAX_VALUE / 4 } }
    private val tools by lazy {
        PackVoiceTools(store, h.app.appFiles, VoiceRecordings(h.app.appFiles, { error("no microphone") }, exportBusy = { false }))
    }

    /** The presets this test made, deleted after it (other presets on the phone are left alone). */
    private val made = mutableListOf<PresetId>()

    @After
    fun tearDown() {
        RecordModule.testMics = null
        h.cleanUp()
        runBlocking { made.forEach { h.app.presetStore.delete(it) } }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        RenderHarness.report("preset-voice $line")
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
        Thread.sleep(900)
        val dir = File(external, "screens").apply { mkdirs() }
        val bitmap = checkNotNull(automation.takeScreenshot())
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A preset the user made, "Annual day", with a frame and [voice] as its default voice. */
    private fun preset(voice: VoiceChoice): PresetId =
        runBlocking {
            val id = requireNotNull(h.app.presetStore.create("Annual day", PresetParts(intro = false, frame = true, ending = null)))
            made += id
            val frame = h.insert("fixtures/frame.mp4", "video/mp4")
            assertThat(h.app.presetStore.bindSlot(id, PresetSlot.FRAME, frame)).isInstanceOf(SlotBindResult.Bound::class.java)
            val stored = requireNotNull(h.app.presetStore.definition(id)).preset
            assertThat(h.app.presetStore.save(stored.copy(voice = voice))).isInstanceOf(PresetSaveResult.Saved::class.java)
            assertThat(
                h.app.presetStore
                    .definition(id)
                    ?.preset
                    ?.voice,
            ).isEqualTo(voice)
            id
        }

    /** A new video from preset [id], as Home makes one: a 20 s photo, and a voice row; open in the editor. */
    private fun videoFrom(
        id: PresetId,
        name: String,
    ): Project =
        runBlocking {
            val preset = requireNotNull(h.app.presetStore.definition(id)).preset
            val project =
                ProjectFactory
                    .fromPreset(ProjectId(RandomIds.next()), preset, h.app.presetStore.presetMedia(id))
                    .addToMain(listOf(h.photo(20_000)))
                    .addRow(TrackType.AUDIO, AudioRole.VOICE, id = voiceRow)
                    .rename(name)
            assertThat(project.defaultVoice).isEqualTo(preset.voice.takeUnless { it == VoiceChoice.NONE })
            assertThat(h.app.projectRepository.createDraft(project, inEditor = true)).isTrue()
            project
        }

    private fun showRows() {
        waitFor(hasTestTag(PREVIEW_TAG), 30_000)
        Thread.sleep(1_500)
        if (compose.onAllNodes(hasTestTag(TIMELINE_ROWS_TOGGLE_TAG)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).performClick()
            Thread.sleep(1_000)
        }
        // A reopened video says "We brought back your last video" once; let it go first.
        val note = h.context.getString(R.string.editor_brought_back)
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(note)).fetchSemanticsNodes().isEmpty() }
    }

    /** Opens the voice row's sheet; its Voice in the video shows [voice]. */
    private fun openSheet(voice: VoiceChoice) {
        // An empty row has its "+" at its start (Add here); one with items has it at its end.
        val plus = hasTestTag(rowAddTag(voiceRow.value)) or hasTestTag(rowEndPlusTag(voiceRow.value))
        waitFor(plus)
        compose.onNode(plus).performClick()
        waitFor(hasTestTag(VOICE_CHOICE_TAG))
        compose.onNodeWithTag(VOICE_CHOICE_TAG).assertTextContains(h.context.getString(voice.labelRes()))
    }

    @Test
    fun screenshots() {
        val prefix = args.getString("presetvoiceshots")
        assumeTrue("screenshots run only when asked", prefix != null)
        h.setUp()
        val id = preset(VoiceChoice.BRITISH_FEMALE)
        // 1. Edit preset: the Default voice card, then its list.
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(hasTestTag(presetEditTag(id)))
            compose.onNodeWithTag(presetEditTag(id)).performClick()
            waitFor(hasTestTag(PRESET_VOICE_TAG))
            // The files card under it into sight too, so the card shows between its neighbours.
            compose.onNodeWithTag(PRESET_FILES_TAG).performScrollTo()
            compose.onNodeWithTag(PRESET_VOICE_CHOICE_TAG).assertTextContains(h.context.getString(R.string.voice_british_female))
            shot("$prefix-1-edit-preset-voice")
            compose.onNodeWithTag(PRESET_VOICE_CHOICE_TAG).performClick()
            waitFor(hasTestTag("${PRESET_VOICE_CHOICE_TAG}_british_female"))
            shot("$prefix-2-edit-preset-voice-menu")
        }
        // 2. A new video from it: the voice row's sheet starts at the preset's voice.
        videoFrom(id, "Annual day")
        ActivityScenario.launch(MainActivity::class.java).use {
            showRows()
            openSheet(VoiceChoice.BRITISH_FEMALE)
            shot("$prefix-3-record-sheet-default")
            compose.onNodeWithTag(insertOptionTag(RECORD_OPTION)).performScrollTo()
            shot("$prefix-3b-record-sheet-default-record")
        }
    }

    /**
     * A recording made with the preset's default voice preselected: the sheet
     * shows it, the words are found and shown to check with that voice, Make
     * voice says them in it, and the export plays it. The same speech in a
     * video from a preset with No change plays as recorded.
     */
    @Test
    fun aRecordingInAVideoFromThePresetIsSaidInItsDefaultVoice() {
        assumeTrue("needs the voice pack's files, by hand only", args.getString("cm.voicepack") == "true")
        val pcm = File(external, "voicetest/recording-en.pcm")
        assumeTrue("needs $pcm", pcm.isFile)
        h.setUp()
        automation.grantRuntimePermission(h.context.packageName, Manifest.permission.RECORD_AUDIO)
        val dir = File(external, "voicepack")
        val sources = manifest.needed(abi, SpokenLanguage.ENGLISH).map { f -> { File(dir, f.file).takeIf { it.isFile }?.inputStream() } }
        val added = tools.addPackFiles(sources) {}
        log("pack added=${added.added.map { it.file }} rejected=${added.rejected}")
        assertThat(tools.missing(SpokenLanguage.ENGLISH)).isEmpty()
        for (voice in listOf(VoiceChoice.BRITISH_FEMALE, VoiceChoice.NONE)) {
            val project = videoFrom(preset(voice), "preset-voice-${voice.id}")
            RecordModule.testMics = MicSources { PcmMic(pcm, leadInMs = LEAD_IN_MS) }
            ActivityScenario.launch(MainActivity::class.java).use {
                showRows()
                openSheet(voice)
                compose.onNodeWithTag(insertOptionTag(RECORD_OPTION)).performScrollTo().performClick()
                waitFor(hasTestTag(RECORDING_STOP_TAG), 20_000)
                Thread.sleep(RECORD_MS)
                compose.onNodeWithTag(RECORDING_STOP_TAG).performClick()
                compose.waitUntil(20_000) { stored(project)?.track(voiceRow)?.clips?.isNotEmpty() == true }
                if (voice != VoiceChoice.NONE) {
                    // The words are found and shown to check, with the preset's voice; the pack is there, so no dialog.
                    waitFor(hasTestTag(VOICE_MAKE_TAG), 120_000)
                    assertThat(compose.onAllNodes(hasTestTag(VOICE_PACK_DIALOG_TAG)).fetchSemanticsNodes()).isEmpty()
                    compose.onNodeWithTag(VOICE_CHOICE_TAG).assertTextContains(h.context.getString(voice.labelRes()))
                    args.getString("presetvoiceshots")?.let { shot("$it-4-check-words-default") }
                    compose.onNodeWithTag(VOICE_MAKE_TAG).performScrollTo().performClick()
                    compose.waitUntil(180_000) { recorded(project)?.voiceOver?.generated != null }
                } else {
                    Thread.sleep(2_000)
                    assertThat(compose.onAllNodes(hasTestTag(VOICE_MAKE_TAG)).fetchSemanticsNodes()).isEmpty()
                }
            }
            val clip = requireNotNull(recorded(project))
            log(
                "${voice.id} clip start=${clip.startMs} ms=${clip.durationMs} media=${clip.media} played=${clip.playedMedia} " +
                    "voice=${clip.voiceOver?.voice?.id} words=${clip.voiceOver?.text}",
            )
            if (voice == VoiceChoice.NONE) {
                assertThat(clip.voiceOver).isNull()
                assertThat(clip.playedMedia).isEqualTo(clip.media)
            } else {
                assertThat(clip.voiceOver?.voice).isEqualTo(voice)
                assertThat(clip.playedMedia).isNotEqualTo(clip.media)
            }
            val outcome = runBlocking { h.pipeline().run(project.id, ExportQuality.SMALL, project.name ?: "preset-voice") {} }
            val done = (outcome as ExportOutcome.Done).done
            h.exported(done.uri)
            val kept = h.keep(done.uri, "preset-voice-${voice.id}.mp4")
            log("${voice.id} file=${kept.name} bytes=${kept.length()}")
        }
    }

    private fun stored(project: Project): Project? = runBlocking { h.app.projectRepository.read(project.id) }

    private fun recorded(project: Project): AudioClip? = stored(project)?.track(voiceRow)?.clips?.singleOrNull() as? AudioClip

    companion object {
        const val TAG = "PresetVoiceCheck"

        /** The speech starts this long after the recording, once the video plays. */
        const val LEAD_IN_MS = 2_500L

        /** The speech is 14.9 s; the recording runs a little past it. */
        const val RECORD_MS = 18_000L
    }
}
