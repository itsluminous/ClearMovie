package com.itsluminous.clearmovie.export

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.project.ProjectMedia
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.ResizeEdge
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.attachSpan
import com.itsluminous.clearmovie.domain.model.moveMainClip
import com.itsluminous.clearmovie.domain.model.resizeClip
import com.itsluminous.clearmovie.domain.model.setTalkLowering
import com.itsluminous.clearmovie.domain.model.setTrackMuted
import com.itsluminous.clearmovie.domain.model.setVolume
import com.itsluminous.clearmovie.domain.model.trimVideo
import com.itsluminous.clearmovie.domain.model.validate
import com.itsluminous.clearmovie.render.plan.LayerRole
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.plan.VisualSegment
import com.itsluminous.clearmovie.ui.editor.percentToDb
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
 *
 * With `-e cm.captions true` (cm-captions) it exports the same project at
 * Best with an English caption attached to the first item and a Hindi one to
 * the second (`captions-before.mp4`), then with the first item moved to the
 * end (`captions-after.mp4`), each with its timeline; the reordered project
 * is left open in the editor, for the preview frames at the same times.
 *
 * With `-e cm.handles true` (cm-timeline-handles) it exports, at Small:
 * captions with one side detached by its handle before and after the item
 * before them is trimmed (`handles-text-before.mp4`, `handles-text-after.mp4`),
 * and the frame's music lowered for a voice clip at 50% and at 5%, once
 * (the main row muted, so only the voice lowers it), once
 * with the voice heard (`duck-voice-50.mp4`, `duck-voice-5.mp4`) and once
 * with the voice turned all the way down, so the music's own level can be
 * measured under it (`duck-quiet-50.mp4`, `duck-quiet-5.mp4`), each with its
 * timeline.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RealMediaExportTest {
    private val h = ExportHarness()
    private val dir get() = File(checkNotNull(h.context.getExternalFilesDir(null)), "realmedia")
    private val demo = InstrumentationRegistry.getArguments().getString("cm.demo") == "true"
    private val captions = InstrumentationRegistry.getArguments().getString("cm.captions") == "true"
    private val handles = InstrumentationRegistry.getArguments().getString("cm.handles") == "true"

    @Before
    fun setUp() {
        val enabled = demo || captions || handles || InstrumentationRegistry.getArguments().getString("cm.realmedia") == "true"
        assumeTrue("Run by hand with -e cm.realmedia true and the files in realmedia/", enabled && File(dir, "Intro.mp4").isFile)
        h.setUp()
    }

    @After
    fun tearDown() {
        if (File(dir, "Intro.mp4").isFile && !demo && !captions && !handles) h.cleanUp()
    }

    private fun file(name: String) = File(dir, name)

    @Test
    fun exportEnglishAndHindiCaptionsBeforeAndAfterAReorder() {
        assumeTrue("Run with -e cm.captions true", captions)
        val items = realItems()
        assertThat(items.size).isAtLeast(2)
        val made = h.project(items, "Spasht Dhaara", caption = null)
        val english = "Welcome to Spasht Dhaara"
        val hindi = "स्पष्ट धारा में आपका स्वागत है"
        val before = made.addCaptionFor(items[0].id, english).addCaptionFor(items[1].id, hindi)
        val after = before.moveMainClip(items[0].id, items.lastIndex)
        for ((name, project) in listOf("captions-before" to before, "captions-after" to after)) {
            val stored =
                project.copy(
                    id =
                        com.itsluminous.clearmovie.domain.model
                            .ProjectId(RandomIds.next()),
                )
            assertThat(runBlocking { h.app.projectRepository.createDraft(stored, inEditor = name == "captions-after") }).isTrue()
            writeTimeline(stored, "$name-timeline.txt")
            val outcome = runBlocking { h.pipeline().run(stored.id, ExportQuality.BEST, name) {} }
            val done = (outcome as ExportOutcome.Done).done
            h.exported(done.uri)
            h.keep(done.uri, "$name.mp4")
            Log.i(ExportHarness.TAG, "CAPTIONS $name $done")
        }
    }

    @Test
    fun exportDetachedCaptionsAndTwoTalkLevels() {
        assumeTrue("Run with -e cm.handles true", handles)
        // Three videos of 4 s each, so the exports stay short.
        // (A file whose length the phone can't read is left out.)
        val items =
            realItems()
                .filterIsInstance<VideoClip>()
                .filter { it.sourceDurationMs >= 4000 }
                .take(3)
                .map { it.copy(trimEndMs = 4000L) }
        assertThat(items.size).isEqualTo(3)
        val base = h.project(items, "Handles", caption = null)
        // "Start detached" on the second video, its start dragged 1.5 s later; "End detached" on the third, its end 1 s earlier.
        var text = base.addCaptionFor(items[1].id, "Start detached", captionId = ClipId("cap-start"))
        text = text.addCaptionFor(items[2].id, "End detached", captionId = ClipId("cap-end"))
        text = text.resizeClip(ClipId("cap-start"), ResizeEdge.START, text.attachSpan(items[1].id)!!.first + 1500)
        text = text.resizeClip(ClipId("cap-end"), ResizeEdge.END, text.attachSpan(items[2].id)!!.second - 1000)
        // Then the second video is trimmed 1.5 s shorter: the first caption's end and the second's start follow, the detached sides stay.
        val trimmed = text.trimVideo(items[1].id, 0, items[1].trimEndMs - 1500)
        val speech = h.insert("speech_last.mp3", "audio/mpeg") { file(it).inputStream() }
        val voiceMs =
            android.media.MediaMetadataRetriever().run {
                setDataSource(file("speech_last.mp3").path)
                extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong().also { release() }
            }
        val voiceAt = base.attachSpan(items[0].id)!!.first + 1000
        val voice = AudioClip(ClipId("voice"), MediaRef.Store(speech.toString()), voiceAt, voiceMs, sourceDurationMs = voiceMs)
        val music = base.tracks.single { it.presetRole == PresetRole.FRAME_MUSIC }.id
        val runs = mutableListOf("handles-text-before" to text, "handles-text-after" to trimmed)
        for (percent in listOf(50, 5)) {
            val level = percentToDb(percent.toFloat())
            // MAIN muted, so only the voice lowers the music (its videos would too, at the same level).
            val heard =
                base
                    .addTimedClip(
                        voice,
                        AudioRole.VOICE,
                    ).setTalkLowering(music, lower = true, levelDb = level)
                    .setTrackMuted(base.main.id, true)
            runs += "duck-voice-$percent" to heard
            runs += "duck-quiet-$percent" to heard.setVolume(ClipId("voice"), Limits.MIN_VOLUME_DB)
        }
        for ((name, project) in runs) {
            val stored =
                project.copy(
                    id =
                        com.itsluminous.clearmovie.domain.model
                            .ProjectId(RandomIds.next()),
                )
            assertThat(stored.validate()).isEmpty()
            assertThat(runBlocking { h.app.projectRepository.createDraft(stored, inEditor = false) }).isTrue()
            writeTimeline(stored, "$name-timeline.txt")
            val outcome = runBlocking { h.pipeline().run(stored.id, ExportQuality.SMALL, name) {} }
            val done = (outcome as ExportOutcome.Done).done
            h.exported(done.uri)
            h.keep(done.uri, "$name.mp4")
            Log.i(ExportHarness.TAG, "HANDLES $name $done")
        }
    }

    @Test
    fun exportTheRealPresetProjectAtEverySize() {
        assumeTrue("Not in the captions or handles run", !captions && !handles)
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
            assertThat(h.probe(done.uri).height).isEqualTo(quality.shortSide)
        }
    }

    private fun realProject(): com.itsluminous.clearmovie.domain.model.Project =
        h.project(realItems(), "Spasht Dhaara", caption = "Welcome to Spasht Dhaara")

    private fun realItems(): List<MainClip> {
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
        return items
    }

    /** MAIN's pieces, the transitions' midpoints, the caption and the ending, in seconds of the export. */
    private fun writeTimeline(
        project: com.itsluminous.clearmovie.domain.model.Project,
        fileName: String = "realmedia-timeline.txt",
    ) {
        val set = runBlocking { ProjectMedia(h.app.presetStore, h.app.appFiles).forProject(project) }
        val plan = RenderPlanBuilder(set.preset, set.loudness).build(project, shortSide = 1080)
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
            lines +=
                "caption ${it.startUs / 1e6} ${it.endUs / 1e6} ${(it.source as? com.itsluminous.clearmovie.render.plan.Source.Caption)?.text}"
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
        val out = File(File(checkNotNull(h.context.getExternalFilesDir(null)), "render-tests").apply { mkdirs() }, fileName)
        out.writeText(lines.joinToString("\n"))
        Log.i(ExportHarness.TAG, "TIMELINE\n" + lines.joinToString("\n"))
    }
}
