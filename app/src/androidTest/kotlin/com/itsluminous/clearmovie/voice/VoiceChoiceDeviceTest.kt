package com.itsluminous.clearmovie.voice

import android.app.UiAutomation
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.data.record.VoiceRecordings
import com.itsluminous.clearmovie.data.voice.PackVoiceTools
import com.itsluminous.clearmovie.data.voice.VoicePackStore
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoiceOver
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.model.VoiceRecordingFiles
import com.itsluminous.clearmovie.domain.model.WordsSource
import com.itsluminous.clearmovie.domain.model.addRecording
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.playedMedia
import com.itsluminous.clearmovie.domain.model.recordingClip
import com.itsluminous.clearmovie.domain.model.rename
import com.itsluminous.clearmovie.domain.model.setVoiceOver
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.TYPE_WORDS_OPTION
import com.itsluminous.clearmovie.ui.editor.VOICE_CHOICE_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_MAKE_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_PACK_DIALOG_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_PACK_DOWNLOAD_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_TYPED_TAG
import com.itsluminous.clearmovie.ui.editor.barActionTag
import com.itsluminous.clearmovie.ui.editor.insertOptionTag
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.clipTag
import com.itsluminous.clearmovie.ui.editor.timeline.rowEndPlusTag
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
 * The voice choice on a device (cm-voice-choice), by hand only, because it
 * needs the voice pack's files (about 200 MB, never in the repo's tests):
 * put the pack's zips under the app's external files in `voicepack/` and
 * a person's recordings as `voicetest/recording-en.m4a` and
 * `recording-hi.m4a`, then run with `-e cm.voicepack true`. The pack is
 * added through the app's own import, the recordings' words are found on
 * the phone, and a video is exported with the recording in each voice and
 * with "No change"; the outputs land in `render-tests/` and the report in
 * the log (tag VoiceCheck). `-e voiceshots <prefix>` takes the screenshots
 * (in `screens/`).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VoiceChoiceDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val args get() = InstrumentationRegistry.getArguments()
    private val external: File get() = checkNotNull(h.context.getExternalFilesDir(null))

    /** Where the pack's zips and the recordings are: the app's external files, or `-e cm.voicedir <dir>`. */
    private val inputs: File get() = args.getString("cm.voicedir")?.let(::File) ?: external
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

    @After
    fun tearDown() {
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        h.cleanUp()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        RenderHarness.report("voice-check $line")
    }

    /** Adds the pack through the app's own import, from the files put in `voicepack/`. */
    private fun addPack() {
        val dir = File(inputs, "voicepack")
        val files = manifest.needed(abi, SpokenLanguage.ENGLISH) + manifest.needed(abi, SpokenLanguage.HINDI)
        val sources = files.distinct().map { f -> { File(dir, f.file).takeIf { it.isFile }?.inputStream() } }
        val t0 = System.nanoTime()
        val result = tools.addPackFiles(sources) {}
        log(
            "pack added=${result.added.map {
                it.file
            }} rejected=${result.rejected} ms=${(System.nanoTime() - t0) / 1_000_000} onDisk=${store.bytesOnDisk()}",
        )
        assertThat(tools.missing(SpokenLanguage.ENGLISH)).isEmpty()
        assertThat(tools.missing(SpokenLanguage.HINDI)).isEmpty()
    }

    /** A 20 s video (one photo) with [recording] on a voice row from 1 s, stored as a draft. */
    private fun projectWith(
        recording: File,
        name: String,
        inEditor: Boolean = false,
        voiceOver: (Project, ProjectId, AudioClip) -> VoiceOver? = { _, _, _ -> null },
    ): Pair<Project, AudioClip> {
        val id = ProjectId(RandomIds.next())
        val ref =
            MediaRef.Copied(
                VoiceRecordingFiles.nameFor(
                    RandomIds
                        .next()
                        .filter { it.isLetterOrDigit() }
                        .lowercase()
                        .take(16)
                        .padEnd(8, 'a'),
                ),
            )
        val file = h.app.appFiles.copiedFile(id, ref)
        file.parentFile?.mkdirs()
        recording.copyTo(file, overwrite = true)
        val ms =
            MediaMetadataRetriever().use {
                it.setDataSource(file.path)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            }
        val clip = requireNotNull(recordingClip(ClipId("rec"), ref, startMs = 1_000, recordedMs = ms, videoEndMs = 20_000))
        var project =
            ProjectFactory
                .blank(id)
                .addToMain(listOf(h.photo(20_000)))
                .addRow(TrackType.AUDIO, AudioRole.VOICE, id = voiceRow)
                .addRecording(clip, voiceRow)
                .rename(name)
        voiceOver(project, id, clip)?.let { project = project.setVoiceOver(clip.id, it) }
        assertThat(runBlocking { h.app.projectRepository.createDraft(project, inEditor = inEditor) }).isTrue()
        return project to (project.clip(clip.id) as AudioClip)
    }

    private fun export(
        project: Project,
        file: String,
    ): File {
        val outcome = runBlocking { h.pipeline().run(project.id, ExportQuality.SMALL, project.name ?: file) {} }
        val done = (outcome as ExportOutcome.Done).done
        h.exported(done.uri)
        return h.keep(done.uri, file)
    }

    @Test
    fun exportsTheRecordingInEachVoice() {
        assumeTrue("needs the voice pack's files, by hand only", args.getString("cm.voicepack") == "true")
        h.setUp()
        addPack()
        for ((lang, file) in listOf(SpokenLanguage.ENGLISH to "recording-en.m4a", SpokenLanguage.HINDI to "recording-hi.m4a")) {
            val recording = File(inputs, "voicetest/$file")
            assumeTrue("needs $recording", recording.isFile)
            val code = lang.id
            // The words, found on the phone.
            val (plain, clip) = projectWith(recording, "voice-$code-none")
            val t0 = System.nanoTime()
            val phrases = tools.recognize(plain.id, clip.media as MediaRef.Copied, lang)
            val heard = phrases.joinToString(" | ") { "${it.startMs}:${it.text}" }
            log("$code recognized ms=${(System.nanoTime() - t0) / 1_000_000} recordingMs=${clip.sourceDurationMs} phrases=$heard")
            assertThat(phrases).isNotEmpty()
            val none = export(plain, "voice-$code-none.mp4")
            log("$code none file=${none.name} bytes=${none.length()}")
            for (voice in VoiceChoice.forLanguage(lang).filter { it != VoiceChoice.NONE }) {
                var spokenMs = 0L
                val (voiced, voicedClip) =
                    projectWith(recording, "voice-$code-${voice.id}") { _, id, c ->
                        val s0 = System.nanoTime()
                        val made = tools.speak(id, phrases, voice, lang, c.sourceDurationMs) {}
                        spokenMs = (System.nanoTime() - s0) / 1_000_000
                        log(
                            "$code ${voice.id} spoken ms=$spokenMs lengthMs=${made.lengthMs} file=${made.media.relPath} " +
                                "byRule=${made.byRule} starts=${made.phrases.map { it.startMs }}",
                        )
                        VoiceOver(voice, lang, WordsSource.SPOKEN, made.phrases, made.media)
                    }
                assertThat(voicedClip.voiceOver?.generated).isNotNull()
                assertThat(voicedClip.playedMedia).isNotEqualTo(voicedClip.media)
                val out = export(voiced, "voice-$code-${voice.id}.mp4")
                log("$code ${voice.id} file=${out.name} bytes=${out.length()}")
            }
        }
        // Typed words, in English and in Hindi.
        for ((lang, voice, text) in listOf(
            Triple(
                SpokenLanguage.ENGLISH,
                VoiceChoice.BRITISH_FEMALE,
                "Welcome Priya and Arjun to the Diwali party. Please take your seats.",
            ),
            Triple(SpokenLanguage.HINDI, VoiceChoice.INDIAN_MALE, "दिवाली की पार्टी में आपका स्वागत है। कृपया अपनी सीट पर बैठिए।"),
        )) {
            val id = ProjectId(RandomIds.next())
            val made =
                tools.speak(
                    id,
                    listOf(VoicePhrase(0, text)).flatMap {
                        com.itsluminous.clearmovie.domain.voice.Phrases
                            .fromTyped(it.text)
                    },
                    voice,
                    lang,
                    null,
                ) {}
            log("typed ${lang.id} ${voice.id} lengthMs=${made.lengthMs} starts=${made.phrases.map { it.startMs }} byRule=${made.byRule}")
            val clip =
                requireNotNull(recordingClip(ClipId("typed"), made.media, 1_000, made.lengthMs, 20_000))
                    .copy(voiceOver = VoiceOver(voice, lang, WordsSource.TYPED, made.phrases))
            val project =
                ProjectFactory
                    .blank(id)
                    .addToMain(listOf(h.photo(20_000)))
                    .addRow(TrackType.AUDIO, AudioRole.VOICE, id = voiceRow)
                    .addRecording(clip, voiceRow)
                    .rename("voice-typed-${lang.id}")
            assertThat(runBlocking { h.app.projectRepository.createDraft(project, inEditor = false) }).isTrue()
            val out = export(project, "voice-typed-${lang.id}-${voice.id}.mp4")
            log("typed ${lang.id} file=${out.name} bytes=${out.length()}")
        }
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

    private fun showRows() {
        try {
            waitFor(hasTestTag(PREVIEW_TAG), 30_000)
        } catch (e: Throwable) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog(TAG) }
            throw e
        }
        Thread.sleep(1_500)
        if (compose.onAllNodes(hasTestTag(TIMELINE_ROWS_TOGGLE_TAG)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).performClick()
            Thread.sleep(1_000)
        }
    }

    @Test
    fun screenshots() {
        val prefix = args.getString("voiceshots")
        assumeTrue("screenshots run only when asked", prefix != null)
        h.setUp()
        store.remove()
        val recording = File(inputs, "voicetest/recording-en.m4a")
        // 1. Without the pack: the sheet, the open menu, and the voice pack dialog.
        projectWith(recording, "Annual day", inEditor = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            showRows()
            waitFor(hasTestTag(rowEndPlusTag(voiceRow.value)))
            compose.onNodeWithTag(rowEndPlusTag(voiceRow.value)).performClick()
            waitFor(hasTestTag(VOICE_CHOICE_TAG))
            shot("$prefix-1-sheet")
            compose.onNodeWithTag(VOICE_CHOICE_TAG).performClick()
            waitFor(hasTestTag("${VOICE_CHOICE_TAG}_british_male"))
            shot("$prefix-2-menu")
            compose.onNodeWithTag("${VOICE_CHOICE_TAG}_british_male").performClick()
            waitFor(hasTestTag(VOICE_PACK_DIALOG_TAG))
            shot("$prefix-3-pack-needed")
            compose.onNodeWithTag(VOICE_PACK_DOWNLOAD_TAG).performClick()
            Thread.sleep(2_000)
            // The page opens in a browser when there is one; come back to the app.
            ActivityScenario.launch(MainActivity::class.java).close()
            Thread.sleep(500)
            shot("$prefix-4-pack-steps")
        }
        if (!File(inputs, "voicepack").isDirectory) return
        // 2. With the pack: the sheet's Spoken in and Type the words, the typed panel, and a recording's words to check.
        addPack()
        h.cleanUp()
        h.setUp()
        val (withWords, clip) =
            projectWith(recording, "Annual day", inEditor = true) { _, id, c ->
                VoiceOver(
                    VoiceChoice.BRITISH_MALE,
                    SpokenLanguage.ENGLISH,
                    WordsSource.SPOKEN,
                    tools.recognize(id, c.media as MediaRef.Copied, SpokenLanguage.ENGLISH),
                )
            }
        ActivityScenario.launch(MainActivity::class.java).use {
            showRows()
            waitFor(hasTestTag(clipTag(clip.id)))
            compose.onNodeWithTag(clipTag(clip.id)).performClick()
            waitFor(hasTestTag(barActionTag(EditAction.CHANGE_VOICE)))
            shot("$prefix-5-bar")
            compose.onNodeWithTag(barActionTag(EditAction.CHANGE_VOICE)).performClick()
            waitFor(hasTestTag(VOICE_MAKE_TAG))
            shot("$prefix-6-check-words")
            compose.onNodeWithTag(VOICE_MAKE_TAG).performScrollTo().performClick()
            compose.waitUntil(180_000) {
                (runBlocking { h.app.projectRepository.read(withWords.id) }?.clip(clip.id) as? AudioClip)?.voiceOver?.generated != null
            }
            // The panel closes once the voice is made; the item stays selected.
            Thread.sleep(1_500)
            shot("$prefix-7-voiced")
        }
        // A fresh editor (no item selected, so the row's end "+" shows): the sheet with a voice chosen, and Type the words.
        ActivityScenario.launch(MainActivity::class.java).use {
            showRows()
            waitFor(hasTestTag(rowEndPlusTag(voiceRow.value)))
            compose.onNodeWithTag(rowEndPlusTag(voiceRow.value)).performClick()
            waitFor(hasTestTag(VOICE_CHOICE_TAG))
            compose.onNodeWithTag(VOICE_CHOICE_TAG).performClick()
            waitFor(hasTestTag("${VOICE_CHOICE_TAG}_indian_female"))
            compose.onNodeWithTag("${VOICE_CHOICE_TAG}_indian_female").performClick()
            waitFor(hasTestTag(insertOptionTag(TYPE_WORDS_OPTION)))
            shot("$prefix-8-sheet-with-voice")
            compose.onNodeWithTag(insertOptionTag(TYPE_WORDS_OPTION)).performScrollTo().performClick()
            waitFor(hasTestTag(VOICE_TYPED_TAG))
            compose.onNodeWithTag(VOICE_TYPED_TAG).performTextInput("Welcome everyone to the annual day.")
            shot("$prefix-9-type-words")
        }
    }

    companion object {
        const val TAG = "VoiceCheck"
    }
}
