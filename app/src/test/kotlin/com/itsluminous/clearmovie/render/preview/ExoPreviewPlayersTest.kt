package com.itsluminous.clearmovie.render.preview

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.KeySpec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The real players behind [PreviewPlayer] are set up per role as the engine
 * set them up before it took them from a factory: a frame player is muted
 * with no sound track, a sound player has no picture track, MAIN plays as a
 * movie, and speed and volume go through to ExoPlayer.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ExoPreviewPlayersTest {
    private val players = ExoPreviewPlayers(ApplicationProvider.getApplicationContext())

    private fun exo(p: PreviewPlayer): androidx.media3.exoplayer.ExoPlayer =
        p.javaClass
            .getDeclaredField("p")
            .apply { isAccessible = true }
            .get(p) as androidx.media3.exoplayer.ExoPlayer

    @Test
    fun `each role gets its own tracks, sound and effects`() {
        val main = players.create(PlayerRole.MAIN, null)
        val overlay = players.create(PlayerRole.OVERLAY, KeySpec(ArgbColor(0xFF00D922), 0.18, 0.08))
        val sound = players.create(PlayerRole.SOUND, null)
        try {
            assertThat(exo(main).audioAttributes.contentType).isEqualTo(C.AUDIO_CONTENT_TYPE_MOVIE)
            assertThat(overlay.volume).isEqualTo(0f)
            assertThat(exo(overlay).trackSelectionParameters.disabledTrackTypes).contains(C.TRACK_TYPE_AUDIO)
            assertThat(exo(sound).trackSelectionParameters.disabledTrackTypes).contains(C.TRACK_TYPE_VIDEO)
            assertThat(exo(main).trackSelectionParameters.disabledTrackTypes).isEmpty()
            main.speed = 2f
            assertThat(exo(main).playbackParameters.speed).isEqualTo(2f)
            main.volume = 0.5f
            assertThat(exo(main).volume).isEqualTo(0.5f)
        } finally {
            listOf(main, overlay, sound).forEach { it.release() }
        }
    }

    @Test
    fun `a playlist of file parts and silences becomes one media item each`() {
        val main = players.create(PlayerRole.MAIN, null)
        try {
            main.setSegments(
                listOf(
                    Segment.Media(android.net.Uri.parse("file:///nonexistent.mp4"), 1_000_000, 3_000_000),
                    Segment.Silence(2_000_000),
                ),
            )
            assertThat(exo(main).mediaItemCount).isEqualTo(2)
        } finally {
            main.release()
        }
    }
}
