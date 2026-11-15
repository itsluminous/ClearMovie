package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import org.junit.Assert.assertThrows
import org.junit.Test

/** Undo and redo of every operation, the 50-step cap, and refused edits. */
class UndoTest {
    private val ids = SequentialIds("u")
    private val pipRow = TrackId("piprow")

    /** Preset project with a photo, a video, a caption and a picture-in-picture row. */
    private fun start(): Project =
        Fixtures
            .presetProject(ids)
            .addToMain(listOf(Fixtures.photo("a", 3000), Fixtures.video("v", 10_000), Fixtures.photo("b", 3000)), ids = ids)
            .addText("hello", 7000, 2000, ids)
            .let { it.setCaptionStyle(it.textId(), CaptionStyleOverride(sizePx = 80)) }
            .addRow(TrackType.VISUAL, id = pipRow)
            .addToTrack(pipRow, Fixtures.photo("pp", 2000, PipPlacement.DEFAULT), 7000, ids)

    private fun Project.textId() = tracks.flatMap { it.clips }.first { it is TextClip }.id

    private fun Project.music() = byRole(PresetRole.FRAME_MUSIC)

    private val a = ClipId("a")
    private val v = ClipId("v")

    private val operations: List<Pair<String, Project.(IdSource) -> Project>> =
        listOf(
            "addToMain" to { addToMain(listOf(Fixtures.photo("n1")), ids = it) },
            "insertAt MAIN" to { insertAt(main.id, a, Side.BEFORE, Fixtures.photo("n2"), it) },
            "insertAt text" to { insertAt(trackOf(textId())!!.id, textId(), Side.AFTER, Fixtures.text("n3", 0), it) },
            "addText" to { addText("more", 0, 1000, it) },
            "addCaptionFor" to { addCaptionFor(a, "cap", it) },
            "addTimedClip" to { addTimedClip(Fixtures.audio("n4", 0), AudioRole.EFFECT, it) },
            "addToTrack" to { addToTrack(pipRow, Fixtures.photo("n5", 1000, PipPlacement.DEFAULT), 0, it) },
            "deleteClip" to { deleteClip(a) },
            "moveMainClip" to { moveMainClip(a, 2) },
            "moveClip" to { moveClip(textId(), 0, ids = it) },
            "addRow" to { addRow(TrackType.TEXT, id = it.trackId()) },
            "removeRow" to { removeRow(pipRow) },
            "moveRow" to { moveRow(main.id, 0) },
            "setTrackEnabled" to { setTrackEnabled(byRole(PresetRole.FRAME).id, false) },
            "setTrackMuted" to { setTrackMuted(music().id, true) },
            "setLowerWhenTalking" to { setLowerWhenTalking(music().id, false) },
            "bringToTop" to { bringToTop(pipRow) },
            "bringForward" to { bringForward(pipRow) },
            "sendBackward" to { sendBackward(pipRow) },
            "sendToBottom" to { sendToBottom(pipRow) },
            "trimVideo" to { trimVideo(v, 1000, 5000) },
            "trimAudio" to { trimAudio(music().clips.single().id, 0, 60_000) },
            "setSpeed" to { setSpeed(v, Speed.X2) },
            "setLoops" to { setLoops(v, 2) },
            "setPhotoDuration" to { setPhotoDuration(a, 5000) },
            "rotate" to { rotate(a) },
            "setFit" to { setFit(a, Fit.FIT_BARS) },
            "setPip" to { setPip(ClipId("pp"), PipPlacement(Corner.TOP_LEFT, 0.5)) },
            "setVolume" to { setVolume(v, -3.0) },
            "setAudioFades" to { setAudioFades(music().clips.single().id, 1000, 1000) },
            "setText" to { setText(textId(), "changed") },
            "setTextTiming" to { setTextTiming(textId(), 7000, 1500) },
            "setCaptionStyle" to { setCaptionStyle(textId(), CaptionStyleOverride(sizePx = 90)) },
            "resetCaptionStyle" to { resetCaptionStyle(textId()) },
            "splitClip" to { splitClip(v, 4000, it.clipId()) },
            "joinClips" to { splitClip(v, 4000, ClipId("v2")).joinClips(v, ClipId("v2")) },
            "setTransition" to { setTransition(a, Transition(TransitionKind.CROSSFADE, 800)) },
            "applyTransitionToAll" to { applyTransitionToAll(Transition(TransitionKind.ZOOM_IN, 500)) },
            "setAnimation" to { setAnimation(a, AnimSlot.DURING, Anim(AnimKind.KEN_BURNS_OUT)) },
            "rename" to { rename("Party") },
        )

    @Test
    fun `every operation is one undo step that undoes and redoes exactly`() {
        for ((name, op) in operations) {
            val editor = ProjectEditor(start(), ids)
            val before = editor.project
            assertWithMessage(name).that(editor.edit(op)).isEqualTo(EditResult.Applied)
            val after = editor.project
            assertWithMessage(name).that(after).isNotEqualTo(before)
            assertWithMessage(name).that(editor.undoDepth).isEqualTo(1)
            assertWithMessage(name).that(editor.undo()).isTrue()
            assertWithMessage(name).that(editor.project).isEqualTo(before)
            assertWithMessage(name).that(editor.redo()).isTrue()
            assertWithMessage(name).that(editor.project).isEqualTo(after)
        }
    }

    @Test
    fun `the 51st edit drops the oldest`() {
        val editor = ProjectEditor(start(), ids)
        val first = editor.project
        repeat(51) { i -> assertThat(editor.edit { rename("Name $i") }).isEqualTo(EditResult.Applied) }
        assertThat(editor.undoDepth).isEqualTo(50)
        repeat(50) { assertThat(editor.undo()).isTrue() }
        assertThat(editor.undo()).isFalse()
        // The oldest state reachable is after the first edit, not the start.
        assertThat(editor.project.name).isEqualTo("Name 0")
        assertThat(editor.project).isNotEqualTo(first)
    }

    @Test
    fun `a new edit clears redo, and undo and redo at the ends do nothing`() {
        val editor = ProjectEditor(start(), ids)
        assertThat(editor.canUndo).isFalse()
        assertThat(editor.undo()).isFalse()
        assertThat(editor.redo()).isFalse()
        editor.edit { rename("one") }
        editor.undo()
        assertThat(editor.canRedo).isTrue()
        editor.edit { rename("two") }
        assertThat(editor.canRedo).isFalse()
        assertThat(editor.redo()).isFalse()
    }

    @Test
    fun `a refused or empty edit adds no step and changes nothing`() {
        val editor = ProjectEditor(start(), ids)
        val before = editor.project
        assertThat(editor.edit { removeRow(main.id) }).isEqualTo(EditResult.Refused("This row can't be removed"))
        assertThat(editor.edit { this }).isEqualTo(EditResult.Unchanged)
        // A result that breaks an invariant is refused too.
        val broken = editor.edit { copy(stacking = emptyList()) }
        assertThat(broken).isInstanceOf(EditResult.Refused::class.java)
        assertThat((broken as EditResult.Refused).violations.map { it.rule }).contains(Violation.Rule.STACKING)
        assertThat(editor.project).isEqualTo(before)
        assertThat(editor.canUndo).isFalse()
    }

    @Test
    fun `the undo stack itself`() {
        val stack = UndoStack<Int>(capacity = 2)
        stack.push(1)
        stack.push(2)
        stack.push(3)
        assertThat(stack.undoDepth).isEqualTo(2)
        assertThat(stack.undo(4)).isEqualTo(3)
        assertThat(stack.redoDepth).isEqualTo(1)
        assertThat(stack.redo(3)).isEqualTo(4)
        stack.clear()
        assertThat(stack.canUndo).isFalse()
        assertThat(stack.canRedo).isFalse()
        assertThrows(IllegalArgumentException::class.java) { UndoStack<Int>(0) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor(start().copy(stacking = emptyList())) }
    }
}
