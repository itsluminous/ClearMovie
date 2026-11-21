package com.itsluminous.clearmovie.render.preview

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.render.media3.ChromaKeyEffect
import com.itsluminous.clearmovie.render.plan.PreviewSchedule

/** One entry of a player's playlist: part of a file, or silence. */
sealed interface Segment {
    data class Media(
        val uri: Uri,
        val startUs: Long,
        val endUs: Long,
    ) : Segment

    data class Silence(
        val durationUs: Long,
    ) : Segment
}

/** What a player is for; it decides its sound, picture and effects. */
enum class PlayerRole {
    /** The clock: MAIN's pictures and sound. */
    MAIN,

    /** A frame row: muted, keyed, drawn over MAIN. */
    OVERLAY,

    /** A sound row: no picture. */
    SOUND,

    /** The end of the outgoing MAIN item during a transition: picture and sound, without taking audio focus from MAIN. */
    TAIL,
}

/**
 * The part of a player [LayeredPreviewEngine] uses, so the engine's
 * behaviour can be tested with a fake one (cm-timeline). The real one is
 * [ExoPreviewPlayers]. Positions are in ms, as Media3 reports them.
 */
interface PreviewPlayer {
    var playWhenReady: Boolean
    val playbackState: Int
    val currentMediaItemIndex: Int
    val currentPosition: Long
    var volume: Float
    var speed: Float

    fun setSegments(segments: List<Segment>)

    fun addListener(listener: Player.Listener)

    fun setVideoTextureView(view: TextureView)

    fun clearVideoTextureView(view: TextureView)

    fun prepare()

    fun seekTo(
        index: Int,
        positionMs: Long,
    )

    fun release()
}

fun interface PreviewPlayerFactory {
    /** A new player for [role]; an overlay with a [key] keys out that green. */
    fun create(
        role: PlayerRole,
        key: KeySpec?,
    ): PreviewPlayer
}

/** [PreviewPlayer]s backed by ExoPlayer. */
@UnstableApi
class ExoPreviewPlayers(
    private val context: Context,
) : PreviewPlayerFactory {
    private val sourceFactory by lazy { DefaultMediaSourceFactory(context) }

    @OptIn(ExperimentalApi::class)
    override fun create(
        role: PlayerRole,
        key: KeySpec?,
    ): PreviewPlayer {
        // Dynamic scheduling (on by default in Media3 1.11) lets a player that plays only sound, as
        // MAIN does over a photo, update its position about four times a second: the motion drawn
        // from MAIN's clock then moved in steps, and followers looked out of step and were moved
        // back over and over. Off, every player keeps its position up to date.
        val player = ExoPlayer.Builder(context).experimentalSetDynamicSchedulingEnabled(false).build()
        when (role) {
            PlayerRole.MAIN -> {
                player.setAudioAttributes(MOVIE_AUDIO, true)
            }

            PlayerRole.OVERLAY -> {
                player.volume = 0f
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                if (key != null) player.setVideoEffects(listOf(ChromaKeyEffect(key), PremultiplyEffect()))
            }

            PlayerRole.TAIL -> {
                // Never asks for audio focus: MAIN holds it, and a second request would pause MAIN.
                player.setAudioAttributes(MOVIE_AUDIO, false)
            }

            PlayerRole.SOUND -> {
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .build()
            }
        }
        return Exo(player)
    }

    private inner class Exo(
        private val p: ExoPlayer,
    ) : PreviewPlayer {
        override var playWhenReady: Boolean
            get() = p.playWhenReady
            set(value) {
                p.playWhenReady = value
            }
        override val playbackState: Int get() = p.playbackState
        override val currentMediaItemIndex: Int get() = p.currentMediaItemIndex
        override val currentPosition: Long get() = p.currentPosition
        override var volume: Float
            get() = p.volume
            set(value) {
                p.volume = value
            }
        override var speed: Float
            get() = p.playbackParameters.speed
            set(value) {
                p.playbackParameters = PlaybackParameters(value)
            }

        override fun setSegments(segments: List<Segment>) = p.setMediaSources(segments.map(::source))

        override fun addListener(listener: Player.Listener) = p.addListener(listener)

        override fun setVideoTextureView(view: TextureView) = p.setVideoTextureView(view)

        override fun clearVideoTextureView(view: TextureView) = p.clearVideoTextureView(view)

        override fun prepare() = p.prepare()

        override fun seekTo(
            index: Int,
            positionMs: Long,
        ) = p.seekTo(index, positionMs)

        override fun release() = p.release()
    }

    private fun source(segment: Segment): MediaSource =
        when (segment) {
            is Segment.Media -> {
                ClippingMediaSource
                    .Builder(sourceFactory.createMediaSource(MediaItem.fromUri(segment.uri)))
                    .setStartPositionUs(segment.startUs)
                    .setEndPositionUs(maxOf(segment.endUs, segment.startUs + PreviewSchedule.MIN_ENTRY_US))
                    .build()
            }

            is Segment.Silence -> {
                SilenceMediaSource
                    .Factory()
                    .setDurationUs(segment.durationUs.coerceAtLeast(PreviewSchedule.MIN_ENTRY_US))
                    .createMediaSource()
            }
        }

    private companion object {
        val MOVIE_AUDIO: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
    }
}
