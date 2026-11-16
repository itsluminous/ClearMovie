package com.itsluminous.clearmovie.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.ClearMovieApplication
import com.itsluminous.clearmovie.data.export.ExportStore
import com.itsluminous.clearmovie.data.media.MediaPermissions
import com.itsluminous.clearmovie.data.preset.SlotBindResult
import com.itsluminous.clearmovie.data.project.ProjectMedia
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.applyTransitionToAll
import com.itsluminous.clearmovie.domain.model.rename
import com.itsluminous.clearmovie.render.media3.EncodeSpec
import com.itsluminous.clearmovie.render.media3.EncoderPreference
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.work.ExportPipeline
import com.itsluminous.clearmovie.work.ExportWorker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream

/**
 * Shared set-up for the export device tests: full gallery access, the
 * bundled preset bound to the synthetic fixtures (put into MediaStore the
 * way a user's files are), short projects of photos and videos with a
 * caption and crossfades, the real [ExportPipeline] (optionally with a
 * failing encoder or a nearly full phone), and a look at what landed in the
 * gallery. Everything it adds is removed in [cleanUp]; nothing touches
 * another app's files.
 */
@UnstableApi
class ExportHarness {
    val context: Context = ApplicationProvider.getApplicationContext()
    val app = ApplicationProvider.getApplicationContext<ClearMovieApplication>()
    val store = ExportStore(context.contentResolver)
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val inserted = mutableListOf<Uri>()
    private val exported = mutableListOf<Uri>()
    val presetId = PresetId("event-frame")

    fun setUp() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        MediaPermissions.toRequest(Build.VERSION.SDK_INT).forEach { automation.grantRuntimePermission(context.packageName, it) }
        if (Build.VERSION.SDK_INT >=
            33
        ) {
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        runBlocking { app.settingsRepository.setMediaAccessAsked() }
        app.workManagerCancelAll()
    }

    fun cleanUp() {
        runBlocking {
            app.workManagerCancelAll()
            app.projectRepository.close()
            app.projectRepository.projectList().forEach { app.projectRepository.delete(it.id) }
        }
        exported.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    /** Puts a fixture into MediaStore under a test folder, as a gallery file. */
    fun insert(
        asset: String,
        mime: String,
        open: (String) -> InputStream = { testAssets.open(it) },
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
                put(MediaStore.MediaColumns.DISPLAY_NAME, "cmexport-${System.nanoTime()}-${asset.substringAfterLast('/')}")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        inserted += uri
        context.contentResolver.openOutputStream(uri)!!.use { out -> open(asset).use { it.copyTo(out) } }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Binds the bundled preset's intro, frame and ending to the given fixtures. */
    fun bindPreset(
        intro: Uri = insert("fixtures/intro.mp4", "video/mp4"),
        frame: Uri = insert("fixtures/frame.mp4", "video/mp4"),
        ending: Uri = insert("fixtures/speech.m4a", "audio/mp4"),
    ) = runBlocking {
        listOf(PresetSlot.INTRO to intro, PresetSlot.FRAME to frame, PresetSlot.ENDING to ending).forEach { (slot, uri) ->
            assertThat(app.presetStore.bindSlot(presetId, slot, uri)).isInstanceOf(SlotBindResult.Bound::class.java)
        }
    }

    /**
     * The preset project with [items] on MAIN, a caption on the first, a
     * crossfade at every junction and [name]; stored as a draft (open in the
     * editor when [inEditor]).
     */
    fun project(
        items: List<MainClip>,
        name: String?,
        caption: String? = "Happy birthday",
        inEditor: Boolean = false,
    ): Project =
        runBlocking {
            val preset = requireNotNull(app.presetStore.effectivePreset(presetId))
            var project =
                ProjectFactory
                    .fromPreset(ProjectId(RandomIds.next()), preset, app.presetStore.presetMedia(presetId))
                    .addToMain(items)
                    .applyTransitionToAll(Transition(TransitionKind.CROSSFADE, 600))
            if (caption != null) project = project.addCaptionFor(items.first().id, caption)
            project = project.rename(name)
            assertThat(app.projectRepository.createDraft(project, inEditor = inEditor)).isTrue()
            project
        }

    fun photo(
        ms: Long = 3000,
        asset: String = "fixtures/photo_quadrants.png",
    ): PhotoClip = PhotoClip(ClipId(RandomIds.next()), MediaRef.Store(insert(asset, "image/png").toString()), ms)

    fun video(asset: String = "fixtures/clip_dark.mp4"): VideoClip =
        VideoClip(ClipId(RandomIds.next()), MediaRef.Store(insert(asset, "video/mp4").toString()), sourceDurationMs = 3000)

    /** A typical short video: two photos and two videos. */
    fun typicalItems(photoMs: Long = 3000): List<MainClip> =
        listOf(photo(photoMs), video("fixtures/clip_dark.mp4"), photo(photoMs, "fixtures/photo_blue.png"), video("fixtures/clip_light.mp4"))

    /** The plan's length, as the export plans it. */
    fun plannedUs(project: Project): Long =
        runBlocking {
            val set = ProjectMedia(app.presetStore, app.appFiles).forProject(project)
            RenderPlanBuilder(set.preset, set.loudness).build(project, height = 480).durationUs
        }

    fun pipeline(
        freeBytes: () -> Long = { Long.MAX_VALUE / 4 },
        baseEncoders: ((EncodeSpec, EncoderPreference) -> androidx.media3.transformer.Codec.EncoderFactory)? = null,
    ) = ExportPipeline(
        context,
        app.projectRepository,
        ProjectMedia(app.presetStore, app.appFiles),
        app.presetStore,
        app.mediaAccess,
        store,
        freeBytes,
        baseEncoders,
    )

    /** Remembers a gallery entry to remove after the test. */
    fun exported(uri: Uri): Uri = uri.also { exported += it }

    data class Probe(
        val width: Int,
        val height: Int,
        val videoDurationUs: Long,
        val frameRate: Int?,
        val videoMime: String,
        val audioMime: String?,
        val sizeBytes: Long,
    ) {
        /** Average bits per second of the whole file. */
        val bitsPerSecond: Long get() = sizeBytes * 8 * 1_000_000 / videoDurationUs.coerceAtLeast(1)
    }

    /** What a player finds in the gallery video [uri]. */
    fun probe(uri: Uri): Probe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && video == null) video = f
                if (mime.startsWith("audio/") && audio == null) audio = f
            }
            val v = requireNotNull(video) { "no video track" }
            val size = context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
            return Probe(
                width = v.getInteger(MediaFormat.KEY_WIDTH),
                height = v.getInteger(MediaFormat.KEY_HEIGHT),
                videoDurationUs = v.getLong(MediaFormat.KEY_DURATION),
                frameRate = if (v.containsKey(MediaFormat.KEY_FRAME_RATE)) v.getInteger(MediaFormat.KEY_FRAME_RATE) else null,
                videoMime = v.getString(MediaFormat.KEY_MIME).orEmpty(),
                audioMime = audio?.getString(MediaFormat.KEY_MIME),
                sizeBytes = size,
            )
        } finally {
            extractor.release()
        }
    }

    /** Copies [uri] to the app's external files (render-tests/), so a run's outputs can be pulled as evidence. */
    fun keep(
        uri: Uri,
        name: String,
    ): File {
        val dir = File(checkNotNull(context.getExternalFilesDir(null)), "render-tests").apply { mkdirs() }
        val out = File(dir, name)
        context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        Log.i(TAG, "Kept $name (${out.length()} bytes)")
        return out
    }

    companion object {
        const val TAG = "ExportTest"

        /** Encoders of [mime] the platform marks as hardware. */
        fun isHardware(name: String?): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && it.name == name && it.isHardwareAccelerated }

        fun isEmulator(): Boolean =
            Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") ||
                Build.PRODUCT.contains("sdk") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
    }
}

/** Stops every export the tests started (only this app's work). */
fun ClearMovieApplication.workManagerCancelAll() {
    androidx.work.WorkManager
        .getInstance(this)
        .cancelUniqueWork(ExportWorker.UNIQUE_NAME)
        .result
        .get()
}
