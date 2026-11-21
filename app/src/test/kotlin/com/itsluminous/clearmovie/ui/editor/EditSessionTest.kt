package com.itsluminous.clearmovie.ui.editor

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EditResult
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.Fixtures.mainIds
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.chooseShape
import com.itsluminous.clearmovie.domain.model.deleteClip
import com.itsluminous.clearmovie.domain.model.moveMainClip
import com.itsluminous.clearmovie.domain.model.setPhotoDuration
import com.itsluminous.clearmovie.domain.model.setShape
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

    @Test
    fun `edits with the same key make one step, and anything else ends it`() {
        val s = EditSession().apply { adopt(start) }
        s.edit("k") { setPhotoDuration(ClipId("a"), 1000) }
        s.edit("k") { setPhotoDuration(ClipId("a"), 2000) }
        s.edit("k") { setPhotoDuration(ClipId("b"), 1500) }
        assertThat(s.undo()).isEqualTo(start)
        assertThat(s.canUndo).isFalse()
        // Redo brings the whole run back.
        assertThat(s.redo()!!.requireClip(ClipId("b")).durationMs).isEqualTo(1500)
        // An undo, an edit without the key, or endMerge ends a run.
        s.edit("k") { setPhotoDuration(ClipId("c"), 1000) }
        s.endMerge()
        s.edit("k") { setPhotoDuration(ClipId("c"), 2000) }
        assertThat(s.undo()!!.requireClip(ClipId("c")).durationMs).isEqualTo(1000)
        s.edit("j") { setPhotoDuration(ClipId("c"), 2500) }
        s.edit { setPhotoDuration(ClipId("c"), 2600) }
        s.edit("j") { setPhotoDuration(ClipId("c"), 2700) }
        assertThat(s.undo()!!.requireClip(ClipId("c")).durationMs).isEqualTo(2600)
    }

    @Test
    fun `reverting a run takes back all of it and leaves nothing to redo`() {
        val s = EditSession().apply { adopt(start) }
        s.edit { setPhotoDuration(ClipId("a"), 1000) }
        val before = s.current
        s.edit("caption") { setPhotoDuration(ClipId("b"), 1000) }
        s.edit("caption") { setPhotoDuration(ClipId("c"), 1000) }
        assertThat(s.revertMerge("other")).isNull()
        assertThat(s.revertMerge("caption")).isEqualTo(before)
        assertThat(s.current).isEqualTo(before)
        assertThat(s.canRedo).isFalse()
        // Only once: the run is over.
        assertThat(s.revertMerge("caption")).isNull()
        // A run ended by an undo can't be reverted any more (its step is gone).
        s.edit("caption") { setPhotoDuration(ClipId("b"), 1200) }
        s.undo()
        assertThat(s.revertMerge("caption")).isNull()
    }

    @Test
    fun `settling a change makes no undo step and leaves earlier steps as they were`() {
        // cm-aspect-ratio: the first-time shape choice must not be undone into asking again.
        val session = EditSession()
        val start = ProjectFactory.blank(Fixtures.PROJECT_ID, askShape = true)
        session.adopt(start)
        val settled = session.settle { chooseShape(CanvasShape.PORTRAIT) }!!
        assertThat(settled.shape).isEqualTo(CanvasShape.PORTRAIT)
        assertThat(settled.askShape).isFalse()
        assertThat(session.canUndo).isFalse()
        // Our own write coming back adds no step either.
        session.adopt(settled)
        assertThat(session.canUndo).isFalse()
        // A later shape change is an ordinary step, undone to the settled shape.
        assertThat(session.edit { setShape(CanvasShape.SQUARE) }).isEqualTo(EditResult.Applied)
        assertThat(session.undo()!!.shape).isEqualTo(CanvasShape.PORTRAIT)
        // Nothing to settle, or a change that makes nothing new, is refused.
        assertThat(session.settle { this }).isNull()
    }
}
