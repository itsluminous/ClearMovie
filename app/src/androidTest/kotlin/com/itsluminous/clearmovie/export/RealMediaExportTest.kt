package com.itsluminous.clearmovie.export

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.project.ProjectMedia
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.render.plan.LayerRole
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.plan.VisualSegment
import com.itsluminous.clearmovie.work.ExportOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Hand-run evidence export with real preset media (never committed): put
 * `Intro.mp4`, `Frame-White.mp4` and `speech_last.mp3`, plus photos
 * `photo1.jpg`.. and videos `video1.mp4`.., in the app's external files
 * folder under `realmedia/`, and run with
 * `-e cm.realmedia true`. It exports the same project (intro, framed photos
 * and videos with crossfades, a caption, the closing speech) at each size
 * through the real pipeline, keeps each output in `render-tests/`, and writes
 * the plan's times (`realmedia-timeline.txt`) for frame extraction.
 *
 * With `-e cm.demo true` instead, it only sets the project up and leaves it
 * in My videos, for a by-hand run of the export screens (a screen recording).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RealMediaExportTest {
    private val h = ExportHarness()
    private val dir get() = File(checkNotNull(h.context.getExternalFilesDir(null)), "realmedia")
    private val demo = InstrumentationRegistry.getArguments().getString("cm.demo") == "true"

    @Before
    fun setUp() {
        val enabled = demo || InstrumentationRegistry.getArguments().getString("cm.realmedia") == "true"
        assumeTrue("Run by hand with -e cm.realmedia true and the files in realmedia/", enabled && File(dir, "Intro.mp4").isFile)
        h.setUp()
    }

    @After
    fun tearDown() {
        if (File(dir, "Intro.mp4").isFile && !demo) h.cleanUp()
    }

    private fun file(name: String) = File(dir, name)

    @Test
    fun exportTheRealPresetProjectAtEverySize() {
        val project = realProject()
        if (demo) return
        writeTimeline(project)
        for (quality in ExportQuality.entries) {
            val started = System.nanoTime()
            val outcome = runBlocking { h.pipeline().run(project.id, quality, "New video") {} }
            val wallMs = (System.nanoTime() - started) / 1_000_000
            val done = (outcome as ExportOutcome.Done).done
            h.exported(done.uri)
            h.keep(done.uri, "realmedia-${quality.name.lowercase()}.mp4")
            Log.i(ExportHarness.TAG, "REALMEDIA ${quality.name} wall=${wallMs}ms $done")
            assertThat(h.probe(done.uri).height).isEqualTo(quality.height)
        }
    }

    private fun realProject(): com.itsluminous.clearmovie.domain.model.Project {
        val open = { name: String -> file(name).inputStream() }
        h.bindPreset(
            intro = h.insert("Intro.mp4", "video/mp4", open),
            frame = h.insert("Frame-White.mp4", "video/mp4", open),
            ending = h.insert("speech_last.mp3", "audio/mpeg", open),
        )
        val items =
            buildList<MainClip> {
                for (name in dir.list().orEmpty().sorted()) {
                    when {
                        name.startsWith("photo") -> {
                            val uri = h.insert(name, "image/jpeg", open)
                            add(PhotoClip(ClipId(RandomIds.next()), MediaRef.Store(uri.toString()), 4000))
                        }

                        name.startsWith("video") -> {
                            val uri = h.insert(name, "video/mp4", open)
                            val ms =
                                android.media.MediaMetadataRetriever().run {
                                    setDataSource(file(name).path)
                                    extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                                    )!!.toLong().also { release() }
                                }
                            add(VideoClip(ClipId(RandomIds.next()), MediaRef.Store(uri.toString()), sourceDurationMs = ms))
                        }
                    }
                }
            }
        assertThat(items).isNotEmpty()
        return h.project(items, "Spasht Dhaara", caption = "Welcome to Spasht Dhaara")
    }

    /** MAIN's pieces, the transitions' midpoints, the caption and the ending, in seconds of the export. */
    private fun writeTimeline(project: com.itsluminous.clearmovie.domain.model.Project) {
        val set = runBlocking { ProjectMedia(h.app.presetStore, h.app.appFiles).forProject(project) }
        val plan = RenderPlanBuilder(set.preset, set.loudness).build(project, height = 1080)
        val lines = mutableListOf("duration ${plan.durationUs / 1e6}")
        val main = plan.visual.filter { it.role == LayerRole.MAIN || it.role == LayerRole.MAIN_B }
        val pieces =
            main
                .flatMap { l ->
                    l.segments.filterIsInstance<VisualSegment.Picture>().map { l.role to it }
                }.sortedBy { it.second.startUs }
        pieces.forEach { (role, p) -> lines += "main $role ${p.startUs / 1e6} ${p.endUs / 1e6} ${p.source::class.simpleName}" }
        pieces.zipWithNext().forEach { (a, b) ->
            if (b.second.startUs <
                a.second.endUs
            ) {
                lines += "transition-mid ${(b.second.startUs + a.second.endUs) / 2e6}"
            }
        }
        plan.visual.filter { it.role == LayerRole.TEXT }.flatMap { it.segments.filterIsInstance<VisualSegment.Picture>() }.forEach {
            lines += "caption ${it.startUs / 1e6} ${it.endUs / 1e6}"
        }
        plan.visual.filter { it.role == LayerRole.OVERLAY }.flatMap { it.segments.filterIsInstance<VisualSegment.Picture>() }.forEach {
            lines += "frame ${it.startUs / 1e6} ${it.endUs / 1e6}"
        }
        plan.audio.forEach { layer ->
            layer.segments.forEach {
                lines +=
                    "audio ${layer.role} ${it.startUs / 1e6} ${it.endUs / 1e6} ${it::class.simpleName}"
            }
        }
        val out = File(File(checkNotNull(h.context.getExternalFilesDir(null)), "render-tests").apply { mkdirs() }, "realmedia-timeline.txt")
        out.writeText(lines.joinToString("\n"))
        Log.i(ExportHarness.TAG, "TIMELINE\n" + lines.joinToString("\n"))
    }
}
