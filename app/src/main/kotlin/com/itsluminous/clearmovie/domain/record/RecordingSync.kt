package com.itsluminous.clearmovie.domain.record

import kotlin.math.roundToLong

/**
 * Lines a voice recording up with the picture (cm-record-voice). The
 * microphone starts first, while the countdown shows, so it is running when
 * the picture moves; the preview then plays from the playhead. Two clocks
 * meet on the phone's monotonic clock (`System.nanoTime`):
 *
 * - the preview's position, read again and again while it plays
 *   ([Sample]): the moment it showed the start ([playbackStartNs]) is when
 *   the user saw the first picture;
 * - the microphone's capture timestamps (a frame number and the time that
 *   frame reached the microphone, from `AudioRecord.getTimestamp`):
 *   [frameAt] turns a moment into the frame recorded then.
 *
 * The frames before that one are dropped, so the recording's first sound is
 * what was said as the first picture showed, however long the microphone
 * and the player took to start. Pure Kotlin, tested on the JVM.
 */
object RecordingSync {
    /** The preview at one moment: [positionUs] of the video's time, read at [wallNs]. */
    data class Sample(
        val wallNs: Long,
        val positionUs: Long,
    )

    /** Samples before the picture has moved this far from the start are not counted (it may not have started yet). */
    const val MIN_MOVE_US = 30_000L

    /** At most this many moving samples are used: later ones may include a stall. */
    const val MAX_SAMPLES = 24

    /** The preview ran ahead of steady playback by more than this: it was moved (a seek), and the recording stops. */
    const val AHEAD_LIMIT_US = 500_000L

    /** The preview fell behind steady playback by more than this (moved back, or held up by a call): the recording stops. */
    const val BEHIND_LIMIT_US = 3_000_000L

    /**
     * The moment the preview showed [startUs] and began to move from it, on
     * the monotonic clock: for each sample after the picture moved, its time
     * less how far the picture had got; the median of those, so one late
     * reading changes nothing. Null when no sample shows it moving yet.
     */
    fun playbackStartNs(
        samples: List<Sample>,
        startUs: Long,
    ): Long? {
        val starts =
            samples
                .filter { it.positionUs >= startUs + MIN_MOVE_US }
                .take(MAX_SAMPLES)
                .map { it.wallNs - (it.positionUs - startUs) * 1000 }
                .sorted()
        if (starts.isEmpty()) return null
        val mid = starts.size / 2
        return if (starts.size % 2 == 1) starts[mid] else starts[mid - 1] + (starts[mid] - starts[mid - 1]) / 2
    }

    /**
     * The frame the microphone captured at [wallNs], from its timestamp: frame
     * [anchorFrame] reached the microphone at [anchorNs], and frames follow at
     * [sampleRate] a second.
     */
    fun frameAt(
        wallNs: Long,
        anchorFrame: Long,
        anchorNs: Long,
        sampleRate: Int,
    ): Long {
        require(sampleRate > 0) { "A sample rate is positive" }
        return anchorFrame + ((wallNs - anchorNs).toDouble() * sampleRate / NANOS).roundToLong()
    }

    /** How long [frames] at [sampleRate] last, in milliseconds (rounded down). */
    fun msOf(
        frames: Long,
        sampleRate: Int,
    ): Long = if (frames <= 0 || sampleRate <= 0) 0 else frames * 1000 / sampleRate

    /** Microseconds from the start of the file at which frame [index] (counted from the first kept frame) plays. */
    fun ptsUs(
        index: Long,
        sampleRate: Int,
    ): Long = index * 1_000_000 / sampleRate

    /**
     * Whether the preview stopped following the recording: at [now] it is
     * more than [AHEAD_LIMIT_US] ahead of, or [BEHIND_LIMIT_US] behind, where
     * steady playback from [startUs] at [startNs] would be. A short hold-up
     * (the next item loading) is not counted: the recording goes on, and its
     * words stay where they were said in time.
     */
    fun leftTheRecording(
        now: Sample,
        startNs: Long,
        startUs: Long,
    ): Boolean {
        val expected = startUs + (now.wallNs - startNs) / 1000
        val drift = now.positionUs - expected
        return drift > AHEAD_LIMIT_US || drift < -BEHIND_LIMIT_US
    }

    private const val NANOS = 1_000_000_000.0
}
