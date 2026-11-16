package com.itsluminous.clearmovie.render.preview

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.render.media3.ChromaKeyEffect
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.plan.GainEnvelope
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.PreviewSchedule.LaneEntry
import com.itsluminous.clearmovie.render.plan.PreviewSchedule.MainContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the preview shows now. */
data class PreviewState(
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val playing: Boolean = false,
    /** Index into [PreviewSchedule.main], or -1 with nothing to play. */
    val mainIndex: Int = -1,
    /** The MAIN player's picture size for the current video, or null before it is known. */
    val mainVideoSize: VideoSize? = null,
    /** Per overlay lane: whether its picture shows now. */
    val overlayVisible: List<Boolean> = emptyList(),
    /** Media that could not be opened; those parts are left out of the preview. */
    val unavailable: Int = 0,
    /**
     * Counts [LayeredPreviewEngine.load]s. A keyed player's picture can only go to a fresh
     * surface: the video effects of the player it replaces may still hold the old one, so the
     * UI makes new frame surfaces whenever this changes.
     */
    val loads: Int = 0,
)

/**
 * The layered editor preview (render decision D1): MAIN plays in one plain
 * ExoPlayer and is the clock; each frame row plays muted in a player of its
 * own, keyed with the export's [ChromaKeyEffect] and drawn over MAIN; each
 * sound row plays in an audio-only player. Followers are kept within
 * [MAX_DRIFT_US] of MAIN, and every player's volume follows the plan's gain
 * envelopes, so the music ducks as it will in the export.
 *
 * Photos, held frames and cards are drawn by the UI over the MAIN player,
 * which plays silence for their length. Every method must be called on the
 * main thread. [release] frees every player; the owner (the editor's view
 * model) calls it when it is cleared, so a rotation keeps playing.
 */
@UnstableApi
class LayeredPreviewEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = stateFlow.asStateFlow()

    var schedule: PreviewSchedule? = null
        private set

    private var main: ExoPlayer? = null
    private val overlays = mutableListOf<ExoPlayer?>()
    private val sounds = mutableListOf<ExoPlayer?>()
    private var ticker: Job? = null
    private var loads = 0

    /** Whether the current players have been given the surfaces above. */
    private var mainAttached = false
    private val overlaysAttached = mutableSetOf<Int>()
    private var mainSurface: TextureView? = null
    private val overlaySurfaces = mutableMapOf<Int, TextureView>()
    private val sourceFactory by lazy { DefaultMediaSourceFactory(context) }

    /** Plays [next] from [startUs], replacing whatever was loaded. */
    fun load(
        next: PreviewSchedule,
        resolver: MediaResolver,
        startUs: Long,
        play: Boolean,
    ) {
        checkMainThread()
        releasePlayers()
        schedule = next
        loads++
        // The old players are gone; their surfaces go with them (new ones attach when the UI makes them).
        overlaySurfaces.clear()
        var missing = 0

        fun uri(ref: MediaRef): Uri? = runCatching { resolver.resolve(ref) }.getOrNull().also { if (it == null) missing++ }

        if (next.main.isEmpty()) {
            stateFlow.value = PreviewState(durationUs = next.durationUs, loads = loads)
            return
        }
        val mainSources =
            next.main.map { e ->
                val video = e.content as? MainContent.Video
                val u = video?.let { uri(it.media) }
                if (video != null && u != null) clipped(u, video.clipStartUs, video.clipEndUs(e.durationUs)) else silence(e.durationUs)
            }
        main =
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(MOVIE_AUDIO, true)
                setMediaSources(mainSources)
                addListener(MainListener())
                mainSurface?.let {
                    setVideoTextureView(it)
                    mainAttached = true
                }
            }
        next.overlays.forEachIndexed { i, lane ->
            val sources =
                lane.entries.map { e ->
                    (e as? LaneEntry.Play)?.let { p -> uri(p.media)?.let { clipped(it, p.clipStartUs, p.clipEndUs()) } }
                }
            val key = lane.entries.firstNotNullOfOrNull { (it as? LaneEntry.Play)?.key }
            overlays +=
                if (sources.all { it == null }) {
                    null
                } else {
                    ExoPlayer.Builder(context).build().apply {
                        volume = 0f
                        trackSelectionParameters =
                            trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                        if (key != null) setVideoEffects(listOf(ChromaKeyEffect(key), PremultiplyEffect()))
                        setMediaSources(lane.entries.mapIndexed { j, e -> sources[j] ?: silence(e.durationUs) })
                        addListener(FollowerListener(this))
                        overlaySurfaces[i]?.let {
                            setVideoTextureView(it)
                            overlaysAttached += i
                        }
                    }
                }
        }
        next.sounds.forEach { lane ->
            val sources =
                lane.entries.map { e ->
                    (e as? LaneEntry.Play)?.let { p -> uri(p.media)?.let { clipped(it, p.clipStartUs, p.clipEndUs()) } }
                }
            sounds +=
                if (sources.all { it == null }) {
                    null
                } else {
                    ExoPlayer.Builder(context).build().apply {
                        trackSelectionParameters =
                            trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                        setMediaSources(lane.entries.mapIndexed { j, e -> sources[j] ?: silence(e.durationUs) })
                        addListener(FollowerListener(this))
                    }
                }
        }
        stateFlow.value =
            PreviewState(
                durationUs = next.durationUs,
                overlayVisible = List(next.overlays.size) { false },
                unavailable = missing,
                loads = loads,
            )
        allPlayers().forEach { it.prepare() }
        seekTo(startUs)
        if (play) play() else publish()
    }

    fun play() {
        checkMainThread()
        val p = main ?: return
        stillPending.clear()
        if (p.playbackState == Player.STATE_ENDED) seekTo(0)
        allPlayers().forEach { it.playWhenReady = true }
        startTicker()
        publish()
    }

    fun pause() {
        checkMainThread()
        allPlayers().forEach { it.playWhenReady = false }
        sync(force = true)
        publish()
    }

    fun togglePlay() = if (main?.playWhenReady == true) pause() else play()

    /** Moves every player to [us] of the video's time. */
    fun seekTo(us: Long) {
        checkMainThread()
        val s = schedule ?: return
        val p = main ?: return
        val t = us.coerceIn(0, s.durationUs)
        val i = s.mainIndexAt(t)
        val e = s.main[i]
        p.seekTo(i, mediaOffsetMs(e.content, t - e.startUs))
        applySpeed(i)
        followTo(t)
        publish(t)
    }

    val positionUs: Long get() = mainTimeUs()

    val isPlaying: Boolean get() = main?.playWhenReady == true

    /** Shows MAIN on [view]. Calling it again with the same view does nothing, so the UI may call it on every update. */
    fun setMainSurface(view: TextureView?) {
        checkMainThread()
        if (view === mainSurface && view != null && mainAttached) return
        mainSurface?.let { old -> if (old !== view) main?.clearVideoTextureView(old) }
        mainSurface = view
        mainAttached = view != null && main != null
        view?.let { main?.setVideoTextureView(it) }
    }

    fun setOverlaySurface(
        index: Int,
        view: TextureView?,
    ) {
        checkMainThread()
        val player = overlays.getOrNull(index)
        if (view != null && overlaySurfaces[index] === view && index in overlaysAttached) return
        overlaySurfaces[index]?.let { old -> if (old !== view) player?.clearVideoTextureView(old) }
        if (view == null) {
            overlaySurfaces.remove(index)
            overlaysAttached -= index
        } else {
            overlaySurfaces[index] = view
            if (player != null) {
                player.setVideoTextureView(view)
                overlaysAttached += index
            }
        }
    }

    /** [view] is going away; it is detached only if it is still the one in use (a newer one may have replaced it). */
    fun releaseMainSurface(view: TextureView) {
        checkMainThread()
        if (mainSurface === view) setMainSurface(null)
    }

    /** [view] is going away; it is detached only if it is still the one in use (a newer one may have replaced it). */
    fun releaseOverlaySurface(
        index: Int,
        view: TextureView,
    ) {
        checkMainThread()
        if (overlaySurfaces[index] === view) setOverlaySurface(index, null)
    }

    fun release() {
        checkMainThread()
        releasePlayers()
        mainSurface = null
        overlaySurfaces.clear()
        schedule = null
    }

    // ---------------------------------------------------------------- internals

    private fun releasePlayers() {
        stillPending.clear()
        mainAttached = false
        overlaysAttached.clear()
        ticker?.cancel()
        ticker = null
        allPlayers().forEach { it.release() }
        main = null
        overlays.clear()
        sounds.clear()
    }

    private fun allPlayers(): List<ExoPlayer> = listOfNotNull(main) + overlays.filterNotNull() + sounds.filterNotNull()

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker =
            scope.launch {
                while (isActive) {
                    sync(force = false)
                    publish()
                    if (main?.playWhenReady != true) break
                    delay(TICK_MS)
                }
            }
    }

    /** The video's time from the MAIN player. */
    private fun mainTimeUs(): Long {
        val s = schedule ?: return 0
        val p = main ?: return 0
        val e = s.main.getOrNull(p.currentMediaItemIndex) ?: return 0
        val offset = timelineUs(e.content, p.currentPosition * 1000)
        return (e.startUs + offset.coerceIn(0, e.durationUs)).coerceIn(0, s.durationUs)
    }

    /** Puts every follower at the MAIN player's time, and sets every volume. */
    private fun sync(force: Boolean) {
        val s = schedule ?: return
        val t = mainTimeUs()
        if (force) followTo(t) else keepInStep(t)
        main?.volume = gainOf(s.mainGainDbAt(t))
        s.sounds.forEachIndexed { i, lane -> sounds.getOrNull(i)?.volume = gainOf(lane.gainDbAt(t)) }
    }

    private fun followTo(t: Long) {
        val s = schedule ?: return
        val paused = main?.playWhenReady != true
        s.overlays.forEachIndexed { i, lane ->
            val player = overlays.getOrNull(i) ?: return@forEachIndexed
            seekLane(player, lane.entries, t)
            // A player with video effects shows nothing after a seek until it plays, so a paused
            // preview would lose the frame. It plays (silent: its sound is off) until the first
            // frame is on screen, then stops again.
            if (paused && lane.entries.getOrNull(lane.indexAt(t)) is LaneEntry.Play) showStill(player)
        }
        s.sounds.forEachIndexed { i, lane -> sounds.getOrNull(i)?.let { seekLane(it, lane.entries, t) } }
    }

    /** Followers playing only to show their first frame after a seek, with the request each is for. */
    private val stillPending = mutableMapOf<ExoPlayer, Int>()
    private var stillRequests = 0

    private fun showStill(player: ExoPlayer) {
        val request = ++stillRequests
        stillPending[player] = request
        player.playWhenReady = true
        scope.launch {
            delay(STILL_TIMEOUT_MS)
            // Never left playing if no frame came (a file that can't be read, say). A later
            // seek's request replaces this one, so only the latest one's timeout counts.
            if (stillPending[player] == request && main?.playWhenReady != true) {
                stillPending -= player
                player.playWhenReady = false
            }
        }
    }

    private fun keepInStep(t: Long) {
        val s = schedule ?: return
        val playing = main?.playWhenReady == true

        fun check(
            player: ExoPlayer,
            entries: List<LaneEntry>,
        ) {
            player.playWhenReady = playing
            val at = laneTimeUs(player, entries) ?: return seekLane(player, entries, t)
            if (abs(at - t) > MAX_DRIFT_US) seekLane(player, entries, t)
        }
        s.overlays.forEachIndexed { i, lane -> overlays.getOrNull(i)?.let { check(it, lane.entries) } }
        s.sounds.forEachIndexed { i, lane -> sounds.getOrNull(i)?.let { check(it, lane.entries) } }
    }

    private fun laneTimeUs(
        player: ExoPlayer,
        entries: List<LaneEntry>,
    ): Long? {
        val e = entries.getOrNull(player.currentMediaItemIndex) ?: return null
        val pos = player.currentPosition * 1000
        val offset = if (e is LaneEntry.Play) pos * e.speed.denominator / e.speed.numerator else pos
        return e.startUs + offset
    }

    private fun seekLane(
        player: ExoPlayer,
        entries: List<LaneEntry>,
        t: Long,
    ) {
        if (entries.isEmpty()) return
        val i = entries.indexOfLast { it.startUs <= t }.coerceAtLeast(0)
        val e = entries[i]
        val within = (t - e.startUs).coerceIn(0, e.durationUs)
        val mediaUs = if (e is LaneEntry.Play) within * e.speed.numerator / e.speed.denominator else within
        player.seekTo(i, mediaUs / 1000)
    }

    private fun applySpeed(index: Int) {
        val e = schedule?.main?.getOrNull(index) ?: return
        val speed = (e.content as? MainContent.Video)?.speed ?: Speed.X1
        val wanted = speed.numerator.toFloat() / speed.denominator
        main?.let { if (it.playbackParameters.speed != wanted) it.playbackParameters = PlaybackParameters(wanted) }
    }

    private fun publish(at: Long? = null) {
        val s = schedule ?: return
        val p = main
        val overlayShown =
            s.overlays.mapIndexed { i, lane ->
                val player = overlays.getOrNull(i) ?: return@mapIndexed false
                lane.entries.getOrNull(player.currentMediaItemIndex) is LaneEntry.Play
            }
        stateFlow.value =
            stateFlow.value.copy(
                positionUs = at ?: mainTimeUs(),
                durationUs = s.durationUs,
                playing = p?.playWhenReady == true && p.playbackState != Player.STATE_ENDED,
                mainIndex = p?.currentMediaItemIndex ?: -1,
                overlayVisible = overlayShown,
            )
    }

    private inner class MainListener : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            main?.let { applySpeed(it.currentMediaItemIndex) }
            sync(force = false)
            publish()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) stateFlow.value = stateFlow.value.copy(mainVideoSize = videoSize)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                (overlays + sounds).filterNotNull().forEach { it.playWhenReady = false }
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            // A file that fails mid-play stops the preview, never the app; Play starts again.
            allPlayers().forEach { it.playWhenReady = false }
            stateFlow.value = stateFlow.value.copy(unavailable = stateFlow.value.unavailable + 1)
            main?.prepare()
            publish()
        }
    }

    private inner class FollowerListener(
        private val player: ExoPlayer,
    ) : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) = publish()

        override fun onRenderedFirstFrame() {
            val request = stillPending[player] ?: return
            // The frame is decoded, but still on its way through the keyer to the surface: stop a
            // moment later, or the surface keeps showing nothing.
            scope.launch {
                delay(STILL_SETTLE_MS)
                if (stillPending[player] == request) {
                    stillPending -= player
                    if (main?.playWhenReady != true) player.playWhenReady = false
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A frame or sound that can't play is left out; MAIN keeps playing.
            stateFlow.value = stateFlow.value.copy(unavailable = stateFlow.value.unavailable + 1)
        }
    }

    private fun clipped(
        uri: Uri,
        startUs: Long,
        endUs: Long,
    ): MediaSource =
        ClippingMediaSource
            .Builder(sourceFactory.createMediaSource(MediaItem.fromUri(uri)))
            .setStartPositionUs(startUs)
            .setEndPositionUs(maxOf(endUs, startUs + PreviewSchedule.MIN_ENTRY_US))
            .build()

    private fun silence(durationUs: Long): MediaSource =
        SilenceMediaSource
            .Factory()
            .setDurationUs(durationUs.coerceAtLeast(PreviewSchedule.MIN_ENTRY_US))
            .createMediaSource()

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "Use the preview on the main thread" }

    companion object {
        const val TICK_MS = 100L
        const val MAX_DRIFT_US = 200_000L
        private const val STILL_TIMEOUT_MS = 10_000L
        private const val STILL_SETTLE_MS = 300L

        private val MOVIE_AUDIO: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

        internal fun gainOf(db: Double?): Float = if (db == null) 0f else GainEnvelope.dbToGain(db).coerceIn(0f, 1f)

        /** Player position within an entry (media time) to the video's time, and back. */
        internal fun timelineUs(
            content: MainContent,
            mediaUs: Long,
        ): Long {
            val speed = (content as? MainContent.Video)?.speed ?: return mediaUs
            return mediaUs * speed.denominator / speed.numerator
        }

        internal fun mediaOffsetMs(
            content: MainContent,
            timelineUs: Long,
        ): Long {
            val speed = (content as? MainContent.Video)?.speed ?: Speed.X1
            return timelineUs * speed.numerator / speed.denominator / 1000
        }
    }
}
