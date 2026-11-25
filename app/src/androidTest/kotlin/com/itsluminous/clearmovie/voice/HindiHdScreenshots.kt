package com.itsluminous.clearmovie.voice

import android.app.UiAutomation
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Process
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.voice.VoicePackStore
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VoiceRecordingFiles
import com.itsluminous.clearmovie.domain.model.addRecording
import com.itsluminous.clearmovie.domain.model.addRow
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.recordingClip
import com.itsluminous.clearmovie.domain.model.rename
import com.itsluminous.clearmovie.domain.voice.HindiModel
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.ui.editor.PREVIEW_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_CHOICE_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_LANGUAGE_TAG
import com.itsluminous.clearmovie.ui.editor.VOICE_PACK_DIALOG_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.rowEndPlusTag
import com.itsluminous.clearmovie.ui.settings.SETTINGS_HINDI_HD_DOWNLOAD_TAG
import com.itsluminous.clearmovie.ui.settings.SETTINGS_HINDI_HD_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Hindi HD's screens (cm-hindi-voices), by hand only (`-e hdshots <prefix>`),
 * without the pack: the voice menu with the two new voices, the pack dialog
 * for Hindi words (with its Hindi HD line), and Settings > Storage > Hindi HD
 * for this phone's memory. With `-e hddownload true` it also taps Settings'
 * Download and takes the download's progress and how it ends. Needs
 * `hdtest/hi-read-1.m4a` under the app's external files. Pictures land in
 * `screens/`.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class HindiHdScreenshots {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val args get() = InstrumentationRegistry.getArguments()
    private val external: File get() = checkNotNull(h.context.getExternalFilesDir(null))
    private val voiceRow = TrackId("voice")

    private fun asset(name: String) =
        requireNotNull(
            h.context.assets.open(name).bufferedReader().use {
                VoicePackManifest.parse(it.readText())
            },
        )

    @After
    fun tearDown() = h.cleanUp()

    private fun shot(name: String) {
        Thread.sleep(900)
        val dir = File(external, "screens").apply { mkdirs() }
        FileOutputStream(
            File(dir, "$name.png"),
        ).use { checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(
        matcher: SemanticsMatcher,
        ms: Long = 15_000,
    ) = compose.waitUntil(ms) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun project(recording: File) {
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
        val project =
            ProjectFactory
                .blank(id)
                .addToMain(listOf(h.photo(20_000)))
                .addRow(TrackType.AUDIO, AudioRole.VOICE, id = voiceRow)
                .addRecording(clip, voiceRow)
                .rename("Shaadi")
        assertThat(runBlocking { h.app.projectRepository.createDraft(project, inEditor = true) }).isTrue()
    }

    @Test
    fun screens() {
        val prefix = args.getString("hdshots")
        assumeTrue("screenshots run only when asked", prefix != null)
        h.setUp()
        val manifest = asset(VoicePackStore.MANIFEST_ASSET) + asset(VoicePackStore.HINDI_HD_MANIFEST_ASSET)
        val abi = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).first { manifest.supports(it) }
        // No download left over from an earlier run: the dialog would show its progress instead of asking.
        androidx.work.WorkManager
            .getInstance(
                h.context,
            ).cancelUniqueWork(com.itsluminous.clearmovie.work.VoicePackDownloadWorker.UNIQUE_NAME)
            .result
            .get()
        Thread.sleep(3_000)
        VoicePackStore(h.context.filesDir, manifest, abi, HindiModel.SMALL) { 0 }.remove()
        project(File(external, "hdtest/hi-read-1.m4a"))
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(hasTestTag(PREVIEW_TAG), 30_000)
            Thread.sleep(1_500)
            if (compose.onAllNodes(hasTestTag(TIMELINE_ROWS_TOGGLE_TAG)).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).performClick()
            }
            val note = h.context.getString(R.string.editor_brought_back)
            compose.waitUntil(30_000) { compose.onAllNodes(hasText(note)).fetchSemanticsNodes().isEmpty() }
            waitFor(hasTestTag(rowEndPlusTag(voiceRow.value)))
            compose.onNodeWithTag(rowEndPlusTag(voiceRow.value)).performClick()
            waitFor(hasTestTag(VOICE_CHOICE_TAG))
            // 1. The voice menu, with the two new Indian voices.
            compose.onNodeWithTag(VOICE_CHOICE_TAG).performClick()
            waitFor(hasTestTag("${VOICE_CHOICE_TAG}_indian_female_2"))
            shot("$prefix-1-menu")
            compose.onNodeWithTag("${VOICE_CHOICE_TAG}_indian_male").performClick()
            // The pack dialog for English words first: Not now, then Spoken in Hindi.
            waitFor(hasTestTag(VOICE_PACK_DIALOG_TAG))
            compose.onNodeWithText(h.context.getString(R.string.media_access_not_now)).performClick()
            compose.onNodeWithTag(VOICE_LANGUAGE_TAG).performClick()
            waitFor(hasTestTag("${VOICE_LANGUAGE_TAG}_hindi"))
            compose.onNodeWithTag("${VOICE_LANGUAGE_TAG}_hindi").performClick()
            // 2. A new voice for Hindi words: the dialog lists Hindi HD with its size.
            compose.onNodeWithTag(VOICE_CHOICE_TAG).performClick()
            waitFor(hasTestTag("${VOICE_CHOICE_TAG}_indian_female_2"))
            compose.onNodeWithTag("${VOICE_CHOICE_TAG}_indian_female_2").performClick()
            waitFor(hasTestTag(VOICE_PACK_DIALOG_TAG))
            shot("$prefix-2-pack-hindi-hd")
            compose.onNodeWithText(h.context.getString(R.string.media_access_not_now)).performClick()
        }
        // 3. Settings > Storage > Hindi HD, for this phone's memory.
        h.cleanUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = h.context.getString(R.string.action_settings)
            compose.waitUntil(30_000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasContentDescription(settings),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription(settings).performClick()
            waitFor(hasTestTag(SETTINGS_HINDI_HD_TAG))
            compose.onNodeWithTag(SETTINGS_HINDI_HD_TAG).performScrollTo()
            shot("$prefix-3-settings")
            if (args.getString("hddownload") == "true" &&
                compose.onAllNodes(hasTestTag(SETTINGS_HINDI_HD_DOWNLOAD_TAG)).fetchSemanticsNodes().isNotEmpty()
            ) {
                compose.onNodeWithTag(SETTINGS_HINDI_HD_DOWNLOAD_TAG).performClick()
                // Waits through Compose, so the picture is the row as it is now (a plain sleep leaves the frame stale):
                // the download's Cancel must show.
                val cancel = h.context.getString(R.string.action_cancel)
                compose.waitUntil(30_000) { compose.onAllNodes(hasText(cancel)).fetchSemanticsNodes().isNotEmpty() }
                Thread.sleep(4_000)
                compose.waitForIdle()
                shot("$prefix-4-settings-downloading")
                // It ends with Hindi HD added, or with what stopped it (and Try again).
                compose.waitUntil(1_800_000) {
                    compose.onAllNodes(hasTestTag(SETTINGS_HINDI_HD_DOWNLOAD_TAG)).fetchSemanticsNodes().isNotEmpty() ||
                        compose
                            .onAllNodes(
                                hasText(h.context.getString(R.string.settings_hindi_hd_added)),
                            ).fetchSemanticsNodes()
                            .isNotEmpty()
                }
                compose.onNodeWithTag(SETTINGS_HINDI_HD_TAG).performScrollTo()
                shot("$prefix-5-settings-after")
            }
        }
    }
}
