package com.itsluminous.clearmovie.data.preset

import java.io.File

/** What [MediaProbe] found in a file. */
data class ProbeResult(
    val durationUs: Long,
    val mime: String?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    /** Frames at the requested points, scaled down; empty when none were asked for or none decoded. */
    val frames: List<FrameSample> = emptyList(),
    /** Integrated loudness of the audio track, or null without audio (or when it could not be measured). */
    val loudnessLufs: Double? = null,
)

/**
 * Reads a media file's facts at preset setup: its length, whether it has
 * pictures and sound, a few frames for the key-colour check, and its
 * loudness. Behind an interface because decoding needs the phone's codecs;
 * JVM tests use a fake, and the instrumented tests run the real one on
 * synthetic fixtures.
 */
interface MediaProbe {
    /** Null when the file is not playable media. */
    suspend fun probe(
        file: File,
        /** Where to take frames, as fractions (0..1) of the file's length. */
        frameAt: List<Double> = emptyList(),
        measureLoudness: Boolean = false,
    ): ProbeResult?
}
