package com.itsluminous.clearmovie.ui.editor

import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import com.itsluminous.clearmovie.data.record.VoiceRecorder
import com.itsluminous.clearmovie.data.record.VoiceRecordings
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.recordingClip
import com.itsluminous.clearmovie.domain.record.RecordingSync
import com.itsluminous.clearmovie.render.preview.LayeredPreviewEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the recording panel shows while a voice is recorded (cm-record-voice). */
sealed interface RecordingUi {
    /** Where on the video the recording starts. */
    val startMs: Long

    /** The microphone is on; the picture starts in [secondsLeft]. */
    data class Countdown(
        override val startMs: Long,
        val secondsLeft: Int,
    ) : RecordingUi

    /** Recording: [elapsedMs] said so far, [leftMs] of video left to speak over. */
    data class Recording(
        override val startMs: Long,
        val elapsedMs: Long,
        val leftMs: Long,
    ) : RecordingUi

    /** Stopped: the file is being finished and put on the timeline. */
    data class Saving(
        override val startMs: Long,
    ) : RecordingUi
}

/** How a recording ended, when the editor has something to say about it. */
enum class RecordingNote {
    /** It went on the timeline; a call or leaving the app stopped it first. */
    KEPT_AFTER_INTERRUPTION,

    /** Too short to keep (Stop straight away). */
    TOO_SHORT,

    /** The microphone could not be opened (another app is using it). */
    NO_MICROPHONE,

    /** The file could not be written (the phone is full, say). */
    FAILED,

    /** The playhead is at the very end: there is no video left to speak over. */
    NO_ROOM,
}

/**
 * Records a voice over the video (cm-record-voice): the microphone opens and
 * a short countdown shows, then the preview plays from the playhead, with
 * its own sound off so it never goes back into the microphone, and what is
 * said is recorded. The recording is lined up with the picture
 * ([RecordingSync]): it starts with what was said as the first picture
 * showed. It ends at Stop, at the end of the video, when the preview stops
 * or is moved, or when the microphone is taken (a call), and what was
 * recorded is kept and put on a voice row at the playhead as one undo step
 * ([place]). Cancel keeps nothing. Lives in the editor's view model, so a
 * rotation keeps recording. Main thread only.
 */
@OptIn(UnstableApi::class)
internal class VoiceRecordingController(
    private val scope: CoroutineScope,
    private val engine: LayeredPreviewEngine,
    private val recordings: VoiceRecordings,
    private val projectId: ProjectId,
    /** Puts [AudioClip] on the timeline (row, or null for the first free voice row); its id, or null when refused. */
    private val place: (AudioClip, TrackId?) -> ClipId?,
    private val onPlaced: (ClipId) -> Unit,
    private val onNote: (RecordingNote) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val stateFlow = MutableStateFlow<RecordingUi?>(null)
    val state: StateFlow<RecordingUi?> = stateFlow.asStateFlow()

    val active: Boolean get() = stateFlow.value != null

    private var job: Job? = null
    private var recorder: VoiceRecorder? = null
    private var pending: VoiceRecordings.Pending? = null
    private var row: TrackId? = null
    private var startUs = 0L
    private var durationUs = 0L
    private val samples = mutableListOf<RecordingSync.Sample>()
    private var pictureStartNs: Long? = null

    /** When Play was asked for: the start used if the picture never shows moving (a part that can't be played). */
    private var playAskedNs: Long? = null

    /** Starts a recording from [atMs] of the video, for voice row [row] (null: the first free one). */
    fun start(
        row: TrackId?,
        atMs: Long,
    ) {
        if (active) return
        val schedule = engine.schedule ?: return
        engine.pause()
        durationUs = schedule.durationUs
        startUs = (atMs * 1000).coerceIn(0, durationUs)
        if (durationUs - startUs < MIN_ROOM_US) {
            onNote(RecordingNote.NO_ROOM)
            return
        }
        this.row = row
        samples.clear()
        pictureStartNs = null
        playAskedNs = null
        val startMs = startUs / 1000
        val p = recordings.newRecording(projectId)
        val r = recordings.recorder(p)
        pending = p
        recorder = r
        stateFlow.value = RecordingUi.Countdown(startMs, COUNTDOWN_S)
        job =
            scope.launch {
                // The microphone opens with the countdown on screen, and is warm when the picture moves.
                val opened = withContext(Dispatchers.IO) { r.start() }
                if (!opened) {
                    if (recorder === r) {
                        recorder = null
                        pending = null
                        stateFlow.value = null
                        withContext(Dispatchers.IO + NonCancellable) { recordings.discard(p) }
                        onNote(RecordingNote.NO_MICROPHONE)
                    }
                    return@launch
                }
                engine.seekTo(startUs)
                for (s in COUNTDOWN_S downTo 1) {
                    stateFlow.value = RecordingUi.Countdown(startMs, s)
                    delay(SECOND_MS)
                }
                engine.monitorGain = 0f
                playAskedNs = nanoTime()
                engine.play()
                stateFlow.value = RecordingUi.Recording(startMs, 0, (durationUs - startUs) / 1000)
                var shown = 0L
                while (isActive) {
                    val now = nanoTime()
                    val sample = RecordingSync.Sample(now, engine.positionUs)
                    onSample?.invoke(sample.wallNs, sample.positionUs)
                    var start = pictureStartNs
                    if (start == null) {
                        // Only readings after the picture moved count, and a dozen are enough.
                        if (sample.positionUs >= startUs + RecordingSync.MIN_MOVE_US) samples += sample
                        if (samples.size >= SYNC_SAMPLES) {
                            start = RecordingSync.playbackStartNs(samples, startUs)
                            pictureStartNs = start
                            start?.let(r::startAt)
                        }
                    }
                    if (now - shown >= SHOW_EVERY_NS) {
                        shown = now
                        // Both from the picture's time, so they add up to what the playback strip shows.
                        val at = sample.positionUs.coerceIn(startUs, durationUs)
                        stateFlow.value = RecordingUi.Recording(startMs, (at - startUs) / 1000, (durationUs - at) / 1000)
                    }
                    val ended = sample.positionUs >= durationUs - END_SLACK_US || !engine.isPlaying
                    val moved = start != null && RecordingSync.leftTheRecording(sample, start, startUs)
                    if (ended || moved || r.interrupted) {
                        end(keep = true, interrupted = r.interrupted || (moved && !ended), atNs = now)
                        return@launch
                    }
                    delay(if (start == null) SAMPLE_MS else TICK_MS)
                }
            }
    }

    /** Stop: keeps what was recorded. */
    fun stop(interrupted: Boolean = false) = end(keep = true, interrupted = interrupted, atNs = nanoTime())

    /** Cancel: keeps nothing. */
    fun cancel() = end(keep = false, interrupted = false, atNs = nanoTime())

    /** The view model is going: a recording still running is dropped, off the main thread. */
    fun release() {
        val r = recorder ?: return
        val p = pending
        recorder = null
        pending = null
        job?.cancel()
        Thread {
            r.cancel()
            p?.let(recordings::discard)
        }.start()
    }

    private fun end(
        keep: Boolean,
        interrupted: Boolean,
        atNs: Long,
    ) {
        val now = stateFlow.value ?: return
        if (now is RecordingUi.Saving) return
        job?.cancel()
        job = null
        engine.pause()
        engine.monitorGain = 1f
        val r = recorder
        val p = pending
        recorder = null
        pending = null
        // The picture's start; if it never showed moving, when Play was asked for (kept, lined up as well as can be).
        val start = pictureStartNs ?: RecordingSync.playbackStartNs(samples, startUs) ?: playAskedNs
        val startMs = now.startMs
        if (r == null || p == null) {
            stateFlow.value = null
            return
        }
        if (!keep || now is RecordingUi.Countdown || start == null) {
            // Nothing to keep: cancelled, or stopped in the countdown.
            stateFlow.value = null
            engine.seekTo(startUs)
            scope.launch(Dispatchers.IO + NonCancellable) {
                r.cancel()
                recordings.discard(p)
            }
            return
        }
        stateFlow.value = RecordingUi.Saving(startMs)
        scope.launch {
            val result = withContext(Dispatchers.IO + NonCancellable) { r.finish(atNs, start) }
            val kept = result.ok && withContext(Dispatchers.IO + NonCancellable) { recordings.keep(p) }
            val clip = recordingClip(RandomIds.clipId(), p.ref, startMs, result.durationMs, durationUs / 1000)
            val placed = if (kept && clip != null) place(clip, row) else null
            // A refused placement has been said already ("That can't be done"): nothing more to add.
            val refused = kept && clip != null && placed == null
            if (placed == null) {
                withContext(Dispatchers.IO + NonCancellable) { recordings.discard(p) }
            }
            stateFlow.value = null
            engine.seekTo(startUs)
            val spokenMs = (atNs - start) / 1_000_000
            when {
                placed != null -> {
                    onPlaced(placed)
                    if (interrupted) onNote(RecordingNote.KEPT_AFTER_INTERRUPTION)
                }

                refused -> {
                    Unit
                }

                spokenMs < TOO_SHORT_MS -> {
                    onNote(RecordingNote.TOO_SHORT)
                }

                else -> {
                    onNote(RecordingNote.FAILED)
                }
            }
        }
    }

    companion object {
        /** Tests only: each reading of the preview while recording (the moment, and the video's time then). */
        @VisibleForTesting
        @Volatile
        var onSample: ((wallNs: Long, positionUs: Long) -> Unit)? = null

        const val COUNTDOWN_S = 3
        private const val SECOND_MS = 1000L

        /** Less video than this after the playhead is too little to record over. */
        const val MIN_ROOM_US = 1_000_000L

        /** Stopped sooner than this after the picture started: too short, rather than failed. */
        private const val TOO_SHORT_MS = 500L

        /** The video counts as ended this close to its end (the last frame). */
        private const val END_SLACK_US = 40_000L

        /** Moving samples taken before the picture's start is worked out. */
        private const val SYNC_SAMPLES = 12

        /** How often the preview is read while the start is worked out, and after. */
        private const val SAMPLE_MS = 8L
        private const val TICK_MS = 50L
        private const val SHOW_EVERY_NS = 200_000_000L
    }
}
