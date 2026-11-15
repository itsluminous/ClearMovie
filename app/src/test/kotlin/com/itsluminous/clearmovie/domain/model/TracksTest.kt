package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import com.itsluminous.clearmovie.domain.model.Fixtures.trackOfType
import org.junit.Assert.assertThrows
import org.junit.Test

/** N rows of each type, "Add text", auto-removal, and rows that can't be removed (sections 6 and 6.1). */
class TracksTest {
    private val ids = SequentialIds("n")

    /** MAIN with one 10 s photo, two Text rows and two Music rows. */
    private fun twoOfEach(): Project {
        var p = Fixtures.blankWith(Fixtures.photo("a", 10_000), ids = ids)
        p = p.addRow(TrackType.TEXT, id = TrackId("text2"))
        p = p.addRow(TrackType.AUDIO, AudioRole.MUSIC, id = TrackId("music1"))
        p = p.addRow(TrackType.AUDIO, AudioRole.MUSIC, id = TrackId("music2"))
        return p.assertValid()
    }

    @Test
    fun `a project holds two Text rows and two Audio rows`() {
        val p = twoOfEach()
        assertThat(p.tracks.count { it.type == TrackType.TEXT }).isEqualTo(2)
        assertThat(p.tracks.count { it.type == TrackType.AUDIO }).isEqualTo(2)
        // The new Text row is on screen directly above the existing one, and on top of the stack.
        assertThat(p.tracks.first().id).isEqualTo(TrackId("text2"))
        assertThat(p.stacking.last()).isEqualTo(TrackId("text2"))
        // Sound rows are mixed, never stacked.
        assertThat(p.stacking).containsNoneOf(TrackId("music1"), TrackId("music2"))
    }

    @Test
    fun `Add text uses the highest free text row in stacking order`() {
        var p = twoOfEach()
        val top = p.stackingTopFirst.first()
        p = p.addText("one", startMs = 0, durationMs = 2000, ids = ids)
        assertThat(p.requireTrack(top).clips).hasSize(1)
        // Same time: the top row is busy, so the next text row down takes it.
        p = p.addText("two", startMs = 1000, durationMs = 2000, ids = ids)
        val second = p.stackingTopFirst.filter { p.requireTrack(it).type == TrackType.TEXT }[1]
        assertThat(p.requireTrack(second).clips.map { (it as TextClip).text }).containsExactly("two")
        // Later and free again: back on the top row.
        p = p.addText("three", startMs = 5000, durationMs = 1000, ids = ids)
        assertThat(p.requireTrack(top).clips).hasSize(2)
        p.assertValid()
    }

    @Test
    fun `Add text makes a new row on top when every text row is busy`() {
        var p = twoOfEach()
        p = p.addText("one", 0, 3000, ids).addText("two", 0, 3000, ids)
        val before = p.tracks.count { it.type == TrackType.TEXT }
        p = p.addText("three", 500, 1000, ids).assertValid()
        val texts = p.tracks.filter { it.type == TrackType.TEXT }
        assertThat(texts).hasSize(before + 1)
        val created = texts.single { t -> t.clips.any { (it as TextClip).text == "three" } }
        assertThat(p.tracks.first()).isEqualTo(created)
        assertThat(p.stacking.last()).isEqualTo(created.id)
    }

    @Test
    fun `Caption on a photo covers exactly that photo`() {
        var p = Fixtures.blankWith(Fixtures.photo("a", 3000), Fixtures.photo("b", 5000), ids = ids)
        p = p.addCaptionFor(ClipId("b"), "hello", ids).assertValid()
        val caption =
            p.tracks
                .flatMap { it.clips }
                .filterIsInstance<TextClip>()
                .single()
        assertThat(caption.startMs).isEqualTo(3000)
        assertThat(caption.durationMs).isEqualTo(5000)
        assertThrows(IllegalArgumentException::class.java) { p.addCaptionFor(ClipId("nope"), "x", ids) }
    }

    @Test
    fun `sound goes on a free row of its own role`() {
        var p = twoOfEach()
        // music2 was added last, so it sits above music1 on screen: sound rows are tried top first.
        p = p.addTimedClip(Fixtures.audio("m1", 0), AudioRole.MUSIC, ids)
        assertThat(p.requireTrack(TrackId("music2")).clips.map { it.id }).containsExactly(ClipId("m1"))
        p = p.addTimedClip(Fixtures.audio("m2", 0), AudioRole.MUSIC, ids)
        assertThat(p.requireTrack(TrackId("music1")).clips.map { it.id }).containsExactly(ClipId("m2"))
        // No free voice row: a new one is made, with the voice role.
        p = p.addTimedClip(Fixtures.audio("v1", 0), AudioRole.VOICE, ids).assertValid()
        assertThat(p.trackOf(ClipId("v1"))!!.audioRole).isEqualTo(AudioRole.VOICE)
        assertThrows(IllegalArgumentException::class.java) { p.addTimedClip(Fixtures.audio("v2", 0), null, ids) }
        assertThrows(IllegalArgumentException::class.java) { p.addTimedClip(Fixtures.text("t9", 0), AudioRole.MUSIC, ids) }
    }

    @Test
    fun `deleting the last clip removes the row, and undo brings it back`() {
        val editor = ProjectEditor(twoOfEach(), ids)
        editor.edit { addText("only", 0, 2000, it) }
        val row =
            editor.project.trackOf(
                editor.project.tracks
                    .flatMap { it.clips }
                    .single { it is TextClip }
                    .id,
            )!!
        val clipId = row.clips.single().id
        val rowCount = editor.project.tracks.size

        assertThat(editor.edit { deleteClip(clipId) }).isEqualTo(EditResult.Applied)
        assertThat(editor.project.track(row.id)).isNull()
        assertThat(editor.project.stacking).doesNotContain(row.id)
        assertThat(editor.project.tracks).hasSize(rowCount - 1)

        assertThat(editor.undo()).isTrue()
        assertThat(editor.project.track(row.id)).isEqualTo(row)
        assertThat(editor.project.stacking).contains(row.id)
    }

    @Test
    fun `an empty row made with Add row stays until removed`() {
        val p = twoOfEach().addText("x", 0, 1000, ids)
        val other = p.tracks.first { it.type == TrackType.TEXT && it.clips.isEmpty() }
        assertThat(p.track(other.id)).isNotNull()
        val removed = p.removeRow(other.id).assertValid()
        assertThat(removed.track(other.id)).isNull()
        assertThat(removed.stacking).doesNotContain(other.id)
    }

    @Test
    fun `MAIN and the preset frame row are never removed`() {
        val p = Fixtures.presetProject().assertValid()
        val frame = p.byRole(PresetRole.FRAME)
        assertThrows(IllegalArgumentException::class.java) { p.removeRow(p.main.id) }
        assertThrows(IllegalArgumentException::class.java) { p.removeRow(frame.id) }

        // Deleting the frame switches the row off and keeps the frame, so On brings it back.
        val noFrame = p.deleteClip(frame.clips.single().id).assertValid()
        val kept = noFrame.requireTrack(frame.id)
        assertThat(kept.clips).isEqualTo(frame.clips)
        assertThat(kept.enabled).isFalse()
        assertThat(noFrame.stacking).contains(frame.id)
        assertThat(noFrame.setTrackEnabled(frame.id, true)).isEqualTo(p)
        // The frame never leaves its row, so the row can't be left empty.
        val other = TrackId("other")
        assertThrows(IllegalArgumentException::class.java) {
            p.addRow(TrackType.OVERLAY, id = other).moveClip(frame.clips.single().id, 0, other, ids)
        }
        // Moving it in time while it is switched off keeps it on its row, still off.
        val shifted = noFrame.moveClip(frame.clips.single().id, 100, ids = ids).assertValid()
        assertThat(shifted.trackOf(frame.clips.single().id)!!.id).isEqualTo(frame.id)
        assertThat(shifted.requireTrack(frame.id).enabled).isFalse()

        // Deleting every MAIN item keeps MAIN.
        val emptyMain =
            p.main.clips
                .fold(p) { acc, c -> acc.deleteClip(c.id) }
                .assertValid()
        assertThat(emptyMain.track(p.main.id)).isNotNull()
        assertThat(emptyMain.main.clips).isEmpty()
    }

    @Test
    fun `the frame row can be switched off and on, and sound rows muted`() {
        val p = Fixtures.presetProject()
        val frame = p.byRole(PresetRole.FRAME)
        assertThat(p.setTrackEnabled(frame.id, false).requireTrack(frame.id).enabled).isFalse()
        val music = p.byRole(PresetRole.FRAME_MUSIC)
        assertThat(p.setTrackMuted(music.id, true).requireTrack(music.id).muted).isTrue()
        assertThat(p.setLowerWhenTalking(music.id, false).requireTrack(music.id).lowerWhenTalking).isFalse()
        val voice = p.byRole(PresetRole.CLOSING_MESSAGE)
        assertThrows(IllegalArgumentException::class.java) { p.setLowerWhenTalking(voice.id, false) }
        assertThrows(IllegalArgumentException::class.java) { p.setTrackMuted(TrackId("nope"), true) }
    }

    @Test
    fun `addRow refuses MAIN, a sound row without a role and a reused id`() {
        val p = twoOfEach()
        assertThrows(IllegalArgumentException::class.java) { p.addRow(TrackType.MAIN) }
        assertThrows(IllegalArgumentException::class.java) { p.addRow(TrackType.AUDIO) }
        assertThrows(IllegalArgumentException::class.java) { p.addRow(TrackType.TEXT, AudioRole.MUSIC) }
        assertThrows(IllegalArgumentException::class.java) { p.addRow(TrackType.TEXT, id = TrackId("text2")) }
    }

    @Test
    fun `new rows go directly above the rows of their type on screen`() {
        var p = Fixtures.presetProject()
        // Default screen order: Text, Frame, MAIN, Music, Closing message.
        assertThat(p.tracks.map { it.type })
            .containsExactly(
                TrackType.TEXT,
                TrackType.OVERLAY,
                TrackType.MAIN,
                TrackType.AUDIO,
                TrackType.AUDIO,
            ).inOrder()
        p = p.addRow(TrackType.VISUAL, id = TrackId("pip"))
        assertThat(p.tracks.indexOfFirst { it.id == TrackId("pip") }).isEqualTo(p.tracks.indexOf(p.main) - 1)
        p = p.addRow(TrackType.OVERLAY, id = TrackId("ov"))
        assertThat(p.tracks.indexOfFirst { it.id == TrackId("ov") }).isEqualTo(1)
        p = p.addRow(TrackType.AUDIO, AudioRole.EFFECT, id = TrackId("fx"))
        assertThat(p.tracks.indexOfFirst { it.id == TrackId("fx") }).isEqualTo(p.tracks.indexOf(p.main) + 1)
        p.assertValid()

        // Without any sound row yet, the first one goes directly below MAIN.
        val blank = ProjectFactory.blank(Fixtures.PROJECT_ID, ids).addRow(TrackType.AUDIO, AudioRole.VOICE, id = TrackId("v"))
        assertThat(blank.tracks.last().id).isEqualTo(TrackId("v"))
        // And the first VISUAL row goes directly above MAIN.
        val pip = ProjectFactory.blank(Fixtures.PROJECT_ID, ids).addRow(TrackType.VISUAL, id = TrackId("p"))
        assertThat(pip.tracks.map { it.type }).containsExactly(TrackType.TEXT, TrackType.VISUAL, TrackType.MAIN).inOrder()
    }
}
