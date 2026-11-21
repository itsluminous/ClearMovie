package com.itsluminous.clearmovie

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.media.MediaPermissions
import com.itsluminous.clearmovie.data.preset.SlotBindResult
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.ui.editor.BAR_TAG
import com.itsluminous.clearmovie.ui.editor.EDITOR_TIMELINE_TAG
import com.itsluminous.clearmovie.ui.editor.PANEL_DONE_TAG
import com.itsluminous.clearmovie.ui.editor.PANEL_TAG
import com.itsluminous.clearmovie.ui.editor.TEXT_FIELD_TAG
import com.itsluminous.clearmovie.ui.editor.ZOOM_IN_TAG
import com.itsluminous.clearmovie.ui.editor.barActionTag
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import com.itsluminous.clearmovie.ui.editor.timeline.RulerTickKey
import com.itsluminous.clearmovie.ui.editor.timeline.TIMELINE_RULER_TAG
import com.itsluminous.clearmovie.ui.home.HOME_LIST_TAG
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher flow on a device (cm-flow acceptance): Start without a
 * preset, the Photo Picker (stubbed with Espresso-Intents, answering with
 * three items put into MediaStore by the test), the editor with the items,
 * then a recreation that neither opens the picker again nor loses the items;
 * the preview plays; and a preset whose file went missing opens the repair
 * screen instead of crashing.
 */
@RunWith(AndroidJUnit4::class)
class FlowDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = ApplicationProvider.getApplicationContext<ClearMovieApplication>()
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val inserted = mutableListOf<Uri>()

    private fun s(id: Int) = context.getString(id)

    @Before
    fun setUp() {
        // Full photo and video access: the explainer is the Robolectric tests' business, the picker is this one's.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        MediaPermissions.toRequest(Build.VERSION.SDK_INT).forEach { automation.grantRuntimePermission(context.packageName, it) }
        runBlocking { app.settingsRepository.setMediaAccessAsked() }
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
        runBlocking {
            // Leave Home as the next launch's start, with no videos: other tests expect an empty home screen.
            app.projectRepository.close()
            app.projectRepository.projectList().forEach { app.projectRepository.delete(it.id) }
        }
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
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
                put(MediaStore.MediaColumns.DISPLAY_NAME, "cmtest-${System.nanoTime()}-${asset.substringAfterLast('/')}")
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

    /** The picker answers with [uris], as the system Photo Picker does (one ClipData item each). */
    private fun pickerAnswers(uris: List<Uri>) {
        val data =
            Intent().apply {
                clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        intending(not(isInternal())).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, data))
    }

    private fun waitFor(
        matcher: SemanticsMatcher,
        count: Int = 1,
    ) = compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().size >= count }

    private fun texts(part: String): List<String> =
        compose
            .onAllNodes(hasText(part, substring = true))
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }
            .filter { part in it }

    private fun itemsShown(
        photos: Int,
        videos: Int,
    ) {
        waitFor(hasTestTag(EDITOR_TIMELINE_TAG))
        // Each tile reads "Photo, 1 of 3, 0:03 long" (timeline_item_description); the "+" points name items differently.
        val photo = hasContentDescription(s(R.string.editor_item_photo) + ",", substring = true)
        waitFor(photo, photos)
        assertThat(compose.onAllNodes(photo).fetchSemanticsNodes()).hasSize(photos)
        val video = hasContentDescription(s(R.string.editor_item_video) + ",", substring = true)
        waitFor(video, videos)
        assertThat(compose.onAllNodes(video).fetchSemanticsNodes()).hasSize(videos)
    }

    @Test
    fun startWithoutAPresetPicksItemsAndARecreationKeepsThemWithoutOpeningThePickerAgain() {
        pickerAnswers(
            listOf(
                insert("fixtures/photo_blue.png", "image/png"),
                insert("fixtures/clip_light.mp4", "video/mp4"),
                insert("fixtures/photo_quadrants.png", "image/png"),
            ),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(hasTestTag(HOME_LIST_TAG))
            compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(s(R.string.home_start_blank)))
            compose.onNodeWithText(s(R.string.home_start_blank)).performClick()

            itemsShown(photos = 2, videos = 1)
            intended(not(isInternal()), Intents.times(1))

            scenario.recreate()
            itemsShown(photos = 2, videos = 1)
            // The picker answered once and was never opened again.
            intended(not(isInternal()), Intents.times(1))

            // The preview plays: the time label moves on from 0:00.
            compose.onNodeWithText(s(R.string.action_play)).performClick()
            val atStart = context.getString(R.string.editor_time, "0:00", "")
            val of = context.getString(R.string.editor_time, "", "")
            compose.waitUntil(TIMEOUT_MS) { texts(of).any { !it.startsWith(atStart) } }
            // Paused again unless it already reached the end on a slow device.
            if (compose.onAllNodes(hasText(s(R.string.action_pause))).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText(s(R.string.action_pause)).performClick()
            }
        }
    }

    /**
     * cm-timeline: a recreation in the middle of an edit keeps the selected
     * item, the open panel, the zoom and the edit itself (ARCHITECTURE
     * section 11.11), and the picker is not opened again.
     */
    @Test
    fun aRecreationMidEditKeepsTheSelectionTheOpenPanelAndTheZoom() {
        pickerAnswers(
            listOf(
                insert("fixtures/photo_blue.png", "image/png"),
                insert("fixtures/clip_light.mp4", "video/mp4"),
                insert("fixtures/photo_quadrants.png", "image/png"),
            ),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(hasTestTag(HOME_LIST_TAG))
            compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(s(R.string.home_start_blank)))
            compose.onNodeWithText(s(R.string.home_start_blank)).performClick()
            itemsShown(photos = 2, videos = 1)

            // Zoom lives in the bar shown with nothing selected (with Fit all): zoom first, then select.
            repeat(2) { compose.onNodeWithTag(ZOOM_IN_TAG).performClick() }
            compose.onNode(hasContentDescription(s(R.string.editor_item_video) + ",", substring = true)).performClick()
            // The bar holds more buttons than fit (Transition and Animation come early): scroll to Speed.
            compose
                .onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(BAR_TAG)))
                .performScrollToNode(hasTestTag(barActionTag(EditAction.SPEED)))
            compose.onNodeWithTag(barActionTag(EditAction.SPEED)).performClick()
            compose.onNodeWithText(context.getString(R.string.speed_value, "2")).performClick()
            compose.waitForIdle()
            val ruler = tick()
            val edited = runBlocking { app.projectRepository.open.value!! }
            assertThat(
                edited.mainClips
                    .filterIsInstance<VideoClip>()
                    .single()
                    .speed,
            ).isEqualTo(Speed.X2)

            scenario.recreate()
            waitFor(hasTestTag(PANEL_TAG))
            assertThat(tick()).isEqualTo(ruler)
            val after = runBlocking { app.projectRepository.open.value!! }
            assertThat(after).isEqualTo(edited)
            // Done closes the panel; the bar is the selected video's.
            compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
            waitFor(hasTestTag(barActionTag(EditAction.TRIM)))
            intended(not(isInternal()), Intents.times(1))
        }
    }

    /**
     * cm-captions: Add caption on a photo, Hindi typed into the editor, a
     * recreation in the middle of typing, more words; the editor and the
     * words survive, and the caption is attached to the photo.
     */
    @Test
    fun aCaptionBeingTypedSurvivesARecreation() {
        pickerAnswers(listOf(insert("fixtures/photo_blue.png", "image/png"), insert("fixtures/photo_quadrants.png", "image/png")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(hasTestTag(HOME_LIST_TAG))
            compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(s(R.string.home_start_blank)))
            compose.onNodeWithText(s(R.string.home_start_blank)).performClick()
            itemsShown(photos = 2, videos = 0)
            compose.onAllNodes(hasContentDescription(s(R.string.editor_item_photo) + ",", substring = true)).onFirst().performClick()
            waitFor(hasTestTag(barActionTag(EditAction.ADD_CAPTION)))
            compose.onNodeWithTag(barActionTag(EditAction.ADD_CAPTION)).performClick()
            waitFor(hasTestTag(TEXT_FIELD_TAG))
            compose.onNodeWithTag(TEXT_FIELD_TAG).performTextInput("नमस्ते")
            compose.waitUntil(TIMEOUT_MS) { captionText() == "नमस्ते" }

            scenario.recreate()
            waitFor(hasTestTag(TEXT_FIELD_TAG))
            compose.onNodeWithTag(TEXT_FIELD_TAG).performTextInput(" जी")
            compose.waitUntil(TIMEOUT_MS) { captionText() == "नमस्ते जी" }
            compose.onNodeWithTag(PANEL_DONE_TAG).performClick()
            waitFor(hasTestTag(barActionTag(EditAction.EDIT_TEXT)))
            val project = runBlocking { app.projectRepository.open.value!! }
            val caption =
                project.tracks
                    .flatMap { it.clips }
                    .filterIsInstance<TextClip>()
                    .single()
            assertThat(caption.attachedTo?.clip).isEqualTo(project.mainClips.first().id)
        }
    }

    private fun captionText(): String? =
        app.projectRepository.open.value
            ?.tracks
            ?.flatMap { it.clips }
            ?.filterIsInstance<TextClip>()
            ?.singleOrNull()
            ?.text

    /** The ruler's label spacing: it changes with the zoom. */
    private fun tick(): Long =
        compose
            .onNodeWithTag(TIMELINE_RULER_TAG)
            .fetchSemanticsNode()
            .config
            .getOrElse(RulerTickKey) { -1L }

    @Test
    fun aPresetFileThatWentMissingOpensTheRepairScreen() {
        val id = PresetId("event-frame")
        runBlocking {
            val store = app.presetStore
            // A new install has no presets (cm-home-presets): keep the shipped one, as an older install does.
            assertThat(store.adoptShipped(id)).isTrue()
            listOf(
                PresetSlot.INTRO to insert("fixtures/intro.mp4", "video/mp4"),
                PresetSlot.FRAME to insert("fixtures/frame.mp4", "video/mp4"),
                PresetSlot.ENDING to insert("fixtures/speech.m4a", "audio/mp4"),
            ).forEach { (slot, uri) -> assertThat(store.bindSlot(id, slot, uri)).isInstanceOf(SlotBindResult.Bound::class.java) }
            val frame = (store.status(id).slots[PresetSlot.FRAME] as SlotStatus.Ready).binding.file
            assertThat(frame.delete()).isTrue()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val missing = context.resources.getQuantityString(R.plurals.home_preset_missing, 1, 1)
            waitFor(hasText(missing))
            compose.onNodeWithText(s(R.string.preset_event_frame_name)).performClick()
            waitFor(hasText(s(R.string.preset_repair_title)))
            compose.onNodeWithText(s(R.string.preset_slot_missing)).assertExists()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
