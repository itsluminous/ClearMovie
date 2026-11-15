package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import org.junit.Assert.assertThrows
import org.junit.Test

/** Screen order and stacking order are independent (section 6.2). */
class StackingTest {
    private val ids = SequentialIds("s")

    /** Preset project plus a PiP row and a second overlay row. */
    private fun project(): Project =
        Fixtures
            .presetProject()
            .addRow(TrackType.VISUAL, id = TrackId("pip"))
            .addRow(TrackType.OVERLAY, id = TrackId("ov"))
            .assertValid()

    @Test
    fun `the default stacking is text over frame over photos and videos`() {
        val p = Fixtures.presetProject()
        val text = p.tracks.first { it.type == TrackType.TEXT }.id
        val frame = p.byRole(PresetRole.FRAME).id
        assertThat(p.stacking).containsExactly(p.main.id, frame, text).inOrder()
        assertThat(p.stackingTopFirst).containsExactly(text, frame, p.main.id).inOrder()
    }

    @Test
    fun `each type's new row goes to its default stacking position`() {
        val p = project()
        val text = p.tracks.first { it.type == TrackType.TEXT }.id
        val frame = p.byRole(PresetRole.FRAME).id
        // VISUAL just above MAIN; OVERLAY just under the lowest TEXT row; TEXT on top.
        assertThat(p.stacking).containsExactly(p.main.id, TrackId("pip"), frame, TrackId("ov"), text).inOrder()
        val withText = p.addRow(TrackType.TEXT, id = TrackId("t2"))
        assertThat(withText.stacking.last()).isEqualTo(TrackId("t2"))
        // With no text row at all, an overlay goes on top.
        val blank = ProjectFactory.blank(Fixtures.PROJECT_ID, ids)
        val noText = blank.removeRow(blank.tracks.first { it.type == TrackType.TEXT }.id).addRow(TrackType.OVERLAY, id = TrackId("o"))
        assertThat(noText.stacking.last()).isEqualTo(TrackId("o"))
    }

    @Test
    fun `moveRow changes screen order and leaves stacking unchanged`() {
        val p = project()
        val moved = p.moveRow(p.main.id, 0).assertValid()
        assertThat(moved.tracks.first().id).isEqualTo(p.main.id)
        assertThat(moved.stacking).isEqualTo(p.stacking)
        assertThat(moved.tracks.map { it.id }.toSet()).isEqualTo(p.tracks.map { it.id }.toSet())

        val last = p.moveRow(p.tracks.first().id, p.tracks.lastIndex)
        assertThat(last.tracks.last().id).isEqualTo(p.tracks.first().id)
        assertThat(last.stacking).isEqualTo(p.stacking)
        assertThat(p.moveRow(p.main.id, p.tracks.indexOf(p.main))).isSameInstanceAs(p)
        assertThrows(IllegalArgumentException::class.java) { p.moveRow(p.main.id, 99) }
        assertThrows(IllegalArgumentException::class.java) { p.moveRow(TrackId("nope"), 0) }
    }

    @Test
    fun `each stacking operation changes only stacking`() {
        val p = project()
        val pip = TrackId("pip")
        val ops: List<(Project) -> Project> =
            listOf({ it.bringToTop(pip) }, { it.bringForward(pip) }, { it.sendBackward(pip) }, { it.sendToBottom(pip) })
        for (op in ops) {
            val after = op(p).assertValid()
            assertThat(after.tracks).isEqualTo(p.tracks)
            assertThat(after.copy(stacking = p.stacking)).isEqualTo(p)
        }
    }

    @Test
    fun `stacking operations move the row to the expected place`() {
        val p = project()
        val pip = TrackId("pip")
        assertThat(p.bringToTop(pip).stacking.last()).isEqualTo(pip)
        assertThat(p.sendToBottom(pip).stacking.first()).isEqualTo(pip)
        assertThat(p.bringForward(pip).stackIndex(pip)).isEqualTo(p.stackIndex(pip) + 1)
        assertThat(p.sendBackward(pip).stackIndex(pip)).isEqualTo(p.stackIndex(pip) - 1)
        // Already at the edge: nothing changes.
        val top = p.bringToTop(pip)
        assertThat(top.bringForward(pip)).isSameInstanceAs(top)
        val bottom = p.sendToBottom(pip)
        assertThat(bottom.sendBackward(pip)).isSameInstanceAs(bottom)
    }

    @Test
    fun `a row sent below MAIN is reported as hidden behind it`() {
        val p = project()
        val pip = TrackId("pip")
        assertThat(p.isBelowMain(pip)).isFalse()
        val below = p.sendToBottom(pip)
        assertThat(below.isBelowMain(pip)).isTrue()
        assertThat(below.isBelowMain(below.main.id)).isFalse()
        val music = p.byRole(PresetRole.FRAME_MUSIC).id
        assertThat(p.isBelowMain(music)).isFalse()
    }

    @Test
    fun `sound rows have no stacking position`() {
        val p = project()
        val music = p.byRole(PresetRole.FRAME_MUSIC).id
        assertThat(p.stackIndex(music)).isEqualTo(-1)
        assertThrows(IllegalArgumentException::class.java) { p.bringToTop(music) }
    }
}
