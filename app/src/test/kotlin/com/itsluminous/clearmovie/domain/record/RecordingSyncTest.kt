package com.itsluminous.clearmovie.domain.record

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.record.RecordingSync.Sample
import org.junit.Assert.assertThrows
import org.junit.Test

/** Lining a voice recording up with the picture: the latency it measures and takes off (cm-record-voice). */
class RecordingSyncTest {
    private val ms = 1_000_000L

    /** A preview that was asked to play at 0 ns, sat still for [lagMs], then played at real speed from [startUs]. */
    private fun samples(
        startUs: Long,
        lagMs: Long,
        jitterUs: (Int) -> Long = { 0 },
    ): List<Sample> =
        (0 until 60).map { i ->
            val wall = i * 8 * ms
            val moved = ((wall - lagMs * ms) / 1000).coerceAtLeast(0)
            Sample(wall, startUs + moved + if (moved > 0) jitterUs(i) else 0)
        }

    @Test
    fun `the picture's start is found however long the player took to start`() {
        for (lag in listOf(0L, 37L, 180L, 420L)) {
            val start = RecordingSync.playbackStartNs(samples(12_000_000, lag), 12_000_000)
            assertThat(start).isEqualTo(lag * ms)
        }
    }

    @Test
    fun `one late reading of the position does not move the start`() {
        val noisy =
            samples(5_000_000, 200) { i ->
                if (i == 30) {
                    -90_000
                } else if (i % 2 == 0) {
                    2_000
                } else {
                    -2_000
                }
            }
        val start = requireNotNull(RecordingSync.playbackStartNs(noisy, 5_000_000))
        assertThat(start).isIn(
            com.google.common.collect.Range
                .closed(198 * ms, 202 * ms),
        )
    }

    @Test
    fun `nothing is known before the picture has moved`() {
        val still = (0 until 10).map { Sample(it * 10 * ms, 3_000_000 + 10_000) }
        assertThat(RecordingSync.playbackStartNs(still, 3_000_000)).isNull()
        assertThat(RecordingSync.playbackStartNs(emptyList(), 0)).isNull()
    }

    @Test
    fun `the microphone frame at a moment comes from its timestamp`() {
        // Frame 22050 reached the microphone at 1.5 s; 0.5 s later is 22050 more frames at 44.1 kHz.
        assertThat(RecordingSync.frameAt(2_000 * ms, 22_050, 1_500 * ms, 44_100)).isEqualTo(44_100)
        // Before the timestamp too (the picture started before the latest timestamp was taken).
        assertThat(RecordingSync.frameAt(1_000 * ms, 22_050, 1_500 * ms, 44_100)).isEqualTo(0)
        // Rounded to the nearest frame.
        assertThat(RecordingSync.frameAt(10_000, 0, 0, 48_000)).isEqualTo(0)
        assertThat(RecordingSync.frameAt(11_000, 0, 0, 48_000)).isEqualTo(1)
        assertThrows(IllegalArgumentException::class.java) { RecordingSync.frameAt(0, 0, 0, 0) }
    }

    @Test
    fun `the whole chain puts the first kept frame where the first picture showed`() {
        // The microphone started 3.2 s before the picture (the countdown) and its input runs 15 ms late.
        val rate = 44_100
        val micStartNs = -3_200 * ms
        val inputLatencyNs = 15 * ms
        val pictureNs = requireNotNull(RecordingSync.playbackStartNs(samples(8_000_000, 250), 8_000_000))
        // A timestamp read at 1 s: frame n reached the microphone at micStart + latency + n / rate.
        val anchorFrame = 4_096L * 46
        val anchorNs = micStartNs + inputLatencyNs + anchorFrame * 1_000_000_000L / rate
        val first = RecordingSync.frameAt(pictureNs, anchorFrame, anchorNs, rate)
        // Sound captured at the picture's start: 3.2 s + 0.25 s after the microphone started, less its latency.
        val expected = ((pictureNs - micStartNs - inputLatencyNs) * rate / 1_000_000_000L)
        assertThat(first).isIn(
            com.google.common.collect.Range
                .closed(expected - 1, expected + 1),
        )
    }

    @Test
    fun `lengths and times of frames`() {
        assertThat(RecordingSync.msOf(44_100, 44_100)).isEqualTo(1000)
        assertThat(RecordingSync.msOf(22_049, 44_100)).isEqualTo(499)
        assertThat(RecordingSync.msOf(0, 44_100)).isEqualTo(0)
        assertThat(RecordingSync.msOf(5, 0)).isEqualTo(0)
        assertThat(RecordingSync.ptsUs(44_100, 44_100)).isEqualTo(1_000_000)
        assertThat(RecordingSync.ptsUs(1024, 44_100)).isEqualTo(23_219)
    }

    @Test
    fun `a moved or held-up preview ends the recording, a short hold-up does not`() {
        val startNs = 100 * ms

        fun at(
            wallMs: Long,
            posUs: Long,
        ) = RecordingSync.leftTheRecording(Sample(wallMs * ms, posUs), startNs, 2_000_000)
        assertThat(at(1_100, 3_000_000)).isFalse()
        // The next item took 1 s to load: still recording.
        assertThat(at(2_100, 3_000_000)).isFalse()
        // Moved on by a seek.
        assertThat(at(1_100, 3_600_000)).isTrue()
        // Moved back, or held for over 3 s (a call).
        assertThat(at(1_100, 1_000_000)).isFalse()
        assertThat(at(4_200, 1_000_000)).isTrue()
        assertThat(at(5_200, 3_000_000)).isTrue()
    }
}
