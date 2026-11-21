package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.Serializable

/**
 * How music is lowered (section 7). Clip audio and voice are never lowered by
 * music; everything is lowered under the closing speech.
 */
@Serializable
data class DuckingSpec(
    val musicUnderClipDb: Double = -12.0,
    val musicUnderVoiceDb: Double = -20.0,
    val speechDuckDb: Double = -20.0,
    val attackMs: Long = 300,
    val releaseMs: Long = 300,
)

/**
 * A preset's effect defaults (section 11.10). The model carries them; they are
 * applied to new items by cm-effects.
 */
@Serializable
data class EffectDefaults(
    val photoDuring: AnimKind? = null,
    val mainTransition: Transition? = null,
    val textStart: AnimKind? = null,
)

/**
 * A video being edited. [tracks] is the SCREEN order, top row first;
 * [stacking] is the DRAWING order of the visual rows, bottom first. They
 * are independent (section 6.2): moving a row on screen never changes the
 * video. The preset's caption style (and [captionDefaultText], the words a
 * new caption starts with), photo duration, fit, effects and ducking are
 * copied in when the project is created, so a project renders the same even
 * if its preset changes later.
 *
 * [name] is null for an unnamed draft. It is for display only
 * (`TextRules.isValidName`); code that makes a file name from it must make it
 * safe itself.
 *
 * [transitionForAll] is set by **Apply to all**: while it is set, every MAIN
 * junction, including new ones, gets that transition.
 *
 * [shape] is the video's shape (cm-aspect-ratio): the preset's, or the one
 * chosen for a video made without one. [askShape] is set on a new video made
 * without a preset until its shape is chosen, so the editor asks once.
 *
 * The schema version is not a field: `ProjectCodec` writes it.
 */
@Serializable
data class Project(
    val id: ProjectId,
    val tracks: List<Track>,
    val stacking: List<TrackId>,
    val name: String? = null,
    val presetId: PresetId? = null,
    val captionDefaults: CaptionStyle = CaptionStyle.STANDARD,
    val captionDefaultText: LocalizedText? = null,
    val photoDurationMs: Long = DEFAULT_PHOTO_MS,
    val defaultFit: Fit = Fit.FILL_CROP,
    val effectDefaults: EffectDefaults = EffectDefaults(),
    val ducking: DuckingSpec = DuckingSpec(),
    val transitionForAll: Transition? = null,
    val shape: CanvasShape = CanvasShape.LANDSCAPE,
    val askShape: Boolean = false,
) {
    /** The MAIN row. Every valid project has exactly one. */
    val main: Track get() = tracks.first { it.type == TrackType.MAIN }

    val mainClips: List<MainClip> get() = main.clips.filterIsInstance<MainClip>()

    fun track(id: TrackId): Track? = tracks.firstOrNull { it.id == id }

    fun requireTrack(id: TrackId): Track = requireNotNull(track(id)) { "No such row" }

    fun trackOf(clipId: ClipId): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }

    fun clip(clipId: ClipId): Clip? = trackOf(clipId)?.clips?.firstOrNull { it.id == clipId }

    fun requireClip(clipId: ClipId): Clip = requireNotNull(clip(clipId)) { "No such clip" }

    /** Visual rows top first in drawing order, i.e. [stacking] reversed. */
    val stackingTopFirst: List<TrackId> get() = stacking.asReversed()

    /** Index in [stacking] (0 = bottom), or -1 for audio rows. */
    fun stackIndex(id: TrackId): Int = stacking.indexOf(id)

    /** True when [id] is drawn under MAIN, so MAIN hides it while a photo or video plays (section 6.2). */
    fun isBelowMain(id: TrackId): Boolean = stackIndex(id) in 0 until stackIndex(main.id)

    companion object {
        const val DEFAULT_PHOTO_MS = 3000L
    }
}
