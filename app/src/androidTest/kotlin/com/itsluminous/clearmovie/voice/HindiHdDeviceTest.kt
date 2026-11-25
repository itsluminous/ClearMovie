package com.itsluminous.clearmovie.voice

import android.app.ActivityManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
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
import com.itsluminous.clearmovie.domain.model.recordingClip
import com.itsluminous.clearmovie.domain.model.rename
import com.itsluminous.clearmovie.domain.model.setVoiceOver
import com.itsluminous.clearmovie.domain.voice.HindiModel
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.work.ExportOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Hindi HD on a device (cm-hindi-voices), by hand only, because it needs the
 * pack's files (about 1.6 GB, never in the repo's tests): put the v1 zips and
 * Hindi HD's zip and pieces under the app's external files in `hdpack/`, and
 * people's Hindi recordings in `hdtest/` with `refs.tsv` (file, then the
 * words), then run with `-e cm.hindihd true`. The small recognizer goes in
 * first and finds each recording's words (before); Hindi HD goes in through
 * the app's own import (its pieces), and finds them again (after), light and,
 * with `-e cm.hindimodel full`, with its rescoring files. Each recording is
 * exported voiced with the words found before and after. Outputs land in
 * `render-tests/`, the report in the log (tag HindiHdCheck).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class HindiHdDeviceTest {
    private val h = ExportHarness()
    private val args get() = InstrumentationRegistry.getArguments()
    private val external: File get() = checkNotNull(h.context.getExternalFilesDir(null))
    private val voiceRow = TrackId("voice")

    private fun asset(name: String) =
        requireNotNull(
            h.context.assets.open(name).bufferedReader().use {
                VoicePackManifest.parse(it.readText())
            },
        )

    private val manifest by lazy { asset(VoicePackStore.MANIFEST_ASSET) + asset(VoicePackStore.HINDI_HD_MANIFEST_ASSET) }
    private val abi by lazy {
        (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).first { manifest.supports(it) }
    }
    private val memory by lazy {
        ActivityManager.MemoryInfo().also { h.context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    }
    private val model by lazy {
        if (args.getString("cm.hindimodel") ==
            "full"
        ) {
            HindiModel.FULL
        } else {
            HindiModel.of(memory).coerceAtMost(HindiModel.LIGHT)
        }
    }
    private val store by lazy { VoicePackStore(h.context.filesDir, manifest, abi, model) { Long.MAX_VALUE / 4 } }
    private val tools by lazy {
        PackVoiceTools(store, h.app.appFiles, VoiceRecordings(h.app.appFiles, { error("no microphone") }, exportBusy = { false }))
    }

    @After
    fun tearDown() = h.cleanUp()

    private fun log(line: String) {
        Log.i(TAG, line)
        RenderHarness.report("hindi-hd $line")
    }

    private fun add(files: List<VoicePackFile>) {
        val dir = File(external, "hdpack")
        val sources = files.flatMap { it.downloads }.map { n -> { File(dir, n).takeIf { it.isFile }?.inputStream() } }
        val t0 = System.nanoTime()
        val r = tools.addPackFiles(sources) {}
        log(
            "added=${r.added.map {
                it.file
            }} rejected=${r.rejected} ms=${(System.nanoTime() - t0) / 1_000_000} onDisk=${store.bytesOnDisk()}",
        )
    }

    private fun projectWith(
        recording: File,
        name: String,
        voiceOver: (ProjectId, AudioClip) -> VoiceOver? = { _, _ -> null },
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
        voiceOver(id, clip)?.let { project = project.setVoiceOver(clip.id, it) }
        assertThat(runBlocking { h.app.projectRepository.createDraft(project, inEditor = false) }).isTrue()
        return project to (project.clip(clip.id) as AudioClip)
    }

    private fun export(
        project: Project,
        file: String,
    ): File {
        val done = (runBlocking { h.pipeline().run(project.id, ExportQuality.SMALL, project.name ?: file) {} } as ExportOutcome.Done).done
        h.exported(done.uri)
        return h.keep(done.uri, file)
    }

    private fun heard(
        recording: File,
        label: String,
    ): List<VoicePhrase> {
        val (p, clip) = projectWith(recording, "hd-$label")
        val t0 = System.nanoTime()
        val phrases = tools.recognize(p.id, clip.media as MediaRef.Copied, SpokenLanguage.HINDI)
        log(
            "recognized $label ms=${(System.nanoTime() - t0) / 1_000_000} model=${store.asrDir(SpokenLanguage.HINDI).name} " +
                "nativeHeapMB=${Debug.getNativeHeapSize() / 1_000_000} words=${phrases.joinToString(" | ") { "${it.startMs}:${it.text}" }}",
        )
        return phrases
    }

    private fun voiced(
        recording: File,
        name: String,
        phrases: List<VoicePhrase>,
        voice: VoiceChoice,
    ) {
        val (p, _) =
            projectWith(recording, name) { id, c ->
                val made = tools.speak(id, phrases, voice, SpokenLanguage.HINDI, c.sourceDurationMs) {}
                log("spoken $name voice=${voice.id} starts=${made.phrases.map { it.startMs }}")
                VoiceOver(voice, SpokenLanguage.HINDI, WordsSource.SPOKEN, made.phrases, made.media)
            }
        val out = export(p, "$name.mp4")
        log("exported $name file=${out.name} bytes=${out.length()}")
    }

    @Test
    fun recognizesAndVoicesBeforeAndAfterHindiHd() {
        assumeTrue("needs the packs' files, by hand only", args.getString("cm.hindihd") == "true")
        h.setUp()
        log("memoryBytes=$memory phoneModel=${HindiModel.of(memory)} testModel=$model")
        tools.removePack()
        val dir = File(external, "hdtest")
        val refs = File(dir, "refs.tsv").readLines().filter { it.isNotBlank() }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        // Before: today's pack, with the small Hindi recognizer.
        add(manifest.needed(abi, null) + manifest.hindiSpeech(HindiModel.SMALL))
        assertThat(store.missing(SpokenLanguage.HINDI)).isEmpty()
        val before = refs.associate { (f, _) -> f to heard(File(dir, f), "before-$f") }
        // After: Hindi HD through the app's own import of its pieces.
        add(manifest.hindiSpeech(model))
        assertThat(store.hindiHdMissing()).isEmpty()
        assertThat(store.asrDir(SpokenLanguage.HINDI).path).endsWith(VoicePackFile.HD_MODEL)
        val after = refs.associate { (f, _) -> f to heard(File(dir, f), "after-$f") }
        for ((f, _) in refs.take(args.getString("cm.exports")?.toInt() ?: 2)) {
            val stem = f.substringBeforeLast('.')
            voiced(File(dir, f), "hd-$stem-before-indian_male", before.getValue(f), VoiceChoice.INDIAN_MALE)
            voiced(File(dir, f), "hd-$stem-after-indian_male", after.getValue(f), VoiceChoice.INDIAN_MALE)
            voiced(File(dir, f), "hd-$stem-after-indian_female_2", after.getValue(f), VoiceChoice.INDIAN_FEMALE_2)
            voiced(File(dir, f), "hd-$stem-after-indian_male_2", after.getValue(f), VoiceChoice.INDIAN_MALE_2)
        }
        // Typed words with names and numbers in the two new voices.
        val typed = "राहुल और प्रियंका की शादी 25 दिसंबर 2026 को जयपुर में होगी। 1920 में बना यह महल 1,00,000 लोग देखते हैं।"
        for (voice in listOf(VoiceChoice.INDIAN_FEMALE_2, VoiceChoice.INDIAN_MALE_2)) {
            val id = ProjectId(RandomIds.next())
            val made =
                tools.speak(
                    id,
                    com.itsluminous.clearmovie.domain.voice.Phrases
                        .fromTyped(typed),
                    voice,
                    SpokenLanguage.HINDI,
                    null,
                ) {}
            val keep = File(File(external, "render-tests").apply { mkdirs() }, "hd-typed-${voice.id}.m4a")
            h.app.appFiles
                .copiedFile(id, made.media)
                .copyTo(keep, overwrite = true)
            log("typed voice=${voice.id} lengthMs=${made.lengthMs} file=${keep.name}")
        }
    }

    private companion object {
        const val TAG = "HindiHdCheck"
    }
}
