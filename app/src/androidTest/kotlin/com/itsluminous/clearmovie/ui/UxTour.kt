package com.itsluminous.clearmovie.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.clearmovie.ClearMovieApplication
import com.itsluminous.clearmovie.MainActivity
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.ShareActivity
import com.itsluminous.clearmovie.data.media.CopyBudget
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaPermissions
import com.itsluminous.clearmovie.data.media.MediaStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.addText
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.ui.editor.BAR_TAG
import com.itsluminous.clearmovie.ui.editor.CaptionTab
import com.itsluminous.clearmovie.ui.editor.EDITOR_EXPORT_TAG
import com.itsluminous.clearmovie.ui.editor.EDITOR_TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.PANEL_CANCEL_TAG
import com.itsluminous.clearmovie.ui.editor.PANEL_DONE_TAG
import com.itsluminous.clearmovie.ui.editor.PANEL_TAG
import com.itsluminous.clearmovie.ui.editor.ZOOM_FIT_TAG
import com.itsluminous.clearmovie.ui.editor.ZOOM_IN_TAG
import com.itsluminous.clearmovie.ui.editor.barActionTag
import com.itsluminous.clearmovie.ui.editor.captionTabTag
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_ROWS_TOGGLE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.timeline.clipTag
import com.itsluminous.clearmovie.ui.export.EXPORT_CLOSE_TAG
import com.itsluminous.clearmovie.ui.export.EXPORT_SAVE_TAG
import com.itsluminous.clearmovie.ui.export.ExportBlock
import com.itsluminous.clearmovie.ui.export.ExportSheet
import com.itsluminous.clearmovie.ui.export.ExportSheetActions
import com.itsluminous.clearmovie.ui.export.ExportStep
import com.itsluminous.clearmovie.ui.export.exportQualityTag
import com.itsluminous.clearmovie.ui.home.HOME_LIST_TAG
import com.itsluminous.clearmovie.ui.media.CopyBudgetDialog
import com.itsluminous.clearmovie.ui.media.CopyProgressDialog
import com.itsluminous.clearmovie.ui.media.MediaAccessExplainer
import com.itsluminous.clearmovie.ui.media.MissingMediaTile
import com.itsluminous.clearmovie.ui.media.NotEnoughSpaceDialog
import com.itsluminous.clearmovie.ui.media.PartialAccessBanner
import com.itsluminous.clearmovie.ui.settings.SETTINGS_LIST_TAG
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import com.itsluminous.clearmovie.work.ExportFailure
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The UX review's screenshot tour: drives the real app (Hilt view models,
 * real storage, a real preset and real MediaStore items) through every screen
 * and meaningful state, and saves a full-screen picture of each, dialogs and
 * system bars included, with a manifest line per picture.
 *
 * Runs only when asked: `-e uxtour <config>`, where the config name is the
 * folder the pictures go to (`files/ux/<config>/` in the app's external
 * files). The device settings (size, density, font scale, dark mode, the
 * app's language) are the caller's: `scripts/ux-screens.sh` sets them for each
 * config. `-e uxdyn true` turns Colours from wallpaper on. Media comes from
 * `files/uxmedia/` (photo1-4.jpg, video1-2.mp4, music.m4a, Intro.mp4,
 * Frame-White-20s.mp4, speech_last.mp3) when present, else the fixtures.
 * A state that cannot be reached is logged in the manifest as FAILED and the
 * tour goes on.
 */
@RunWith(AndroidJUnit4::class)
class UxTour {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = ApplicationProvider.getApplicationContext<ClearMovieApplication>()
    private val args = InstrumentationRegistry.getArguments()
    private val config: String? = args.getString("uxtour")
    private val dynamic = args.getString("uxdyn") == "true"
    private val realExport = args.getString("uxexport") == "true"
    private val only: Set<String>? = args.getString("uxonly")?.split(',')?.toSet()
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val inserted = mutableListOf<Uri>()
    private val startedAt = System.currentTimeMillis()
    private val manifest = mutableListOf<String>()
    private lateinit var out: File

    private fun s(id: Int) = context.getString(id)

    private fun media(name: String): File? = context.getExternalFilesDir("uxmedia")?.let { File(it, name) }?.takeIf { it.isFile }

    @After
    fun tearDown() {
        if (config == null) return
        File(out, "manifest.txt").appendText(manifest.joinToString("\n", postfix = "\n"))
        runCatching { Intents.release() }
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        // The video a real export saved during the tour: only ones added since it started.
        runCatching {
            context.contentResolver.delete(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                arrayOf("Movies/ClearMovie%", (startedAt / 1000).toString()),
            )
        }
    }

    private fun prepare(): Boolean {
        if (config == null) return false
        out = File(checkNotNull(context.getExternalFilesDir(null)), "ux/$config").apply { mkdirs() }
        return true
    }

    // ---- pictures ------------------------------------------------------------------------------

    private fun shot(
        name: String,
        what: String,
    ) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        val full: Bitmap = automation.takeScreenshot()
        // Half size: still sharp enough to judge, and a whole run stays small on disk.
        val bitmap = Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true)
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        manifest += "$name.png\t$what"
    }

    /** One state: [reach] gets there, then a picture. A failure is noted and the tour goes on. */
    private fun state(
        name: String,
        what: String,
        reach: () -> Unit,
    ) {
        // A state left out by -e uxonly is still reached (later states start from it), only not pictured.
        val pictured = only == null || only.any { name.startsWith(it) }
        runCatching {
            reach()
            if (pictured) shot(name, what)
        }.onFailure {
            Log.w(TAG, "state $name failed", it)
            manifest += "$name.png\tFAILED: ${it.javaClass.simpleName}: ${it.message?.lineSequence()?.firstOrNull()}"
        }
    }

    // ---- media ---------------------------------------------------------------------------------

    private fun insert(
        name: String,
        fallbackAsset: String,
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
                video -> "Movies/ClearMovieUx"
                audio -> "Music/ClearMovieUx"
                else -> "Pictures/ClearMovieUx"
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "ux-${System.nanoTime()}-$name")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        inserted += uri
        context.contentResolver.openOutputStream(uri)!!.use { sink ->
            val file = media(name)
            if (file != null) file.inputStream().use { it.copyTo(sink) } else testAssets.open(fallbackAsset).use { it.copyTo(sink) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun pickerAnswers(uris: List<Uri>) {
        val data =
            Intent().apply {
                clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        intending(not(isInternal())).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, data))
    }

    private fun pickerCancels() {
        intending(not(isInternal())).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
    }

    // ---- navigation helpers --------------------------------------------------------------------

    private fun waitFor(
        matcher: SemanticsMatcher,
        timeout: Long = TIMEOUT_MS,
    ) = compose.waitUntil(timeout) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun clickText(text: String) {
        waitFor(hasText(text))
        compose.onAllNodes(hasText(text)).onFirst().performClick()
    }

    private fun homeScrollTo(text: String) {
        waitFor(hasTestTag(HOME_LIST_TAG))
        // Home's cards wait for the presets to be read (cm-home-presets).
        compose.waitUntil(TIMEOUT_MS) { runCatching { compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(text)) }.isSuccess }
    }

    private fun back() {
        Espresso.pressBackUnconditionally()
        compose.waitForIdle()
    }

    /** Clicks a bar button, scrolling the bar to it first when it does not fit. */
    private fun bar(action: EditAction) {
        val t = barActionTag(action)
        waitFor(hasTestTag(BAR_TAG))
        // A lazy row composes only the buttons on screen: scroll to it first, then wait for it.
        val scroller = hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG))
        if (exists(scroller)) runCatching { compose.onNode(scroller).performScrollToNode(hasTestTag(t)) }
        waitFor(hasTestTag(t))
        compose.onNodeWithTag(t).performClick()
        compose.waitForIdle()
    }

    /** Scrolls the screen up by most of its height, from its middle (never from a button pinned at the bottom). */
    private fun pageDown() {
        compose.onRoot().performTouchInput { swipe(Offset(centerX, height * 0.7f), Offset(centerX, height * 0.15f), 500) }
        compose.waitForIdle()
    }

    private fun barToEnd() {
        repeat(3) { compose.onNodeWithTag(BAR_TAG).performTouchInput { swipeLeft() } }
    }

    private fun closePanel() {
        when {
            exists(hasTestTag(PANEL_CANCEL_TAG)) -> compose.onNodeWithTag(PANEL_CANCEL_TAG).performClick()
            exists(hasTestTag(PANEL_DONE_TAG)) -> compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
            exists(hasTestTag(PANEL_TAG)) -> back()
        }
        compose.waitForIdle()
    }

    /** Selects an item as TalkBack does (its own click action), so a "+" or a transition marker drawn over it is never hit. */
    private fun select(id: ClipId) {
        waitFor(hasTestTag(clipTag(id)))
        compose.onNodeWithTag(clipTag(id)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** Nothing selected and no panel: a tap in the timeline's empty bottom-right corner. */
    private fun deselect() {
        closePanel()
        if (exists(hasTestTag(ZOOM_IN_TAG))) return
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput { click(Offset(width - 6f, height - 6f)) }
        compose.waitForIdle()
    }

    private fun project(): Project = checkNotNull(app.projectRepository.open.value)

    private fun clip(predicate: (com.itsluminous.clearmovie.domain.model.Clip) -> Boolean): ClipId =
        project()
            .tracks
            .flatMap { it.clips }
            .first(predicate)
            .id

    // ---- the tour ------------------------------------------------------------------------------

    @Test
    fun tour() {
        assumeTrue("the UX tour runs only when asked", prepare())
        MediaPermissions.toRequest(Build.VERSION.SDK_INT).forEach { automation.grantRuntimePermission(context.packageName, it) }
        runBlocking {
            app.settingsRepository.setMediaAccessAsked()
            app.settingsRepository.setThemeMode(ThemeMode.SYSTEM)
            app.settingsRepository.setDynamicColor(dynamic)
            app.projectRepository.close()
            app.projectRepository.projectList().forEach { app.projectRepository.delete(it.id) }
        }
        Intents.init()
        val photos =
            listOf(
                insert("photo1.jpg", "fixtures/photo_blue.png", "image/jpeg"),
                insert("photo2.jpg", "fixtures/photo_quadrants.png", "image/jpeg"),
                insert("photo3.jpg", "fixtures/photo_target.png", "image/jpeg"),
                insert("photo4.jpg", "fixtures/photo_blue.png", "image/jpeg"),
            )
        val videos =
            listOf(
                insert("video1.mp4", "fixtures/clip_light.mp4", "video/mp4"),
                insert("video2.mp4", "fixtures/clip_dark.mp4", "video/mp4"),
            )
        val music = insert("music.m4a", "fixtures/speech.m4a", "audio/mp4")
        val presetId = PresetId("event-frame")
        val presetName = s(R.string.preset_event_frame_name)
        val bound =
            runBlocking {
                // A new install has no presets (cm-home-presets): keep the shipped one, as an older install does.
                app.presetStore.adoptShipped(presetId)
                app.presetStore
                    .status(presetId)
                    .slots.values
                    .all { it is SlotStatus.Ready }
            }

        ActivityScenario.launch(MainActivity::class.java).use {
            // ---- entry flows -------------------------------------------------------------------
            if (!bound) {
                state("home-needs-setup", "Home and preset pick, the preset's files not chosen yet") { homeScrollTo(presetName) }
                state("preset-setup", "Preset setup, first visit") { clickText(presetName) }
                state("preset-setup-end", "Preset setup, scrolled to the end") {
                    repeat(2) { pageDown() }
                }
                if (exists(hasTestTag(com.itsluminous.clearmovie.ui.preset.SETUP_LIST_TAG))) back()
            }
            runBlocking {
                val store = app.presetStore
                listOf(
                    PresetSlot.INTRO to insert("Intro.mp4", "fixtures/intro.mp4", "video/mp4"),
                    PresetSlot.FRAME to insert("Frame-White-20s.mp4", "fixtures/frame.mp4", "video/mp4"),
                    PresetSlot.ENDING to insert("speech_last.mp3", "fixtures/speech.m4a", "audio/mpeg"),
                ).forEach { (slot, uri) -> store.bindSlot(presetId, slot, uri) }
                // The frame goes missing for the repair states.
                (store.status(presetId).slots[PresetSlot.FRAME] as? SlotStatus.Ready)?.binding?.file?.delete()
            }
            it.recreate()
            state("home-preset-missing", "Home, the preset's frame file went missing") { homeScrollTo(presetName) }
            state("preset-repair", "Preset repair screen") { clickText(presetName) }
            if (exists(hasTestTag(com.itsluminous.clearmovie.ui.preset.SETUP_LIST_TAG))) back()
            runBlocking {
                app.presetStore.bindSlot(
                    presetId,
                    PresetSlot.FRAME,
                    insert("Frame-White-20s.mp4", "fixtures/frame.mp4", "video/mp4"),
                )
            }
            it.recreate()
            state("home", "Home and preset pick, preset ready, no videos yet") { waitFor(hasText(presetName)) }
            state("home-end", "Home scrolled to Start without a preset and My videos") { homeScrollTo(s(R.string.home_my_videos)) }

            state("review", "Preset review (what this preset adds)") {
                homeScrollTo(presetName)
                clickText(presetName)
                waitFor(hasTestTag(com.itsluminous.clearmovie.ui.preset.REVIEW_LIST_TAG))
            }
            state("review-end", "Preset review, scrolled to the end") {
                repeat(2) { pageDown() }
            }
            pickerAnswers(listOf(photos[0], videos[0], photos[1], photos[2], videos[1]))
            state("editor-new", "Editor right after picking 3 photos and 2 videos with the preset") {
                clickText(s(R.string.action_next))
                waitFor(hasTestTag(EDITOR_TIMELINE_TAG))
                compose.waitUntil(TIMEOUT_MS) {
                    app.projectRepository.open.value
                        ?.mainClips
                        ?.size ?: 0 >= 7
                }
            }
            if (exists(hasTestTag(EDITOR_TIMELINE_TAG))) editorTour(photos, music)
        }

        // ---- Home with videos, Settings, an empty editor ----------------------------------------
        ActivityScenario.launch(MainActivity::class.java).use {
            // The editor may reopen at launch (the last video was open): go Home.
            compose.waitUntil(TIMEOUT_MS) { exists(hasTestTag(HOME_LIST_TAG)) || exists(hasTestTag(EDITOR_TIMELINE_TAG)) }
            if (exists(hasTestTag(EDITOR_TIMELINE_TAG))) back()
            state("home-videos", "Home with a saved video and a draft, scrolled to My videos") { homeScrollTo(s(R.string.home_my_videos)) }
            state("settings", "Settings") {
                compose.onNodeWithContentDescriptionCompat(s(R.string.action_settings)).performClick()
                waitFor(hasTestTag(SETTINGS_LIST_TAG))
            }
            state("settings-end", "Settings scrolled to the end (storage, about)") {
                repeat(3) { pageDown() }
            }
            if (exists(hasTestTag(SETTINGS_LIST_TAG))) back()
            pickerCancels()
            state("editor-empty", "Start without a preset, the picker cancelled: what shows then") {
                homeScrollTo(s(R.string.home_start_blank))
                clickText(s(R.string.home_start_blank))
                Thread.sleep(3000)
            }
            if (!exists(hasTestTag(EDITOR_TIMELINE_TAG))) {
                // Back on Home: open the saved video, for the share into an open video.
                if (!exists(hasTestTag(HOME_LIST_TAG))) back()
                runCatching {
                    homeScrollTo("Dadi's birthday")
                    clickText("Dadi's birthday")
                    waitFor(hasTestTag(EDITOR_TIMELINE_TAG))
                }
            }
            // ---- share entry -------------------------------------------------------------------
            state("share-into-open", "A share while a video is open in the editor: add to it or start new") {
                share(photos.take(2) + videos.take(1))
                waitFor(hasText(s(R.string.share_add_title)))
            }
            if (exists(hasText(s(R.string.share_start_new)))) {
                state("share-pick", "Share entry: choose a preset for the shared items") {
                    clickText(s(R.string.share_start_new))
                    waitFor(hasText(s(R.string.share_choose_preset)))
                    compose.waitUntil(TIMEOUT_MS) { !exists(hasText(s(R.string.share_getting_ready))) }
                }
            }
        }
    }

    private fun share(uris: List<Uri>) {
        val intent =
            Intent(context, ShareActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<android.os.Parcelable>(uris))
                clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        Thread.sleep(1500)
    }

    private fun editorTour(
        photos: List<Uri>,
        music: Uri,
    ) {
        // A fuller video: a text card and a colour card, captions, a picture in picture and music.
        val firstPhoto = project().mainClips.first { it is PhotoClip }.id
        val secondPhoto = project().mainClips.filterIsInstance<PhotoClip>()[1].id
        app.projectRepository.update { p ->
            p
                .addToMain(
                    listOf(
                        CardClip(ClipId("ux-textcard"), 3000, ArgbColor(0xFF1E3A8AL), text = s(R.string.preset_event_frame_name)),
                        CardClip(ClipId("ux-colourcard"), 3000, ArgbColor(0xFFF59E0BL)),
                    ),
                ).addCaptionFor(firstPhoto, "Happy birthday, Dadi!")
                .addText("Welcome everyone", 9000, 3000)
                .addTimedClip(
                    PhotoClip(ClipId("ux-pip"), MediaRef.Store(photos[3].toString()), 3000, pip = PipPlacement.DEFAULT, startMs = 8000),
                ).addTimedClip(AudioClip(ClipId("ux-music"), MediaRef.Store(music.toString()), 7000, 12_000, 12_000), AudioRole.MUSIC)
        }
        runBlocking { app.projectRepository.saveAs("Dadi's birthday") }
        compose.waitForIdle()
        runCatching { compose.onNodeWithTag(ZOOM_FIT_TAG).performClick() }
        val video = clip { it is VideoClip && it.pip == null }
        val intro = clip { it is IntroClip }
        val ending = clip { it is EndingClip }
        val caption = clip { it is TextClip && it.attachedTo != null }
        val frame = runCatching { clip { it is OverlayClip } }.getOrNull()

        state("editor", "Editor with items, nothing selected (zoom bar)") { deselect() }
        state("editor-bar-end", "Editor, nothing selected, the bar scrolled to its end") { barToEnd() }
        state("editor-photo", "A photo selected") {
            deselect()
            select(secondPhoto)
        }
        state("editor-photo-bar-end", "A photo selected, the bar scrolled to its end") { barToEnd() }
        state("panel-duration", "Photo: How long it shows") {
            bar(EditAction.DURATION)
        }
        state("panel-fit", "Photo: How it fills the frame") {
            closePanel()
            bar(EditAction.FIT)
        }
        state("panel-transition", "Transition picker") {
            closePanel()
            bar(EditAction.TRANSITION)
        }
        state("panel-transition-end", "Transition picker, scrolled") { compose.onNodeWithTag(PANEL_TAG).performTouchInput { swipeUp() } }
        state("panel-animation", "Animation picker") {
            deselect()
            select(secondPhoto)
            bar(EditAction.ANIMATION)
        }
        state("item-menu", "Long-press menu on a photo") {
            // Zoomed in and nothing selected, so the long press lands on the photo itself, not on a "+" or a
            // transition marker drawn over a narrow item.
            deselect()
            repeat(3) { runCatching { compose.onNodeWithTag(ZOOM_IN_TAG).performClick() } }
            compose.onNodeWithTag(clipTag(secondPhoto)).performTouchInput { longClick() }
            waitFor(hasTestTag(PANEL_TAG))
        }
        runCatching {
            closePanel()
            deselect()
            compose.onNodeWithTag(ZOOM_FIT_TAG).performClick()
        }
        state("panel-insert", "The \"+\" chooser (add before or after this photo)") {
            closePanel()
            select(secondPhoto)
            // The "+" after the selected photo (the other "+" points are row ends).
            val plus =
                androidx.compose.ui.test.hasContentDescription(
                    context.getString(R.string.timeline_add_after, s(R.string.editor_item_photo)),
                    substring = true,
                )
            waitFor(plus)
            compose.onAllNodes(plus).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            waitFor(hasTestTag(PANEL_TAG))
        }
        state("caption-words", "Caption editor: Words") {
            closePanel()
            select(firstPhoto)
            bar(EditAction.EDIT_TEXT.takeIf { exists(hasTestTag(barActionTag(it))) } ?: EditAction.ADD_CAPTION)
            waitFor(hasTestTag(com.itsluminous.clearmovie.ui.editor.CAPTION_PANEL_TAG))
        }
        for (tab in listOf(CaptionTab.PLACE, CaptionTab.SIZE, CaptionTab.FONT, CaptionTab.COLOUR)) {
            state("caption-${tab.name.lowercase()}", "Caption editor: ${tab.name.lowercase()} tab") {
                tag(captionTabTag(tab)).performClick()
            }
        }
        state("editor-video", "A video selected") {
            deselect()
            select(video)
        }
        state("panel-trim", "Video: Trim") { bar(EditAction.TRIM) }
        state("panel-split", "Video: Split") {
            closePanel()
            bar(EditAction.SPLIT)
        }
        state("panel-speed", "Video: Speed") {
            closePanel()
            bar(EditAction.SPEED)
        }
        state("panel-loop", "Video: Loop") {
            closePanel()
            bar(EditAction.LOOP)
        }
        state("panel-volume", "Video: Volume") {
            closePanel()
            bar(EditAction.VOLUME)
        }
        state("editor-textcard", "A text card selected") {
            deselect()
            select(ClipId("ux-textcard"))
        }
        state("panel-card-text", "Text card: its text") { bar(EditAction.EDIT_TEXT) }
        state("panel-card-colour", "Text card: its colour") {
            closePanel()
            bar(EditAction.COLOUR)
        }
        state("editor-colourcard", "A colour card selected") {
            deselect()
            select(ClipId("ux-colourcard"))
        }
        state("editor-intro", "The preset intro selected") {
            deselect()
            select(intro)
        }
        state("editor-ending", "The preset ending selected") {
            deselect()
            select(ending)
        }
        state("editor-caption", "A caption (text row) selected") {
            deselect()
            select(caption)
        }
        state("editor-pip", "A picture-in-picture photo selected") {
            deselect()
            select(ClipId("ux-pip"))
        }
        state("panel-pip-position", "Picture in picture: Position") { bar(EditAction.POSITION) }
        state("panel-move-to-row", "Move to another row") {
            closePanel()
            bar(EditAction.MOVE_TO_ROW)
        }
        state("editor-music", "Music selected") {
            deselect()
            select(ClipId("ux-music"))
        }
        state("panel-fade", "Music: Fade") { bar(EditAction.FADE) }
        state("panel-sound-trim", "Music: Trim") {
            closePanel()
            bar(EditAction.TRIM)
        }
        if (frame != null) {
            state("editor-frame", "The preset frame selected") {
                deselect()
                select(frame)
            }
        }
        val header =
            SemanticsMatcher("a row header") { n ->
                n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("timeline_row_header_")
            }
        state("editor-row", "A row selected (tap on its icon)") {
            deselect()
            compose.onAllNodes(header)[1].performSemanticsAction(SemanticsActions.OnClick)
        }
        state("row-menu", "The row menu (what shows on top), long-press on a row icon") {
            val h = compose.onAllNodes(header)[1]
            runCatching { h.performSemanticsAction(SemanticsActions.OnLongClick) }.onFailure { h.performTouchInput { longClick() } }
            waitFor(hasTestTag(PANEL_TAG))
        }
        state("remove-row-confirm", "Remove row: the question") {
            closePanel()
            // The text row (the first): the preset frame's row cannot be removed.
            compose.onAllNodes(header)[0].performSemanticsAction(SemanticsActions.OnClick)
            bar(EditAction.REMOVE_ROW)
            waitFor(hasTestTag(com.itsluminous.clearmovie.ui.editor.REMOVE_ROW_CONFIRM_TAG))
        }
        if (exists(hasText(s(R.string.action_cancel)))) clickText(s(R.string.action_cancel))
        state("panel-add-row", "Add a row") {
            deselect()
            bar(EditAction.ADD_ROW)
        }
        state("editor-all-rows", "A panel open and all rows shown: the preview shrinks or floats") {
            closePanel()
            select(secondPhoto)
            bar(EditAction.DURATION)
            waitFor(hasTestTag(TIMELINE_ROWS_TOGGLE_TAG))
            compose.onNodeWithTag(TIMELINE_ROWS_TOGGLE_TAG).performClick()
        }
        closePanel()
        deselect()
        // ---- export ----------------------------------------------------------------------------
        state("export-choose", "Export sheet: the three sizes") {
            compose.onNodeWithTag(EDITOR_EXPORT_TAG).performClick()
            waitFor(hasTestTag(EXPORT_SAVE_TAG))
        }
        if (!realExport) {
            if (exists(hasTestTag(EXPORT_CLOSE_TAG))) compose.onNodeWithTag(EXPORT_CLOSE_TAG).performClick() else back()
            return
        }
        state("export-progress", "Export sheet: saving, with progress") {
            compose.onNodeWithTag(exportQualityTag(ExportQuality.SMALL)).performClick()
            compose.onNodeWithTag(EXPORT_SAVE_TAG).performClick()
            waitFor(hasText(s(R.string.export_saving_title)))
            Thread.sleep(4000)
        }
        state("export-done", "Export sheet: the video is ready") {
            waitFor(hasText(s(R.string.export_done_title)), EXPORT_TIMEOUT_MS)
        }
        if (exists(hasTestTag(EXPORT_CLOSE_TAG))) compose.onNodeWithTag(EXPORT_CLOSE_TAG).performClick()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithContentDescriptionCompat(d: String) =
        onNode(
            androidx.compose.ui.test
                .hasContentDescription(d),
        )

    // ---- states that are not reachable on an emulator ---------------------------------------------

    /** Dialogs and sheets whose states need a denial, a full phone or a failure: the same composables, stateless. */
    @Test
    fun stateless() {
        assumeTrue("the UX tour runs only when asked", prepare())
        runBlocking { app.settingsRepository.setDynamicColor(dynamic) }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)

        fun show(content: @Composable () -> Unit) {
            scenario.onActivity {
                it.enableEdgeToEdge()
                it.setContent { ClearMovieTheme(themeMode = ThemeMode.SYSTEM, dynamicColor = dynamic) { content() } }
            }
            compose.waitForIdle()
        }
        val page: @Composable (@Composable () -> Unit) -> Unit = { inner ->
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) { inner() }
            }
        }
        val none =
            object : ExportSheetActions {
                override fun save(quality: ExportQuality) = Unit

                override fun stop() = Unit

                override fun hide() = Unit

                override fun close() = Unit

                override fun play() = Unit

                override fun share() = Unit

                override fun tryAgain() = Unit

                override fun replaceOther() = Unit
            }
        val shots =
            listOf<Triple<String, String, @Composable () -> Unit>>(
                Triple(
                    "picker-explainer",
                    "Photo access: the one-time explanation",
                    { MediaAccessExplainer(MediaAccessState.NotAsked, {}, {}, {}) },
                ),
                Triple("picker-denied", "Photo access denied", { MediaAccessExplainer(MediaAccessState.Denied, {}, {}, {}) }),
                Triple(
                    "picker-blocked",
                    "Photo access denied for good",
                    { MediaAccessExplainer(MediaAccessState.DeniedForGood, {}, {}, {}) },
                ),
                Triple("missing-item", "A missing photo and the partial-access banner", {
                    page {
                        androidx.compose.foundation.layout.Column {
                            MissingMediaTile(MediaStatus.MISSING, onReplace = {})
                            Box(Modifier.padding(top = 16.dp)) { PartialAccessBanner(onAllowAll = {}) }
                        }
                    }
                }),
                Triple("copy-budget", "Copy question before a big copy", {
                    page {}
                    CopyBudgetDialog(
                        CopyBudget.Verdict.NeedsConfirmation(12, 1_400_000_000, 3_200_000_000, offerAllowAll = true),
                        {},
                        {},
                        {},
                    )
                }),
                Triple("copy-no-space", "Not enough space to copy", {
                    page {}
                    NotEnoughSpaceDialogCompat()
                }),
                Triple("copy-progress", "Copying progress", {
                    page {}
                    CopyProgressDialog(37L * 1024 * 1024, 100L * 1024 * 1024, onCancel = {})
                }),
                Triple("export-choose-sizes", "Export sheet with sizes and one too big", {
                    page {}
                    ExportSheet(
                        ExportStep.Choose(
                            sizes =
                                mapOf(
                                    ExportQuality.BEST to 412_000_000L,
                                    ExportQuality.GOOD to 186_000_000L,
                                    ExportQuality.SMALL to 64_000_000L,
                                ),
                            shortBy = mapOf(ExportQuality.BEST to 120_000_000L),
                        ),
                        null,
                        none,
                    )
                }),
                Triple("export-running", "Export sheet: 42% with time left", {
                    page {}
                    ExportSheet(ExportStep.Running(42, 3, restarted = true), null, none)
                }),
                Triple("export-error", "Export sheet: failed (a photo went missing)", {
                    page {}
                    ExportSheet(ExportStep.Failed(ExportFailure.MISSING_MEDIA), null, none)
                }),
                Triple("export-error-space", "Export sheet: failed (phone full)", {
                    page {}
                    ExportSheet(ExportStep.Failed(ExportFailure.NO_SPACE, 250_000_000L), null, none)
                }),
                Triple("export-blocked", "Export sheet: blocked by a broken preset", {
                    page {}
                    ExportSheet(ExportStep.Choose(), ExportBlock.PRESET_BROKEN, none)
                }),
                Triple("export-other-running", "Export sheet: another video is saving", {
                    page {}
                    ExportSheet(ExportStep.OtherRunning, null, none)
                }),
            )
        for ((name, what, content) in shots) {
            if (only != null && only.none { name.startsWith(it) }) continue
            runCatching {
                show(content)
                shot(name, what)
            }.onFailure { manifest += "$name.png\tFAILED: ${it.javaClass.simpleName}: ${it.message}" }
        }
        scenario.close()
    }

    @Composable
    private fun NotEnoughSpaceDialogCompat() {
        NotEnoughSpaceDialog(CopyBudget.Verdict.NotEnoughSpace(12, 1_400_000_000L, 300_000_000L), onChooseFewer = {})
    }

    private companion object {
        const val TAG = "UxTour"
        const val TIMEOUT_MS = 8_000L
        const val EXPORT_TIMEOUT_MS = 600_000L
        const val SETTLE_MS = 700L
    }
}
