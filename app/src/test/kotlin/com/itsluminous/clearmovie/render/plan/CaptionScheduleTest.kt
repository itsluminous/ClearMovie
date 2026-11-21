package com.itsluminous.clearmovie.render.plan

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.setPhotoDuration
import com.itsluminous.clearmovie.domain.model.setText
import org.junit.Test

/** The preview takes a caption-only change without reloading its players (cm-captions). */
class CaptionScheduleTest {
    private val preset = Fixtures.preset

    private fun schedule(p: Project) = PreviewSchedule.of(RenderPlanBuilder(preset).build(p, shortSide = 720))

    private val base =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, preset, Fixtures.presetMedia, SequentialIds("x"))
            .addToMain(listOf(Fixtures.photo("a"), Fixtures.photo("b")))
            .addCaptionFor(ClipId("a"), "Hello", SequentialIds("c"))

    @Test
    fun `a caption typed or added differs only in captions, a photo length does not`() {
        val id =
            base.tracks
                .flatMap { it.clips }
                .first { it is TextClip }
                .id
        val typed = base.setText(id, "Hello there")
        val added = base.addCaptionFor(ClipId("b"), "Second", SequentialIds("d"))
        assertThat(schedule(typed).differsOnlyInCaptions(schedule(base))).isTrue()
        assertThat(schedule(added).differsOnlyInCaptions(schedule(base))).isTrue()
        assertThat(schedule(typed)).isNotEqualTo(schedule(base))
        val longer = base.setPhotoDuration(ClipId("a"), 5000)
        assertThat(schedule(longer).differsOnlyInCaptions(schedule(base))).isFalse()
    }
}
