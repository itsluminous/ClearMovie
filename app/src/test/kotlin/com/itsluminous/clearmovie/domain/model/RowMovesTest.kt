package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import org.junit.Assert.assertThrows
import org.junit.Test

/** "Earlier", "Later" and "Move to another row" > "A new row" (cm-tracks). */
class RowMovesTest {
    private val ids = SequentialIds("m")

    /** A 10 s photo with two captions on one text row: 1-3 s and 5-7 s. */
    private fun captions(): Project =
        Fixtures
            .blankWith(Fixtures.photo("a", 10_000), ids = ids)
            .addText("one", 1000, 2000, ids)
            .addText("two", 5000, 2000, ids)
            .assertValid()

    private fun Project.textClip(words: String) = tracks.flatMap { it.clips }.filterIsInstance<TextClip>().single { it.text == words }

    @Test
    fun `Earlier and Later move an item on its row and stop at its neighbours and at the start`() {
        var p = captions()
        val one = p.textClip("one").id
        val two = p.textClip("two").id
        p = p.nudgeClip(one, -500).assertValid()
        assertThat(p.textClip("one").startMs).isEqualTo(500)
        // At most to the start of the video.
        p = p.nudgeClip(one, -800).assertValid()
        assertThat(p.textClip("one").startMs).isEqualTo(0)
        assertThat(p.nudgedStart(one, -500)).isNull()
        assertThrows(IllegalArgumentException::class.java) { p.nudgeClip(one, -500) }
        // Later stops where the next caption starts: it never jumps onto another row.
        p = p.nudgeClip(one, 4000).assertValid()
        assertThat(p.textClip("one").endMs).isEqualTo(5000)
        assertThat(p.nudgedStart(one, 500)).isNull()
        assertThat(p.trackOf(one)).isEqualTo(p.trackOf(two))
        // Backwards for the second one, as far as the first one's end.
        assertThat(p.nudgedStart(two, -500)).isNull()
        assertThat(p.nudgedStart(two, 500)).isEqualTo(5500)
        // Later stops at the end of the 10 s video.
        p = p.nudgeClip(two, 9000).assertValid()
        assertThat(p.textClip("two").endMs).isEqualTo(10_000)
        assertThat(p.nudgedStart(two, 500)).isNull()
        // A caption already past the end (the video got shorter): Later is off, never a move back.
        val past = p.copy(tracks = p.tracks.map { t -> t.copy(clips = t.clips.map { if (it.id == two) it.withStartMs(12_000) else it }) })
        assertThat(past.nudgedStart(two, 500)).isNull()
    }

    @Test
    fun `nudging a MAIN item or by nothing is refused`() {
        val p = captions()
        assertThrows(IllegalArgumentException::class.java) { p.nudgeClip(ClipId("a"), 500) }
        assertThrows(IllegalArgumentException::class.java) { p.nudgeClip(p.textClip("one").id, 0) }
        assertThat(p.nudgedStart(ClipId("a"), 500)).isNull()
        assertThat(p.nudgedStart(ClipId("nope"), 500)).isNull()
    }

    @Test
    fun `A new row keeps the item's time, puts the row in its kind's place, and the emptied row goes`() {
        var p = captions()
        val oldRow = p.trackOf(p.textClip("one").id)!!.id
        p = p.moveClipToNewRow(p.textClip("one").id, ids).assertValid()
        val newRow = p.trackOf(p.textClip("one").id)!!
        assertThat(newRow.id).isNotEqualTo(oldRow)
        assertThat(p.textClip("one").startMs).isEqualTo(1000)
        // A new text row goes on top of the stack and directly above the text rows on screen.
        assertThat(p.stacking.last()).isEqualTo(newRow.id)
        assertThat(p.tracks.first().id).isEqualTo(newRow.id)
        // Moving the last one away removes its old row, in the same step.
        p = p.moveClipToNewRow(p.textClip("two").id, ids).assertValid()
        assertThat(p.track(oldRow)).isNull()
        assertThat(p.tracks.count { it.type == TrackType.TEXT }).isEqualTo(2)
    }

    @Test
    fun `sound keeps its role on a new row, and the preset's own rows keep their items`() {
        var p = Fixtures.presetProject(ids).addToMain(listOf(Fixtures.photo("a", 10_000)))
        p = p.addTimedClip(Fixtures.audio("s", 0, 2000), AudioRole.VOICE, ids).assertValid()
        p = p.moveClipToNewRow(ClipId("s"), ids).assertValid()
        assertThat(p.trackOf(ClipId("s"))!!.audioRole).isEqualTo(AudioRole.VOICE)
        val frameClip =
            p
                .byRole(PresetRole.FRAME)
                .clips
                .single()
                .id
        assertThrows(IllegalArgumentException::class.java) { p.moveClipToNewRow(frameClip, ids) }
        assertThrows(IllegalArgumentException::class.java) { p.moveClipToNewRow(ClipId("a"), ids) }
    }
}
