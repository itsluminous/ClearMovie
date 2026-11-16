package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

/** The preset review's choices: use, skip or replace each part for one video. */
class PresetChoicesTest {
    private val preset = Fixtures.preset
    private val bound = Fixtures.presetMedia
    private val mine = MediaRef.Store("content://media/external/video/media/77")
    private val myVoice = MediaRef.Copied("voice.m4a")

    private fun build(choices: PresetChoices) =
        ProjectFactory.fromPreset(Fixtures.PROJECT_ID, preset, choices.applyTo(bound), SequentialIds("r"))

    @Test
    fun `the default choices give the preset's own project`() {
        assertThat(build(PresetChoices())).isEqualTo(ProjectFactory.fromPreset(Fixtures.PROJECT_ID, preset, bound, SequentialIds("r")))
    }

    @Test
    fun `a replaced intro plays the user's file with its own length`() {
        val p = build(PresetChoices(intro = PartChoice.Replace(mine, 4200, "party.mp4"))).assertValid()
        val intro = p.mainClips.first() as IntroClip
        assertThat(intro.media).isEqualTo(mine)
        assertThat(intro.durationMs).isEqualTo(4200)
        assertThat(p.introEndMs()).isEqualTo(4200)
    }

    @Test
    fun `a replaced frame changes the overlay and its music together, and keeps the preset's key`() {
        val p = build(PresetChoices(frame = PartChoice.Replace(mine, 60_000))).assertValid()
        val overlay = p.byRole(PresetRole.FRAME).clips.single() as OverlayClip
        val music = p.byRole(PresetRole.FRAME_MUSIC).clips.single() as AudioClip
        assertThat(overlay.media).isEqualTo(mine)
        assertThat(overlay.sourceDurationMs).isEqualTo(60_000)
        assertThat(overlay.key).isEqualTo(preset.frame!!.key)
        assertThat(music.media).isEqualTo(mine)
    }

    @Test
    fun `a replaced spoken ending changes the closing message and the held frame's length`() {
        val p = build(PresetChoices(ending = PartChoice.Replace(myVoice, 9000))).assertValid()
        val ending = p.mainClips.last() as EndingClip
        val speech = p.byRole(PresetRole.CLOSING_MESSAGE).clips.single() as AudioClip
        assertThat(ending.durationMs).isEqualTo(9000)
        assertThat(ending.media).isEqualTo(myVoice)
        assertThat(speech.media).isEqualTo(myVoice)
        assertThat(speech.durationMs).isEqualTo(9000)
    }

    @Test
    fun `skipped parts are left out, with their rows`() {
        val p = build(PresetChoices(intro = PartChoice.Skip, frame = PartChoice.Skip, ending = PartChoice.Skip)).assertValid()
        assertThat(p.mainClips).isEmpty()
        assertThat(p.tracks.map { it.presetRole }.filterNotNull()).isEmpty()
        assertThat(p.tracks.map { it.type }).containsExactly(TrackType.TEXT, TrackType.MAIN).inOrder()
    }

    @Test
    fun `new choices keep the photos and videos already on MAIN, the id and the name`() {
        val start = build(PresetChoices()).addToMain(listOf(Fixtures.photo("a"), Fixtures.video("b"))).rename("Party")
        val next = start.withPresetChoices(preset, bound, PresetChoices(intro = PartChoice.Skip), SequentialIds("n")).assertValid()
        assertThat(next.id).isEqualTo(start.id)
        assertThat(next.name).isEqualTo("Party")
        assertThat(next.mainClips.map { it.id.value })
            .containsExactly(
                "a",
                "b",
                next.mainClips
                    .last()
                    .id.value,
            ).inOrder()
        assertThat(next.mainClips.last()).isInstanceOf(EndingClip::class.java)
        // And back to the preset's intro: the choice can be undone.
        val back = next.withPresetChoices(preset, bound, PresetChoices(), SequentialIds("m")).assertValid()
        assertThat(back.mainClips.first()).isInstanceOf(IntroClip::class.java)
        assertThat(back.mainClips.filterIsInstance<PhotoClip>().map { it.id.value }).containsExactly("a")
    }

    @Test
    fun `a replacement is the user's file and has a usable length`() {
        assertThrows(IllegalArgumentException::class.java) { PartChoice.Replace(MediaRef.Preset(PresetSlot.INTRO), 1000) }
        assertThrows(IllegalArgumentException::class.java) { PartChoice.Replace(mine, 10) }
        assertThrows(IllegalArgumentException::class.java) { PartChoice.Replace(mine, 1000, "x".repeat(300)) }
    }

    @Test
    fun `a part can never play another part's preset file`() {
        assertThrows(IllegalArgumentException::class.java) { PresetMedia(introMs = 1000, intro = MediaRef.Preset(PresetSlot.FRAME)) }
        val p = build(PresetChoices())
        val wrong =
            p.copy(
                tracks =
                    p.tracks.map { t ->
                        if (t.type ==
                            TrackType.MAIN
                        ) {
                            t.copy(clips = t.clips.map { c -> (c as? IntroClip)?.copy(media = MediaRef.Preset(PresetSlot.FRAME)) ?: c })
                        } else {
                            t
                        }
                    },
            )
        assertThat(wrong.validate().map { it.rule }).contains(Violation.Rule.RANGE)
    }

    @Test
    fun `the choices round-trip through JSON, for the saved state`() {
        val json = Json { ignoreUnknownKeys = true }
        val choices =
            PresetChoices(
                intro = PartChoice.Replace(mine, 4200, "a.mp4"),
                frame = PartChoice.Skip,
                ending = PartChoice.Replace(myVoice, 900),
            )
        val text = json.encodeToString(PresetChoices.serializer(), choices)
        assertThat(json.decodeFromString(PresetChoices.serializer(), text)).isEqualTo(choices)
        assertThat(choices[PresetSlot.FRAME]).isEqualTo(PartChoice.Skip)
        assertThat(choices.with(PresetSlot.FRAME, PartChoice.UsePreset).frame).isEqualTo(PartChoice.UsePreset)
    }

    @Test
    fun `a project written before intros carried a file reads as the preset's own intro`() {
        val codec = ProjectCodec()
        val text =
            codec
                .encode(
                    build(PresetChoices()),
                ).replace(
                    Regex(""""animations": \{\},\s*"media":\s*\{\s*"type":\s*"preset",\s*"slot":\s*"(intro|ending)"\s*\}"""),
                    "\"animations\": {}",
                )
        assertThat(text).doesNotContain("\"slot\": \"intro\"")
        assertThat(text).contains("\"slot\": \"ending\"") // the closing message keeps its file
        val decoded = (codec.decode(text) as ProjectDecodeResult.Ok).project
        assertThat((decoded.mainClips.first() as IntroClip).media).isEqualTo(MediaRef.Preset(PresetSlot.INTRO))
        assertThat((decoded.mainClips.last() as EndingClip).media).isEqualTo(MediaRef.Preset(PresetSlot.ENDING))
    }

    @Test
    fun `a replaced frame keys out its own measured green`() {
        val green = ArgbColor(0xFF10C030)
        val start = build(PresetChoices())
        val p =
            start
                .withPresetChoices(
                    preset,
                    bound,
                    PresetChoices(frame = PartChoice.Replace(mine, 60_000, keyColor = green)),
                    SequentialIds("k"),
                ).assertValid()
        val overlay = p.byRole(PresetRole.FRAME).clips.single() as OverlayClip
        assertThat(overlay.key!!.color).isEqualTo(green)
        assertThat(overlay.key!!.tolerance).isEqualTo(preset.frame!!.key.tolerance)
        // Back to the preset's frame: its own key again.
        val back = p.withPresetChoices(preset, bound, PresetChoices(), SequentialIds("j"))
        assertThat((back.byRole(PresetRole.FRAME).clips.single() as OverlayClip).key).isEqualTo(preset.frame!!.key)
    }

    @Test
    fun `a project saved before this change still reads, with the preset's own intro and ending`() {
        val text = requireNotNull(javaClass.getResource("/fixtures/project/rich-v1-before-flow.json")).readText()
        val decoded = ProjectCodec().decode(text) as ProjectDecodeResult.Ok
        assertThat((decoded.project.mainClips.first() as IntroClip).media).isEqualTo(MediaRef.Preset(PresetSlot.INTRO))
        assertThat((decoded.project.mainClips.last() as EndingClip).media).isEqualTo(MediaRef.Preset(PresetSlot.ENDING))
        assertThat(decoded.project).isEqualTo(Fixtures.richProject())
    }
}
