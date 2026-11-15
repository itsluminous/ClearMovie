package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.mainIds
import org.junit.Assert.assertThrows
import org.junit.Test

/** "+" before and after an item, on every row type (section 11.6). */
class InsertTest {
    private val ids = SequentialIds("i")
    private val pip = PipPlacement.DEFAULT

    private fun mainOf(vararg names: String) = Fixtures.blankWith(*names.map { Fixtures.photo(it, 3000) }.toTypedArray(), ids = ids)

    @Test
    fun `before and after on MAIN insert next to the item and shift later clips`() {
        val p = mainOf("a", "b", "c")
        val before = p.insertAt(p.main.id, ClipId("b"), Side.BEFORE, Fixtures.video("x", 2000), ids).assertValid()
        assertThat(before.mainIds()).containsExactly("a", "x", "b", "c").inOrder()
        assertThat(before.mainSlot(ClipId("b"))!!.startMs).isEqualTo(5000)
        assertThat(before.mainSlot(ClipId("c"))!!.startMs).isEqualTo(8000)
        assertThat(before.totalDurationMs()).isEqualTo(11_000)

        val after = p.insertAt(p.main.id, ClipId("b"), Side.AFTER, Fixtures.photo("y", 1000), ids).assertValid()
        assertThat(after.mainIds()).containsExactly("a", "b", "y", "c").inOrder()
        assertThat(after.mainSlot(ClipId("b"))!!.startMs).isEqualTo(3000)
        assertThat(after.mainSlot(ClipId("c"))!!.startMs).isEqualTo(7000)

        val card = CardClip(ClipId("card"), Limits.DEFAULT_CARD_MS, ArgbColor.BLACK, text = "Hello")
        assertThat(p.insertAt(p.main.id, ClipId("a"), Side.BEFORE, card, ids).mainIds().first()).isEqualTo("card")
    }

    @Test
    fun `MAIN keeps the intro first and the ending last`() {
        val p = Fixtures.presetProject().addToMain(listOf(Fixtures.photo("a")), ids = ids)
        val intro =
            p.main.clips
                .first()
                .id
        val ending =
            p.main.clips
                .last()
                .id
        assertThrows(IllegalArgumentException::class.java) { p.insertAt(p.main.id, intro, Side.BEFORE, Fixtures.photo("x"), ids) }
        assertThrows(IllegalArgumentException::class.java) { p.insertAt(p.main.id, ending, Side.AFTER, Fixtures.photo("x"), ids) }
        val afterIntro = p.insertAt(p.main.id, intro, Side.AFTER, Fixtures.photo("x"), ids).assertValid()
        assertThat(afterIntro.mainIds().subList(1, 3)).containsExactly("x", "a").inOrder()
        val beforeEnding = p.insertAt(p.main.id, ending, Side.BEFORE, Fixtures.photo("y"), ids).assertValid()
        assertThat(beforeEnding.mainIds().takeLast(2)).containsExactly("y", ending.value).inOrder()
        // Only content goes on MAIN, and never with a picture-in-picture placement.
        assertThrows(IllegalArgumentException::class.java) { p.insertAt(p.main.id, ClipId("a"), Side.AFTER, Fixtures.text("t", 0), ids) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { p.insertAt(p.main.id, ClipId("a"), Side.AFTER, Fixtures.photo("z", pip = pip), ids) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { p.insertAt(p.main.id, ClipId("a"), Side.AFTER, IntroClip(ClipId("i2"), 1000), ids) }
    }

    /** One timed row of [type] with an item at 2..5 s, and the clip factory for that row. */
    private data class Case(
        val type: TrackType,
        val role: AudioRole?,
        val make: (id: String, start: Long, ms: Long) -> Clip,
    )

    private val cases =
        listOf(
            Case(TrackType.VISUAL, null) { id, s, ms -> Fixtures.photo(id, ms, pip).withStartMs(s) },
            Case(TrackType.TEXT, null) { id, s, ms -> Fixtures.text(id, s, ms) },
            Case(TrackType.OVERLAY, null) { id, s, ms -> Fixtures.overlay(id, s, ms) },
            Case(TrackType.AUDIO, AudioRole.MUSIC) { id, s, ms -> Fixtures.audio(id, s, ms) },
        )

    /** A project whose only row of the case's type is `row`, holding an item at 2..5 s. */
    private fun rowWith(case: Case): Pair<Project, TrackId> {
        val row = TrackId("row")
        val blank = mainOf("a", "b", "c")
        val others = blank.tracks.filter { it.type == case.type }.fold(blank) { acc, t -> acc.removeRow(t.id) }
        val p = others.addRow(case.type, case.role, id = row)
        return p.addToTrack(row, case.make("anchor", 2000, 3000), 2000, ids).assertValid() to row
    }

    @Test
    fun `before an item on another row the new clip ends where the item starts`() {
        for (case in cases) {
            val (p, row) = rowWith(case)
            val after = p.insertAt(row, ClipId("anchor"), Side.BEFORE, case.make("new", 0, 1000), ids).assertValid()
            val placed = after.requireClip(ClipId("new"))
            assertThat(after.trackOf(placed.id)!!.id).isEqualTo(row)
            assertThat(placed.startMs).isEqualTo(1000)
            assertThat(placed.endMs).isEqualTo(2000)
            // MAIN is untouched.
            assertThat(after.main).isEqualTo(p.main)
        }
    }

    @Test
    fun `after an item on another row the new clip starts where the item ends`() {
        for (case in cases) {
            val (p, row) = rowWith(case)
            val after = p.insertAt(row, ClipId("anchor"), Side.AFTER, case.make("new", 0, 1000), ids).assertValid()
            val placed = after.requireClip(ClipId("new"))
            assertThat(after.trackOf(placed.id)!!.id).isEqualTo(row)
            assertThat(placed.startMs).isEqualTo(5000)
        }
    }

    @Test
    fun `an insert that would overlap goes on a new row of the same type`() {
        for (case in cases) {
            val (start, row) = rowWith(case)
            val p = start.addToTrack(row, case.make("busy", 5500, 1000), 5500, ids).assertValid()
            val rowsBefore = p.tracks.count { it.type == case.type }
            val after = p.insertAt(row, ClipId("anchor"), Side.AFTER, case.make("new", 0, 1000), ids).assertValid()
            val landed = after.trackOf(ClipId("new"))!!
            assertThat(landed.id).isNotEqualTo(row)
            assertThat(landed.type).isEqualTo(case.type)
            assertThat(landed.audioRole).isEqualTo(case.role)
            assertThat(after.requireClip(ClipId("new")).startMs).isEqualTo(5000)
            assertThat(after.tracks.count { it.type == case.type }).isEqualTo(rowsBefore + 1)
            if (case.type.isVisual) assertThat(after.stacking).contains(landed.id) else assertThat(after.stacking).doesNotContain(landed.id)
        }
    }

    @Test
    fun `an insert that would overlap uses a free row of the same type first`() {
        for (case in cases) {
            val (start, row) = rowWith(case)
            val spare = TrackId("spare")
            val p = start.addToTrack(row, case.make("busy", 5500, 1000), 5500, ids).addRow(case.type, case.role, id = spare)
            val after = p.insertAt(row, ClipId("anchor"), Side.AFTER, case.make("new", 0, 1000), ids).assertValid()
            assertThat(after.trackOf(ClipId("new"))!!.id).isEqualTo(spare)
            assertThat(after.tracks).hasSize(p.tracks.size)
        }
    }

    @Test
    fun `before an item near the start never goes before 0`() {
        for (case in cases) {
            val row = TrackId("row")
            val p = mainOf("a").addRow(case.type, case.role, id = row).addToTrack(row, case.make("anchor", 500, 1000), 500, ids)
            val after = p.insertAt(row, ClipId("anchor"), Side.BEFORE, case.make("new", 0, 1000), ids).assertValid()
            val placed = after.requireClip(ClipId("new"))
            assertThat(placed.startMs).isEqualTo(0)
            // It would overlap the anchor where it is, so it moved to another row.
            assertThat(after.trackOf(placed.id)!!.id).isNotEqualTo(row)
        }
    }

    @Test
    fun `insert refuses a wrong anchor, a reused id or a clip of another kind`() {
        val (p, row) = rowWith(cases[1])
        assertThrows(IllegalArgumentException::class.java) { p.insertAt(row, ClipId("a"), Side.AFTER, Fixtures.text("n", 0), ids) }
        assertThrows(IllegalArgumentException::class.java) { p.insertAt(row, ClipId("anchor"), Side.AFTER, Fixtures.text("a", 0), ids) }
        assertThrows(IllegalArgumentException::class.java) { p.insertAt(row, ClipId("anchor"), Side.AFTER, Fixtures.overlay("o", 0), ids) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { p.insertAt(TrackId("nope"), ClipId("anchor"), Side.AFTER, Fixtures.text("n", 0), ids) }
        // A picture-in-picture row needs a placement.
        val (v, vRow) = rowWith(cases[0])
        assertThrows(IllegalArgumentException::class.java) { v.insertAt(vRow, ClipId("anchor"), Side.AFTER, Fixtures.photo("n"), ids) }
    }

    @Test
    fun `addToMain appends before the ending or at a content position`() {
        val p = Fixtures.presetProject()
        val two = p.addToMain(listOf(Fixtures.photo("a"), Fixtures.video("b")), ids = ids).assertValid()
        assertThat(two.mainIds().subList(1, 3)).containsExactly("a", "b").inOrder()
        assertThat(two.main.clips.last()).isInstanceOf(EndingClip::class.java)
        val first = two.addToMain(listOf(Fixtures.photo("c")), index = 0, ids = ids)
        assertThat(first.mainIds()[1]).isEqualTo("c")
        assertThrows(IllegalArgumentException::class.java) { two.addToMain(listOf(Fixtures.photo("d")), index = 5, ids = ids) }
        assertThrows(IllegalArgumentException::class.java) { two.addToMain(listOf(IntroClip(ClipId("i9"), 1000)), ids = ids) }
    }

    @Test
    fun `Add here on a busy row falls back to the highest free row`() {
        val (p, row) = rowWith(cases[1])
        val after = p.addToTrack(row, Fixtures.text("n", 0), 3000, ids).assertValid()
        assertThat(after.trackOf(ClipId("n"))!!.id).isNotEqualTo(row)
        assertThrows(IllegalArgumentException::class.java) { p.addToTrack(p.main.id, Fixtures.photo("m"), 0, ids) }
        assertThrows(IllegalArgumentException::class.java) { p.addToTrack(row, Fixtures.overlay("o", 0), 0, ids) }
        assertThrows(IllegalArgumentException::class.java) { p.addToTrack(row, Fixtures.text("anchor", 0), 9000, ids) }
    }
}
