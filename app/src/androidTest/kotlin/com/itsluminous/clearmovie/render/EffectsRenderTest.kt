package com.itsluminous.clearmovie.render

import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.Anim
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.AnimSlot
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.setAnimation
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.domain.model.totalDurationMs
import com.itsluminous.clearmovie.render.RenderHarness.fixture
import com.itsluminous.clearmovie.render.RenderHarness.frameAt
import com.itsluminous.clearmovie.render.RenderHarness.luma
import com.itsluminous.clearmovie.render.RenderHarness.report
import com.itsluminous.clearmovie.render.RenderHarness.rgb
import com.itsluminous.clearmovie.render.RenderHarness.savePng
import com.itsluminous.clearmovie.render.RenderHarness.toneDb
import com.itsluminous.clearmovie.render.plan.LayerRole
import com.itsluminous.clearmovie.render.plan.MotionMath
import com.itsluminous.clearmovie.render.plan.RenderPlan
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.render.plan.VisualSegment
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * cm-effects through the real export (Media3 Transformer and the GL shaders,
 * on the emulator's GPU, SwiftShader on CI): every transition at the start,
 * middle and end of its overlap, every animation at known frames against
 * the curve the plan holds, and the sound level across a crossfade. Frames
 * are saved to `render-tests/` for the evidence contact sheets.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class EffectsRenderTest {
    private val height = 360
    private val fps = 30
    private val frameUs = 1_000_000L / fps
    private val dark = 0
    private val light = 1

    private fun frameTimeUs(k: Int) = k * 1_000_000L / fps

    private val tones by lazy {
        val d = luma(rgb(frameAt(File(RenderHarness.fixtureDir, "clip_dark.mp4"), 1_500_000), 0.5, 0.6))
        val l = luma(rgb(frameAt(File(RenderHarness.fixtureDir, "clip_light.mp4"), 1_500_000), 0.5, 0.6))
        d to l
    }

    private fun share(y: Double) = (y - tones.first) / (tones.second - tones.first)

    /** Two 3 s videos, dark into light, with a 1 s [kind] transition: the overlap is 2..3 s. */
    private fun junction(
        kind: TransitionKind,
        firstClip: MainClip = VideoClip(ClipId("a"), fixture("clip_dark.mp4"), sourceDurationMs = 3000),
    ): Project {
        val ids = SequentialIds("j")
        return ProjectFactory
            .blank(ProjectId("fx"), ids)
            .addToMain(listOf(firstClip, VideoClip(ClipId("b"), fixture("clip_light.mp4"), sourceDurationMs = 3000)), ids = ids)
            .setTransition(firstClip.id, Transition(kind, 1000))
    }

    /** The kinds checked in one long export: every one but Zoom in, which needs a picture with edges. */
    private val kinds = TransitionKind.entries - TransitionKind.NONE - TransitionKind.ZOOM_IN

    /**
     * One video with every transition: 3 s clips, alternately dark and light
     * (a 440 Hz and a 550 Hz tone), joined by 1 s transitions, so junction j
     * overlaps from 2j + 2 s to 2j + 3 s. One export keeps the run short on
     * a software-encoding emulator.
     */
    private fun allJunctions(): Project {
        val ids = SequentialIds("j")
        val clips =
            (0..kinds.size).map { i ->
                VideoClip(ClipId("c$i"), fixture(if (i % 2 == 0) "clip_dark.mp4" else "clip_light.mp4"), sourceDurationMs = 3000)
            }
        var p = ProjectFactory.blank(ProjectId("fx"), ids).addToMain(clips, ids = ids)
        kinds.forEachIndexed { j, kind -> p = p.setTransition(clips[j].id, Transition(kind, 1000)) }
        return p
    }

    private val junctions by lazy {
        val project = allJunctions()
        assertThat(project.totalDurationMs()).isEqualTo(3000L * (kinds.size + 1) - 1000L * kinds.size)
        RenderHarness.export(RenderPlanBuilder().build(project, height = height), "fx-all-transitions")
    }

    @Test
    fun everyTransitionBlendsOrMovesBothClipsAsSpecified() {
        val out = junctions
        val failures = mutableListOf<String>()
        val aspect = 16.0 / 9.0
        kinds.forEachIndexed { j, kind ->
            val overlapUs = (2L * j + 2) * 1_000_000
            // Even junctions go dark into light, odd ones light into dark.
            val inLight = j % 2 == 0

            fun incoming(y: Double) = if (inLight) share(y) else 1 - share(y)

            fun incomingAt(
                f: Bitmap,
                x: Double,
                y: Double,
            ) = incoming(luma(rgb(f, x, y)))
            val name = kind.name.lowercase(Locale.ROOT)
            val start = frameAt(out.file, overlapUs + frameUs).also { savePng(it, "fx-$name-start") }
            val mid = frameAt(out.file, overlapUs + 500_000).also { savePng(it, "fx-$name-mid") }
            val end = frameAt(out.file, overlapUs + 1_000_000 - frameUs).also { savePng(it, "fx-$name-end") }
            val sStart = incomingAt(start, 0.5, 0.6)
            val sEnd = incomingAt(end, 0.5, 0.6)
            val line =
                StringBuilder(
                    "transition %s (junction %d, %.1f-%.1f s, %s): incoming share start %.3f end %.3f".format(
                        Locale.ROOT,
                        name,
                        j,
                        overlapUs / 1e6,
                        overlapUs / 1e6 + 1,
                        if (inLight) "dark into light" else "light into dark",
                        sStart,
                        sEnd,
                    ),
                )

            fun fail(why: String) {
                failures += "$name: $why"
            }

            /** Where the incoming picture starts along a line: the first crossing of half way. */
            fun crossing(
                vertical: Boolean,
                at: Double,
                from: Double,
                to: Double,
            ): Double? {
                val n = 400
                var prev: Boolean? = null
                for (i in 0..n) {
                    val q = from + (to - from) * i / n
                    val isIn = incoming(luma(if (vertical) rgb(mid, at, q, 1) else rgb(mid, q, at, 1))) > 0.5
                    if (prev != null && isIn != prev) return q
                    prev = isIn
                }
                return null
            }
            when (kind) {
                TransitionKind.CROSSFADE -> {
                    val sh = incomingAt(mid, 0.5, 0.6)
                    line.append(", mid share %.3f (want 0.4-0.6)".format(Locale.ROOT, sh))
                    if (sh !in 0.4..0.6) fail("mid share $sh")
                    if (sStart > 0.1 || sEnd < 0.9) fail("ends $sStart $sEnd")
                }

                TransitionKind.FADE_BLACK, TransitionKind.FADE_WHITE -> {
                    val y = luma(rgb(mid, 0.5, 0.6))
                    val target = if (kind == TransitionKind.FADE_BLACK) 0.0 else 255.0
                    line.append(", mid luma %.1f (want within 5%% of %.0f)".format(Locale.ROOT, y, target))
                    if (abs(y - target) > 0.05 * 255) fail("mid luma $y")
                    if (sStart > 0.15 || sEnd < 0.85) fail("ends $sStart $sEnd")
                }

                TransitionKind.WIPE_LEFT, TransitionKind.WIPE_RIGHT, TransitionKind.SLIDE_LEFT, TransitionKind.SLIDE_RIGHT -> {
                    val b = crossing(vertical = false, at = 0.6, from = 0.02, to = 0.98)
                    // The incoming picture comes from the right for "left", from the left for "right".
                    val inRight = kind == TransitionKind.WIPE_LEFT || kind == TransitionKind.SLIDE_LEFT
                    val right = incomingAt(mid, 0.8, 0.6)
                    val left = incomingAt(mid, 0.2, 0.6)
                    line.append(
                        ", boundary x %s (want 0.50+-0.02), incoming left %.2f right %.2f".format(
                            Locale.ROOT,
                            b?.let { "%.4f".format(Locale.ROOT, it) },
                            left,
                            right,
                        ),
                    )
                    if (b == null || abs(b - 0.5) > 0.02) fail("boundary $b")
                    if (inRight != (right > 0.5) || inRight == (left > 0.5)) fail("wrong side")
                    if (sStart > 0.15 || sEnd < 0.85) fail("ends $sStart $sEnd")
                }

                TransitionKind.WIPE_UP, TransitionKind.WIPE_DOWN, TransitionKind.SLIDE_UP, TransitionKind.SLIDE_DOWN -> {
                    // From below the clips' moving grey block (their top 10%).
                    val b = crossing(vertical = true, at = 0.5, from = 0.15, to = 0.98)
                    val inBelow = kind == TransitionKind.WIPE_UP || kind == TransitionKind.SLIDE_UP
                    val below = incomingAt(mid, 0.5, 0.8)
                    val above = incomingAt(mid, 0.5, 0.3)
                    line.append(
                        ", boundary y %s (want 0.50+-0.02), incoming above %.2f below %.2f".format(
                            Locale.ROOT,
                            b?.let { "%.4f".format(Locale.ROOT, it) },
                            above,
                            below,
                        ),
                    )
                    if (b == null || abs(b - 0.5) > 0.02) fail("boundary $b")
                    if (inBelow != (below > 0.5) || inBelow == (above > 0.5)) fail("wrong side")
                    if (sStart > 0.15 || sEnd < 0.85) fail("ends $sStart $sEnd")
                }

                TransitionKind.CIRCLE -> {
                    // From the centre to the right: the incoming picture inside the circle.
                    val edge = crossing(vertical = false, at = 0.5, from = 0.5, to = 0.999)
                    val radius = edge?.let { (it - 0.5) * aspect }
                    val want = MotionMath.cornerDistance(NormRect.FULL, aspect) / 2
                    line.append(
                        ", radius %s heights (want %.4f +-2%%)".format(Locale.ROOT, radius?.let { "%.4f".format(Locale.ROOT, it) }, want),
                    )
                    if (radius == null || abs(radius - want) > 0.02 * want) fail("radius $radius")
                    if (incomingAt(mid, 0.5, 0.5) < 0.9) fail("centre is not the incoming picture")
                    if (sEnd < 0.85) fail("end $sEnd")
                }

                else -> {
                    Unit
                }
            }
            report(line.toString())
        }
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }

    /** Zoom in: the outgoing picture (a white box on black) is 1.2 times its size at the midpoint. */
    @Test
    fun zoomInScalesTheOutgoingPictureTo1_2AtTheMidpoint() {
        val project = junction(TransitionKind.ZOOM_IN, PhotoClip(ClipId("a"), fixture("photo_target.png"), 3000))
        val out = RenderHarness.export(RenderPlanBuilder().build(project, height = height), "fx-ZOOM_IN")
        frameAt(out.file, 2_000_000 + frameUs).also { savePng(it, "fx-zoom_in-start") }
        val mid = frameAt(out.file, 2_500_000).also { savePng(it, "fx-zoom_in-mid") }
        frameAt(out.file, 3_000_000 - frameUs).also { savePng(it, "fx-zoom_in-end") }
        val (l, r) = edges(mid, vertical = false, at = 0.5, threshold = 94.0)
        val scale = (r - l) / 0.5
        report("transition zoom_in: box %.4f..%.4f, outgoing scale %.4f (want 1.20 +-1%%)".format(Locale.ROOT, l, r, scale))
        assertThat(scale).isWithin(0.012).of(1.2)
    }

    /** The first and last positions along a line where the luma is over [threshold]. */
    private fun edges(
        f: Bitmap,
        vertical: Boolean,
        at: Double,
        threshold: Double,
    ): Pair<Double, Double> {
        val size = if (vertical) f.height else f.width
        var first = -1
        var last = -1
        for (i in 0 until size) {
            val c = if (vertical) f.getPixel((at * f.width).toInt(), i) else f.getPixel(i, (at * f.height).toInt())
            val y = luma(intArrayOf(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)))
            if (y > threshold) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return Double.NaN to Double.NaN
        return first.toDouble() / size to (last + 1).toDouble() / size
    }

    private data class AnimCase(
        val name: String,
        val clip: MainClip,
        val slot: AnimSlot,
        val anim: Anim,
        val frames: List<Int>,
        /** The bright box of the picture before it moves (a white box on black, or a whole white card). */
        val box: NormRect,
    )

    private val target = NormRect(0.25, 0.25, 0.5, 0.5)

    private fun photo() = PhotoClip(ClipId("ph"), fixture("photo_target.png"), 3000)

    private fun card() = CardClip(ClipId("cd"), 3000, ArgbColor.WHITE, null)

    @Test
    fun everyAnimationFollowsItsCurveAtKnownFrames() {
        val cases =
            listOf(
                AnimCase("fade_in", photo(), AnimSlot.START, Anim(AnimKind.FADE_IN, 600), listOf(0, 9, 18), target),
                AnimCase("fade_out", photo(), AnimSlot.END, Anim(AnimKind.FADE_OUT, 600), listOf(72, 81, 87), target),
                AnimCase("zoom_in", photo(), AnimSlot.START, Anim(AnimKind.ZOOM_IN, 600), listOf(3, 9, 18), target),
                AnimCase("zoom_out", photo(), AnimSlot.END, Anim(AnimKind.ZOOM_OUT, 600), listOf(72, 81, 87), target),
                AnimCase("slide_in_left", photo(), AnimSlot.START, Anim(AnimKind.SLIDE_IN_L, 600), listOf(0, 9, 18), target),
                AnimCase("slide_in_bottom", photo(), AnimSlot.START, Anim(AnimKind.SLIDE_IN_B, 600), listOf(0, 9, 18), target),
                AnimCase("slide_out_right", photo(), AnimSlot.END, Anim(AnimKind.SLIDE_OUT_R, 600), listOf(72, 81, 87), target),
                AnimCase("ken_burns_in", photo(), AnimSlot.DURING, Anim(AnimKind.KEN_BURNS_IN, seed = 0), listOf(0, 45, 89), target),
                AnimCase("ken_burns_out", photo(), AnimSlot.DURING, Anim(AnimKind.KEN_BURNS_OUT, seed = 3), listOf(0, 45, 89), target),
                AnimCase("pop", card(), AnimSlot.START, Anim(AnimKind.POP, 600), listOf(3, 9, 18), NormRect.FULL),
                AnimCase("bounce", card(), AnimSlot.START, Anim(AnimKind.BOUNCE, 600), listOf(3, 9, 18), NormRect.FULL),
                AnimCase("pulse", card(), AnimSlot.DURING, Anim(AnimKind.PULSE), listOf(0, 9, 27), NormRect.FULL),
            )
        // Every case in one video, one item after another (one export keeps the run short).
        val ids = SequentialIds("an")
        val items = cases.mapIndexed { i, c -> c.clip.withId(ClipId("i$i")) as MainClip }
        var project = ProjectFactory.blank(ProjectId("anim"), ids).addToMain(items, ids = ids)
        cases.forEachIndexed { i, c -> project = project.setAnimation(items[i].id, c.slot, c.anim) }
        val plan = RenderPlanBuilder().build(project, height = height)
        val pictures =
            plan.visual
                .single { it.role == LayerRole.MAIN }
                .segments
                .filterIsInstance<VisualSegment.Picture>()
        assertThat(pictures).hasSize(cases.size)
        val out = RenderHarness.export(plan, "anim-all")
        val failures = mutableListOf<String>()
        cases.forEachIndexed { i, c ->
            val picture = pictures[i]
            for (k in c.frames) {
                val t = frameTimeUs(k)
                val f = frameAt(out.file, picture.startUs + t).also { savePng(it, "anim-${c.name}-f%02d".format(Locale.ROOT, k)) }
                val m = picture.motionAt(t, 16.0 / 9.0)
                // The box after the move, clipped to the canvas.
                val l = (0.5 + (c.box.x - 0.5) * m.scale + m.dx).coerceIn(0.0, 1.0)
                val r = (0.5 + (c.box.x + c.box.w - 0.5) * m.scale + m.dx).coerceIn(0.0, 1.0)
                val top = (0.5 + (c.box.y - 0.5) * m.scale + m.dy).coerceIn(0.0, 1.0)
                val bottom = (0.5 + (c.box.y + c.box.h - 0.5) * m.scale + m.dy).coerceIn(0.0, 1.0)
                val visible = r - l > 0.01 && bottom - top > 0.01 && m.alpha > 0.05
                val line =
                    StringBuilder(
                        "anim %s frame %d (t=%.3f s): want scale %.4f dx %.4f dy %.4f alpha %.3f".format(
                            Locale.ROOT,
                            c.name,
                            k,
                            t / 1e6,
                            m.scale,
                            m.dx,
                            m.dy,
                            m.alpha,
                        ),
                    )
                if (!visible) {
                    val brightest = listOf(0.25, 0.5, 0.75).maxOf { y -> listOf(0.25, 0.5, 0.75).maxOf { x -> luma(rgb(f, x, y)) } }
                    line.append(", nothing should show: brightest %.1f".format(Locale.ROOT, brightest))
                    if (brightest > 20) failures += "${c.name}@$k: something shows ($brightest)"
                } else {
                    val midX = (l + r) / 2
                    val midY = (top + bottom) / 2
                    val alpha = luma(rgb(f, midX, midY)) / 255.0
                    val threshold = 255.0 * m.alpha / 2
                    val (ml, mr) = edges(f, vertical = false, at = midY, threshold = threshold)
                    val (mt, mb) = edges(f, vertical = true, at = midX, threshold = threshold)
                    line.append(
                        "; got box x %.4f..%.4f (want %.4f..%.4f) y %.4f..%.4f (want %.4f..%.4f) alpha %.3f".format(
                            Locale.ROOT,
                            ml,
                            mr,
                            l,
                            r,
                            mt,
                            mb,
                            top,
                            bottom,
                            alpha,
                        ),
                    )
                    val px = 3.0 / f.width
                    val wantW = r - l
                    if (abs((mr - ml) - wantW) > maxOf(0.01 * wantW, px)) failures += "${c.name}@$k width ${mr - ml} want $wantW"
                    if (abs(ml - l) > 0.01 || abs(mr - r) > 0.01) failures += "${c.name}@$k x $ml..$mr want $l..$r"
                    if (abs(mt - top) > 0.01 || abs(mb - bottom) > 0.01) failures += "${c.name}@$k y $mt..$mb want $top..$bottom"
                    if (abs(alpha - m.alpha) > 0.07) failures += "${c.name}@$k alpha $alpha want ${m.alpha}"
                }
                report(line.toString())
            }
        }
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }

    /**
     * Sound across a 1 s crossfade of a 440 Hz clip into a 550 Hz one, both
     * at -20 dBFS: each tone follows its equal-power curve and their summed
     * power stays within 1.5 dB of a single clip's. A fade through black dips
     * to silence at its midpoint.
     */
    @Test
    fun soundCrossfadesAtConstantPowerAndStaysInStep() {
        // Junction 0 is the crossfade (dark 440 Hz into light 550 Hz, 2-3 s); junction 1 fades through black (4-5 s).
        assertThat(kinds.take(2)).containsExactly(TransitionKind.CROSSFADE, TransitionKind.FADE_BLACK).inOrder()
        val pcm = RenderHarness.decodeAudio(junctions.file)
        val failures = mutableListOf<String>()
        val trace = StringBuilder("time_s,a440_dbfs,b550_dbfs,total_dbfs\n")
        val base = toneDb(pcm, 440.0, 0.5, 1.5)
        report("audio crossfade: clip a alone %.2f dBFS".format(Locale.ROOT, base))
        var t = 1.5
        while (t < 3.95) {
            val a = toneDb(pcm, 440.0, t, t + 0.1)
            val b = toneDb(pcm, 550.0, t, t + 0.1)
            val total = 10 * log10(10.0.pow(a / 10) + 10.0.pow(b / 10))
            trace.append("%.2f,%.2f,%.2f,%.2f\n".format(Locale.ROOT, t + 0.05, a, b, total))
            if (abs(total - base) > 1.5) failures += "t=$t total $total vs $base"
            t += 0.1
        }
        // In step with the pictures: a quarter in, the outgoing tone is cos(pi/8) = -0.69 dB and the incoming sin(pi/8) = -8.3 dB.
        val aQ = toneDb(pcm, 440.0, 2.2, 2.3) - base
        val bQ = toneDb(pcm, 550.0, 2.2, 2.3) - base
        report("audio crossfade at 2.25 s: a %.2f dB, b %.2f dB relative (want -0.7, -8.3 +-1.5)".format(Locale.ROOT, aQ, bQ))
        if (abs(aQ + 0.69) > 1.5) failures += "a at quarter $aQ"
        if (abs(bQ + 8.34) > 1.5) failures += "b at quarter $bQ"
        // Fade through black: silent at its midpoint, 4.5 s.
        var t2 = 3.5
        while (t2 < 5.95) {
            val a = toneDb(pcm, 440.0, t2, t2 + 0.1)
            val b = toneDb(pcm, 550.0, t2, t2 + 0.1)
            trace.append("%.2f,%.2f,%.2f,%.2f\n".format(Locale.ROOT, t2 + 0.05, a, b, 10 * log10(10.0.pow(a / 10) + 10.0.pow(b / 10))))
            t2 += 0.1
        }
        val dip = 10 * log10(10.0.pow(toneDb(pcm, 440.0, 4.45, 4.55) / 10) + 10.0.pow(toneDb(pcm, 550.0, 4.45, 4.55) / 10))
        report("audio fade through black: level at the midpoint %.2f dBFS (want 15 dB or more below %.2f)".format(Locale.ROOT, dip, base))
        if (dip > base - 15) failures += "fade through black midpoint $dip"
        File(RenderHarness.outDir, "audio-crossfade-trace.csv").writeText(trace.toString())
        report("audio trace:\n$trace")
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }
}
