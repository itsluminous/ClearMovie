package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import org.junit.Assert.assertThrows
import org.junit.Test

/** A frame's own sound in the model (cm-frame-audio): its volume, and Lower for talk on its row. */
class FrameSoundModelTest {
    private val ids = SequentialIds("m")

    private val project: Project =
        Fixtures
            .blankWith(Fixtures.photo("p1", 6000), ids = ids)
            .addTimedClip(Fixtures.overlay("fr", 0, 3000), ids = ids)

    private val row: TrackId get() = project.tracks.single { it.type == TrackType.OVERLAY }.id

    @Test
    fun `a new frame starts at the preset frame music's level`() {
        assertThat((project.requireClip(ClipId("fr")) as OverlayClip).volumeDb).isEqualTo(FrameMusic.DEFAULT_GAIN_DB)
        project.assertValid()
    }

    @Test
    fun `a frame's volume is set like a sound's, within the same limits`() {
        val louder = project.setVolume(ClipId("fr"), 3.0).assertValid()
        assertThat((louder.requireClip(ClipId("fr")) as OverlayClip).volumeDb).isEqualTo(3.0)
        assertThrows(IllegalArgumentException::class.java) { project.setVolume(ClipId("fr"), 40.0) }
        val bad =
            project.copy(
                tracks =
                    project.tracks.map { t ->
                        t.copy(
                            clips =
                                t.clips.map {
                                    (it as? OverlayClip)?.copy(volumeDb = 99.0)
                                        ?: it
                                },
                        )
                    },
            )
        assertThat(bad.validate()).isNotEmpty()
    }

    @Test
    fun `a frame row takes Lower for talk, and its level, as a music row does`() {
        val set = project.setTalkLowering(row, lower = false, levelDb = -24.0).assertValid()
        assertThat(set.track(row)!!.lowerWhenTalking).isFalse()
        assertThat(set.track(row)!!.lowerToDb).isEqualTo(-24.0)
        assertThat(project.setLowerWhenTalking(row, false).track(row)!!.lowerWhenTalking).isFalse()
        // Other rows still refuse it, the preset's frame row too (its sound is the frame music row's).
        val presetFrame =
            Fixtures
                .presetProject()
                .tracks
                .single { it.presetRole == PresetRole.FRAME }
                .id
        assertThrows(IllegalArgumentException::class.java) { Fixtures.presetProject().setTalkLowering(presetFrame, true, -10.0) }
        val text = project.tracks.single { it.type == TrackType.TEXT }.id
        assertThrows(IllegalArgumentException::class.java) { project.setTalkLowering(text, true, -10.0) }
        val badLevel = project.copy(tracks = project.tracks.map { if (it.id == text) it.copy(lowerToDb = -10.0) else it })
        assertThat(badLevel.validate()).isNotEmpty()
    }

    @Test
    fun `a measured loudness is kept on every frame of that file that has none, within range`() {
        val two = project.addTimedClip(Fixtures.overlay("fr", 4000, 1000).copy(id = ClipId("fr2")), ids = ids)
        val kept = two.withFrameLoudness(Fixtures.media("fr"), -17.5).assertValid()
        assertThat(
            kept.tracks
                .flatMap { it.clips }
                .filterIsInstance<OverlayClip>()
                .map { it.soundLufs },
        ).containsExactly(-17.5, -17.5)
        // A value already kept is not replaced.
        assertThat(kept.withFrameLoudness(Fixtures.media("fr"), -30.0)).isEqualTo(kept)
        assertThrows(IllegalArgumentException::class.java) { project.withFrameLoudness(Fixtures.media("fr"), Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { project.withFrameLoudness(Fixtures.media("fr"), 50.0) }
        val bad =
            project.copy(
                tracks =
                    project.tracks.map { t ->
                        t.copy(
                            clips =
                                t.clips.map {
                                    (it as? OverlayClip)?.copy(soundLufs = 99.0)
                                        ?: it
                                },
                        )
                    },
            )
        assertThat(bad.validate()).isNotEmpty()
    }

    @Test
    fun `each change is one undo step`() {
        val editor = ProjectEditor(project, ids)
        assertThat(editor.edit { setVolume(ClipId("fr"), -1.0) }).isEqualTo(EditResult.Applied)
        assertThat(editor.edit { setTalkLowering(row, lower = true, levelDb = -30.0) }).isEqualTo(EditResult.Applied)
        assertThat(editor.undo()).isTrue()
        assertThat(editor.project.track(row)!!.lowerToDb).isNull()
        assertThat((editor.project.requireClip(ClipId("fr")) as OverlayClip).volumeDb).isEqualTo(-1.0)
        assertThat(editor.undo()).isTrue()
        assertThat(editor.project).isEqualTo(project)
    }
}
