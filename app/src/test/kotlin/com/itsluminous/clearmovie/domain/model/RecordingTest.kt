package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import org.junit.Assert.assertThrows
import org.junit.Test

/** A voice recording's file name, its clip, and where it goes on the timeline (cm-record-voice). */
class RecordingTest {
    private val ids = SequentialIds("r")
    private val file = MediaRef.Copied(VoiceRecordingFiles.nameFor("abcd1234efgh5678"))

    /** MAIN with one 20 s photo and two voice rows. */
    private fun twoVoiceRows(): Project =
        Fixtures
            .blankWith(Fixtures.photo("a", 20_000), ids = ids)
            .addRow(TrackType.AUDIO, AudioRole.VOICE, id = TrackId("voice1"))
            .addRow(TrackType.AUDIO, AudioRole.VOICE, id = TrackId("voice2"))
            .assertValid()

    private fun recording(
        id: String,
        startMs: Long,
        ms: Long,
    ) = requireNotNull(recordingClip(ClipId(id), file, startMs, ms, videoEndMs = 20_000))

    @Test
    fun `recording files are named voice-token-m4a and nothing else counts as one`() {
        assertThat(file.relPath).isEqualTo("voice-abcd1234efgh5678.m4a")
        assertThat(VoiceRecordingFiles.isRecording(file.relPath)).isTrue()
        for (other in listOf(
            "voice-ABC12345.m4a",
            "voice-abc.m4a",
            "x-voice-abcd1234.m4a",
            "voice-abcd1234.m4a.part",
            "a/voice-abcd1234.m4a",
            "song.m4a",
        )) {
            assertThat(VoiceRecordingFiles.isRecording(other)).isFalse()
        }
        assertThrows(IllegalArgumentException::class.java) { VoiceRecordingFiles.nameFor("../x") }
        assertThrows(IllegalArgumentException::class.java) { VoiceRecordingFiles.nameFor("short") }
    }

    @Test
    fun `the clip starts at the playhead and is cut where the video ends`() {
        val clip = requireNotNull(recordingClip(ClipId("c"), file, startMs = 15_000, recordedMs = 7_250, videoEndMs = 20_000))
        assertThat(clip.startMs).isEqualTo(15_000)
        assertThat(clip.durationMs).isEqualTo(5_000)
        assertThat(clip.sourceDurationMs).isEqualTo(7_250)
        assertThat(clip.trimStartMs).isEqualTo(0)
        assertThat(clip.trimEndMs).isEqualTo(5_000)
        val whole = requireNotNull(recordingClip(ClipId("c"), file, startMs = 1_000, recordedMs = 3_000, videoEndMs = 20_000))
        assertThat(whole.durationMs).isEqualTo(3_000)
        assertThat(whole.trimEndMs).isEqualTo(whole.sourceDurationMs)
    }

    @Test
    fun `a recording too short to play is not made`() {
        assertThat(recordingClip(ClipId("c"), file, 0, Limits.MIN_CLIP_MS - 1, 20_000)).isNull()
        assertThat(recordingClip(ClipId("c"), file, 19_950, 5_000, 20_000)).isNull()
        assertThat(recordingClip(ClipId("c"), file, 0, 0, 20_000)).isNull()
        assertThat(recordingClip(ClipId("c"), file, -1, 5_000, 20_000)).isNull()
        assertThat(recordingClip(ClipId("c"), file, 0, Limits.MIN_CLIP_MS, 20_000)).isNotNull()
    }

    @Test
    fun `it goes on the voice row it was recorded from, at the playhead`() {
        val p = twoVoiceRows().addRecording(recording("rec", 4_000, 3_000), TrackId("voice2"), ids).assertValid()
        val clip = p.requireTrack(TrackId("voice2")).clips.single()
        assertThat(clip.id).isEqualTo(ClipId("rec"))
        assertThat(clip.startMs).isEqualTo(4_000)
        assertThat(p.requireTrack(TrackId("voice1")).clips).isEmpty()
    }

    @Test
    fun `a busy row sends it to the next free voice row, never to another time`() {
        var p = twoVoiceRows().addRecording(recording("first", 2_000, 4_000), TrackId("voice2"), ids)
        // Overlaps the first one: same start time, other voice row.
        p = p.addRecording(recording("second", 5_000, 3_000), TrackId("voice2"), ids).assertValid()
        assertThat(
            p
                .requireTrack(TrackId("voice1"))
                .clips
                .single()
                .id,
        ).isEqualTo(ClipId("second"))
        assertThat(p.clip(ClipId("second"))!!.startMs).isEqualTo(5_000)
        // Both rows busy then: a new voice row.
        p = p.addRecording(recording("third", 5_500, 1_000), TrackId("voice2"), ids).assertValid()
        val home = requireNotNull(p.trackOf(ClipId("third")))
        assertThat(home.id).isNotIn(listOf(TrackId("voice1"), TrackId("voice2")))
        assertThat(home.audioRole).isEqualTo(AudioRole.VOICE)
        assertThat(p.clip(ClipId("third"))!!.startMs).isEqualTo(5_500)
        // A free time on the asked-for row: back on it.
        p = p.addRecording(recording("fourth", 12_000, 1_000), TrackId("voice2"), ids).assertValid()
        assertThat(p.trackOf(ClipId("fourth"))!!.id).isEqualTo(TrackId("voice2"))
    }

    @Test
    fun `a row that is not a free voice row is never used`() {
        val withMusic = twoVoiceRows().addRow(TrackType.AUDIO, AudioRole.MUSIC, id = TrackId("music"))
        val p = withMusic.addRecording(recording("rec", 1_000, 1_000), TrackId("music"), ids).assertValid()
        assertThat(p.trackOf(ClipId("rec"))!!.audioRole).isEqualTo(AudioRole.VOICE)
        val gone = twoVoiceRows().addRecording(recording("rec", 1_000, 1_000), TrackId("nope"), ids).assertValid()
        assertThat(gone.trackOf(ClipId("rec"))!!.audioRole).isEqualTo(AudioRole.VOICE)
        // The preset's closing message row holds only its message.
        val preset = Fixtures.presetProject(ids).addToMain(listOf(Fixtures.photo("b", 5_000)), ids = ids)
        val closing = preset.byRole(PresetRole.CLOSING_MESSAGE).id
        val placed = preset.addRecording(recording("rec", 1_000, 1_000), closing, ids).assertValid()
        assertThat(placed.trackOf(ClipId("rec"))!!.id).isNotEqualTo(closing)
    }

    @Test
    fun `placing it is one undo step, and undo takes it away`() {
        val editor = ProjectEditor(twoVoiceRows())
        assertThat(editor.edit { addRecording(recording("rec", 4_000, 3_000), TrackId("voice1"), it) }).isEqualTo(EditResult.Applied)
        assertThat(editor.project.clip(ClipId("rec"))).isNotNull()
        assertThat(editor.undo()).isTrue()
        assertThat(editor.project.clip(ClipId("rec"))).isNull()
        assertThat(editor.canUndo).isFalse()
    }

    @Test
    fun `the recordings a project plays are the copied voice files on its audio clips`() {
        val other = MediaRef.Copied(VoiceRecordingFiles.nameFor("zzzz9999zzzz9999"))
        var p = twoVoiceRows().addRecording(recording("rec", 1_000, 1_000), TrackId("voice1"), ids)
        p = p.addRecording(AudioClip(ClipId("song"), MediaRef.Copied("song-1.m4a"), 3_000, 1_000, 1_000), TrackId("voice1"), ids)
        p = p.addRecording(AudioClip(ClipId("other"), other, 5_000, 1_000, 1_000), TrackId("voice1"), ids)
        assertThat(p.recordingsInUse()).containsExactly(file.relPath, other.relPath)
        assertThat(p.deleteClip(ClipId("other")).recordingsInUse()).containsExactly(file.relPath)
    }
}
