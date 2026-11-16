package com.itsluminous.clearmovie.render.preview

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.render.plan.PreviewSchedule.MainContent
import com.itsluminous.clearmovie.render.plan.Source
import org.junit.Test

/** The preview's mapping between the player's position in a file and the video's time. */
class PreviewTimingTest {
    private val ref = MediaRef.Store("content://media/external/video/media/1")

    @Test
    fun `a video at 2x covers twice its file time on the player`() {
        val fast = MainContent.Video(ref, 0, Speed.X2)
        assertThat(LayeredPreviewEngine.timelineUs(fast, 4_000_000)).isEqualTo(2_000_000)
        assertThat(LayeredPreviewEngine.mediaOffsetMs(fast, 2_000_000)).isEqualTo(4_000)
        val slow = MainContent.Video(ref, 0, Speed.X0_5)
        assertThat(LayeredPreviewEngine.timelineUs(slow, 1_000_000)).isEqualTo(2_000_000)
    }

    @Test
    fun `a still runs at the video's own time`() {
        val still = MainContent.Still(Source.Photo(ref))
        assertThat(LayeredPreviewEngine.timelineUs(still, 1_500_000)).isEqualTo(1_500_000)
        assertThat(LayeredPreviewEngine.mediaOffsetMs(still, 1_500_000)).isEqualTo(1_500)
    }

    @Test
    fun `levels become player volumes, silent where there is no sound`() {
        assertThat(LayeredPreviewEngine.gainOf(null)).isEqualTo(0f)
        assertThat(LayeredPreviewEngine.gainOf(0.0)).isEqualTo(1f)
        assertThat(LayeredPreviewEngine.gainOf(-20.0)).isWithin(1e-4f).of(0.1f)
        // Louder than the file cannot be played; it is held at full.
        assertThat(LayeredPreviewEngine.gainOf(6.0)).isEqualTo(1f)
    }
}
