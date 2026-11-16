package com.itsluminous.clearmovie.render.plan

/**
 * The most video decoders [RenderPlan] keeps open at once, and when (risk 5,
 * the codec-instance limit of low-end Android 10 phones). The count only
 * changes where a segment starts, so those are the only times looked at.
 * The export logs it next to what the phone's decoder says it supports.
 */
data class DecoderPeak(
    val video: Int,
    val audio: Int,
    val atUs: Long,
)

fun RenderPlan.decoderPeak(): DecoderPeak {
    val starts =
        (visual.flatMap { layer -> layer.segments.map { it.startUs } } + audio.flatMap { layer -> layer.segments.map { it.startUs } })
            .filter { it in 0 until durationUs }
            .distinct()
    var best = DecoderPeak(0, 0, 0)
    for (t in starts) {
        val v = videoDecodersAt(t)
        val a = audioDecodersAt(t)
        if (v > best.video || (v == best.video && a > best.audio)) best = DecoderPeak(v, a, t)
    }
    return best
}
