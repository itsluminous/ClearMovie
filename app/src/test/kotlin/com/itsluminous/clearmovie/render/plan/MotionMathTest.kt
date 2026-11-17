package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Anim
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.TransitionKind
import org.junit.Test
import kotlin.math.abs

/**
 * The effect curves the export's shader, the preview and the picker tiles
 * all draw (ROADMAP cm-effects: the shapes at each transition's midpoint, and
 * every animation at known timestamps).
 */
class MotionMathTest {
    private val aspect = 16.0 / 9.0
    private val window = NormRect(0.0094, 0.0222, 0.9813, 0.7741)
    private val len = 1_000_000L

    private fun part(
        kind: TransitionKind,
        role: TransitionRole,
        upper: Boolean,
    ) = TransitionPart(kind, 0, len, role, upper)

    private fun incoming(
        kind: TransitionKind,
        t: Long,
        rect: NormRect = window,
    ) = MotionMath.frameAt(PictureMotion(3 * len, rect, true, transitionIn = part(kind, TransitionRole.IN, true)), t, aspect)

    private fun outgoing(
        kind: TransitionKind,
        t: Long,
        rect: NormRect = window,
    ) = MotionMath.frameAt(PictureMotion(len, rect, true, transitionOut = part(kind, TransitionRole.OUT, false)), t, aspect)

    @Test
    fun `crossfade fades the upper picture in, the lower one stays`() {
        assertThat(incoming(TransitionKind.CROSSFADE, 0).alpha).isEqualTo(0f)
        assertThat(incoming(TransitionKind.CROSSFADE, len / 2).alpha).isWithin(1e-6f).of(0.5f)
        assertThat(incoming(TransitionKind.CROSSFADE, len).alpha).isEqualTo(1f)
        assertThat(outgoing(TransitionKind.CROSSFADE, len / 2).alpha).isEqualTo(1f)
    }

    @Test
    fun `fade through black and white reaches the colour at the midpoint`() {
        for ((kind, colour) in listOf(TransitionKind.FADE_BLACK to ArgbColor.BLACK, TransitionKind.FADE_WHITE to ArgbColor.WHITE)) {
            val outMid = outgoing(kind, len / 2)
            assertThat(outMid.tint).isEqualTo(colour)
            assertThat(outMid.tintAmount).isEqualTo(1f)
            assertThat(outgoing(kind, len / 4).tintAmount).isWithin(1e-6f).of(0.5f)
            assertThat(outgoing(kind, 0).tintAmount).isEqualTo(0f)
            // The incoming picture is hidden for the first half, then comes out of the colour.
            assertThat(incoming(kind, len / 4).alpha).isEqualTo(0f)
            assertThat(incoming(kind, len / 2).alpha).isEqualTo(1f)
            assertThat(incoming(kind, len / 2).tintAmount).isEqualTo(1f)
            assertThat(incoming(kind, 3 * len / 4).tintAmount).isWithin(1e-6f).of(0.5f)
            assertThat(incoming(kind, len).tintAmount).isEqualTo(0f)
        }
    }

    @Test
    fun `wipes put the edge half way across the window at the midpoint, each its own way`() {
        val midX = (window.x + window.w / 2).toFloat()
        val midY = (window.y + window.h / 2).toFloat()
        val left = incoming(TransitionKind.WIPE_LEFT, len / 2).mask as Mask.Wipe
        assertThat(left.vertical).isFalse()
        assertThat(left.edge).isWithin(1e-5f).of(midX)
        assertThat(left.keepAfter).isTrue()
        val right = incoming(TransitionKind.WIPE_RIGHT, len / 2).mask as Mask.Wipe
        assertThat(right.edge).isWithin(1e-5f).of(midX)
        assertThat(right.keepAfter).isFalse()
        val up = incoming(TransitionKind.WIPE_UP, len / 2).mask as Mask.Wipe
        assertThat(up.vertical).isTrue()
        assertThat(up.edge).isWithin(1e-5f).of(midY)
        assertThat(up.keepAfter).isTrue()
        val down = incoming(TransitionKind.WIPE_DOWN, len / 2).mask as Mask.Wipe
        assertThat(down.keepAfter).isFalse()
        // Wipe left starts at the right edge (nothing shows) and ends at the left edge (all shows).
        assertThat((incoming(TransitionKind.WIPE_LEFT, 0).mask as Mask.Wipe).edge).isWithin(1e-5f).of((window.x + window.w).toFloat())
        assertThat((incoming(TransitionKind.WIPE_LEFT, len).mask as Mask.Wipe).edge).isWithin(1e-5f).of(window.x.toFloat())
        // The lower picture is never masked.
        assertThat(outgoing(TransitionKind.WIPE_LEFT, len / 2).mask).isEqualTo(Mask.None)
    }

    @Test
    fun `slides move both pictures half a window at the midpoint, the same way`() {
        val w = window.w.toFloat()
        val h = window.h.toFloat()
        for ((kind, dir) in listOf(
            TransitionKind.SLIDE_LEFT to (-1f to 0f),
            TransitionKind.SLIDE_RIGHT to (1f to 0f),
            TransitionKind.SLIDE_UP to (0f to -1f),
            TransitionKind.SLIDE_DOWN to (0f to 1f),
        )) {
            val o = outgoing(kind, len / 2)
            val i = incoming(kind, len / 2)
            assertThat(o.dx).isWithin(1e-5f).of(dir.first * w / 2)
            assertThat(o.dy).isWithin(1e-5f).of(dir.second * h / 2)
            assertThat(i.dx).isWithin(1e-5f).of(-dir.first * w / 2)
            assertThat(i.dy).isWithin(1e-5f).of(-dir.second * h / 2)
            // Where they meet: the outgoing picture's trailing edge is the incoming one's leading edge.
            assertThat(o.dx - i.dx).isWithin(1e-5f).of(dir.first * w)
            assertThat(o.clipRect).isEqualTo(window)
            assertThat(incoming(kind, len).dx).isWithin(1e-6f).of(0f)
            assertThat(incoming(kind, len).dy).isWithin(1e-6f).of(0f)
        }
    }

    @Test
    fun `the circle's radius is half the corner distance at the midpoint`() {
        val c = incoming(TransitionKind.CIRCLE, len / 2).mask as Mask.Circle
        val full = MotionMath.cornerDistance(window, aspect)
        assertThat(c.radius).isWithin(1e-5f).of(full / 2)
        assertThat(c.inside).isTrue()
        assertThat(c.cx).isWithin(1e-6f).of((window.x + window.w / 2).toFloat())
        assertThat((incoming(TransitionKind.CIRCLE, len).mask as Mask.Circle).radius).isWithin(1e-5f).of(full)
    }

    @Test
    fun `zoom in grows the outgoing picture to 1_2 at the midpoint while the incoming one fades in`() {
        assertThat(outgoing(TransitionKind.ZOOM_IN, len / 2).scale).isWithin(0.012f).of(1.2f)
        assertThat(outgoing(TransitionKind.ZOOM_IN, len).scale).isWithin(1e-5f).of(1.4f)
        assertThat(incoming(TransitionKind.ZOOM_IN, len / 2).alpha).isWithin(1e-6f).of(0.5f)
        assertThat(incoming(TransitionKind.ZOOM_IN, len / 2).scale).isEqualTo(1f)
    }

    private fun anim(
        kind: AnimKind,
        t: Long,
        durationMs: Long = 500,
        clipUs: Long = 3_000_000,
        rect: NormRect = window,
        clip: Boolean = true,
    ): MotionFrame {
        val a = Anim(kind, durationMs)
        val m =
            when {
                kind.slots.contains(com.itsluminous.clearmovie.domain.model.AnimSlot.START) -> PictureMotion(clipUs, rect, clip, start = a)
                kind.slots.contains(com.itsluminous.clearmovie.domain.model.AnimSlot.END) -> PictureMotion(clipUs, rect, clip, end = a)
                else -> PictureMotion(clipUs, rect, clip, during = a)
            }
        return MotionMath.frameAt(m, t, aspect)
    }

    @Test
    fun `fade in and out at 0, 50 and 100 percent of their length`() {
        assertThat(anim(AnimKind.FADE_IN, 0).alpha).isEqualTo(0f)
        assertThat(anim(AnimKind.FADE_IN, 250_000).alpha).isWithin(1e-6f).of(0.5f)
        assertThat(anim(AnimKind.FADE_IN, 500_000).alpha).isEqualTo(1f)
        assertThat(anim(AnimKind.FADE_IN, 2_000_000).alpha).isEqualTo(1f)
        assertThat(anim(AnimKind.FADE_OUT, 2_500_000).alpha).isEqualTo(1f)
        assertThat(anim(AnimKind.FADE_OUT, 2_750_000).alpha).isWithin(1e-6f).of(0.5f)
        assertThat(anim(AnimKind.FADE_OUT, 3_000_000).alpha).isEqualTo(0f)
    }

    @Test
    fun `slide in starts a window away and ends in place, slide out leaves the same way`() {
        val w = window.w.toFloat()
        assertThat(anim(AnimKind.SLIDE_IN_L, 0).dx).isWithin(1e-6f).of(-w)
        assertThat(anim(AnimKind.SLIDE_IN_R, 0).dx).isWithin(1e-6f).of(w)
        assertThat(anim(AnimKind.SLIDE_IN_B, 0).dy).isWithin(1e-6f).of(window.h.toFloat())
        assertThat(anim(AnimKind.SLIDE_IN_L, 500_000).dx).isWithin(1e-6f).of(0f)
        assertThat(anim(AnimKind.SLIDE_OUT_R, 3_000_000).dx).isWithin(1e-6f).of(w)
        assertThat(anim(AnimKind.SLIDE_OUT_L, 2_500_000).dx).isWithin(1e-6f).of(0f)
        // A caption, not clipped to its box, travels until it is off the canvas.
        val box = NormRect(0.16, 0.62, 0.81, 0.14)
        assertThat(anim(AnimKind.SLIDE_IN_L, 0, rect = box, clip = false).dx).isWithin(1e-6f).of(-(box.x + box.w).toFloat())
    }

    @Test
    fun `zoom in grows from 0_6 to 1 along its curve, zoom out shrinks back`() {
        val s0 = anim(AnimKind.ZOOM_IN, 0).scale
        val sMid = anim(AnimKind.ZOOM_IN, 250_000).scale
        val sEnd = anim(AnimKind.ZOOM_IN, 500_000).scale
        assertThat(s0).isWithin(0.01f * s0).of(MotionMath.ANIM_ZOOM_FROM)
        // Ease-out: 1 - (1 - 0.5)^3 = 0.875 of the way at half time.
        assertThat(sMid).isWithin(0.01f * sMid).of(0.6f + 0.4f * 0.875f)
        assertThat(sEnd).isWithin(0.01f).of(1f)
        assertThat(anim(AnimKind.ZOOM_OUT, 3_000_000).scale).isWithin(0.01f).of(0.6f)
        assertThat(anim(AnimKind.ZOOM_OUT, 2_500_000).scale).isWithin(0.01f).of(1f)
    }

    @Test
    fun `Ken Burns crops the window by its zoom and pans inside it, at three timestamps`() {
        val clipUs = 3_000_000L
        val m = PictureMotion(clipUs, window, true, during = Anim(AnimKind.KEN_BURNS_IN, seed = 0))
        val start = MotionMath.frameAt(m, 0, aspect).visibleCrop(window)
        val mid = MotionMath.frameAt(m, clipUs / 2, aspect).visibleCrop(window)
        val end = MotionMath.frameAt(m, clipUs, aspect).visibleCrop(window)
        assertThat(start.w).isWithin(1e-9).of(window.w)
        assertThat(mid.w).isWithin(1e-6).of(window.w / (1 + MotionMath.KEN_BURNS_ZOOM / 2))
        assertThat(end.w).isWithin(1e-6).of(window.w / (1 + MotionMath.KEN_BURNS_ZOOM))
        // At the midpoint the crop is centred; it drifts and always stays inside the window.
        assertThat(mid.x + mid.w / 2).isWithin(1e-6).of(window.x + window.w / 2)
        for (crop in listOf(start, mid, end)) {
            assertThat(crop.x).isAtLeast(window.x - 1e-9)
            assertThat(crop.y).isAtLeast(window.y - 1e-9)
            assertThat(crop.x + crop.w).isAtMost(window.x + window.w + 1e-9)
            assertThat(crop.y + crop.h).isAtMost(window.y + window.h + 1e-9)
        }
        assertThat(end.x + end.w / 2).isNotWithin(1e-4).of(window.x + window.w / 2)
        // Gentle zoom out runs the other way.
        val out = PictureMotion(clipUs, window, true, during = Anim(AnimKind.KEN_BURNS_OUT, seed = 1))
        assertThat(MotionMath.frameAt(out, 0, aspect).scale).isWithin(1e-6f).of(1 + MotionMath.KEN_BURNS_ZOOM)
        assertThat(MotionMath.frameAt(out, clipUs, aspect).scale).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `Ken Burns pans four different ways by seed`() {
        assertThat((0..3).map { MotionMath.panDirection(it) }.toSet()).hasSize(4)
        assertThat(MotionMath.panDirection(-3)).isEqualTo(MotionMath.panDirection(1))
    }

    @Test
    fun `pop overshoots then settles, bounce lands, pulse breathes`() {
        val pop = (0..50).map { anim(AnimKind.POP, it * 10_000L).scale }
        assertThat(pop.first()).isWithin(1e-6f).of(0f)
        assertThat(pop.max()).isGreaterThan(1.05f)
        assertThat(pop.last()).isWithin(1e-5f).of(1f)
        val box = NormRect(0.16, 0.62, 0.81, 0.14)
        assertThat(anim(AnimKind.BOUNCE, 0, rect = box, clip = false).dy).isWithin(1e-6f).of(-(box.y + box.h).toFloat())
        assertThat(anim(AnimKind.BOUNCE, 500_000, rect = box, clip = false).dy).isWithin(1e-6f).of(0f)
        val pulse = (0..24).map { anim(AnimKind.PULSE, it * 50_000L).scale }
        assertThat(pulse.min()).isAtLeast(1f - 1e-6f)
        assertThat(pulse.max()).isWithin(0.002f).of(1 + MotionMath.PULSE_AMOUNT)
    }

    @Test
    fun `animations combine with a transition, and still pictures do not move`() {
        val m =
            PictureMotion(
                3 * len,
                window,
                true,
                during = Anim(AnimKind.KEN_BURNS_IN),
                transitionOut = TransitionPart(TransitionKind.ZOOM_IN, 2 * len, 3 * len, TransitionRole.OUT, false),
            )
        val f = MotionMath.frameAt(m, 5 * len / 2, aspect)
        val kb = 1 + MotionMath.KEN_BURNS_ZOOM * (5f / 6f)
        assertThat(f.scale).isWithin(1e-4f).of(kb * 1.2f)
        val still = MotionMath.frameAt(PictureMotion(len, window, true), len / 2, aspect)
        assertThat(still.scale).isEqualTo(1f)
        assertThat(abs(still.dx) + abs(still.dy)).isEqualTo(0f)
        assertThat(still.clipRect).isEqualTo(window)
        // Outside a transition's span the picture is untouched by it.
        assertThat(
            MotionMath
                .frameAt(
                    PictureMotion(3 * len, window, true, transitionIn = part(TransitionKind.SLIDE_LEFT, TransitionRole.IN, true)),
                    2 * len,
                    aspect,
                ).dx,
        ).isEqualTo(0f)
    }
}
