package com.itsluminous.clearmovie.render

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.plan.RenderPlan
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith

/** Exploratory: which compositions the preview player plays. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreviewExploreTest {
    private fun play(
        name: String,
        plan: RenderPlan,
    ) {
        PreviewProbe(plan, RenderHarness.resolver()).use { p ->
            val before = p.frameCount.get()
            p.onMain { it.play() }
            SystemClock.sleep(3000)
            val pos = p.onMain { it.currentPosition }
            val state = p.onMain { it.playbackState }
            p.onMain { it.pause() }
            val seeks = listOf(500_000L, 1_500_000L, 2_500_000L).map { p.pausedSeek(it) }
            report("explore $name: paused seeks new-frame ms=$seeks; visual=${plan.visual.size} audio=${plan.audio.size} frames in 3s=${p.frameCount.get() - before} pos=$pos state=$state err=${p.error.get()?.errorCodeName}")
        }
    }

    @Test
    fun exploreSimple() {
        val ids = SequentialIds("e")
        val a = com.itsluminous.clearmovie.domain.model.ClipId("ea")
        val xfade =
            ProjectFactory
                .blank(ProjectId("e1"), ids)
                .addToMain(
                    listOf(
                        VideoClip(a, fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                        VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000),
                    ),
                    ids = ids,
                ).setTransition(a, com.itsluminous.clearmovie.domain.model.Transition(com.itsluminous.clearmovie.domain.model.TransitionKind.CROSSFADE, 1000))
        val px = RenderPlanBuilder().build(xfade)
        play("crossfade-no-audio", px.copy(audio = emptyList()))
        play("crossfade", px)
        val two =
            ProjectFactory.blank(ProjectId("e2"), ids).addToMain(
                listOf(
                    VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                    VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000),
                ),
                ids = ids,
            )
        play("two-videos-no-transition", RenderPlanBuilder().build(two))
        val text =
            two.addTimedClip(com.itsluminous.clearmovie.domain.model.TextClip(ids.clipId(), 0, 4000, "Hi"), ids = ids)
        play("two-videos+text", RenderPlanBuilder().build(text))
        val pip3 =
            text
                .addTimedClip(PhotoClip(ids.clipId(), fixture("photo_blue.png"), 3000, pip = PipPlacement(Corner.TOP_RIGHT, 0.3)), ids = ids)
                .addTimedClip(PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 3000, pip = PipPlacement(Corner.TOP_LEFT, 0.3)), ids = ids)
                .addTimedClip(PhotoClip(ids.clipId(), fixture("photo_blue.png"), 3000, pip = PipPlacement(Corner.BOTTOM_LEFT, 0.3)), ids = ids)
        val p5 = RenderPlanBuilder().build(pip3)
        play("five-layers-photos", p5)
    }

    private fun rich(): RenderPlan {
        val ids = SequentialIds("r")
        val a = com.itsluminous.clearmovie.domain.model.ClipId("ra")
        val project =
            ProjectFactory
                .fromPreset(ProjectId("rich"), RenderHarness.preset, PresetMedia(introMs = 2000, frameMs = 12000, endingMs = 3000), ids)
                .addToMain(
                    listOf(
                        VideoClip(a, fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                        VideoClip(ids.clipId(), fixture("clip_light.mp4"), sourceDurationMs = 3000),
                        PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 3000),
                    ),
                    ids = ids,
                ).setTransition(a, com.itsluminous.clearmovie.domain.model.Transition(com.itsluminous.clearmovie.domain.model.TransitionKind.CROSSFADE, 1000))
                .addTimedClip(PhotoClip(ids.clipId(), fixture("photo_blue.png"), 4000, startMs = 3000, pip = PipPlacement(Corner.TOP_RIGHT, 0.3)), ids = ids)
                .addTimedClip(com.itsluminous.clearmovie.domain.model.TextClip(ids.clipId(), 2500, 5000, "Preview"), ids = ids)
        return RenderPlanBuilder(RenderHarness.preset).build(project)
    }

    private fun playThrough(
        name: String,
        plan: RenderPlan,
        seconds: Int,
    ) {
        PreviewProbe(plan, RenderHarness.resolver()).use { p ->
            p.onMain { it.play() }
            val samples = mutableListOf<String>()
            repeat(seconds) {
                SystemClock.sleep(1000)
                samples += "${p.onMain { it.currentPosition }}/${p.onMain { it.playbackState }}/${p.frameCount.get()}"
            }
            report("explore $name play (pos/state/frames each s): $samples err=${p.error.get()?.errorCodeName}")
        }
    }

    @Test
    fun boundaries() {
        val r = rich()
        playThrough("rich-no-audio", r.copy(audio = emptyList()), 15)
        playThrough("rich-audio-only-main", r.copy(audio = r.audio.take(2)), 15)
        val ids = SequentialIds("b")
        val vidThenPhoto =
            ProjectFactory.blank(ProjectId("b1"), ids).addToMain(
                listOf(
                    VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                    PhotoClip(ids.clipId(), fixture("photo_blue.png"), 3000),
                ),
                ids = ids,
            )
        playThrough("video-then-photo", RenderPlanBuilder().build(vidThenPhoto), 8)
        playThrough("video-then-photo-no-audio", RenderPlanBuilder().build(vidThenPhoto).copy(audio = emptyList()), 8)
    }

    @Test
    fun primaryVsSecondary() {
        val ids = SequentialIds("q")
        val base =
            ProjectFactory.blank(ProjectId("q1"), ids).addToMain(
                listOf(
                    VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000),
                    PhotoClip(ids.clipId(), fixture("photo_blue.png"), 3000),
                ),
                ids = ids,
            ).addTimedClip(PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 6000, pip = PipPlacement(Corner.TOP_RIGHT, 0.3)), ids = ids)
        val pipRow = base.tracks.single { it.type == com.itsluminous.clearmovie.domain.model.TrackType.VISUAL }
        playThrough("main-secondary(pip on top)", RenderPlanBuilder().build(base).copy(audio = emptyList()), 8)
        val mainOnTop = base.copy(stacking = listOf(pipRow.id) + base.stacking.filter { it != pipRow.id })
        playThrough("main-primary(pip below)", RenderPlanBuilder().build(mainOnTop).copy(audio = emptyList()), 8)
    }

    @Test
    fun bisect() {
        val r = rich().let { it.copy(audio = emptyList()) }
        report("explore bisect layers bottom-first: ${r.visual.map { it.role }}")
        for (drop in r.visual.indices) {
            playThrough("rich-without-${r.visual[drop].role}", r.copy(visual = r.visual.filterIndexed { i, _ -> i != drop }), 8)
        }
    }

    @Test
    fun richPlaysThrough() {
        PreviewProbe(rich(), RenderHarness.resolver()).use { p ->
            p.onMain { it.play() }
            val samples = mutableListOf<String>()
            repeat(16) {
                SystemClock.sleep(1000)
                samples += "${p.onMain { it.currentPosition }}/${p.onMain { it.playbackState }}/${p.frameCount.get()}"
            }
            report("explore rich play from 0 (pos/state/frames each s): $samples err=${p.error.get()?.errorCodeName}")
        }
    }

    @Test
    fun scrubbingModeSeeks() {
        PreviewProbe(rich(), RenderHarness.resolver()).use { p ->
            p.onMain { it.setScrubbingModeEnabled(true) }
            val seeks = listOf(1_000_000L, 3_200_000L, 4_500_000L, 6_000_000L, 8_500_000L, 11_500_000L).map { p.pausedSeek(it, 5000) to p.lastFrame()?.timeUs }
            report("explore rich scrubbing seeks (ms to new frame, frame time): $seeks")
            p.onMain { it.setScrubbingModeEnabled(false) }
        }
    }

    @Test
    fun simpleSeekThenPlay() {
        val ids = SequentialIds("w")
        val project =
            ProjectFactory
                .blank(ProjectId("w1"), ids)
                .addToMain(listOf(PhotoClip(ids.clipId(), fixture("photo_blue.png"), 8000)), ids = ids)
                .addTimedClip(PhotoClip(ids.clipId(), fixture("photo_quadrants.png"), 8000, pip = PipPlacement(Corner.TOP_RIGHT, 0.3)), ids = ids)
        seekThenPlay("photos-only-2-layers", RenderPlanBuilder().build(project).copy(audio = emptyList()))
        val one =
            ProjectFactory.blank(ProjectId("w2"), ids).addToMain(listOf(VideoClip(ids.clipId(), fixture("clip_dark.mp4"), sourceDurationMs = 3000, loops = 3)), ids = ids)
        seekThenPlay("one-video-looped-plus-fill", RenderPlanBuilder().build(one).copy(audio = emptyList()))
    }

    @Test
    fun baselineSingleInput() {
        val f = java.io.File(RenderHarness.fixtureDir, "clip_dark.mp4")
        val item =
            androidx.media3.transformer.EditedMediaItem
                .Builder(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(f)))
                .setDurationUs(3_000_000)
                .build()
        val seq = androidx.media3.transformer.EditedMediaItemSequence.Builder(item, item, item).build()
        val comp = androidx.media3.transformer.Composition.Builder(seq).build()
        val dummy = RenderPlanBuilder().build(ProjectFactory.blank(ProjectId("z"), SequentialIds("z")).addToMain(listOf(PhotoClip(com.itsluminous.clearmovie.domain.model.ClipId("z9"), fixture("photo_blue.png"), 9000))))
        seekThenPlay("baseline-single-input-default-player", dummy, comp, true)
        seekThenPlay("baseline-single-sequence-multi-input-player", dummy, comp, false)
    }

    @Test
    fun playPauseSeeks() = seekThenPlay("rich", rich())

    private fun seekThenPlay(
        name: String,
        plan: RenderPlan,
        composition: androidx.media3.transformer.Composition? = null,
        singleInput: Boolean = false,
    ) {
        PreviewProbe(plan, RenderHarness.resolver(), composition = composition, singleInput = singleInput).use { p ->
            val out = mutableListOf<String>()
            for (t in listOf(1_000_000L, 3_200_000L, 6_000_000L, 7_500_000L).filter { it < plan.durationUs }) {
                val before = p.frameCount.get()
                p.onMain {
                    it.seekTo(t / 1000)
                    it.play()
                }
                SystemClock.sleep(1500)
                p.onMain { it.pause() }
                out += "seek ${t / 1000}: +${p.frameCount.get() - before} frames, last at ${p.lastFrame()?.timeUs}, state ${p.onMain { it.playbackState }}"
            }
            report("explore $name seek+play: $out")
        }
    }
}
