package com.itsluminous.clearmovie.domain.model

/**
 * Voice recorded in the editor ("Record voice" on a voice row, cm-record-voice).
 * A recording is an AAC file in M4A, kept in the project's own media folder
 * as `voice-<token>.m4a` and played by an [AudioClip] like any other sound.
 */
object VoiceRecordingFiles {
    const val PREFIX = "voice-"
    const val EXTENSION = ".m4a"
    private val TOKEN = Regex("^[a-z0-9]{8,32}$")
    private val NAME = Regex("^voice-[a-z0-9]{8,32}\\.m4a$")

    /** The file name for a new recording; [token] is 8 to 32 lower-case letters and digits. */
    fun nameFor(token: String): String {
        require(TOKEN.matches(token)) { "A recording token is 8 to 32 letters and digits" }
        return PREFIX + token + EXTENSION
    }

    /** Whether [relPath] (a [MediaRef.Copied] path) names a recording made in the editor. */
    fun isRecording(relPath: String): Boolean = NAME.matches(relPath)
}

/** The recordings the project still plays (an undo history is not counted: it lives only while the editor is open). */
fun Project.recordingsInUse(): Set<String> =
    tracks
        .asSequence()
        .flatMap { it.clips }
        .filterIsInstance<AudioClip>()
        .mapNotNull { (it.media as? MediaRef.Copied)?.relPath }
        .filter(VoiceRecordingFiles::isRecording)
        .toSet()

/**
 * The clip for a recording that starts at [startMs] of the video and lasted
 * [recordedMs]: cut where the video ends ([videoEndMs]), so it never plays
 * past it. Null when what is left is shorter than [Limits.MIN_CLIP_MS].
 */
fun recordingClip(
    id: ClipId,
    media: MediaRef.Copied,
    startMs: Long,
    recordedMs: Long,
    videoEndMs: Long,
): AudioClip? {
    if (startMs < 0 || recordedMs <= 0) return null
    val length = minOf(recordedMs, videoEndMs - startMs, Limits.MAX_MEDIA_MS)
    if (length < Limits.MIN_CLIP_MS) return null
    return AudioClip(id, media, startMs = startMs, durationMs = length, sourceDurationMs = recordedMs, trimEndMs = length)
}

/**
 * Puts a new recording on the timeline at its own start time, never moved
 * (its words line up with the picture there): on [preferred], the voice row
 * whose menu started it, when that row is free for the recording's span;
 * otherwise on the highest free voice row; otherwise on a new voice row
 * (the section 6.1 rule every row follows).
 */
fun Project.addRecording(
    clip: AudioClip,
    preferred: TrackId?,
    ids: IdSource = RandomIds,
): Project {
    val row = preferred?.let(::track)?.takeIf { it.type == TrackType.AUDIO && it.audioRole == AudioRole.VOICE }
    return if (row != null) addToTrack(row.id, clip, clip.startMs, ids) else addTimedClip(clip, AudioRole.VOICE, ids)
}
