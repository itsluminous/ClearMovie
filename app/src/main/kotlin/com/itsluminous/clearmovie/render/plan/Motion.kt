package com.itsluminous.clearmovie.render.plan

import com.itsluminous.clearmovie.domain.model.Anim
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.TransitionKind
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Which side of a transition a picture is on. */
enum class TransitionRole { IN, OUT }

/**
 * One picture's part in a transition, in CLIP time: from [startUs] to
 * [endUs] of its own clip. [upper] is true when the picture is in the MAIN B
 * lane, which is drawn over lane A: the upper picture carries the mask or the
 * fade, the lower one only moves (ARCHITECTURE section 7.1).
 */
data class TransitionPart(
    val kind: TransitionKind,
    val startUs: Long,
    val endUs: Long,
    val role: TransitionRole,
    val upper: Boolean,
)

/**
 * What moves one picture over its clip (cm-effects): its START, END and
 * DURING animations and its part in the transitions into and out of it.
 * Movement is measured in [rect], the box the picture is drawn in (the frame
 * window, a picture-in-picture box, a caption box, or the whole canvas):
 * zooms are about its centre and slides travel its width or height. With
 * [clipToRect] (pictures in the frame window) nothing is drawn outside it.
 *
 * The export's shader ([com.itsluminous.clearmovie.render.media3.MotionEffect])
 * and the layered preview both draw [MotionMath.frameAt] of this, so they
 * cannot drift apart.
 */
data class PictureMotion(
    val clipDurationUs: Long,
    val rect: NormRect,
    val clipToRect: Boolean,
    val start: Anim? = null,
    val end: Anim? = null,
    val during: Anim? = null,
    val transitionIn: TransitionPart? = null,
    val transitionOut: TransitionPart? = null,
) {
    val isStill: Boolean get() = start == null && end == null && during == null && transitionIn == null && transitionOut == null
}

/** A region of the canvas that stays visible; the rest is cut away. Coordinates are canvas fractions, y down. */
sealed interface Mask {
    data object None : Mask

    /** Keeps what is past [edge] (x, or y when [vertical]) when [keepAfter], else what is before it. */
    data class Wipe(
        val vertical: Boolean,
        val edge: Float,
        val keepAfter: Boolean,
    ) : Mask

    /**
     * Keeps the inside (or the outside) of a circle at ([cx], [cy]). [radius]
     * is in canvas HEIGHTS, so the circle is round on any canvas.
     */
    data class Circle(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val inside: Boolean,
    ) : Mask
}

/**
 * How a picture is drawn at one moment: scaled by [scale] about the centre
 * of its motion rect, then moved by ([dx], [dy]) canvas fractions; its colour
 * mixed [tintAmount] of the way to [tint]; its opacity times [alpha]; then cut
 * to [mask] and, with [clipRect], to that rect.
 */
data class MotionFrame(
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val alpha: Float = 1f,
    val tint: ArgbColor = ArgbColor.BLACK,
    val tintAmount: Float = 0f,
    val mask: Mask = Mask.None,
    val clipRect: NormRect? = null,
) {
    /**
     * The part of the motion rect that shows inside it (Ken Burns' crop),
     * in canvas fractions: the rect mapped back through the scale and move.
     */
    fun visibleCrop(rect: NormRect): NormRect {
        val cx = rect.x + rect.w / 2
        val cy = rect.y + rect.h / 2
        val w = rect.w / scale
        val h = rect.h / scale
        return NormRect(cx - dx / scale - w / 2, cy - dy / scale - h / 2, w, h)
    }

    companion object {
        val STILL = MotionFrame()
    }
}

/**
 * The effect curves (ARCHITECTURE sections 7.1 and 11.10), pure, so the
 * export, the preview and the picker's tiles all draw the same thing and the
 * curves are tested on the JVM. The progress curves follow the ideas of
 * LibreCuts' preview (alpha for fades, a moving edge for wipes, a translate
 * for slides, a growing circle, scale and alpha for zoom), re-implemented.
 */
object MotionMath {
    /** Ken Burns zooms this much over the item (8%: slow enough for a 3 s photo). */
    const val KEN_BURNS_ZOOM = 0.08f

    /** The share of the room the zoom makes that Ken Burns pans across. */
    const val KEN_BURNS_PAN = 0.7f

    /** "Zoom in" (transition): the outgoing picture grows from 1 to 1.4 while it fades. */
    const val TRANSITION_ZOOM = 0.4f

    /** Start "Zoom in" and end "Zoom out" grow from, or shrink to, this size. */
    const val ANIM_ZOOM_FROM = 0.6f

    /** Pulse: a 4% breath every [PULSE_PERIOD_US]. */
    const val PULSE_AMOUNT = 0.04f
    const val PULSE_PERIOD_US = 1_200_000L

    /** Smooth start and stop, symmetric, so the midpoint is exactly half way. */
    fun ease(p: Float): Float {
        val t = p.coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun easeOut(p: Float): Float {
        val t = 1 - p.coerceIn(0f, 1f)
        return 1 - t * t * t
    }

    private fun easeIn(p: Float): Float {
        val t = p.coerceIn(0f, 1f)
        return t * t * t
    }

    /** Overshoots to about 110% before settling at 1 (Pop). */
    internal fun backOut(p: Float): Float {
        val t = p.coerceIn(0f, 1f) - 1
        val s = 1.70158f
        return 1 + t * t * ((s + 1) * t + s)
    }

    /** Falls and bounces twice before resting at 1 (Bounce). */
    internal fun bounceOut(p: Float): Float {
        val t = p.coerceIn(0f, 1f)
        val n = 7.5625f
        val d = 2.75f
        return when {
            t < 1 / d -> n * t * t
            t < 2 / d -> (t - 1.5f / d).let { n * it * it + 0.75f }
            t < 2.5 / d -> (t - 2.25f / d).let { n * it * it + 0.9375f }
            else -> (t - 2.625f / d).let { n * it * it + 0.984375f }
        }
    }

    private fun progress(
        clipUs: Long,
        fromUs: Long,
        toUs: Long,
    ): Float = if (toUs <= fromUs) (if (clipUs >= toUs) 1f else 0f) else ((clipUs - fromUs).toFloat() / (toUs - fromUs)).coerceIn(0f, 1f)

    /** The Ken Burns pan direction for [seed]: one of four diagonals. */
    fun panDirection(seed: Int): Pair<Float, Float> =
        when (Math.floorMod(seed, 4)) {
            0 -> 1f to 1f
            1 -> -1f to 1f
            2 -> 1f to -1f
            else -> -1f to -1f
        }

    /** The motion [m] at [clipUs] of its clip, on a canvas [aspect] wide per unit of height. */
    fun frameAt(
        m: PictureMotion,
        clipUs: Long,
        aspect: Double,
    ): MotionFrame {
        if (m.isStill) return MotionFrame(clipRect = m.rect.takeIf { m.clipToRect })
        val r = m.rect
        // How far a slide travels: across the rect when it is clipped to it, else until it is off the canvas.
        val leftOff = if (m.clipToRect) r.w.toFloat() else (r.x + r.w).toFloat()
        val rightOff = if (m.clipToRect) r.w.toFloat() else (1 - r.x).toFloat()
        val upOff = if (m.clipToRect) r.h.toFloat() else (r.y + r.h).toFloat()
        val downOff = if (m.clipToRect) r.h.toFloat() else (1 - r.y).toFloat()

        // The animations: scale sA about the centre, then move by (ax, ay).
        var sA = 1f
        var ax = 0f
        var ay = 0f
        var alpha = 1f
        m.during?.let { a ->
            val t = progress(clipUs, 0, m.clipDurationUs)
            when (a.kind) {
                AnimKind.KEN_BURNS_IN, AnimKind.KEN_BURNS_OUT -> {
                    val grow = if (a.kind == AnimKind.KEN_BURNS_IN) t else 1 - t
                    sA = 1 + KEN_BURNS_ZOOM * grow
                    val (px, py) = panDirection(a.seed)
                    // Never further than the zoom leaves room for, so the picture always covers its window.
                    val room = (sA - 1) / 2 * KEN_BURNS_PAN
                    val drift = 2 * t - 1
                    ax = px * room * r.w.toFloat() * drift
                    ay = py * room * r.h.toFloat() * drift
                }

                AnimKind.PULSE -> {
                    sA = 1 + PULSE_AMOUNT * sin(2 * PI * clipUs / PULSE_PERIOD_US).toFloat().let { (it + 1) / 2 }
                }

                else -> {
                    Unit
                }
            }
        }
        m.start?.let { a ->
            val p = progress(clipUs, 0, a.durationMs * US)
            when (a.kind) {
                AnimKind.FADE_IN -> {
                    alpha *= p
                }

                AnimKind.SLIDE_IN_L -> {
                    ax -= leftOff * (1 - easeOut(p))
                }

                AnimKind.SLIDE_IN_R -> {
                    ax += rightOff * (1 - easeOut(p))
                }

                AnimKind.SLIDE_IN_B -> {
                    ay += downOff * (1 - easeOut(p))
                }

                AnimKind.ZOOM_IN -> {
                    sA *= ANIM_ZOOM_FROM + (1 - ANIM_ZOOM_FROM) * easeOut(p)
                    alpha *= p
                }

                AnimKind.POP -> {
                    sA *= backOut(p)
                    alpha *= (p * 3).coerceAtMost(1f)
                }

                AnimKind.BOUNCE -> {
                    ay -= upOff * (1 - bounceOut(p))
                }

                else -> {
                    Unit
                }
            }
        }
        m.end?.let { a ->
            val len = a.durationMs * US
            val p = progress(clipUs, m.clipDurationUs - len, m.clipDurationUs)
            when (a.kind) {
                AnimKind.FADE_OUT -> {
                    alpha *= 1 - p
                }

                AnimKind.SLIDE_OUT_L -> {
                    ax -= leftOff * easeIn(p)
                }

                AnimKind.SLIDE_OUT_R -> {
                    ax += rightOff * easeIn(p)
                }

                AnimKind.SLIDE_OUT_B -> {
                    ay += downOff * easeIn(p)
                }

                AnimKind.ZOOM_OUT -> {
                    sA *= 1 - (1 - ANIM_ZOOM_FROM) * easeIn(p)
                    alpha *= 1 - p
                }

                else -> {
                    Unit
                }
            }
        }

        // The transitions: scale sT about the centre and move (tx, ty), outside the animations.
        var sT = 1f
        var tx = 0f
        var ty = 0f
        var tint = ArgbColor.BLACK
        var tintAmount = 0f
        var mask: Mask = Mask.None
        for (part in listOfNotNull(m.transitionIn, m.transitionOut)) {
            val p = progress(clipUs, part.startUs, part.endUs)
            if (clipUs < part.startUs || clipUs > part.endUs) continue
            val e = ease(p)
            val incoming = part.role == TransitionRole.IN
            when (part.kind) {
                TransitionKind.NONE -> {
                    Unit
                }

                TransitionKind.CROSSFADE -> {
                    if (part.upper) alpha *= if (incoming) p else 1 - p
                }

                TransitionKind.FADE_BLACK, TransitionKind.FADE_WHITE -> {
                    tint = if (part.kind == TransitionKind.FADE_BLACK) ArgbColor.BLACK else ArgbColor.WHITE
                    // The outgoing picture turns to the colour in the first half, the incoming one comes out of it in the second.
                    tintAmount = if (incoming) (2 - 2 * p).coerceIn(0f, 1f) else (2 * p).coerceIn(0f, 1f)
                    if (part.upper) alpha *= if (incoming == (p >= 0.5f)) 1f else 0f
                }

                TransitionKind.WIPE_LEFT, TransitionKind.WIPE_RIGHT, TransitionKind.WIPE_UP, TransitionKind.WIPE_DOWN -> {
                    if (part.upper) mask = wipe(part.kind, r, e, incoming)
                }

                TransitionKind.SLIDE_LEFT -> {
                    tx += if (incoming) r.w.toFloat() * (1 - e) else -r.w.toFloat() * e
                }

                TransitionKind.SLIDE_RIGHT -> {
                    tx += if (incoming) -r.w.toFloat() * (1 - e) else r.w.toFloat() * e
                }

                TransitionKind.SLIDE_UP -> {
                    ty += if (incoming) r.h.toFloat() * (1 - e) else -r.h.toFloat() * e
                }

                TransitionKind.SLIDE_DOWN -> {
                    ty += if (incoming) -r.h.toFloat() * (1 - e) else r.h.toFloat() * e
                }

                TransitionKind.CIRCLE -> {
                    if (part.upper) {
                        val full = cornerDistance(r, aspect)
                        mask = Mask.Circle((r.x + r.w / 2).toFloat(), (r.y + r.h / 2).toFloat(), full * e, inside = incoming)
                    }
                }

                TransitionKind.ZOOM_IN -> {
                    if (!incoming) sT *= 1 + TRANSITION_ZOOM * p
                    if (part.upper) alpha *= if (incoming) p else 1 - p
                }
            }
        }
        return MotionFrame(
            scale = sT * sA,
            dx = tx + sT * ax,
            dy = ty + sT * ay,
            alpha = alpha.coerceIn(0f, 1f),
            tint = tint,
            tintAmount = tintAmount,
            mask = mask,
            clipRect = r.takeIf { m.clipToRect },
        )
    }

    /** The distance from the centre of [r] to a corner, in canvas heights. */
    fun cornerDistance(
        r: NormRect,
        aspect: Double,
    ): Float {
        val hw = r.w * aspect / 2
        val hh = r.h / 2
        return sqrt(hw * hw + hh * hh).toFloat()
    }

    /**
     * The wipe edge at eased progress [e]: the incoming picture shows on the
     * side the wipe comes from ("Wipe left" sweeps from the right edge to the
     * left one).
     */
    private fun wipe(
        kind: TransitionKind,
        r: NormRect,
        e: Float,
        incoming: Boolean,
    ): Mask.Wipe {
        val (vertical, edge, inAfter) =
            when (kind) {
                TransitionKind.WIPE_LEFT -> Triple(false, r.x + r.w * (1 - e), true)
                TransitionKind.WIPE_RIGHT -> Triple(false, r.x + r.w * e, false)
                TransitionKind.WIPE_UP -> Triple(true, r.y + r.h * (1 - e), true)
                else -> Triple(true, r.y + r.h * e, false)
            }
        return Mask.Wipe(vertical, edge.toFloat(), keepAfter = if (incoming) inAfter else !inAfter)
    }

    private const val US = 1000L
}
