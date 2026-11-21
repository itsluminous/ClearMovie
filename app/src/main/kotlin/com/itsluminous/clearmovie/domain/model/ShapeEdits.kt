package com.itsluminous.clearmovie.domain.model

/**
 * "Video shape" (cm-aspect-ratio): the video becomes [shape], as one undo
 * step. Photos and videos keep their own fit (crop, blur or bars) and are
 * placed again on the new canvas when it is drawn; picture in picture keeps
 * its corner and size.
 *
 * Captions (and the caption style new ones start from) keep their share of
 * the width and the height of their middle, and get the height their words
 * need at the same text size: text sizes are measured on the canvas's short
 * side, so a box over the same share of a narrower canvas holds fewer words
 * a line and needs more lines ([refitBox]).
 *
 * [frameShape] is the shape the preset's frame is made for, when the video
 * has one: the frame row is switched off when the new shape differs from it
 * (a frame of another shape would only show small, with bars), and switched
 * on again when the video goes back to the frame's own shape.
 */
fun Project.setShape(
    shape: CanvasShape,
    frameShape: CanvasShape? = null,
): Project {
    if (shape == this.shape) return this
    val from = this.shape
    val tracks =
        tracks.map { track ->
            when {
                track.presetRole == PresetRole.FRAME && frameShape != null && track.clips.isNotEmpty() -> {
                    track.copy(enabled = shape == frameShape)
                }

                track.type == TrackType.TEXT -> {
                    track.copy(
                        clips =
                            track.clips.map { clip ->
                                if (clip is TextClip && clip.style.box != null) {
                                    clip.copy(style = clip.style.copy(box = refitBox(clip.style.box, from, shape)))
                                } else {
                                    clip
                                }
                            },
                    )
                }

                else -> {
                    track
                }
            }
        }
    return copy(
        shape = shape,
        askShape = false,
        tracks = tracks,
        captionDefaults = captionDefaults.copy(box = refitBox(captionDefaults.box, from, shape)),
    )
}

/**
 * The first-time choice of shape for a video made without a preset: like
 * [setShape], and it stops the editor asking again.
 */
fun Project.chooseShape(shape: CanvasShape): Project = setShape(shape).copy(askShape = false)

/**
 * [box] on a [from] canvas, placed on a [to] canvas: the same share of the
 * width and the same middle, and the height scaled so its words, at the same
 * size on the short side, wrap into as many lines as they need. Kept inside
 * the canvas with [EDGE] to spare when it would grow past it.
 */
fun refitBox(
    box: NormRect,
    from: CanvasShape,
    to: CanvasShape,
): NormRect {
    if (from == to) return box
    // Each canvas's area in short sides squared: 16:9 either way is 16/9, a square is 1. A box with the
    // same share of the width is (old width / new width) times as wide in text units, so its words need
    // that many times the lines; over the new height that is the ratio of the two areas.
    val factor = from.longOverShort / to.longOverShort
    val h = (box.h * factor).coerceAtMost(maxOf(box.h, 1.0 - 2 * EDGE))
    val middle = box.y + box.h / 2
    val y = (middle - h / 2).coerceIn(0.0, 1.0 - h)
    return NormRect(box.x, y, box.w, h).rounded()
}

/** A share of the canvas kept free at its top and bottom when a box grows. */
private const val EDGE = 0.02

/** To 1/10000 of the canvas, the way the caption editor stores boxes, and never past the far edge. */
private fun NormRect.rounded(): NormRect {
    fun r(v: Double) = Math.round(v * 10_000) / 10_000.0
    val nx = r(x)
    val ny = r(y)
    return NormRect(nx, ny, minOf(r(w), 1.0 - nx), minOf(r(h), 1.0 - ny))
}
