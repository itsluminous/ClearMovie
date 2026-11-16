package com.itsluminous.clearmovie.ui.editor

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EditResult
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.Fixtures.mainIds
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.deleteClip
import com.itsluminous.clearmovie.domain.model.moveMainClip
import com.itsluminous.clearmovie.domain.model.setPhotoDuration
import org.junit.Test

/** Undo and redo in the editor, with changes that arrive from elsewhere. */
class EditSessionTest {
    private val start = Fixtures.blankWith(Fixtures.photo("a"), Fixtures.photo("b"), Fixtures.photo("c"))

    @Test
    fun `each edit is one step, undone and redone in order`() {
        val s = EditSession().apply { adopt(start) }
        assertThat(s.edit { moveMainClip(ClipId("a"), 2) }).isEqualTo(EditResult.Applied)
        assertThat(s.edit { deleteClip(ClipId("b")) }).isEqualTo(EditResult.Applied)
        assertThat(s.current!!.mainIds()).containsExactly("c", "a").inOrder()
        assertThat(s.undo()!!.mainIds()).containsExactly("b", "c", "a").inOrder()
        assertThat(s.undo()!!.mainIds()).containsExactly("a", "b", "c").inOrder()
        assertThat(s.undo()).isNull()
        assertThat(s.redo()!!.mainIds()).containsExactly("b", "c", "a").inOrder()
        assertThat(s.canRedo).isTrue()
    }

    @Test
    fun `a refused or empty edit adds no step and changes nothing`() {
        val s = EditSession().apply { adopt(start) }
        assertThat(s.edit { setPhotoDuration(ClipId("nope"), 1000) }).isInstanceOf(EditResult.Refused::class.java)
        assertThat(s.edit { this }).isEqualTo(EditResult.Unchanged)
        assertThat(s.canUndo).isFalse()
        assertThat(s.current).isEqualTo(start)
    }

    @Test
    fun `our own write coming back adds no step, a change from elsewhere does`() {
        val s = EditSession().apply { adopt(start) }
        s.edit { moveMainClip(ClipId("a"), 1) }
        val written = s.current!!
        s.adopt(written)
        s.undo()
        s.redo()
        assertThat(s.canUndo).isTrue()
        // Another screen added a photo: Undo takes it back out rather than losing it silently.
        val elsewhere = written.addToMain(listOf(Fixtures.photo("d")))
        s.adopt(elsewhere)
        assertThat(s.undo()).isEqualTo(written)
    }

    @Test
    fun `a different project starts a fresh history`() {
        val s = EditSession().apply { adopt(start) }
        s.edit { deleteClip(ClipId("a")) }
        s.adopt(start.copy(id = ProjectId("other")))
        assertThat(s.canUndo).isFalse()
    }

    @Test
    fun `the history keeps at most 50 steps`() {
        val s = EditSession().apply { adopt(start) }
        repeat(60) { i -> s.edit { setPhotoDuration(ClipId("a"), 1000L + i * 10) } }
        var n = 0
        while (s.undo() != null) n++
        assertThat(n).isEqualTo(50)
    }
}
