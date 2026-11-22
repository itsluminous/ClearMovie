package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A new frame from the cursor (cm-plus-position): the frame under it hands over, in one edit. */
class FrameEditsTest {
    private val ids = SequentialIds("f")

    /** The preset project with three 3 s photos: the preset frame runs from the intro's end to the video's end. */
    private val project = Fixtures.presetProject().addToMain(listOf(Fixtures.photo("a"), Fixtures.photo("b"), Fixtures.photo("c")))
    private val frameRow = project.tracks.single { it.presetRole == PresetRole.FRAME }
    private val presetFrame = frameRow.clips.single() as OverlayClip
    private val total = project.totalDurationMs()

    @Test
    fun `in the middle of the preset frame it ends at the cursor and the new frame takes over the rest`() {
        val at = presetFrame.startMs + 4000
        val p = project.addFrameFrom(frameRow.id, Fixtures.overlay("n", 0, 1500), at, presetFrame.id, ids)
        val old = p.clip(presetFrame.id)!!
        val made = p.clip(ClipId("n")) as OverlayClip
        assertThat(old.endMs).isEqualTo(at)
        assertThat(old.startMs).isEqualTo(presetFrame.startMs)
        assertThat(made.startMs).isEqualTo(at)
        assertThat(made.endMs).isEqualTo(presetFrame.endMs)
        // Shorter than its time: it repeats to fill it.
        assertThat(made.repeat).isTrue()
        // On another frame row: the preset's row holds only its own frame.
        assertThat(p.trackOf(made.id)!!.id).isNotEqualTo(frameRow.id)
        assertThat(p.trackOf(made.id)!!.type).isEqualTo(TrackType.OVERLAY)
        assertThat(p.validate()).isEmpty()
    }

    @Test
    fun `the handover stays put when photos are added or taken away later, and the new frame still runs to the end`() {
        val at = presetFrame.startMs + 4000
        val p = project.addFrameFrom(frameRow.id, Fixtures.overlay("n", 0, 1500), at, presetFrame.id, ids)
        for (later in listOf(p.addToMain(listOf(Fixtures.photo("d"))), p.deleteClip(ClipId("c")))) {
            val old = later.clip(presetFrame.id)!!
            val made = later.clip(ClipId("n"))!!
            assertThat(old.endMs).isEqualTo(at)
            assertThat(made.startMs).isEqualTo(at)
            assertThat(made.endMs).isEqualTo(later.totalDurationMs())
            assertThat(later.validate()).isEmpty()
        }
    }

    @Test
    fun `near the frame's start it is cut a moment in, and near its end the new frame follows it`() {
        val nearStart = project.addFrameFrom(frameRow.id, Fixtures.overlay("n", 0), presetFrame.startMs + 100, presetFrame.id, ids)
        assertThat(nearStart.clip(presetFrame.id)!!.endMs).isEqualTo(presetFrame.startMs + ResizeRules.MIN_STILL_MS)
        assertThat(nearStart.clip(ClipId("n"))!!.startMs).isEqualTo(presetFrame.startMs + ResizeRules.MIN_STILL_MS)
        val nearEnd = project.addFrameFrom(frameRow.id, Fixtures.overlay("m", 0), presetFrame.endMs - 100, presetFrame.id, ids)
        assertThat(nearEnd.clip(presetFrame.id)!!.endMs).isEqualTo(presetFrame.endMs)
        assertThat(nearEnd.clip(ClipId("m"))!!.startMs).isEqualTo(presetFrame.endMs)
    }

    @Test
    fun `on a frame row of the user's own the new frame stays on that row`() {
        val own = TrackId("own")
        val p0 = project.addRow(TrackType.OVERLAY, id = own).addToTrack(own, Fixtures.overlay("o", 0, 4000).copy(repeat = true), 2000)
        val p = p0.addFrameFrom(own, Fixtures.overlay("n", 0), 4000, ClipId("o"), ids)
        assertThat(p.clip(ClipId("o"))!!.endMs).isEqualTo(4000)
        assertThat(p.clip(ClipId("n"))!!.startMs).isEqualTo(4000)
        assertThat(p.clip(ClipId("n"))!!.endMs).isEqualTo(6000)
        assertThat(p.trackOf(ClipId("n"))!!.id).isEqualTo(own)
    }

    @Test
    fun `in a gap it runs to the next frame on the row, or the video's end`() {
        val own = TrackId("own")
        val p0 = project.addRow(TrackType.OVERLAY, id = own).addToTrack(own, Fixtures.overlay("o", 0, 2000), 8000)
        val p = p0.addFrameFrom(own, Fixtures.overlay("n", 0), 3000, null, ids)
        assertThat(p.clip(ClipId("n"))!!.startMs).isEqualTo(3000)
        assertThat(p.clip(ClipId("n"))!!.endMs).isEqualTo(8000)
        val q = p0.addFrameFrom(own, Fixtures.overlay("m", 0), 11_000, null, ids)
        assertThat(q.clip(ClipId("m"))!!.endMs).isEqualTo(total)
    }

    @Test
    fun `only a frame row takes a frame this way`() {
        val text = TrackId("t")
        val p0 = project.addRow(TrackType.TEXT, id = text)
        val refused = runCatching { p0.addFrameFrom(text, Fixtures.overlay("n", 0), 1000, null, ids) }
        assertThat(refused.isFailure).isTrue()
    }
}
