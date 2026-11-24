package com.itsluminous.clearmovie

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.export.ExportHarness
import com.itsluminous.clearmovie.ui.editor.DELETE_BUSY_DIALOG_TAG
import com.itsluminous.clearmovie.ui.editor.DELETE_CANCEL_TAG
import com.itsluminous.clearmovie.ui.editor.DELETE_CONFIRM_TAG
import com.itsluminous.clearmovie.ui.editor.DELETE_DIALOG_TAG
import com.itsluminous.clearmovie.ui.editor.EDITOR_EXPORT_TAG
import com.itsluminous.clearmovie.ui.editor.EDITOR_MENU_DELETE_TAG
import com.itsluminous.clearmovie.ui.editor.EDITOR_MORE_TAG
import com.itsluminous.clearmovie.ui.home.HOME_LIST_TAG
import com.itsluminous.clearmovie.work.ExportOutcome
import com.itsluminous.clearmovie.work.ExportWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * More options > Delete video on a phone (cm-delete-project): the menu item,
 * the question, Cancel, then Delete, which goes Home with the video gone from
 * My videos. A video exported from it before is still in the gallery, byte
 * for byte, and still plays; the photos and videos it used, the preset's
 * files and another video stay too; the app starts at Home afterwards. While
 * the video is being saved, Delete says to wait and removes nothing.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class DeleteProjectDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val h = ExportHarness()
    private val repository get() = h.app.projectRepository

    @Before
    fun setUp() {
        h.setUp()
        h.bindPreset()
    }

    @After
    fun tearDown() = h.cleanUp()

    /** Waits for [matcher]; a moment when Compose is busy (the preview's players) counts as "not yet". */
    private fun waitFor(
        matcher: SemanticsMatcher,
        timeoutMs: Long = TIMEOUT_MS,
    ) = compose.waitUntil(timeoutMs) { notYetIfBusy { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }

    private fun gone(matcher: SemanticsMatcher) =
        compose.waitUntil(TIMEOUT_MS) { notYetIfBusy { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() } }

    private fun notYetIfBusy(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (e: ComposeNotIdleException) {
            false
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("No compose hierarchies found") != true) throw e
            false
        }

    private fun md5(uri: Uri): String =
        h.context.contentResolver.openInputStream(uri)!!.use { input ->
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    /** A picture from the middle of the gallery video: it still plays. */
    private fun frameOf(uri: Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(h.context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            retriever.getFrameAtTime(ms * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) != null
        } finally {
            retriever.release()
        }
    }

    /** The files a preset slot is bound to, by name and hash: deleting a video must change none of them. */
    private fun presetFiles(): Map<String, String> =
        h.app.appFiles
            .presetDir(h.presetId)
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .associate { it.name to it.readBytes().contentHashCode().toString() }

    /** Exports [project] at Small with the real pipeline: the video a user saved to the gallery earlier. */
    private fun exportFirst(project: Project): Uri {
        val outcome = runBlocking { h.pipeline().run(project.id, ExportQuality.SMALL, "Delete me") {} }
        assertWithMessage("export: $outcome").that(outcome).isInstanceOf(ExportOutcome.Done::class.java)
        return h.exported((outcome as ExportOutcome.Done).done.uri)
    }

    /** Whether [project] is still in My videos. */
    private fun stored(project: Project): Boolean = runBlocking { repository.projectList().any { it.id == project.id } }

    private fun openInEditor(project: Project) {
        runBlocking { repository.open(project.id, inEditor = true) }
    }

    private fun deleteFromMenu() {
        // The preview loaded and stopped, so Compose goes idle for the clicks.
        waitFor(hasTestTag(EDITOR_EXPORT_TAG), LAUNCH_TIMEOUT_MS)
        waitFor(hasTestTag(EDITOR_MORE_TAG).and(isEnabled()), LAUNCH_TIMEOUT_MS)
        compose.onNodeWithTag(EDITOR_MORE_TAG).performClick()
        waitFor(hasTestTag(EDITOR_MENU_DELETE_TAG))
        compose.onNodeWithTag(EDITOR_MENU_DELETE_TAG).performClick()
    }

    @Test
    fun deletingAVideoGoesHomeAndKeepsItsSavedMovieItsMediaAndItsPreset() {
        val keep = h.project(listOf(h.photo(1500)), "Keep me", caption = null)
        val project = h.project(listOf(h.photo(1500), h.photo(1500, "fixtures/photo_blue.png")), "Delete me")
        val movie = exportFirst(project)
        val movieMd5 = md5(movie)
        val movieProbe = h.probe(movie)
        Log.i(ExportHarness.TAG, "Before the delete: $movie md5 $movieMd5 $movieProbe")
        val presetBefore = presetFiles()
        assertThat(presetBefore).isNotEmpty()
        val itemsUsed = project.mainClips.mapNotNull { (it as? PhotoClip)?.media }
        // A copy the app made for this video, in its own folder: that one goes.
        val ownCopy =
            File(
                h.app.appFiles
                    .projectMediaDir(project.id)
                    .apply { mkdirs() },
                "own_copy.jpg",
            ).apply { writeText("x") }
        openInEditor(project)

        ActivityScenario.launch(MainActivity::class.java).use {
            // Cancel changes nothing.
            deleteFromMenu()
            waitFor(hasTestTag(DELETE_DIALOG_TAG))
            compose.onNodeWithTag(DELETE_CANCEL_TAG).performClick()
            gone(hasTestTag(DELETE_DIALOG_TAG))
            assertThat(stored(project)).isTrue()

            deleteFromMenu()
            waitFor(hasTestTag(DELETE_DIALOG_TAG))
            compose.onNodeWithTag(DELETE_CONFIRM_TAG).performClick()
            waitFor(hasTestTag(HOME_LIST_TAG), LAUNCH_TIMEOUT_MS)

            // Home: the other video is listed, the deleted one is not.
            compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText("Keep me"))
            waitFor(hasText("Keep me"))
            assertThat(compose.onAllNodes(hasText("Delete me")).fetchSemanticsNodes()).isEmpty()
        }

        // Only the project went: its row and its own folder.
        assertThat(stored(project)).isFalse()
        assertThat(ownCopy.exists()).isFalse()
        assertThat(
            h.app.appFiles
                .projectDir(project.id)
                .exists(),
        ).isFalse()
        assertThat(runBlocking { repository.projectList() }.map { it.id }).containsExactly(keep.id)
        // The movie saved earlier is still in the gallery, the same bytes, and plays.
        assertThat(md5(movie)).isEqualTo(movieMd5)
        assertThat(h.probe(movie)).isEqualTo(movieProbe)
        assertThat(frameOf(movie)).isTrue()
        Log.i(ExportHarness.TAG, "After the delete: $movie md5 ${md5(movie)} ${h.probe(movie)}")
        // The photos it used and the preset's files are untouched.
        for (ref in itemsUsed) {
            val uri = Uri.parse((ref as MediaRef.Store).uri)
            h.context.contentResolver
                .openInputStream(uri)!!
                .use { assertThat(it.read()).isAtLeast(0) }
        }
        assertThat(presetFiles()).isEqualTo(presetBefore)

        // Opened again, the app starts at Home, not on the deleted video.
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(hasTestTag(HOME_LIST_TAG), LAUNCH_TIMEOUT_MS)
            assertThat(compose.onAllNodes(hasTestTag(EDITOR_EXPORT_TAG)).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun whileTheVideoIsBeingSavedDeleteSaysToWaitAndRemovesNothing() {
        val project = h.project(h.typicalItems(), "Saving", caption = null)
        openInEditor(project)
        val workManager = WorkManager.getInstance(h.context)
        val id = ExportWorker.enqueue(workManager, project.id, ExportQuality.BEST, "Saving", replace = true)
        compose.waitUntil(TIMEOUT_MS) { workManager.getWorkInfoById(id).get()?.state == WorkInfo.State.RUNNING }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                deleteFromMenu()
                waitFor(hasTestTag(DELETE_BUSY_DIALOG_TAG).or(hasTestTag(DELETE_DIALOG_TAG)), LAUNCH_TIMEOUT_MS)
                val busy = compose.onAllNodes(hasTestTag(DELETE_BUSY_DIALOG_TAG)).fetchSemanticsNodes().isNotEmpty()
                val state = workManager.getWorkInfoById(id).get()?.state
                Log.i(ExportHarness.TAG, "Delete during the export: busy=$busy, export $state")
                if (busy) {
                    compose.onNodeWithTag(DELETE_BUSY_DIALOG_TAG).assertExists()
                } else {
                    // Only when the export had already ended before Delete was tapped.
                    assertThat(state?.isFinished).isTrue()
                }
                assertThat(stored(project)).isTrue()
            }
        } finally {
            workManager.cancelWorkById(id).result.get()
            try {
                compose.waitUntil(TIMEOUT_MS) {
                    workManager
                        .getWorkInfoById(id)
                        .get()
                        ?.state
                        ?.isFinished != false
                }
            } catch (e: ComposeTimeoutException) {
                Log.w(ExportHarness.TAG, "The export did not end in time", e)
            }
            workManager
                .getWorkInfoById(id)
                .get()
                ?.outputData
                ?.getString(ExportWorker.KEY_URI)
                ?.let { h.exported(Uri.parse(it)) }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val LAUNCH_TIMEOUT_MS = 60_000L
    }
}
