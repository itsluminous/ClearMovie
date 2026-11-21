package com.itsluminous.clearmovie

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaPermissions
import com.itsluminous.clearmovie.data.media.MediaStatus
import com.itsluminous.clearmovie.data.media.ShareLinks
import com.itsluminous.clearmovie.data.preset.SlotBindResult
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.share.SharingAppProvider
import com.itsluminous.clearmovie.ui.editor.EDITOR_TIMELINE_TAG
import com.itsluminous.clearmovie.ui.home.HOME_LIST_TAG
import com.itsluminous.clearmovie.ui.home.SHARE_HEADER_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Share from another app on a device (ROADMAP cm-share): real `SEND` and
 * `SEND_MULTIPLE` intents with `content://` links and read grants, sent the
 * way a gallery sends them (a PendingIntent the sharing app owns, from its
 * own process and uid, see [SharingAppProvider]). Covers one item and many,
 * photos and videos in capture order, a PDF in the mix, a share with no
 * grant and one whose grant is taken back afterwards, odd and hostile
 * intents (no extras, `file://`, text only, a huge count), a share while a
 * video is open (in the foreground and in the background), a recreation on
 * the share's preset pick, and a preset's intro and ending or its repair.
 */
@RunWith(AndroidJUnit4::class)
class ShareDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = ApplicationProvider.getApplicationContext<ClearMovieApplication>()
    private val testAssets = instrumentation.context.assets
    private val inserted = mutableListOf<Uri>()
    private val shared = mutableListOf<String>()

    private fun s(id: Int) = context.getString(id)

    private fun q(
        id: Int,
        n: Int,
    ) = context.resources.getQuantityString(id, n, n)

    @Before
    fun setUp() {
        val automation = instrumentation.uiAutomation
        MediaPermissions.toRequest(Build.VERSION.SDK_INT).forEach { automation.grantRuntimePermission(context.packageName, it) }
        runBlocking {
            app.settingsRepository.setMediaAccessAsked()
            // Start from Home with no videos: a video left open would (rightly) make every share ask first.
            app.projectRepository.close()
            app.projectRepository.projectList().forEach { app.projectRepository.delete(it.id) }
        }
    }

    @After
    fun tearDown() {
        finishAll()
        runBlocking {
            app.projectRepository.close()
            app.projectRepository.projectList().forEach { app.projectRepository.delete(it.id) }
        }
        if (shared.isNotEmpty()) providerCall(SharingAppProvider.METHOD_REVOKE, shared)
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    /** Every live activity of the app; call on the main thread. */
    private fun liveActivities(): List<Activity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
            .flatMap { monitor.getActivitiesInStage(it) }
    }

    private fun activities(): List<Activity> {
        var all = emptyList<Activity>()
        instrumentation.runOnMainSync { all = liveActivities() }
        return all
    }

    private fun mainActivities() = activities().filterIsInstance<MainActivity>()

    private fun resumedCount(): Int {
        var n = 0
        instrumentation.runOnMainSync { n = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).size }
        return n
    }

    private fun finishAll() {
        instrumentation.runOnMainSync { liveActivities().forEach { it.finish() } }
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (activities().isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    private fun providerCall(
        method: String,
        names: List<String>,
        extras: android.os.Bundle.() -> Unit = {},
    ): android.os.Bundle? =
        context.contentResolver.call(
            Uri.parse("content://${SharingAppProvider.AUTHORITY}"),
            method,
            null,
            android.os.Bundle().apply {
                putStringArrayList(SharingAppProvider.KEY_NAMES, ArrayList(names))
                putString(SharingAppProvider.KEY_TARGET, context.packageName)
                extras()
            },
        )

    /** The sharing app shares [names] (its files) with ClearMovie, as a gallery does. */
    private fun shareFromOtherApp(
        names: List<String>,
        action: String = if (names.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
        grant: Boolean = true,
    ) {
        shared += names
        val result =
            providerCall(SharingAppProvider.METHOD_SHARE, names) {
                putString(SharingAppProvider.KEY_ACTION, action)
                putBoolean(SharingAppProvider.KEY_GRANT, grant)
            }
        val pending =
            requireNotNull(
                result?.let {
                    IntentCompat.getParcelableExtra(Intent().putExtras(it), SharingAppProvider.KEY_PENDING, PendingIntent::class.java)
                },
            )
        val options =
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                ActivityOptions
                    .makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    ).toBundle()
            } else {
                null
            }
        pending.send(context, 0, null, null, null, null, options)
    }

    /** An intent ClearMovie's share entry receives from anyone: built by hand, as a hostile app would. */
    private fun sendRaw(intent: Intent) {
        context.startActivity(
            intent.setClassName(context, "com.itsluminous.clearmovie.ShareActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun waitFor(
        matcher: SemanticsMatcher,
        count: Int = 1,
    ) = compose.waitUntil(
        TIMEOUT_MS,
    ) { runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().size >= count }.getOrDefault(false) }

    private fun waitForProject(predicate: (Project) -> Boolean): Project {
        var found: Project? = null
        compose.waitUntil(TIMEOUT_MS) {
            found =
                app.projectRepository.open.value
                    ?.takeIf(predicate)
            found != null
        }
        return found!!
    }

    /**
     * Taps [text] once its window takes input: a window still animating in
     * (the app brought to the front by a share) refuses injected touches.
     */
    private fun tap(text: String) {
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            try {
                compose.onNodeWithText(text).performClick()
                return
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > until || e.message?.contains("inject") != true) throw e
                Thread.sleep(200)
            }
        }
    }

    private fun readyHeader(items: Int) = waitFor(hasText(q(R.plurals.share_ready, items)))

    private fun startWithoutPreset() {
        compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(s(R.string.home_start_blank)))
        tap(s(R.string.home_start_blank))
        waitFor(hasTestTag(EDITOR_TIMELINE_TAG))
    }

    private fun Project.items() = mainClips.filter { it is PhotoClip || it is VideoClip }

    private fun Project.names(): List<String> =
        items().map { clip ->
            val ref = (clip as? PhotoClip)?.media ?: (clip as VideoClip).media
            (ref as MediaRef.Copied).relPath.substringAfter('_')
        }

    @Test
    fun aSinglePhotoGoesStraightToTheEditorAndOutlivesItsGrant() {
        shareFromOtherApp(listOf("n1~photo_blue.png"))
        readyHeader(1)
        startWithoutPreset()

        val project = waitForProject { it.items().size == 1 }
        assertThat(project.presetId).isNull()
        // Share grants cannot be kept, so the photo was copied while the grant lasted: it opens after the grant is gone,
        // as it will after a reboot or days later.
        providerCall(SharingAppProvider.METHOD_REVOKE, shared)
        val ref = (project.items().single() as PhotoClip).media
        assertThat(ref).isInstanceOf(MediaRef.Copied::class.java)
        assertThat(app.mediaAccess.status(project.id, ref, MediaAccessState.Full)).isEqualTo(MediaStatus.AVAILABLE)
        assertThat(app.appFiles.copiedFile(project.id, ref as MediaRef.Copied).length()).isGreaterThan(0L)
    }

    @Test
    fun aMixedShareGoesInTheOrderTakenAndSkipsThePdf() {
        shareFromOtherApp(listOf("t300~photo_quadrants.png", "t100~clip_light.mp4", "doc.pdf", "t200~photo_blue.png"))
        readyHeader(3)
        waitFor(hasText(q(R.plurals.share_skipped_not_media, 1)))
        startWithoutPreset()

        val project = waitForProject { it.items().size == 3 }
        assertThat(project.items().map { it::class }).containsExactly(VideoClip::class, PhotoClip::class, PhotoClip::class).inOrder()
        assertThat(project.names()).containsExactly("t100_clip_light.mp4", "t200_photo_blue.png", "t300_photo_quadrants.png").inOrder()
    }

    @Test
    fun aShareWithoutAGrantIsDroppedNeverReadWithTheAppsOwnAccess() {
        // The sender gives no read grant: ClearMovie never opens the links with its own access, and starts no video.
        shareFromOtherApp(listOf("n1~photo_blue.png", "n2~clip_dark.mp4"), grant = false)
        Thread.sleep(3_000)
        assertThat(mainActivities()).isEmpty()
        assertThat(runBlocking { app.projectRepository.projectList() }).isEmpty()
        assertThat(
            app.appFiles.projectsRoot
                .walk()
                .filter { it.isFile }
                .toList(),
        ).isEmpty()
    }

    @Test
    fun aGrantTakenBackBeforeTheItemsAreTakenInIsReportedNotCrashedOn() {
        // The many-items notice holds the intake; meanwhile the sharing app takes its grants back.
        val names = (1..101).map { "g$it~photo_blue.png" }
        shareFromOtherApp(names)
        waitFor(hasText(s(R.string.share_many_title)))
        providerCall(SharingAppProvider.METHOD_REVOKE, names)
        tap(s(R.string.share_continue))
        waitFor(hasText(s(R.string.share_nothing_title)))
        compose.onNodeWithText(q(R.plurals.share_skipped_unreadable, 101)).assertExists()
        tap(s(R.string.action_ok))
        waitFor(hasText(s(R.string.home_new_video)))
        assertThat(runBlocking { app.projectRepository.projectList() }).isEmpty()
    }

    @Test
    fun theShareTargetIsOfferedForPhotosAndVideosOnlyAndMainActivityIgnoresADirectSend() {
        val pm = context.packageManager

        fun offered(
            action: String,
            type: String,
        ) = pm.queryIntentActivities(Intent(action).setType(type).setPackage(context.packageName), 0).map { it.activityInfo.name }
        val share = "com.itsluminous.clearmovie.ShareActivity"
        assertThat(offered(Intent.ACTION_SEND, "image/jpeg")).containsExactly(share)
        assertThat(offered(Intent.ACTION_SEND, "video/mp4")).containsExactly(share)
        assertThat(offered(Intent.ACTION_SEND_MULTIPLE, "image/*")).containsExactly(share)
        assertThat(offered(Intent.ACTION_SEND, "application/pdf")).isEmpty()
        assertThat(offered(Intent.ACTION_SEND, "text/plain")).isEmpty()

        // MainActivity is exported (the launcher entry), but takes shares only from ShareActivity.
        val photo = insert("fixtures/photo_blue.png", "image/png")
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setClass(context, MainActivity::class.java)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, photo)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitFor(hasText(s(R.string.home_new_video)))
        // Nor a made-up hand-off: the internal action with links but no token from this process.
        context.startActivity(
            Intent(ShareLinks.ACTION_SHARED)
                .setClass(context, MainActivity::class.java)
                .putExtra(com.itsluminous.clearmovie.data.media.ShareHandoff.EXTRA_TOKEN, "made-up")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(1_500)
        compose.onNodeWithTag(SHARE_HEADER_TAG).assertDoesNotExist()
        assertThat(runBlocking { app.projectRepository.projectList() }).isEmpty()
    }

    @Test
    fun aPhotosOwnExifDateIsReadWhenTheProviderGivesNone() {
        val project =
            com.itsluminous.clearmovie.domain.model
                .ProjectId("exif-check")
        val ref = MediaRef.Copied("exif_test.jpg")
        val file = app.appFiles.copiedFile(project, ref).apply { parentFile?.mkdirs() }
        try {
            file.outputStream().use { out ->
                android.graphics.Bitmap
                    .createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            androidx.exifinterface.media.ExifInterface(file.path).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:14 09:00:00")
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+05:30")
                saveAttributes()
            }
            val reader =
                com.itsluminous.clearmovie.data.media
                    .AndroidCaptureTimeReader(context, app.appFiles)
            val info =
                com.itsluminous.clearmovie.data.media
                    .SourceInfo("exif_test.jpg", file.length(), "image/jpeg", null)
            val taken =
                runBlocking {
                    reader.takenAt(
                        project,
                        com.itsluminous.clearmovie.data.media
                            .IntakeItem(Uri.EMPTY, ref, info),
                    )
                }
            assertThat(taken).isEqualTo(1_794_627_000_000L)
            // A video with no creation time in it has no date: it keeps its shared place.
            val video = MediaRef.Copied("nodate.mp4")
            app.appFiles.copiedFile(project, video).outputStream().use { out ->
                testAssets.open("fixtures/clip_light.mp4").use { it.copyTo(out) }
            }
            val videoInfo =
                com.itsluminous.clearmovie.data.media
                    .SourceInfo("nodate.mp4", null, "video/mp4", null)
            assertThat(
                runBlocking {
                    reader.takenAt(
                        project,
                        com.itsluminous.clearmovie.data.media
                            .IntakeItem(Uri.EMPTY, video, videoInfo),
                    )
                },
            ).isNull()
        } finally {
            app.appFiles.projectDir(project).deleteRecursively()
        }
    }

    @Test
    fun oddAndHostileIntentsAreReportedNeverCrashOrReadPrivateFiles() {
        // No extras at all.
        sendRaw(Intent(Intent.ACTION_SEND).setType("image/*"))
        waitFor(hasText(s(R.string.share_nothing_title)))
        tap(s(R.string.action_ok))

        // A file:// link to ClearMovie's own database, and a link to its own provider.
        val db = context.getDatabasePath("clearmovie.db")
        sendRaw(
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(Uri.fromFile(db), Uri.parse("content://${context.packageName}.androidx-startup/x")),
            ),
        )
        waitFor(hasText(s(R.string.share_nothing_title)))
        compose.onNodeWithText(q(R.plurals.share_skipped_unreadable, 2)).assertExists()
        tap(s(R.string.action_ok))

        // Only text: not a photo or video.
        shareFromOtherApp(listOf("note.txt"))
        waitFor(hasText(q(R.plurals.share_skipped_not_media, 1)))
        tap(s(R.string.action_ok))

        // A list of other parcelables where the links should be (an unchecked cast on Android 10 to 12).
        sendRaw(
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<android.os.Parcelable>(Intent(), android.os.Bundle())),
        )
        waitFor(hasText(s(R.string.share_nothing_title)))
        tap(s(R.string.action_ok))

        // An extra of the wrong type.
        sendRaw(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, "content://media/external/video/media/1"))
        waitFor(hasText(s(R.string.share_nothing_title)))
        tap(s(R.string.action_ok))

        // A huge share: the length notice comes first, and Cancel leaves nothing behind.
        shareFromOtherApp((1..150).map { "n$it~photo_blue.png" })
        waitFor(hasText(s(R.string.share_many_title)))
        tap(s(R.string.action_cancel))
        waitFor(hasText(s(R.string.home_new_video)))

        assertThat(runBlocking { app.projectRepository.projectList() }).isEmpty()
        assertThat(
            File(app.appFiles.projectsRoot, "").listFiles().orEmpty().filter { it.isDirectory && it.walk().any(File::isFile) },
        ).isEmpty()
        assertThat(mainActivities()).hasSize(1)
    }

    @Test
    fun aShareWhileAVideoIsOpenAsksAndAddPutsTheItemsOnItWithoutASecondApp() {
        shareFromOtherApp(listOf("t100~photo_blue.png"))
        readyHeader(1)
        startWithoutPreset()
        val open = waitForProject { it.items().size == 1 }

        shareFromOtherApp(listOf("t50~photo_quadrants.png", "t60~clip_light.mp4"))
        waitFor(hasText(s(R.string.share_add_title)))
        assertThat(mainActivities()).hasSize(1)
        tap(s(R.string.share_add_here))

        val project = waitForProject { it.id == open.id && it.items().size == 3 }
        // The new items go after the ones already there, in the order they were taken.
        assertThat(project.names()).containsExactly("t100_photo_blue.png", "t50_photo_quadrants.png", "t60_clip_light.mp4").inOrder()
        waitFor(hasContentDescription(s(R.string.editor_item_video), substring = true))
        assertThat(mainActivities()).hasSize(1)
    }

    @Test
    fun aShareWhileClearMovieIsInTheBackgroundReachesItAndStartANewVideoKeepsTheOpenOne() {
        shareFromOtherApp(listOf("n1~photo_blue.png"))
        readyHeader(1)
        startWithoutPreset()
        val open = waitForProject { it.items().size == 1 }
        instrumentation.runOnMainSync { liveActivities().filterIsInstance<MainActivity>().single().moveTaskToBack(true) }
        compose.waitUntil(TIMEOUT_MS) { resumedCount() == 0 }

        shareFromOtherApp(listOf("n2~clip_dark.mp4"))
        waitFor(hasText(s(R.string.share_add_title)))
        assertThat(mainActivities()).hasSize(1)
        tap(s(R.string.share_start_new))
        readyHeader(1)
        startWithoutPreset()

        val fresh = waitForProject { it.id != open.id && it.items().size == 1 }
        assertThat(fresh.items().single()).isInstanceOf(VideoClip::class.java)
        assertThat(runBlocking { app.projectRepository.projectList() }.map { it.id }).containsExactly(open.id, fresh.id)
    }

    @Test
    fun recreatingOnTheSharesPresetPickKeepsTheItemsAndTakesNothingTwice() {
        shareFromOtherApp(listOf("t1~photo_blue.png", "t2~clip_light.mp4"))
        readyHeader(2)
        waitFor(hasTestTag(SHARE_HEADER_TAG))
        instrumentation.runOnMainSync { liveActivities().filterIsInstance<MainActivity>().single().recreate() }
        readyHeader(2)
        assertThat(runBlocking { app.projectRepository.projectList() }).hasSize(1)
        startWithoutPreset()
        assertThat(waitForProject { it.items().size == 2 }.names()).containsExactly("t1_photo_blue.png", "t2_clip_light.mp4").inOrder()
    }

    @Test
    fun activityScenarioRecreateOnThePickAndInTheEditorTakesTheShareOnce() {
        // The share as ShareActivity hands it on, started straight into a recreatable scenario.
        val items = arrayListOf(insert("fixtures/photo_quadrants.png", "image/png"), insert("fixtures/clip_dark.mp4", "video/mp4"))
        val token =
            com.itsluminous.clearmovie.data.media.ShareHandoff
                .put(
                    com.itsluminous.clearmovie.data.media
                        .SharedLinks(items),
                )
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(ShareLinks.ACTION_SHARED)
                .putExtra(com.itsluminous.clearmovie.data.media.ShareHandoff.EXTRA_TOKEN, token)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            readyHeader(2)
            scenario.recreate()
            readyHeader(2)
            assertThat(runBlocking { app.projectRepository.projectList() }).hasSize(1)
            startWithoutPreset()
            waitForProject { it.items().size == 2 }

            scenario.recreate()
            waitFor(hasTestTag(EDITOR_TIMELINE_TAG))
            // Not taken a second time from the activity's intent.
            assertThat(runBlocking { app.projectRepository.projectList() }).hasSize(1)
            assertThat(waitForProject { it.items().size == 2 }.items()).hasSize(2)
        }
    }

    @Test
    fun aReadyPresetBringsItsIntroAndEndingAndAMissingFileOpensRepairFirst() {
        val id = PresetId("event-frame")
        val store = app.presetStore
        runBlocking {
            // A new install has no presets (cm-home-presets): keep the shipped one, as an older install does.
            assertThat(store.adoptShipped(id)).isTrue()
            listOf(
                PresetSlot.INTRO to insert("fixtures/intro.mp4", "video/mp4"),
                PresetSlot.FRAME to insert("fixtures/frame.mp4", "video/mp4"),
                PresetSlot.ENDING to insert("fixtures/speech.m4a", "audio/mp4"),
            ).forEach { (slot, uri) -> assertThat(store.bindSlot(id, slot, uri)).isInstanceOf(SlotBindResult.Bound::class.java) }
        }
        shareFromOtherApp(listOf("t2~photo_quadrants.png", "t1~photo_blue.png"))
        readyHeader(2)
        tap(s(R.string.preset_event_frame_name))
        waitFor(hasTestTag(EDITOR_TIMELINE_TAG))

        val project = waitForProject { it.items().size == 2 && it.presetId == id }
        assertThat(project.main.clips.first()).isInstanceOf(IntroClip::class.java)
        assertThat(project.main.clips.last()).isInstanceOf(EndingClip::class.java)
        assertThat(project.names()).containsExactly("t1_photo_blue.png", "t2_photo_quadrants.png").inOrder()
        finishAll()
        runBlocking { app.projectRepository.close() }

        // The frame file goes missing: the share's preset pick opens repair, not the editor.
        runBlocking { assertThat((store.status(id).slots[PresetSlot.FRAME] as SlotStatus.Ready).binding.file.delete()).isTrue() }
        shareFromOtherApp(listOf("n3~photo_blue.png"))
        readyHeader(1)
        tap(s(R.string.preset_event_frame_name))
        waitFor(hasText(s(R.string.preset_repair_title)))
        compose.onNodeWithText(s(R.string.preset_slot_missing)).assertExists()
    }

    private fun insert(
        asset: String,
        mime: String,
    ): Uri {
        val video = mime.startsWith("video/")
        val audio = mime.startsWith("audio/")
        val collection =
            when {
                video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                audio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        val dir =
            when {
                video -> "Movies/ClearMovieTest"
                audio -> "Music/ClearMovieTest"
                else -> "Pictures/ClearMovieTest"
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "cmshare-${System.nanoTime()}-${asset.substringAfterLast('/')}")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        inserted += uri
        context.contentResolver.openOutputStream(uri)!!.use { out -> testAssets.open(asset).use { it.copyTo(out) } }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L

        init {
            check(ShareLinks.MAX_ITEMS > 150)
        }
    }
}
