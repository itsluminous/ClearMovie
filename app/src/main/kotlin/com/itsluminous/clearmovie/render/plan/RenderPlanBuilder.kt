package com.itsluminous.clearmovie.render.plan

import com.itsluminous.clearmovie.domain.model.Anim
import com.itsluminous.clearmovie.domain.model.Animations
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.Corner
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MainSlot
import com.itsluminous.clearmovie.domain.model.MediaClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.OverlayClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.SoundRank
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.Track
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.containedRect
import com.itsluminous.clearmovie.domain.model.isValid
import com.itsluminous.clearmovie.domain.model.mainLayout
import com.itsluminous.clearmovie.domain.model.totalDurationMs
import com.itsluminous.clearmovie.domain.model.within
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Integrated loudness (EBU R128, LUFS) measured when a file was bound; null when unknown. */
fun interface LoudnessTable {
    fun lufs(media: MediaRef): Double?

    companion object {
        val NONE = LoudnessTable { null }
    }
}

/**
 * The files whose sound would lower something and is not known yet
 * (cm-frame-audio): the intro and a video ending, and every video on MAIN
 * and picture-in-picture rows that sound, at a volume that lowers music,
 * without a kept [VideoClip.hasSound]. Empty when no row would be lowered
 * under them (no music or user frame row, below videos in the [SoundRank]
 * ladder, that sounds with Lower for talk on), so nothing needs to be
 * looked at.
 */
fun duckingMedia(project: Project): List<MediaRef> {
    val audible = project.tracks.filter { it.enabled && !it.muted }
    val lowered =
        audible.any { t -> t.soundRank.let { it != null && it < SoundRank.VIDEO } && t.lowerWhenTalking && t.clips.isNotEmpty() }
    if (!lowered) return emptyList()
    return audible
        .filter { it.type == TrackType.MAIN || it.type == TrackType.VISUAL }
        .flatMap { t ->
            t.clips.mapNotNull {
                when (it) {
                    is VideoClip -> it.media.takeIf { _ -> it.hasSound == null && it.volumeDb > RenderPlanBuilder.AUDIBLE_DB }
                    is IntroClip -> it.media
                    is EndingClip -> it.media.takeIf { _ -> it.kind == EndingKind.VIDEO }
                    else -> null
                }
            }
        }.distinct()
}

/**
 * Whether a file has a sound track: true, false, or null when not known
 * (cm-frame-audio). A video known to have none lowers no music or frame
 * sound under it; an unknown one lowers them as every video did before.
 */
fun interface SoundPresence {
    fun hasSound(media: MediaRef): Boolean?

    companion object {
        val UNKNOWN = SoundPresence { null }
    }
}

/**
 * Builds the [RenderPlan] for a project (ARCHITECTURE section 7). Pure: no
 * Android, no files. The rules:
 * - one layer per visual row, in `Project.stacking` order; MAIN becomes two
 *   lanes (A, and B just above it) when it has a transition, alternating at
 *   each transition so the two clips overlap;
 * - every gap is a transparent [VisualSegment.Fill], so an empty stretch of a
 *   row never hides the rows below it;
 * - photos and videos go into the frame window ([Preset.frame]'s content
 *   rect) while the preset frame row is on, else the whole canvas;
 * - sound is planned per row, with ducking computed from the timeline on
 *   one ladder ([SoundRank]): frame sound under music, both under videos'
 *   own sound, all three under voices, everything under the closing
 *   message; nothing is lowered under a row below it.
 *
 * The project is untrusted input (it comes from stored JSON): [build] refuses
 * one that fails `validate()`, and a plan that would need more than
 * [MAX_SEGMENTS_PER_LAYER] items in one layer (a tiny frame repeated over a
 * long video, for example), with [IllegalArgumentException].
 */
class RenderPlanBuilder(
    private val preset: Preset? = null,
    private val loudness: LoudnessTable = LoudnessTable.NONE,
    private val sound: SoundPresence = SoundPresence.UNKNOWN,
) {
    /**
     * The plan at [shortSide] pixels across the canvas's short side (the
     * height of a landscape video, the width of an upright one): see
     * [CanvasShape.size].
     */
    fun build(
        project: Project,
        shortSide: Int = 1080,
        fps: Int = 30,
    ): RenderPlan {
        require(shortSide in MIN_HEIGHT..MAX_HEIGHT) { "Unsupported output size" }
        require(fps in 1..60) { "Unsupported frame rate" }
        require(project.isValid) { "The project is not valid" }
        val (width, height) = project.shape.size(shortSide)
        val totalUs = project.totalDurationMs() * US
        val ctx = Context(project, totalUs, windowRect(project), width.toDouble() / height, partsPlacement(project), frameBoxFor(project))

        val visual = mutableListOf<VisualLayer>()
        for (id in project.stacking) {
            val track = project.track(id) ?: continue
            if (!track.enabled) continue
            when (track.type) {
                TrackType.MAIN -> visual += mainLayers(ctx, track)
                TrackType.VISUAL -> visual += timedLayer(ctx, track, LayerRole.VISUAL)
                TrackType.TEXT -> visual += timedLayer(ctx, track, LayerRole.TEXT)
                TrackType.OVERLAY -> visual += timedLayer(ctx, track, LayerRole.OVERLAY)
                TrackType.AUDIO -> Unit
            }
        }
        val audio = audioLayers(ctx)
        require(visual.all { it.segments.size <= MAX_SEGMENTS_PER_LAYER } && audio.all { it.segments.size <= MAX_SEGMENTS_PER_LAYER }) {
            "The video has too many pieces to render"
        }
        return RenderPlan(
            width = width,
            height = height,
            fps = fps,
            durationUs = totalUs,
            visual = visual.filter { layer -> layer.segments.any { it !is VisualSegment.Fill } },
            audio = audio.filter { layer -> layer.segments.any { it !is AudioSegment.Silence } },
        )
    }

    private class Context(
        val project: Project,
        val totalUs: Long,
        val window: NormRect,
        val aspect: Double,
        /** Where the intro and a video ending go: the whole canvas, or fitted over a blurred copy on a canvas of another shape. */
        val parts: Placement,
        /** Where the preset frame is drawn: the whole canvas, or its own shape centred on a canvas of another shape. */
        val frameBox: NormRect,
    ) {
        val slots: List<MainSlot> = project.mainLayout()

        /** MAIN lane per slot: switches at each transition, so the two clips can overlap. */
        val lanes: List<Int> =
            buildList {
                var lane = 0
                slots.forEachIndexed { i, slot ->
                    add(lane)
                    if (i < slots.lastIndex && slot.overlapToNextMs > 0) lane = 1 - lane
                }
            }
    }

    private fun windowRect(project: Project): NormRect = frameWindow(project, preset)

    /** Where photos and videos shown from [startMs] to [endMs] go: see [pictureWindow]. */
    private fun windowFor(
        ctx: Context,
        startMs: Long,
        endMs: Long,
    ): NormRect = pictureWindow(ctx.project, preset, startMs, endMs, ctx.window)

    private fun frameBoxFor(project: Project): NormRect = frameBox(project, preset)

    // The preset's intro and video ending are made for its shape: on a canvas of another shape they are
    // fitted whole over a blurred copy of themselves (never stretched, never cut).
    private fun partsPlacement(project: Project): Placement =
        if (preset == null || preset.shape == project.shape) Placement.FullFrame else Placement.Window(NormRect.FULL, Fit.FIT_BLUR)

    // ---- MAIN ----------------------------------------------------------------

    private fun mainLayers(
        ctx: Context,
        track: Track,
    ): List<VisualLayer> {
        val a = SegmentLane(ctx.totalUs)
        val b = SegmentLane(ctx.totalUs)
        ctx.slots.forEachIndexed { i, slot ->
            val lane = if (ctx.lanes[i] == 0) a else b
            // B is drawn over A: the clip in B carries the transition's mask or fade, as the
            // incoming clip or the outgoing one; both move (MotionMath).
            val upper = ctx.lanes[i] == 1
            val durUs = slot.durationMs * US
            val into =
                ctx.slots.getOrNull(i - 1)?.takeIf { it.overlapToNextMs > 0 }?.let { prev ->
                    TransitionPart(prev.clip.transitionToNext!!.kind, 0, prev.overlapToNextMs * US, TransitionRole.IN, upper)
                }
            val out =
                slot.takeIf { i < ctx.slots.lastIndex && it.overlapToNextMs > 0 }?.let {
                    TransitionPart(it.clip.transitionToNext!!.kind, durUs - it.overlapToNextMs * US, durUs, TransitionRole.OUT, upper)
                }
            val held = heldPose(ctx, slot)
            for (part in mainParts(ctx, slot)) {
                val motion =
                    if (held != null) {
                        // A clip length of 0 puts the "during" curve at its end for the whole hold.
                        motionFor(Animations(during = held), 0, part.placement, into, out)
                    } else {
                        motionFor(slot.clip.animations, durUs, part.placement, into, out)
                    }
                lane.add(part.copy(motion = motion, startUs = slot.startMs * US + part.clipOffsetUs))
            }
        }
        val layers = mutableListOf(VisualLayer(track.id, LayerRole.MAIN, a.finish()))
        if (ctx.lanes.any { it == 1 }) layers += VisualLayer(track.id, LayerRole.MAIN_B, b.finish())
        return layers
    }

    /** A MAIN clip as picture segments at clip-relative start (startUs = clipOffsetUs). */
    private fun mainParts(
        ctx: Context,
        slot: MainSlot,
    ): List<VisualSegment.Picture> {
        val durUs = slot.durationMs * US
        val window = windowFor(ctx, slot.startMs, slot.startMs + slot.durationMs)
        return when (val clip = slot.clip) {
            is IntroClip -> {
                listOf(picture(durUs, Source.Video(clip.media, durUs, 0, durUs), ctx.parts))
            }

            is EndingClip -> {
                if (clip.kind == EndingKind.VIDEO) {
                    listOf(picture(durUs, Source.Video(clip.media, durUs, 0, durUs), ctx.parts))
                } else {
                    listOf(holdLast(ctx, slot, durUs))
                }
            }

            is PhotoClip -> {
                listOf(picture(durUs, Source.Photo(clip.media), Placement.Window(window, clip.fit, clip.rotation)))
            }

            is VideoClip -> {
                videoCopies(clip, durUs).map { (offset, length) ->
                    picture(length, videoSource(clip), Placement.Window(window, clip.fit, clip.rotation), offset)
                }
            }

            is CardClip -> {
                val style = clip.style.resolve(ctx.project.captionDefaults)
                listOf(picture(durUs, Source.Card(clip.color, clip.text, style), Placement.Window(window, Fit.FILL_CROP)))
            }
        }
    }

    /** The `holdLast` ending: the last frame of the item before it, where that item was drawn. */
    private fun holdLast(
        ctx: Context,
        slot: MainSlot,
        durUs: Long,
    ): VisualSegment.Picture {
        val prevSlot = ctx.slots.getOrNull(slot.index - 1)
        val prev = prevSlot?.clip
        // Where the item before it was drawn, so the held picture does not jump at the cut (cm-frame-area).
        val window = prevSlot?.let { windowFor(ctx, it.startMs, it.startMs + it.durationMs) } ?: ctx.window
        return when (prev) {
            is VideoClip -> {
                val lastUs = (prev.trimEndMs * US - FRAME_US).coerceAtLeast(prev.trimStartMs * US)
                picture(durUs, Source.VideoFrame(prev.media, lastUs), Placement.Window(window, prev.fit, prev.rotation))
            }

            is PhotoClip -> {
                picture(durUs, Source.Photo(prev.media), Placement.Window(window, prev.fit, prev.rotation))
            }

            is CardClip -> {
                val style = prev.style.resolve(ctx.project.captionDefaults)
                picture(durUs, Source.Card(prev.color, prev.text, style), Placement.Window(window, Fit.FILL_CROP))
            }

            is IntroClip -> {
                // A spoken ending straight after the intro holds the intro's last frame, full frame.
                val lastUs = (prev.durationMs * US - FRAME_US).coerceAtLeast(0)
                picture(durUs, Source.VideoFrame(prev.media, lastUs), ctx.parts)
            }

            else -> {
                picture(durUs, Source.Card(ArgbColor.BLACK, null, ctx.project.captionDefaults), Placement.Window(window, Fit.FILL_CROP))
            }
        }
    }

    private fun picture(
        durationUs: Long,
        source: Source,
        placement: Placement,
        clipOffsetUs: Long = 0,
    ) = VisualSegment.Picture(
        startUs = clipOffsetUs,
        durationUs = durationUs,
        source = source,
        placement = placement,
        clipOffsetUs = clipOffsetUs,
    )

    /**
     * A spoken ending holds the last picture of the item before it, so it
     * holds that picture's last "during" pose too (a photo's gentle zoom ends
     * zoomed in and panned; the held frame stays there instead of jumping
     * back). Null for any other slot, or when that item has no such pose.
     */
    private fun heldPose(
        ctx: Context,
        slot: MainSlot,
    ): Anim? {
        val ending = slot.clip as? EndingClip ?: return null
        if (ending.kind != EndingKind.AUDIO) return null
        return ctx.slots
            .getOrNull(slot.index - 1)
            ?.clip
            ?.animations
            ?.during
    }

    /** The motion of a picture drawn at [placement] for a clip [durUs] long, or null when it does not move. */
    private fun motionFor(
        animations: Animations,
        durUs: Long,
        placement: Placement,
        into: TransitionPart? = null,
        out: TransitionPart? = null,
    ): PictureMotion? {
        val (rect, clip) =
            when (placement) {
                is Placement.Window -> placement.rect to true
                is Placement.Pip -> placement.rect to false
                is Placement.Box -> placement.rect to false
                Placement.FullFrame -> NormRect.FULL to false
            }
        return PictureMotion(durUs, rect, clip, animations.start, animations.end, animations.during, into, out).takeUnless { it.isStill }
    }

    private fun videoSource(clip: VideoClip) =
        Source.Video(clip.media, clip.sourceDurationMs * US, clip.trimStartMs * US, clip.trimEndMs * US, clip.speed)

    /** A looped video as (clip offset, length) per copy; the lengths add up to exactly [durUs]. */
    private fun videoCopies(
        clip: VideoClip,
        durUs: Long,
    ): List<Pair<Long, Long>> = evenSplit(durUs, clip.loops)

    // ---- Timed rows ----------------------------------------------------------

    private fun timedLayer(
        ctx: Context,
        track: Track,
        role: LayerRole,
    ): VisualLayer {
        val lane = SegmentLane(ctx.totalUs)
        for (clip in track.clips.sortedBy { it.startMs }) {
            val startUs = clip.startMs * US
            val durUs = clip.durationMs * US
            when (clip) {
                is TextClip -> {
                    if (clip.text.isBlank()) continue
                    val style = clip.style.resolve(ctx.project.captionDefaults)
                    val box = Placement.Box(style.box)
                    lane.add(
                        VisualSegment.Picture(
                            startUs,
                            durUs,
                            Source.Caption(clip.text, style),
                            box,
                            motion = motionFor(clip.animations, durUs, box),
                        ),
                    )
                }

                is OverlayClip -> {
                    // The preset frame keeps its own shape (centred, when the video's shape differs); a frame the
                    // user added covers the canvas.
                    val placement =
                        if (track.presetRole == PresetRole.FRAME && ctx.frameBox != NormRect.FULL) {
                            Placement.Box(ctx.frameBox)
                        } else {
                            Placement.FullFrame
                        }
                    overlayCopies(clip).forEach { (offset, length, srcEnd) ->
                        lane.add(
                            VisualSegment.Picture(
                                startUs = startUs + offset,
                                durationUs = length,
                                source = Source.Video(clip.media, clip.sourceDurationMs * US, 0, srcEnd),
                                placement = placement,
                                key = clip.key,
                                motion = motionFor(clip.animations, durUs, placement),
                                clipOffsetUs = offset,
                            ),
                        )
                    }
                }

                is MediaClip -> {
                    val rect = pipRect(clip.pip ?: PipPlacement.DEFAULT, windowFor(ctx, clip.startMs, clip.endMs), ctx.aspect)
                    val placement = Placement.Pip(rect, clip.rotation)
                    val motion = motionFor(clip.animations, durUs, placement)
                    when (clip) {
                        is PhotoClip -> {
                            lane.add(VisualSegment.Picture(startUs, durUs, Source.Photo(clip.media), placement, motion = motion))
                        }

                        is VideoClip -> {
                            videoCopies(clip, durUs).forEach { (offset, length) ->
                                lane.add(
                                    VisualSegment.Picture(
                                        startUs + offset,
                                        length,
                                        videoSource(clip),
                                        placement,
                                        motion = motion,
                                        clipOffsetUs = offset,
                                    ),
                                )
                            }
                        }
                    }
                }

                else -> {
                    Unit
                }
            }
        }
        return VisualLayer(track.id, role, lane.finish())
    }

    /** A repeated overlay as (clip offset, length, source end) per copy; the last copy is cut. */
    private fun overlayCopies(clip: OverlayClip): List<Triple<Long, Long, Long>> {
        val durUs = clip.durationMs * US
        val srcUs = clip.sourceDurationMs * US
        if (durUs <= 0 || srcUs <= 0) return emptyList()
        if (!clip.repeat) return listOf(Triple(0L, minOf(durUs, srcUs), minOf(durUs, srcUs)))
        requireFewCopies(durUs, srcUs)
        val copies = mutableListOf<Triple<Long, Long, Long>>()
        var offset = 0L
        while (offset < durUs) {
            val length = minOf(srcUs, durUs - offset)
            copies += Triple(offset, length, length)
            offset += length
        }
        return copies
    }

    // ---- Audio ---------------------------------------------------------------

    private fun audioLayers(ctx: Context): List<AudioLayer> {
        val project = ctx.project
        val ducking = project.ducking
        val attackUs = ducking.attackMs * US
        val releaseUs = ducking.releaseMs * US

        val audibleTracks = project.tracks.filter { it.enabled && !it.muted }
        val main = project.main
        val clipSound = mutableListOf<DuckInterval>()
        if (main.enabled && !main.muted) {
            for (slot in ctx.slots) {
                val vol = slotVolumeDb(slot) ?: continue
                // A video whose file has no sound track lowers nothing (kupraki's call, cm-frame-audio).
                val silent = soundOf(slot) == false
                if (vol > AUDIBLE_DB && !silent) clipSound += DuckInterval(slot.startMs * US, slot.endMs * US, ducking.musicUnderClipDb)
            }
        }
        for (track in audibleTracks.filter { it.type == TrackType.VISUAL }) {
            for (clip in track.clips.filterIsInstance<VideoClip>()) {
                if (clip.volumeDb > AUDIBLE_DB && (clip.hasSound ?: sound.hasSound(clip.media)) != false) {
                    clipSound += DuckInterval(clip.startMs * US, clip.endMs * US, ducking.musicUnderClipDb)
                }
            }
        }

        // Music and voices lower the rows below them only where they sound: an item turned down to silence lowers nothing.
        fun soundsOn(
            rank: SoundRank,
            db: Double,
        ) = audibleTracks
            .filter { it.soundRank == rank }
            .flatMap { t -> t.clips.filterIsInstance<AudioClip>() }
            .filter { it.durationMs > 0 && it.volumeDb > AUDIBLE_DB }
            .map { DuckInterval(it.startMs * US, it.endMs * US, db) }
        val music = soundsOn(SoundRank.MUSIC, ducking.musicUnderClipDb)
        val voice = soundsOn(SoundRank.VOICE, ducking.musicUnderVoiceDb)
        val speech =
            audibleTracks
                .filter { it.type == TrackType.AUDIO && it.presetRole == PresetRole.CLOSING_MESSAGE }
                .flatMap { t -> t.clips.map { DuckInterval(it.startMs * US, it.endMs * US, ducking.speechDuckDb) } }

        val speechDuck = duckEnvelope(speech, attackUs, releaseUs)

        // The ducking ladder (cm-voice-ducking): with Lower for talk on, a row is lowered under every row above it
        // ([SoundRank]: frame sound, then music, then videos' own sound, then voices), to its own level from its popup
        // when it has one; with it off, only under the closing message, as everything is. A voice row is lowered only
        // under the closing message; the closing message under nothing.
        fun duckFor(track: Track): GainEnvelope {
            val rank = track.soundRank
            if (rank == null || rank == SoundRank.VOICE || !track.lowerWhenTalking) return speechDuck
            val above =
                buildList {
                    if (rank < SoundRank.MUSIC) addAll(music)
                    if (rank < SoundRank.VIDEO) addAll(clipSound)
                    addAll(voice)
                    addAll(speech)
                }
            val level = track.lowerToDb
            return duckEnvelope(if (level == null) above else above.map { it.copy(db = level) }, attackUs, releaseUs)
        }
        val layers = mutableListOf<AudioLayer>()

        // Clip sound on MAIN, in the same two lanes as the pictures.
        if (main.enabled && !main.muted) {
            val mainDuck = duckFor(main)
            val lanes = listOf(SoundLane(ctx.totalUs), SoundLane(ctx.totalUs))
            ctx.slots.forEachIndexed { i, slot ->
                val sound = slotSound(slot) ?: return@forEachIndexed
                val durUs = slot.durationMs * US
                var clipEnv = GainEnvelope.constant(slotVolumeDb(slot) ?: 0.0)
                ctx.slots.getOrNull(i - 1)?.let { prev ->
                    if (prev.overlapToNextMs > 0) clipEnv += soundIn(prev.clip.transitionToNext!!.kind, prev.overlapToNextMs * US)
                }
                if (i < ctx.slots.lastIndex && slot.overlapToNextMs > 0) {
                    clipEnv += soundOut(slot.clip.transitionToNext!!.kind, durUs, slot.overlapToNextMs * US)
                }
                val startUs = slot.startMs * US
                val env = clipEnv + mainDuck.shifted(-startUs)
                sound.copies(durUs).forEach { (offset, length) ->
                    lanes[ctx.lanes[i]].add(sound.segment(startUs + offset, length, env.slice(offset, offset + length)))
                }
            }
            layers += AudioLayer(main.id, AudioLayerRole.CLIP, lanes[0].finish())
            layers += AudioLayer(main.id, AudioLayerRole.CLIP, lanes[1].finish())
        }

        for (track in audibleTracks.filter { it.type == TrackType.VISUAL }) {
            val lane = SoundLane(ctx.totalUs)
            val duck = duckFor(track)
            for (clip in track.clips.filterIsInstance<VideoClip>().sortedBy { it.startMs }) {
                val startUs = clip.startMs * US
                val durUs = clip.durationMs * US
                val env = GainEnvelope.constant(clip.volumeDb) + duck.shifted(-startUs)
                val sound = VideoSound(clip)
                sound.copies(durUs).forEach { (offset, length) ->
                    lane.add(
                        sound.segment(
                            startUs + offset,
                            length,
                            env.slice(
                                offset,
                                offset + length,
                            ),
                        ),
                    )
                }
            }
            layers += AudioLayer(track.id, AudioLayerRole.CLIP, lane.finish())
        }

        for (track in audibleTracks.filter { it.type == TrackType.AUDIO }) {
            val role =
                when {
                    track.presetRole == PresetRole.CLOSING_MESSAGE -> AudioLayerRole.CLOSING_MESSAGE
                    track.audioRole == AudioRole.MUSIC -> AudioLayerRole.MUSIC
                    track.audioRole == AudioRole.VOICE -> AudioLayerRole.VOICE
                    else -> AudioLayerRole.EFFECT
                }
            val duck =
                when (role) {
                    AudioLayerRole.CLOSING_MESSAGE -> {
                        GainEnvelope.UNITY
                    }

                    else -> {
                        duckFor(track)
                    }
                }
            val target =
                when (role) {
                    AudioLayerRole.MUSIC -> MUSIC_LUFS
                    AudioLayerRole.VOICE, AudioLayerRole.CLOSING_MESSAGE -> VOICE_LUFS
                    else -> null
                }
            val lane = SoundLane(ctx.totalUs)
            for (clip in track.clips.filterIsInstance<AudioClip>().sortedBy { it.startMs }) {
                val startUs = clip.startMs * US
                val durUs = clip.durationMs * US
                if (durUs <= 0) continue
                val norm = target?.let { t -> loudness.lufs(clip.media)?.let { (t - it).coerceIn(MIN_NORM_DB, MAX_NORM_DB) } } ?: 0.0
                val copies = audioCopies(clip)
                var env = GainEnvelope.constant(clip.volumeDb + norm)
                if (clip.fadeInMs > 0) env += fadeIn(minOf(clip.fadeInMs * US, durUs))
                if (clip.fadeOutMs > 0) env += fadeOut(durUs, minOf(clip.fadeOutMs * US, durUs))
                // A repeating soundtrack fades out and back in at each join (review F22).
                for ((offset, _) in copies.drop(1)) {
                    env += dip(offset, minOf(REPEAT_FADE_US, copies.first().second / 4))
                }
                env += duck.shifted(-startUs)
                copies.forEach { (offset, length) ->
                    val srcStart = clip.trimStartMs * US
                    lane.add(
                        AudioSegment.Sound(
                            startUs = startUs + offset,
                            durationUs = length,
                            media = clip.media,
                            sourceDurationUs = clip.sourceDurationMs * US,
                            clipStartUs = srcStart,
                            clipEndUs = srcStart + length,
                            speed = Speed.X1,
                            gain = env.slice(offset, offset + length),
                        ),
                    )
                }
            }
            layers += AudioLayer(track.id, role, lane.finish())
        }

        // A frame video's own sound (cm-frame-audio), played like music: at the frame's volume after the same loudness
        // normalisation as a music row, repeated with its picture (each copy from the file's start, so it stays with
        // the picture, dipping at each join as the preset's frame music does), and lowered for talk as its row says.
        // Only a frame whose sound has been measured plays (its soundLufs, or [loudness]): a file without sound, or
        // one that can't be read, has no loudness. The preset's frame is left out: its sound already plays as the frame music row.
        for (track in audibleTracks.filter { it.type == TrackType.OVERLAY && it.presetRole != PresetRole.FRAME }) {
            // Each frame with its loudness: its own once measured, else the table's (the export measures one that has none).
            val frames =
                track.clips
                    .filterIsInstance<OverlayClip>()
                    .filter { it.durationMs > 0 }
                    .mapNotNull { clip -> (clip.soundLufs ?: loudness.lufs(clip.media))?.let { clip to it } }
                    .sortedBy { it.first.startMs }
            if (frames.isEmpty()) continue
            val duck = duckFor(track)
            val lane = SoundLane(ctx.totalUs)
            for ((clip, lufs) in frames) {
                val startUs = clip.startMs * US
                val copies = overlayCopies(clip)
                val norm = (MUSIC_LUFS - lufs).coerceIn(MIN_NORM_DB, MAX_NORM_DB)
                var env = GainEnvelope.constant(clip.volumeDb + norm)
                for ((offset, _, _) in copies.drop(1)) {
                    env += dip(offset, minOf(REPEAT_FADE_US, copies.first().second / 4))
                }
                env += duck.shifted(-startUs)
                copies.forEach { (offset, length, srcEnd) ->
                    lane.add(
                        AudioSegment.Sound(
                            startUs = startUs + offset,
                            durationUs = length,
                            media = clip.media,
                            sourceDurationUs = clip.sourceDurationMs * US,
                            clipStartUs = 0,
                            clipEndUs = srcEnd,
                            speed = Speed.X1,
                            gain = env.slice(offset, offset + length),
                        ),
                    )
                }
            }
            layers += AudioLayer(track.id, AudioLayerRole.MUSIC, lane.finish())
        }
        return layers
    }

    private fun audioCopies(clip: AudioClip): List<Pair<Long, Long>> {
        val durUs = clip.durationMs * US
        val rangeUs = (clip.trimEndMs - clip.trimStartMs) * US
        if (!clip.repeat || rangeUs >= durUs) return listOf(0L to minOf(durUs, rangeUs.coerceAtLeast(1)))
        requireFewCopies(durUs, rangeUs)
        val copies = mutableListOf<Pair<Long, Long>>()
        var offset = 0L
        while (offset < durUs) {
            val length = minOf(rangeUs, durUs - offset)
            copies += offset to length
            offset += length
        }
        return copies
    }

    private fun requireFewCopies(
        totalUs: Long,
        copyUs: Long,
    ) = require(totalUs / copyUs < MAX_SEGMENTS_PER_LAYER) { "The video has too many pieces to render" }

    /** Whether a MAIN slot's file has a sound track: kept on a video, else [sound]'s answer; null when not known. */
    private fun soundOf(slot: MainSlot): Boolean? =
        when (val c = slot.clip) {
            is VideoClip -> c.hasSound ?: sound.hasSound(c.media)
            is IntroClip -> sound.hasSound(c.media)
            is EndingClip -> if (c.kind == EndingKind.VIDEO) sound.hasSound(c.media) else null
            else -> null
        }

    /** The volume of a MAIN slot's own sound, or null when it has none (photos, cards, a held frame). */
    private fun slotVolumeDb(slot: MainSlot): Double? =
        when (val c = slot.clip) {
            is VideoClip -> c.volumeDb
            is IntroClip -> 0.0
            is EndingClip -> if (c.kind == EndingKind.VIDEO) 0.0 else null
            else -> null
        }

    private interface SlotSound {
        fun copies(durUs: Long): List<Pair<Long, Long>>

        fun segment(
            startUs: Long,
            lengthUs: Long,
            gain: GainEnvelope,
        ): AudioSegment.Sound
    }

    private class VideoSound(
        private val clip: VideoClip,
    ) : SlotSound {
        override fun copies(durUs: Long) = evenSplit(durUs, clip.loops)

        override fun segment(
            startUs: Long,
            lengthUs: Long,
            gain: GainEnvelope,
        ) = AudioSegment.Sound(
            startUs,
            lengthUs,
            clip.media,
            clip.sourceDurationMs * US,
            clip.trimStartMs * US,
            clip.trimEndMs * US,
            clip.speed,
            gain,
        )
    }

    /** The intro's or a video ending's own sound: [media] from its start, for the whole slot. */
    private class PartSound(
        private val media: MediaRef,
        private val durUs: Long,
    ) : SlotSound {
        override fun copies(durUs: Long) = listOf(0L to durUs)

        override fun segment(
            startUs: Long,
            lengthUs: Long,
            gain: GainEnvelope,
        ) = AudioSegment.Sound(startUs, lengthUs, media, durUs, 0, durUs, Speed.X1, gain)
    }

    private fun slotSound(slot: MainSlot): SlotSound? =
        when (val c = slot.clip) {
            is VideoClip -> VideoSound(c)
            is IntroClip -> PartSound(c.media, slot.durationMs * US)
            is EndingClip -> if (c.kind == EndingKind.VIDEO) PartSound(c.media, slot.durationMs * US) else null
            else -> null
        }

    companion object {
        const val US = 1000L
        private const val FRAME_US = 33_334L
        const val MIN_HEIGHT = 144
        const val MAX_HEIGHT = 2160

        /** Clip sound quieter than this does not lower the music. */
        const val AUDIBLE_DB = -40.0

        /** Loudness targets at bind (review F6). */
        const val MUSIC_LUFS = -23.0
        const val VOICE_LUFS = -16.0
        const val MIN_NORM_DB = -24.0
        const val MAX_NORM_DB = 12.0
        const val REPEAT_FADE_US = 2_000_000L

        /** More items than this in one layer is refused (Media3 opens each one). */
        const val MAX_SEGMENTS_PER_LAYER = 2_000
        private const val CURVE_STEPS = 32

        /** A 16:9 canvas width for [height], rounded to an even number (encoders need it). */
        fun evenWidthFor(height: Int): Int = CanvasShape.LANDSCAPE.size(height).first

        /** [total] split into [n] near-equal (offset, length) parts that add up exactly. */
        fun evenSplit(
            total: Long,
            n: Int,
        ): List<Pair<Long, Long>> {
            val parts = n.coerceAtLeast(1)
            return (0 until parts).map { i ->
                val a = total * i / parts
                val b = total * (i + 1) / parts
                a to (b - a)
            }
        }

        /**
         * Picture-in-picture rectangle (section 7): a 16:9 box [PipPlacement.scale]
         * of the window's width, in its corner, inset by 3% of the window width.
         */
        fun pipRect(
            pip: PipPlacement,
            window: NormRect,
            canvasAspect: Double,
        ): NormRect {
            val w = (window.w * pip.scale).coerceAtMost(window.w)
            val h = (w * canvasAspect * 9.0 / 16.0).coerceAtMost(window.h)
            val mx = window.w * 0.03
            val my = (mx * canvasAspect).coerceAtMost((window.h - h) / 2)
            val left = pip.corner == Corner.TOP_LEFT || pip.corner == Corner.BOTTOM_LEFT
            val top = pip.corner == Corner.TOP_LEFT || pip.corner == Corner.TOP_RIGHT
            val x = if (left) window.x + mx else window.x + window.w - mx - w
            val y = if (top) window.y + my else window.y + window.h - my - h
            return NormRect(x.coerceAtLeast(window.x), y.coerceAtLeast(window.y), w, h)
        }

        /**
         * The ducking curve for [intervals]: each lowers the gain to its level,
         * starting [attackUs] before it and recovering over [releaseUs] after
         * it; where several apply, the lowest wins.
         */
        internal fun duckEnvelope(
            intervals: List<DuckInterval>,
            attackUs: Long,
            releaseUs: Long,
        ): GainEnvelope {
            if (intervals.isEmpty()) return GainEnvelope.UNITY

            fun level(
                iv: DuckInterval,
                t: Long,
            ): Double =
                when {
                    t <= iv.startUs - attackUs || t >= iv.endUs + releaseUs -> 0.0
                    t < iv.startUs -> if (attackUs == 0L) iv.db else iv.db * (t - (iv.startUs - attackUs)).toDouble() / attackUs
                    t <= iv.endUs -> iv.db
                    else -> if (releaseUs == 0L) 0.0 else iv.db * (1.0 - (t - iv.endUs).toDouble() / releaseUs)
                }
            val corners =
                intervals
                    .flatMap { listOf(it.startUs - attackUs, it.startUs, it.endUs, it.endUs + releaseUs) }
                    .distinct()
                    .sorted()
            // Between two corners every level is a straight line, so their minimum
            // bends only where two of the lines cross: add those crossings.
            val crossings = mutableListOf<Long>()
            for ((a, b) in corners.zipWithNext()) {
                val active = intervals.filter { it.startUs - attackUs < b && it.endUs + releaseUs > a }
                for (i in active.indices) {
                    for (j in i + 1 until active.size) {
                        val da = level(active[i], a) - level(active[j], a)
                        val db = level(active[i], b) - level(active[j], b)
                        if (da * db < 0) crossings += a + ((b - a) * (da / (da - db))).toLong()
                    }
                }
            }
            val times = (corners + crossings).distinct().sorted()
            return GainEnvelope(times.map { t -> GainPoint(t, intervals.minOf { level(it, t) }) }).simplified()
        }

        private fun ampToDb(a: Double): Double =
            if (a <=
                0.0
            ) {
                GainEnvelope.SILENT_DB
            } else {
                (20.0 * log10(a)).coerceAtLeast(GainEnvelope.SILENT_DB)
            }

        private fun curve(
            fromUs: Long,
            lengthUs: Long,
            amp: (Double) -> Double,
        ): GainEnvelope =
            GainEnvelope(
                (0..CURVE_STEPS).map { k ->
                    GainPoint(fromUs + lengthUs * k / CURVE_STEPS, ampToDb(amp(k.toDouble() / CURVE_STEPS)))
                },
            )

        /**
         * The incoming clip's sound over a transition [lengthUs] long: an
         * equal-power rise, so the two clips together stay at a constant
         * loudness; for a fade through black or white, silent for the first
         * half and rising over the second, as the picture comes out of the colour.
         */
        internal fun soundIn(
            kind: TransitionKind,
            lengthUs: Long,
        ): GainEnvelope =
            if (kind.throughColour) {
                val half = lengthUs / 2
                GainEnvelope(listOf(GainPoint(0, GainEnvelope.SILENT_DB)) + curve(half, lengthUs - half) { sin(it * PI / 2) }.points)
            } else {
                equalPowerIn(lengthUs)
            }

        /** The outgoing clip's sound over its last [lengthUs] of [durUs]: the mirror of [soundIn]. */
        internal fun soundOut(
            kind: TransitionKind,
            durUs: Long,
            lengthUs: Long,
        ): GainEnvelope =
            if (kind.throughColour) {
                val half = lengthUs / 2
                GainEnvelope(curve(durUs - lengthUs, half) { cos(it * PI / 2) }.points + GainPoint(durUs, GainEnvelope.SILENT_DB))
            } else {
                equalPowerOut(durUs, lengthUs)
            }

        private val TransitionKind.throughColour: Boolean
            get() = this == TransitionKind.FADE_BLACK || this == TransitionKind.FADE_WHITE

        /** Equal-power rise over the first [lengthUs] (the incoming clip of a crossfade). */
        internal fun equalPowerIn(lengthUs: Long) = curve(0, lengthUs) { sin(it * PI / 2) }

        /** Equal-power fall over the last [lengthUs] of [durUs] (the outgoing clip). */
        internal fun equalPowerOut(
            durUs: Long,
            lengthUs: Long,
        ) = curve(durUs - lengthUs, lengthUs) { cos(it * PI / 2) }

        internal fun fadeIn(lengthUs: Long) = curve(0, lengthUs) { it }

        /** Silence at [atUs], fading out over [halfUs] before it and back in over [halfUs] after. */
        internal fun dip(
            atUs: Long,
            halfUs: Long,
        ): GainEnvelope {
            val down = curve(atUs - halfUs, halfUs) { 1.0 - it }.points
            val up = curve(atUs, halfUs) { it }.points
            return GainEnvelope(down + up.drop(1))
        }

        internal fun fadeOut(
            endUs: Long,
            lengthUs: Long,
        ) = curve(endUs - lengthUs, lengthUs) { 1.0 - it }
    }
}

/** A stretch of the timeline during which a row is lowered to [db]. */
internal data class DuckInterval(
    val startUs: Long,
    val endUs: Long,
    val db: Double,
)

/** [this] moved by [deltaUs] (negative moves it earlier). */
internal fun GainEnvelope.shifted(deltaUs: Long): GainEnvelope = GainEnvelope(points.map { GainPoint(it.timeUs + deltaUs, it.db) })

/** Builds a gap-free list of visual segments, clipped to the video's length. */
internal class SegmentLane(
    private val totalUs: Long,
) {
    private val out = mutableListOf<VisualSegment>()
    private var cursor = 0L

    fun add(segment: VisualSegment.Picture) {
        val start = maxOf(segment.startUs, cursor)
        val end = minOf(segment.endUs, totalUs)
        if (end <= start) return
        if (start > cursor) out += VisualSegment.Fill(cursor, start - cursor)
        out += segment.trimmedFront(start - segment.startUs).copy(durationUs = end - start)
        cursor = end
    }

    fun finish(): List<VisualSegment> {
        if (cursor < totalUs) out += VisualSegment.Fill(cursor, totalUs - cursor)
        return out.toList()
    }
}

/** Builds a gap-free list of audio segments, clipped to the video's length. */
internal class SoundLane(
    private val totalUs: Long,
) {
    private val out = mutableListOf<AudioSegment>()
    private var cursor = 0L

    fun add(segment: AudioSegment.Sound) {
        val start = maxOf(segment.startUs, cursor)
        val end = minOf(segment.endUs, totalUs)
        if (end <= start) return
        if (start > cursor) out += AudioSegment.Silence(cursor, start - cursor)
        out += (segment.sliced(start, end) as AudioSegment.Sound).copy(startUs = start)
        cursor = end
    }

    fun finish(): List<AudioSegment> {
        if (cursor < totalUs) out += AudioSegment.Silence(cursor, totalUs - cursor)
        return out.toList()
    }
}

internal fun Speed.sourceUs(timelineUs: Long): Long = timelineUs * numerator / denominator

/**
 * Where photos and videos are drawn: the preset frame's window while the
 * frame row is on, else the whole canvas. Captions are placed inside it too.
 * A frame made for another shape than the video's is drawn at its own shape,
 * centred ([frameBox]), and its window with it.
 */
fun frameWindow(
    project: Project,
    preset: Preset?,
): NormRect {
    val frame = preset?.frame ?: return NormRect.FULL
    val row =
        project.tracks.firstOrNull { it.presetRole == PresetRole.FRAME && it.enabled && it.clips.isNotEmpty() } ?: return NormRect.FULL
    // The preset frame's own picture area for this video (cm-frame-area), else the preset's.
    val area = row.clips.firstNotNullOfOrNull { (it as? OverlayClip)?.area } ?: frame.contentRect
    return area.within(frameBox(project, preset))
}

/**
 * Where photos and videos shown from [startMs] to [endMs] go (cm-frame-area):
 * the picture area of the frame that is shown longest over that time, when
 * that is more than half of it (an item keeps one place for its whole time;
 * the preview and the export never move it mid-item), among
 * the frames on rows that are switched on and that have an area (the preset
 * frame always has one; a frame the user added has one once it was set).
 * Equal times go to the frame drawn on top. With no such frame over that
 * time, [fallback]: the preset frame's window while its row is on, else the
 * whole canvas ([frameWindow]), as before frames had areas of their own.
 *
 * A frame the user added covers the canvas, so its area is already in canvas
 * units; the preset frame's is placed in its box ([frameBox]).
 */
fun pictureWindow(
    project: Project,
    preset: Preset?,
    startMs: Long,
    endMs: Long,
    fallback: NormRect = frameWindow(project, preset),
): NormRect {
    var best: NormRect? = null
    var bestOverlap = 0L
    for (id in project.stacking) {
        val track = project.track(id) ?: continue
        if (track.type != TrackType.OVERLAY || !track.enabled) continue
        for (clip in track.clips) {
            if (clip !is OverlayClip) continue
            val overlap = minOf(endMs, clip.endMs) - maxOf(startMs, clip.startMs)
            if (overlap <= 0) continue
            val area =
                if (track.presetRole == PresetRole.FRAME) {
                    val frame = preset?.frame ?: continue
                    (clip.area ?: frame.contentRect).within(frameBox(project, preset))
                } else {
                    clip.area ?: continue
                }
            // Later in the stacking is drawn on top, so it wins a tie.
            if (overlap >= bestOverlap) {
                best = area
                bestOverlap = overlap
            }
        }
    }
    // A frame over less than half of the item leaves it where it would be without that frame.
    return best?.takeIf { bestOverlap * 2 > endMs - startMs } ?: fallback
}

/** Where the preset's frame is drawn on the video: the whole canvas, or its own shape centred on a canvas of another. */
fun frameBox(
    project: Project,
    preset: Preset?,
): NormRect = if (preset?.frame == null) NormRect.FULL else containedRect(preset.shape, project.shape)
