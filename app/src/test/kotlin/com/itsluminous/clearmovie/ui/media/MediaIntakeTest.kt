package com.itsluminous.clearmovie.ui.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaResolver
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeOtherAppProvider
import com.itsluminous.clearmovie.testing.FakeUriGrants
import com.itsluminous.clearmovie.testing.fileDatabase
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.InputStream

/**
 * ROADMAP cm-storage: "partial access copies, and the 1 GB budget prompt
 * appears", end to end: real links through MediaAccess, the prompt on screen,
 * Copy anyway, progress, and Cancel leaving no file.
 */
@RunWith(RobolectricTestRunner::class)
class MediaIntakeTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private lateinit var chat: FakeOtherAppProvider
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var intake: MediaIntake
    private val project = ProjectId("p1")

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        chat = Robolectric.setupContentProvider(FakeOtherAppProvider::class.java, CHAT)
        db = fileDatabase(context, File(tmp.root, "db"))
        files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
        val gateway = MediaStoreGateway(context)
        val grants = FakeUriGrants()
        val media =
            MediaAccess(
                context.contentResolver,
                MediaResolver(gateway, grants, context.packageName),
                gateway,
                grants,
                MediaCopier(Dispatchers.IO, bufferBytes = 64 * 1024),
                files,
                db,
                freeBytes = { 50_000_000_000L },
                dispatcher = Dispatchers.IO,
            )
        intake = MediaIntake(media, scope)
        compose.setContent {
            val state by intake.state.collectAsStateWithLifecycle()
            ClearMovieTheme(dynamicColor = false) {
                MediaIntakeDialogs(state, intake::copyAnyway, intake::chooseFewer, intake::allowAll, intake::cancel)
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Three shared videos, 400 MB each as the chat app reports them (1.2 GB in all). */
    private fun shareVideos(bytesEach: Int = 4 * 1024 * 1024): List<Uri> =
        (1..3).map { n ->
            val uri = Uri.parse("content://$CHAT/v$n")
            chat.add(FakeMediaProvider.Row(uri, "VID_$n.mp4", 400_000_000, "video/mp4"))
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { Zeros(bytesEach) }
            uri
        }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(15_000) { condition() }
        compose.waitForIdle()
    }

    @Test
    fun `under partial access the 1 GB prompt appears, and Copy anyway copies with progress`() {
        intake.start(project, shareVideos(), MediaAccessState.Partial)
        waitFor { intake.state.value is IntakeState.Confirm }
        val size =
            android.text.format.Formatter
                .formatShortFileSize(context, 1_200_000_000)
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.copy_budget_body, 3, 3, size)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.media_access_allow_all)).assertIsDisplayed()

        compose.onNodeWithText(context.getString(R.string.copy_budget_copy_anyway)).assertIsDisplayed().performClick()
        waitFor { intake.state.value is IntakeState.Done }

        val items = (intake.state.value as IntakeState.Done).items
        assertThat(items.map { it.ref }.all { it is MediaRef.Copied }).isTrue()
        assertThat(files.projectMediaDir(project).listFiles()!!).hasLength(3)
    }

    @Test
    fun `a double tap on Copy anyway copies once`() {
        intake.start(project, shareVideos(), MediaAccessState.Denied)
        waitFor { intake.state.value is IntakeState.Confirm }
        compose.runOnUiThread {
            intake.copyAnyway()
            intake.copyAnyway()
        }
        waitFor { intake.state.value is IntakeState.Done }
        assertThat(files.projectMediaDir(project).listFiles()!!).hasLength(3)
    }

    @Test
    fun `cancelling a finished intake keeps its result`() {
        intake.start(project, shareVideos(), MediaAccessState.Denied)
        waitFor { intake.state.value is IntakeState.Confirm }
        intake.copyAnyway()
        waitFor { intake.state.value is IntakeState.Done }
        intake.cancel()
        Thread.sleep(200)
        assertThat(intake.state.value).isInstanceOf(IntakeState.Done::class.java)
        assertThat(files.projectMediaDir(project).listFiles()!!).hasLength(3)
    }

    @Test
    fun `Choose fewer copies nothing`() {
        intake.start(project, shareVideos(), MediaAccessState.Denied)
        waitFor { intake.state.value is IntakeState.Confirm }
        compose.onNodeWithText(context.getString(R.string.copy_budget_choose_fewer)).assertIsDisplayed().performClick()
        waitFor { intake.state.value == IntakeState.Cancelled }
        assertThat(files.projectMediaDir(project).listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `Cancel during the copy leaves no file`() {
        intake.start(project, shareVideos(bytesEach = 200 * 1024 * 1024), MediaAccessState.Denied)
        waitFor { intake.state.value is IntakeState.Confirm }
        compose.onNodeWithText(context.getString(R.string.copy_budget_copy_anyway)).assertIsDisplayed().performClick()
        waitFor { (intake.state.value as? IntakeState.Copying)?.copiedBytes?.let { it > 0 } == true }
        compose.onNodeWithText(context.getString(R.string.copy_progress_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        waitFor { intake.state.value == IntakeState.Cancelled }
        assertThat(files.projectMediaDir(project).listFiles().orEmpty()).isEmpty()
    }

    private class Zeros(
        private val size: Int,
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos++ < size) 0 else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (pos >= size) return -1
            val n = minOf(len, size - pos)
            java.util.Arrays.fill(b, off, off + n, 0)
            pos += n
            if (size > 50_000_000) Thread.sleep(2)
            return n
        }
    }

    private companion object {
        const val CHAT = "com.example.chat.files"
    }
}
