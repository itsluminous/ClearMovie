package com.itsluminous.clearmovie.render.plan

import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.TrackId

/**
 * What the layered editor preview plays (render decision D1, ARCHITECTURE
 * section 7 risk 2 fallback), worked out from the same [RenderPlan] the
 * export renders, so the two read one source.
 *
 * - [main]: the MAIN row as one plain player's playlist. A video plays in
 *   the player; anything else (a photo, a held frame, a card) is a still the
 *   preview draws over the player while the player runs silence for it.
 *   Where a transition overlaps two items, the preview cuts to the incoming
 *   item when it starts (the exact draft shows the real blend).
 * - [overlays]: each OVERLAY row with video (the frame) as a muted player of
 *   its own, drawn over MAIN in stacking order and keyed.
 * - [sounds]: every sound row (music, voice, the closing message) as an
 *   audio-only player; the MAIN row's own sound comes from the MAIN player,
 *   at [mainGainDbAt].
 * - [captions]: caption pictures, drawn as text over the layers below them.
 *
 * [layerOrder] is the drawing order, bottom first, of the layers above.
 * Every lane covers exactly [0, [durationUs]).
 */
data class PreviewSchedule(
    val durationUs: Long,
    val main: List<MainEntry>,
    val overlays: List<Lane>,
    val sounds: List<SoundLane>,
    val captions: List<CaptionEntry>,
    val layerOrder: List<LayerRef>,
    private val mainSound: List<AudioSegment>,
) {
    /** The MAIN entry playing at [us] (the last one at or after the end). */
    fun mainIndexAt(us: Long): Int {
        if (main.isEmpty()) return -1
        val t = us.coerceIn(0, durationUs)
        val i = main.indexOfLast { it.startUs <= t }
        return i.coerceAtLeast(0)
    }

    /** The MAIN row's own sound level at [us], in dB, or null where it is silent. */
    fun mainGainDbAt(us: Long): Double? {
        val s = mainSound.firstOrNull { us >= it.startUs && us < it.endUs } as? AudioSegment.Sound ?: return null
        return s.gain.dbAt(us - s.startUs)
    }

    fun captionsAt(us: Long): List<CaptionEntry> = captions.filter { us >= it.startUs && us < it.endUs }

    /** One drawn layer: the MAIN row, an overlay lane by index, or the captions of one text row. */
    sealed interface LayerRef {
        data object Main : LayerRef

        data class Overlay(
            val index: Int,
        ) : LayerRef

        data class Captions(
            val trackId: TrackId,
        ) : LayerRef
    }

    /** One MAIN playlist item, from [startUs] for [durationUs] of the video's time. */
    data class MainEntry(
        val startUs: Long,
        val durationUs: Long,
        val content: MainContent,
        val placement: Placement,
    ) {
        val endUs: Long get() = startUs + durationUs
    }

    sealed interface MainContent {
        /** [clipStartUs].. of the file at [speed], for the entry's length. */
        data class Video(
            val media: MediaRef,
            val clipStartUs: Long,
            val speed: Speed,
        ) : MainContent {
            /** Where in the file the entry ends, for an entry [durationUs] long. */
            fun clipEndUs(durationUs: Long): Long = clipStartUs + speed.sourceUs(durationUs)
        }

        /** Drawn by the preview: a photo, a held frame or a card. */
        data class Still(
            val source: Source,
        ) : MainContent
    }

    /** A follower player's playlist: plays and silent gaps that add up to the video's length. */
    data class Lane(
        val trackId: TrackId,
        val entries: List<LaneEntry>,
    ) {
        fun indexAt(us: Long): Int = laneIndexAt(entries, us)
    }

    data class SoundLane(
        val trackId: TrackId,
        val role: AudioLayerRole,
        val entries: List<LaneEntry>,
    ) {
        fun indexAt(us: Long): Int = laneIndexAt(entries, us)

        /** The level at [us], in dB, or null in a gap. */
        fun gainDbAt(us: Long): Double? {
            val e = entries.getOrNull(indexAt(us)) as? LaneEntry.Play ?: return null
            return e.gain?.dbAt(us - e.startUs)
        }
    }

    sealed interface LaneEntry {
        val startUs: Long
        val durationUs: Long
        val endUs: Long get() = startUs + durationUs

        /** [clipStartUs].. of [media] at [speed]; [key] keys out a frame's green. */
        data class Play(
            override val startUs: Long,
            override val durationUs: Long,
            val media: MediaRef,
            val clipStartUs: Long,
            val speed: Speed = Speed.X1,
            val key: KeySpec? = null,
            val placement: Placement = Placement.FullFrame,
            val gain: GainEnvelope? = null,
        ) : LaneEntry {
            fun clipEndUs(): Long = clipStartUs + speed.sourceUs(durationUs)
        }

        data class Gap(
            override val startUs: Long,
            override val durationUs: Long,
        ) : LaneEntry
    }

    data class CaptionEntry(
        val trackId: TrackId,
        val startUs: Long,
        val durationUs: Long,
        val caption: Source.Caption,
        val rect: NormRect,
    ) {
        val endUs: Long get() = startUs + durationUs
    }

    companion object {
        /** No item is shorter than this in a player playlist; shorter slivers merge into a neighbour. */
        const val MIN_ENTRY_US = 1_000L

        private fun laneIndexAt(
            entries: List<LaneEntry>,
            us: Long,
        ): Int {
            if (entries.isEmpty()) return -1
            val i = entries.indexOfLast { it.startUs <= us }
            return i.coerceAtLeast(0)
        }

        /** The schedule for [plan]. Pure: no files are opened. */
        fun of(plan: RenderPlan): PreviewSchedule {
            val total = plan.durationUs
            val mainLayers = plan.visual.filter { it.role == LayerRole.MAIN || it.role == LayerRole.MAIN_B }
            val mainTrack = mainLayers.firstOrNull()?.trackId
            val order = mutableListOf<LayerRef>()
            val overlays = mutableListOf<Lane>()
            val captions = mutableListOf<CaptionEntry>()
            for (layer in plan.visual) {
                when (layer.role) {
                    LayerRole.MAIN -> {
                        order += LayerRef.Main
                    }

                    LayerRole.MAIN_B -> {
                        Unit
                    }

                    LayerRole.OVERLAY -> {
                        val lane = overlayLane(layer, total)
                        if (lane.entries.any { it is LaneEntry.Play }) {
                            order += LayerRef.Overlay(overlays.size)
                            overlays += lane
                        }
                    }

                    LayerRole.TEXT -> {
                        val mine =
                            layer.segments.filterIsInstance<VisualSegment.Picture>().mapNotNull { p ->
                                val caption = p.source as? Source.Caption ?: return@mapNotNull null
                                val rect = (p.placement as? Placement.Box)?.rect ?: return@mapNotNull null
                                CaptionEntry(layer.trackId, p.startUs, p.durationUs, caption, rect)
                            }
                        if (mine.isNotEmpty()) {
                            order += LayerRef.Captions(layer.trackId)
                            captions += mine
                        }
                    }

                    // Picture-in-picture rows arrive with cm-tracks; the exact draft shows them meanwhile.
                    LayerRole.VISUAL -> {
                        Unit
                    }
                }
            }
            val mainSound =
                plan.audio
                    .firstOrNull { it.role == AudioLayerRole.CLIP && it.trackId == mainTrack }
                    ?.segments
                    .orEmpty()
            val sounds =
                plan.audio
                    .filter { it.role != AudioLayerRole.CLIP }
                    .map { SoundLane(it.trackId, it.role, soundEntries(it, total)) }
                    .filter { lane -> lane.entries.any { it is LaneEntry.Play } }
            return PreviewSchedule(
                durationUs = total,
                main = mainEntries(mainLayers, total),
                overlays = overlays,
                sounds = sounds,
                captions = captions.sortedBy { it.startUs },
                layerOrder = order,
                mainSound = mainSound,
            )
        }

        /** MAIN's two lanes merged into one cut-only list that covers [0, total). */
        private fun mainEntries(
            layers: List<VisualLayer>,
            total: Long,
        ): List<MainEntry> {
            if (total <= 0) return emptyList()
            val pictures = layers.flatMap { it.segments.filterIsInstance<VisualSegment.Picture>() }.sortedBy { it.startUs }
            val out = mutableListOf<MainEntry>()
            var at = 0L
            for ((i, p) in pictures.withIndex()) {
                val nextStart = pictures.getOrNull(i + 1)?.startUs ?: total
                val start = maxOf(p.startUs, at)
                val end = minOf(p.endUs, nextStart, total)
                if (start > at) {
                    out += blank(at, start - at)
                    at = start
                }
                // A sliver is left for the next entry's blank (or the end's) to cover.
                if (end - start < MIN_ENTRY_US) continue
                // A picture that starts late into its own segment (cut by the one before) starts later in its file.
                val trimmed = p.trimmedFront(start - p.startUs)
                out += MainEntry(start, end - start, content(trimmed), trimmed.placement)
                at = end
            }
            if (at < total) out += blank(at, total - at)
            return mergeSlivers(out)
        }

        private fun content(p: VisualSegment.Picture): MainContent =
            when (val s = p.source) {
                is Source.Video -> MainContent.Video(s.media, s.clipStartUs, s.speed)
                else -> MainContent.Still(s)
            }

        private fun blank(
            start: Long,
            length: Long,
        ) = MainEntry(start, length, MainContent.Still(Source.Card(BLACK, null, DEFAULT_STYLE)), Placement.FullFrame)

        /** A gap shorter than [MIN_ENTRY_US] is given to the entry before it, so no player item is empty. */
        private fun mergeSlivers(entries: List<MainEntry>): List<MainEntry> {
            val out = mutableListOf<MainEntry>()
            for (e in entries) {
                val last = out.lastOrNull()
                if (last != null && e.durationUs < MIN_ENTRY_US) {
                    out[out.lastIndex] = last.copy(durationUs = last.durationUs + e.durationUs)
                } else {
                    out += e
                }
            }
            return out
        }

        private fun overlayLane(
            layer: VisualLayer,
            total: Long,
        ): Lane {
            val entries =
                layer.segments.map { seg ->
                    val p = seg as? VisualSegment.Picture
                    val video = p?.source as? Source.Video
                    if (p != null && video != null) {
                        LaneEntry.Play(p.startUs, p.durationUs, video.media, video.clipStartUs, video.speed, p.key, p.placement)
                    } else {
                        LaneEntry.Gap(seg.startUs, seg.durationUs)
                    }
                }
            return Lane(layer.trackId, covered(entries, total))
        }

        private fun soundEntries(
            layer: AudioLayer,
            total: Long,
        ): List<LaneEntry> =
            covered(
                layer.segments.map { seg ->
                    when (seg) {
                        is AudioSegment.Sound -> {
                            LaneEntry.Play(seg.startUs, seg.durationUs, seg.media, seg.clipStartUs, seg.speed, gain = seg.gain)
                        }

                        is AudioSegment.Silence -> {
                            LaneEntry.Gap(seg.startUs, seg.durationUs)
                        }
                    }
                },
                total,
            )

        /** [entries] with gaps added so they cover [0, total) and slivers folded away. */
        private fun covered(
            entries: List<LaneEntry>,
            total: Long,
        ): List<LaneEntry> {
            val out = mutableListOf<LaneEntry>()
            var at = 0L
            for (e in entries.sortedBy { it.startUs }) {
                if (e.startUs > at) {
                    out += LaneEntry.Gap(at, e.startUs - at)
                    at = e.startUs
                }
                val start = maxOf(e.startUs, at)
                val end = minOf(e.endUs, total)
                if (end - start < MIN_ENTRY_US) continue
                out +=
                    when (e) {
                        is LaneEntry.Gap -> {
                            LaneEntry.Gap(start, end - start)
                        }

                        is LaneEntry.Play -> {
                            val front = start - e.startUs
                            e.copy(
                                startUs = start,
                                durationUs = end - start,
                                clipStartUs = e.clipStartUs + e.speed.sourceUs(front),
                                gain = e.gain?.slice(front, front + (end - start)),
                            )
                        }
                    }
                at = end
            }
            if (at < total) out += LaneEntry.Gap(at, total - at)
            // Merge neighbouring gaps and fold slivers.
            val merged = mutableListOf<LaneEntry>()
            for (e in out) {
                val last = merged.lastOrNull()
                when {
                    last is LaneEntry.Gap && e is LaneEntry.Gap -> {
                        merged[merged.lastIndex] =
                            LaneEntry.Gap(last.startUs, last.durationUs + e.durationUs)
                    }

                    else -> {
                        merged += e
                    }
                }
            }
            return merged
        }

        private val BLACK = ArgbColor.BLACK
        private val DEFAULT_STYLE = CaptionStyle()
    }
}
