package com.itsluminous.clearmovie.ui.editor.timeline

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ClipId
import org.junit.Test

/** Where the "+" at a row's right end adds, from the cursor (cm-plus-position). */
class RowEndPlusTest {
    private fun clip(
        id: String,
        start: Long,
        end: Long,
        kind: RowClipKind = RowClipKind.TEXT,
    ) = RowClip(ClipId(id), start, end, kind)

    private fun after(id: String) = EndPlusTarget.After(ClipId(id))

    // A 2-4 s, then B 6-8 s and C 8-9 s touching it, then D 12-13 s.
    private val row = listOf(clip("a", 2000, 4000), clip("b", 6000, 8000), clip("c", 8000, 9000), clip("d", 12_000, 13_000))

    @Test
    fun `cursor in a gap adds at the cursor`() {
        for (cursor in listOf(0L, 1999L, 4000L, 5000L, 9000L, 11_999L)) {
            assertThat(RowEndPlus.timed(row, cursor)).isEqualTo(EndPlusTarget.At(cursor))
        }
    }

    @Test
    fun `cursor on an item adds right after it`() {
        assertThat(RowEndPlus.timed(row, 2000)).isEqualTo(after("a"))
        assertThat(RowEndPlus.timed(row, 3999)).isEqualTo(after("a"))
        assertThat(RowEndPlus.timed(row, 12_500)).isEqualTo(after("d"))
    }

    @Test
    fun `when the space after it is taken too, after the last of the items that touch`() {
        // On B, whose end is C's start: after C, the first free moment.
        assertThat(RowEndPlus.timed(row, 6500)).isEqualTo(after("c"))
        assertThat(RowEndPlus.timed(row, 8500)).isEqualTo(after("c"))
        // A longer chain.
        val chain = listOf(clip("p", 0, 1000), clip("q", 1000, 2000), clip("r", 2000, 3000), clip("s", 3500, 4000))
        assertThat(RowEndPlus.timed(chain, 0)).isEqualTo(after("r"))
    }

    @Test
    fun `cursor past every item on the row, and on an empty row, adds at the cursor`() {
        assertThat(RowEndPlus.timed(row, 14_000)).isEqualTo(EndPlusTarget.At(14_000))
        assertThat(RowEndPlus.timed(row, 13_000)).isEqualTo(EndPlusTarget.At(13_000))
        assertThat(RowEndPlus.timed(emptyList(), 5000)).isEqualTo(EndPlusTarget.At(5000))
        // An item of no length is never under the cursor.
        assertThat(RowEndPlus.timed(listOf(clip("z", 5000, 5000)), 5000)).isEqualTo(EndPlusTarget.At(5000))
    }

    @Test
    fun `nothing is added where it would not be seen, after the end of the video`() {
        // A 14 s video; captions 2-4 s and 6-8 s; a new caption is 3 s.
        val captions = listOf(clip("a", 2000, 4000), clip("b", 6000, 8000))
        // At the very end (after playing it through) and 1 s before it: from 11 s, the last moment that keeps 3 s in.
        assertThat(RowEndPlus.timed(captions, 14_000, 3000, videoMs = 14_000)).isEqualTo(EndPlusTarget.At(11_000))
        assertThat(RowEndPlus.timed(captions, 13_000, 3000, videoMs = 14_000)).isEqualTo(EndPlusTarget.At(11_000))
        // Further from the end the cursor stands.
        assertThat(RowEndPlus.timed(captions, 9000, 3000, videoMs = 14_000)).isEqualTo(EndPlusTarget.At(9000))
        // "The End" captions 5-8 s and 8-12 s, cursor on the first: the row is busy at 11 s, so right after them, at 12 s.
        val theEnd = listOf(clip("p", 5000, 8000), clip("q", 8000, 12_000))
        assertThat(RowEndPlus.timed(theEnd, 6000, 3000, videoMs = 14_000)).isEqualTo(after("q"))
        // A caption running to the end: no room on this row, so at the last moment (the model puts it on another row).
        val toEnd = listOf(clip("z", 8000, 14_000))
        assertThat(RowEndPlus.timed(toEnd, 12_000, 3000, videoMs = 14_000)).isEqualTo(EndPlusTarget.At(11_000))
        // A video shorter than the item: from the start.
        assertThat(RowEndPlus.timed(emptyList(), 2000, 3000, videoMs = 2000)).isEqualTo(EndPlusTarget.At(0))
    }

    @Test
    fun `a sound or a recording counts its own small length, so it stays where the cursor is near the end`() {
        // An empty voice row, cursor at 13 s of 14 s: recording starts at 13 s, not 3 s earlier.
        assertThat(RowEndPlus.timed(emptyList(), 13_000, 100, videoMs = 14_000)).isEqualTo(EndPlusTarget.At(13_000))
        // At the very end: the last moment inside the video.
        assertThat(RowEndPlus.timed(emptyList(), 14_000, 100, videoMs = 14_000)).isEqualTo(EndPlusTarget.At(13_900))
        // On a music clip 9-12 s, cursor 10 s: right after it, on the same row.
        assertThat(RowEndPlus.timed(listOf(clip("m", 9000, 12_000)), 10_000, 100, videoMs = 14_000)).isEqualTo(after("m"))
    }

    @Test
    fun `the same rule on every kind of row other than MAIN`() {
        // Text, picture in picture (photo or video), frame, music, voice and sound rows all hold RowClips.
        for (kind in RowClipKind.entries) {
            val items = listOf(clip("x", 1000, 3000, kind), clip("y", 3000, 4000, kind))
            assertThat(RowEndPlus.timed(items, 500)).isEqualTo(EndPlusTarget.At(500))
            assertThat(RowEndPlus.timed(items, 1500)).isEqualTo(after("y"))
            assertThat(RowEndPlus.timed(items, 4500)).isEqualTo(EndPlusTarget.At(4500))
        }
    }

    // MAIN: intro 0-2, a 2-5, b 5-8 with a 1 s transition into c 7-10, ending 10-12.
    private val main =
        listOf(
            SlotSpan("intro", 0, 2000, content = false),
            SlotSpan("a", 2000, 5000, content = true),
            SlotSpan("b", 5000, 8000, content = true),
            SlotSpan("c", 7000, 10_000, content = true),
            SlotSpan("end", 10_000, 12_000, content = false, ending = true),
        )

    private fun at(
        index: Int,
        side: InsertSide = InsertSide.AFTER,
    ) = MainEndTarget(index, side)

    @Test
    fun `MAIN, cursor on an item adds right after it and later items move later`() {
        assertThat(RowEndPlus.main(main, 3000)).isEqualTo(at(1))
        assertThat(RowEndPlus.main(main, 6000)).isEqualTo(at(2))
        assertThat(RowEndPlus.main(main, 9000)).isEqualTo(at(3))
    }

    @Test
    fun `MAIN, cursor exactly between two items adds there`() {
        // 2 s: between the intro and a; 5 s: between a and b.
        assertThat(RowEndPlus.main(main, 2000)).isEqualTo(at(0))
        assertThat(RowEndPlus.main(main, 5000)).isEqualTo(at(1))
        // 10 s: between c and the ending.
        assertThat(RowEndPlus.main(main, 10_000)).isEqualTo(at(3))
    }

    @Test
    fun `MAIN, in a transition after the later item, so never left of the cursor`() {
        assertThat(RowEndPlus.main(main, 7000)).isEqualTo(at(3))
        assertThat(RowEndPlus.main(main, 7999)).isEqualTo(at(3))
    }

    @Test
    fun `MAIN, never before the intro or after the ending`() {
        // On the intro, at 0: after it.
        assertThat(RowEndPlus.main(main, 0)).isEqualTo(at(0))
        assertThat(RowEndPlus.main(main, 1000)).isEqualTo(at(0))
        // On the ending: just before it.
        assertThat(RowEndPlus.main(main, 11_000)).isEqualTo(at(4, InsertSide.BEFORE))
        // At or past the very end: after the last photo, video or card.
        assertThat(RowEndPlus.main(main, 12_000)).isEqualTo(at(3))
        assertThat(RowEndPlus.main(main, 20_000)).isEqualTo(at(3))
    }

    @Test
    fun `MAIN without an intro or ending adds before the first item at the start and after the last at the end`() {
        val plain = listOf(SlotSpan("a", 0, 3000, content = true), SlotSpan("b", 3000, 6000, content = true))
        assertThat(RowEndPlus.main(plain, 0)).isEqualTo(at(0, InsertSide.BEFORE))
        assertThat(RowEndPlus.main(plain, 1000)).isEqualTo(at(0))
        assertThat(RowEndPlus.main(plain, 3000)).isEqualTo(at(0))
        assertThat(RowEndPlus.main(plain, 6000)).isEqualTo(at(1))
        assertThat(RowEndPlus.main(plain, 9000)).isEqualTo(at(1))
    }

    @Test
    fun `MAIN with only an intro adds after it, with only an ending before it, and with nothing has no target`() {
        assertThat(RowEndPlus.main(emptyList(), 0)).isNull()
        // As the model allows: nothing before the intro, nothing after the ending.
        for (cursor in listOf(0L, 500L, 2000L, 9000L)) {
            assertThat(RowEndPlus.main(listOf(SlotSpan("intro", 0, 2000, content = false)), cursor)).isEqualTo(at(0))
            assertThat(RowEndPlus.main(listOf(SlotSpan("end", 0, 2000, content = false, ending = true)), cursor))
                .isEqualTo(at(0, InsertSide.BEFORE))
        }
    }

    @Test
    fun `a caption goes at the cursor only where it fits, else at the first place right of the cursor where it does`() {
        // Captions 2-4 s and 6-8 s, a 2 s gap between them; a new caption is 3 s.
        val captions = listOf(clip("a", 2000, 4000), clip("b", 6000, 8000))
        // 1 s before "a" there is no room for 3 s, nor in the 2 s after "a": after "b".
        assertThat(RowEndPlus.timed(captions, 1000, 3000)).isEqualTo(after("b"))
        // Room at the very start for 2 s.
        assertThat(RowEndPlus.timed(captions, 0, 2000)).isEqualTo(EndPlusTarget.At(0))
        // On "a": the gap after it is too short, so after "b".
        assertThat(RowEndPlus.timed(captions, 3000, 3000)).isEqualTo(after("b"))
        // With a short enough item the gap after "a" is used.
        assertThat(RowEndPlus.timed(captions, 3000, 2000)).isEqualTo(after("a"))
        // In the gap, too short from the cursor: after the next item.
        assertThat(RowEndPlus.timed(captions, 4500, 3000)).isEqualTo(after("b"))
        // Past every item: always room.
        assertThat(RowEndPlus.timed(captions, 8000, 3000)).isEqualTo(EndPlusTarget.At(8000))
        // A length not known yet (a file still to pick) counts one moment: any gap is room.
        assertThat(RowEndPlus.timed(captions, 4500)).isEqualTo(EndPlusTarget.At(4500))
        assertThat(RowEndPlus.timed(captions, 3000)).isEqualTo(after("a"))
    }
}
