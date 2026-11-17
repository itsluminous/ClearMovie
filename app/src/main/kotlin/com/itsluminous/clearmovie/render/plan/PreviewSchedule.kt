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
 *   Where a transition overlaps two items, the player moves on to the
 *   incoming item when it starts, and the rest of the outgoing item is a
 *   [tail]: a second player plays it (or the preview draws it, for a still)
 *   for the length of the overlap, so both items show and sound at once
 *   and the preview draws the transition from the same [PictureMotion] the
 *   export does.
 * - [overlays]: each OVERLAY row with video (the frame) as a muted player of
 *   its own, drawn in stacking order and keyed; and each picture-in-picture
 *   row the same way, unkeyed, its videos played in their corner and its
 *   photos drawn there as [Lane.stills]. A picture-in-picture video's sound
 *   is one of the [sounds].
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
    val tail: List<TailEntry> = emptyList(),
    /** The tails as a follower player's playlist: the videos play, everything else is a gap. */
    val tailLane: Lane? = null,
) {
    /** The MAIN entry playing at [us] (the last one at or after the end). */
    fun mainIndexAt(us: Long): Int {
        if (main.isEmpty()) return -1
        val t = us.coerceIn(0, durationUs)
        val i = main.indexOfLast { it.startUs <= t }
        return i.coerceAtLeast(0)
    }

    /** The MAIN player's sound level at [us], in dB, or null where it plays no sound. */
    fun mainGainDbAt(us: Long): Double? {
        val e = main.getOrNull(mainIndexAt(us)) ?: return null
        return e.gain?.dbAt(us - e.startUs)
    }

    /** The tail showing at [us], if a transition is under way. */
    fun tailAt(us: Long): TailEntry? = tail.firstOrNull { us >= it.startUs && us < it.endUs }

    /** The tail player's sound level at [us], in dB, or null where it plays no sound. */
    fun tailGainDbAt(us: Long): Double? {
        val e = tailAt(us) ?: return null
        return e.gain?.dbAt(us - e.startUs)
    }

    fun captionsAt(us: Long): List<CaptionEntry> = captions.filter { us >= it.startUs && us < it.endUs }

    /**
     * True when this schedule and [other] play the same pictures and sounds and
     * differ at most in their captions (and so in where caption layers sit in
     * [layerOrder]): the players need no reload.
     */
    fun differsOnlyInCaptions(other: PreviewSchedule): Boolean =
        copy(captions = other.captions, layerOrder = other.layerOrder) == other &&
            layerOrder.filterNot { it is LayerRef.Captions } == other.layerOrder.filterNot { it is LayerRef.Captions }

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

    /**
     * One MAIN playlist item, from [startUs] for [durationUs] of the video's
     * time. [clipOffsetUs] is where it starts in its clip, for [motion];
     * [upper] is true for an item of the B lane (drawn over a [tail] of lane
     * A); [gain] is its sound's level in its own time.
     */
    data class MainEntry(
        val startUs: Long,
        val durationUs: Long,
        val content: MainContent,
        val placement: Placement,
        val motion: PictureMotion? = null,
        val clipOffsetUs: Long = 0,
        val upper: Boolean = false,
        val gain: GainEnvelope? = null,
    ) {
        val endUs: Long get() = startUs + durationUs

        /** Its clip's time at [us] of the video. */
        fun clipUsAt(us: Long): Long = us - startUs + clipOffsetUs
    }

    /** The end of an outgoing MAIN item during a transition, shown with the incoming one. */
    data class TailEntry(
        val startUs: Long,
        val durationUs: Long,
        val content: MainContent,
        val placement: Placement,
        val motion: PictureMotion?,
        val clipOffsetUs: Long,
        val upper: Boolean,
        val gain: GainEnvelope? = null,
    ) {
        val endUs: Long get() = startUs + durationUs

        fun clipUsAt(us: Long): Long = us - startUs + clipOffsetUs
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

    /**
     * A follower player's playlist: plays and silent gaps that add up to the
     * video's length. [stills] are the row's pictures that are not videos
     * (picture-in-picture photos), drawn by the preview itself in the gaps.
     */
    data class Lane(
        val trackId: TrackId,
        val entries: List<LaneEntry>,
        val stills: List<StillEntry> = emptyList(),
    ) {
        fun indexAt(us: Long): Int = laneIndexAt(entries, us)

        fun stillAt(us: Long): StillEntry? = stills.firstOrNull { us >= it.startUs && us < it.endUs }

        /** Whether anything on this row is drawn at all. */
        val drawsAnything: Boolean get() = stills.isNotEmpty() || entries.any { it is LaneEntry.Play }
    }

    /** A picture the preview draws itself on a follower's row: [source] at [placement], from [startUs]. */
    data class StillEntry(
        val startUs: Long,
        val durationUs: Long,
        val source: Source,
        val placement: Placement,
    ) {
        val endUs: Long get() = startUs + durationUs
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
        val motion: PictureMotion? = null,
        val clipOffsetUs: Long = 0,
    ) {
        val endUs: Long get() = startUs + durationUs

        fun clipUsAt(us: Long): Long = us - startUs + clipOffsetUs
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

                    // A frame row and a picture-in-picture row both follow MAIN in a player of their own.
                    LayerRole.OVERLAY, LayerRole.VISUAL -> {
                        val lane = overlayLane(layer, total)
                        if (lane.drawsAnything) {
                            order += LayerRef.Overlay(overlays.size)
                            overlays += lane
                        }
                    }

                    LayerRole.TEXT -> {
                        val mine =
                            layer.segments.filterIsInstance<VisualSegment.Picture>().mapNotNull { p ->
                                val caption = p.source as? Source.Caption ?: return@mapNotNull null
                                val rect = (p.placement as? Placement.Box)?.rect ?: return@mapNotNull null
                                CaptionEntry(layer.trackId, p.startUs, p.durationUs, caption, rect, p.motion, p.clipOffsetUs)
                            }
                        if (mine.isNotEmpty()) {
                            order += LayerRef.Captions(layer.trackId)
                            captions += mine
                        }
                    }
                }
            }
            val mainSounds =
                plan.audio
                    .filter { it.role == AudioLayerRole.CLIP && it.trackId == mainTrack }
                    .flatMap { it.segments.filterIsInstance<AudioSegment.Sound>() }
            // MAIN's own sound plays in the MAIN player; every other row's (a picture-in-picture video's too) in one of its own.
            val sounds =
                plan.audio
                    .filter { it.role != AudioLayerRole.CLIP || it.trackId != mainTrack }
                    .map { SoundLane(it.trackId, it.role, soundEntries(it, total)) }
                    .filter { lane -> lane.entries.any { it is LaneEntry.Play } }
            val (main, tail) = mainEntries(mainLayers, total, mainSounds)
            val tailLane =
                mainTrack
                    ?.let { id ->
                        Lane(
                            id,
                            covered(
                                tailPlays(tail),
                                total,
                            ),
                        )
                    }?.takeIf { lane -> lane.entries.any { it is LaneEntry.Play } }
            return PreviewSchedule(
                durationUs = total,
                main = main,
                overlays = overlays,
                sounds = sounds,
                captions = captions.sortedBy { it.startUs },
                layerOrder = order,
                tail = tail,
                tailLane = tailLane,
            )
        }

        /**
         * MAIN's two lanes merged into one cut-only list that covers [0, total),
         * and the tails: the part of each item after the next one has started.
         */
        private fun mainEntries(
            layers: List<VisualLayer>,
            total: Long,
            sounds: List<AudioSegment.Sound>,
        ): Pair<List<MainEntry>, List<TailEntry>> {
            if (total <= 0) return emptyList<MainEntry>() to emptyList()
            val pictures =
                layers
                    .flatMap { layer ->
                        layer.segments.filterIsInstance<VisualSegment.Picture>().map {
                            it to
                                (layer.role == LayerRole.MAIN_B)
                        }
                    }.sortedBy { it.first.startUs }
            val out = mutableListOf<MainEntry>()
            val tails = mutableListOf<TailEntry>()
            var at = 0L
            for ((i, pair) in pictures.withIndex()) {
                val (p, upper) = pair
                val nextStart = pictures.getOrNull(i + 1)?.first?.startUs ?: total
                val start = maxOf(p.startUs, at)
                val end = minOf(p.endUs, nextStart, total)
                val sound = soundOf(p, sounds)
                // The rest of an item whose successor starts before it ends: a transition's overlap.
                val tailEnd = minOf(p.endUs, total)
                if (nextStart < tailEnd && tailEnd - nextStart >= MIN_ENTRY_US) {
                    val t = p.trimmedFront(nextStart - p.startUs)
                    tails +=
                        TailEntry(
                            nextStart,
                            tailEnd - nextStart,
                            content(t),
                            t.placement,
                            t.motion,
                            t.clipOffsetUs,
                            upper,
                            sound?.let { it.gain.slice(nextStart - it.startUs, tailEnd - it.startUs) },
                        )
                }
                if (start > at) {
                    out += blank(at, start - at)
                    at = start
                }
                // A sliver is left for the next entry's blank (or the end's) to cover.
                if (end - start < MIN_ENTRY_US) continue
                // A picture that starts late into its own segment (cut by the one before) starts later in its file.
                val trimmed = p.trimmedFront(start - p.startUs)
                out +=
                    MainEntry(
                        start,
                        end - start,
                        content(trimmed),
                        trimmed.placement,
                        trimmed.motion,
                        trimmed.clipOffsetUs,
                        upper,
                        sound?.let { it.gain.slice(start - it.startUs, end - it.startUs) },
                    )
                at = end
            }
            if (at < total) out += blank(at, total - at)
            return mergeSlivers(out) to tails
        }

        /**
         * The tail player's videos. Each starts up to [TAIL_PREROLL_US] early
         * (earlier in its file too, never before the file's start or the
         * previous tail's end), so the decoder is running and its picture
         * ready when the overlap begins; until then it plays hidden.
         */
        private fun tailPlays(tails: List<TailEntry>): List<LaneEntry.Play> {
            var free = 0L
            return tails.mapNotNull { t ->
                val video = t.content as? MainContent.Video ?: return@mapNotNull null
                val roomInFile = video.clipStartUs * video.speed.denominator / video.speed.numerator
                val pre = minOf(TAIL_PREROLL_US, roomInFile, (t.startUs - free).coerceAtLeast(0))
                free = t.endUs
                LaneEntry.Play(
                    t.startUs - pre,
                    t.durationUs + pre,
                    video.media,
                    video.clipStartUs - video.speed.sourceUs(pre),
                    video.speed,
                    placement = t.placement,
                )
            }
        }

        /** How early the tail player starts each outgoing video, hidden, so it is decoding when its overlap starts. */
        const val TAIL_PREROLL_US = 500_000L

        /** The sound planned with a MAIN picture: the clip sound of the same file, starting with it. */
        private fun soundOf(
            p: VisualSegment.Picture,
            sounds: List<AudioSegment.Sound>,
        ): AudioSegment.Sound? {
            val media = (p.source as? Source.Video)?.media ?: return null
            return sounds.firstOrNull { it.media == media && it.startUs == p.startUs }
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
            val stills =
                layer.segments
                    .filterIsInstance<VisualSegment.Picture>()
                    .filter { it.source is Source.Photo || it.source is Source.VideoFrame }
                    .map { StillEntry(it.startUs, it.durationUs, it.source, it.placement) }
            return Lane(layer.trackId, covered(entries, total), stills)
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
