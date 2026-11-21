package com.itsluminous.clearmovie.data.preset

import android.net.Uri
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
    /** The picture's width and height as it is shown (rotation applied); 0 when unknown or without pictures. */
    val shownWidth: Int = 0,
    val shownHeight: Int = 0,
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

    /**
     * The same for a file used where it is (cm-frame-area), by its
     * `content://` [uri]; null when it cannot be read.
     */
    suspend fun probe(
        uri: Uri,
        frameAt: List<Double> = emptyList(),
        measureLoudness: Boolean = false,
    ): ProbeResult? = null
}
