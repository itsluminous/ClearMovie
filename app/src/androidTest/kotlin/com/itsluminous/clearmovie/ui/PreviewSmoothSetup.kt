package com.itsluminous.clearmovie.ui

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.clearmovie.ClearMovieApplication
import com.itsluminous.clearmovie.data.media.MediaPermissions
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.validate
import com.itsluminous.clearmovie.domain.model.withEffectDefaultsFor
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sets up a video for measuring the editor preview by hand (screen
 * recordings and frame timings from the host): the Event frame preset with
 * its intro, frame and closing voice, three videos, two photos and two
 * captions, with the preset's transitions and animations, left open so the
 * app reopens it in the editor at its next launch.
 *
 * Runs only when asked (`-e smoothsetup true`), so CI skips it. Media comes
 * from the app's `files/smoothmedia/` folder (Intro.mp4, Frame.mp4,
 * speech_last.mp3, video1-3.mp4, photo1-2.jpg) when present, else the test
 * fixtures. It deletes this app's videos and drafts first: an emulator only.
 */
@RunWith(AndroidJUnit4::class)
class PreviewSmoothSetup {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = ApplicationProvider.getApplicationContext<ClearMovieApplication>()
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun media(name: String): File? = context.getExternalFilesDir("smoothmedia")?.let { File(it, name) }?.takeIf { it.isFile }

    private fun insert(
        name: String,
        fallbackAsset: String,
        mime: String,
    ): Uri {
        val collection =
            when {
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        val dir =
            when {
                mime.startsWith("video/") -> "Movies/ClearMovieSmooth"
                mime.startsWith("audio/") -> "Music/ClearMovieSmooth"
                else -> "Pictures/ClearMovieSmooth"
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "smooth-${System.nanoTime()}-$name")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        context.contentResolver.openOutputStream(uri)!!.use { sink ->
            val file = media(name)
            if (file != null) file.inputStream().use { it.copyTo(sink) } else testAssets.open(fallbackAsset).use { it.copyTo(sink) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun durationMs(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            checkNotNull(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
        } finally {
            r.release()
        }
    }

    @Test
    fun setUp() {
        assumeTrue("runs only when asked", InstrumentationRegistry.getArguments().getString("smoothsetup") == "true")
        // It deletes every video and draft of the app: an emulator only.
        assumeTrue("an emulator only", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.startsWith("sdk"))
        // -e smoothpreset false: a video without a preset (no intro, frame, ending or transitions).
        val withPreset = InstrumentationRegistry.getArguments().getString("smoothpreset") != "false"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        MediaPermissions.toRequest(Build.VERSION.SDK_INT).forEach { automation.grantRuntimePermission(context.packageName, it) }
        runBlocking {
            app.settingsRepository.setMediaAccessAsked()
            app.projectRepository.close()
            app.projectRepository.projectList().forEach { app.projectRepository.delete(it.id) }
            val presetId = PresetId("event-frame")
            val store = app.presetStore
            store.adoptShipped(presetId)
            listOf(
                PresetSlot.INTRO to insert("Intro.mp4", "fixtures/intro.mp4", "video/mp4"),
                PresetSlot.FRAME to insert("Frame.mp4", "fixtures/frame.mp4", "video/mp4"),
                PresetSlot.ENDING to insert("speech_last.mp3", "fixtures/speech.m4a", "audio/mpeg"),
            ).forEach { (slot, uri) -> store.bindSlot(presetId, slot, uri) }
            val preset = checkNotNull(store.effectivePreset(presetId))
            val v1 = insert("video1.mp4", "fixtures/clip_light.mp4", "video/mp4")
            val p1 = insert("photo1.jpg", "fixtures/photo_blue.png", "image/jpeg")
            val v2 = insert("video2.mp4", "fixtures/clip_dark.mp4", "video/mp4")
            val p2 = insert("photo2.jpg", "fixtures/photo_quadrants.png", "image/jpeg")
            val v3 = insert("video3.mp4", "fixtures/clip_light.mp4", "video/mp4")

            fun video(
                id: String,
                uri: Uri,
                keepMs: Long,
            ): VideoClip {
                val ms = durationMs(uri)
                return VideoClip(ClipId(id), MediaRef.Store(uri.toString()), sourceDurationMs = ms, trimEndMs = minOf(ms, keepMs))
            }
            val clips =
                listOf(
                    video("v1", v1, 6000),
                    PhotoClip(ClipId("p1"), MediaRef.Store(p1.toString()), 3000),
                    video("v2", v2, 6000),
                    PhotoClip(ClipId("p2"), MediaRef.Store(p2.toString()), 3000),
                    video("v3", v3, 6000),
                )
            val project =
                (
                    if (withPreset) {
                        ProjectFactory.fromPreset(ProjectId("smooth"), preset, store.presetMedia(presetId))
                    } else {
                        ProjectFactory.blank(ProjectId("smooth"))
                    }
                ).addToMain(clips)
                    .withEffectDefaultsFor(clips.map { it.id })
                    .addCaptionFor(ClipId("v1"), "Happy birthday", captionId = ClipId("c1"))
                    .withEffectDefaultsFor(listOf(ClipId("c1")))
                    .addCaptionFor(ClipId("p1"), "Dadi and Dada", captionId = ClipId("c2"))
                    .withEffectDefaultsFor(listOf(ClipId("c2")))
            check(project.validate().isEmpty()) { project.validate().toString() }
            check(app.projectRepository.createDraft(project, inEditor = true)) { "draft not saved" }
            app.projectRepository.flush()
        }
    }
}
