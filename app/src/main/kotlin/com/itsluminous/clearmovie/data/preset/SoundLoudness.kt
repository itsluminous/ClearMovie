package com.itsluminous.clearmovie.data.preset

import android.content.Context
import android.net.Uri

/**
 * The integrated loudness (LUFS) of a file's sound, or null when it has no
 * sound track or can't be read (cm-frame-audio). The editor measures the
 * sounds the preset did not (a frame the user added, music, a voice) with
 * it, so the preview plays them at the levels the export does.
 */
fun interface SoundLoudness {
    suspend fun lufs(uri: Uri): Double?
}

/** [SoundLoudness] by decoding the file's first audio track, as the export measures it. */
class AndroidSoundLoudness(
    private val context: Context,
    private val probe: AndroidMediaProbe = AndroidMediaProbe(),
) : SoundLoudness {
    override suspend fun lufs(uri: Uri): Double? = probe.loudnessOf(context, uri)?.takeIf { it.isFinite() }
}
