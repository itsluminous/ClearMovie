package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.media.MediaMetadataRetriever
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.render.plan.SoundPresence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Whether each of [refs] has a sound track (cm-frame-audio), read with
 * `METADATA_KEY_HAS_AUDIO` through [resolver] and the renderer's [UriGuard],
 * as the export's composition reads it. The editor and the export both use
 * this, so a video lowers music in the preview exactly when it does in the
 * export. A file that can't be opened is left out (unknown). [known] answers
 * already found are not looked at again.
 */
suspend fun readSoundTracks(
    context: Context,
    refs: List<MediaRef>,
    resolver: MediaResolver,
    known: Map<MediaRef, Boolean> = emptyMap(),
): Map<MediaRef, Boolean> =
    withContext(Dispatchers.IO) {
        val guard = runCatching { UriGuard(context) }.getOrNull() ?: return@withContext known
        val found = known.toMutableMap()
        for (ref in refs) {
            ensureActive()
            if (ref in found) continue
            val uri = runCatching { guard.check(resolver.resolve(ref)) }.getOrNull() ?: continue
            val r = runCatching { MediaMetadataRetriever() }.getOrNull() ?: continue
            try {
                r.setDataSource(context, uri)
                found[ref] = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // Unreadable now: unknown, so it lowers music as before.
            } finally {
                runCatching { r.release() }
            }
        }
        found
    }

/** [readSoundTracks] behind an interface, so the editor's tests can answer for it. */
fun interface SoundTracks {
    suspend fun read(
        refs: List<MediaRef>,
        resolver: MediaResolver,
        known: Map<MediaRef, Boolean>,
    ): Map<MediaRef, Boolean>
}

/** [SoundTracks] on the device. */
class AndroidSoundTracks(
    private val context: Context,
) : SoundTracks {
    override suspend fun read(
        refs: List<MediaRef>,
        resolver: MediaResolver,
        known: Map<MediaRef, Boolean>,
    ) = readSoundTracks(context, refs, resolver, known)
}

/** [answers] as a [SoundPresence]; a file not in it is unknown. */
fun soundPresenceOf(answers: Map<MediaRef, Boolean>): SoundPresence = SoundPresence { answers[it] }
