package com.itsluminous.clearmovie.render.preview

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Speed
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
    /**
     * Playing, but MAIN (the clock, and the only player that asks for audio
     * focus) is held back by the system for a moment (focus lost to a call or
     * an assistant): the video does not move on, so the screen need not stay
     * on (cm-keep-screen-on).
     */
    val suppressed: Boolean = false,
    /** Index into [PreviewSchedule.main], or -1 with nothing to play. */
    val mainIndex: Int = -1,
    /** The MAIN player's picture size for the current video, or null before it is known. */
    val mainVideoSize: VideoSize? = null,
    /**
     * Whether the MAIN player's surface holds a picture of the video playing:
     * false from a photo, card or gap on (the player plays silence there) until
     * the next video's first picture is drawn, as the surface still holds the
     * last picture of the video before.
     */
    val mainSurfaceReady: Boolean = false,
    /** The tail player's picture size (the outgoing video in a transition), or null before it is known. */
    val tailVideoSize: VideoSize? = null,
    /** Per overlay lane: whether its picture shows now. */
    val overlayVisible: List<Boolean> = emptyList(),
    /** Per overlay lane: its player's picture size, or null before it is known (a picture in picture is cropped by it). */
    val overlaySizes: List<VideoSize?> = emptyList(),
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
 * ExoPlayer and is the clock; during a transition a second player plays the
 * end of the outgoing item (the schedule's tail), picture and sound, so the
 * two items blend as in the export; each frame row plays muted in a player of
 * its own, keyed with the export's [ChromaKeyEffect] and drawn over MAIN; each
 * sound row plays in an audio-only player. Followers are kept within
 * [MAX_DRIFT_US] of MAIN, and every player's volume follows the plan's gain
 * envelopes, so the music ducks as it will in the export.
 *
 * Photos, held frames and cards are drawn by the UI over the MAIN player,
 * which plays silence for their length. Every method must be called on the
 * main thread. [release] frees every player; the owner (the editor's view
 * model) calls it when it is cleared, so a rotation keeps playing. The
 * players come from [players], so tests can drive the engine with fakes.
 */
@UnstableApi
class LayeredPreviewEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val players: PreviewPlayerFactory = ExoPreviewPlayers(context),
) {
    private val stateFlow = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = stateFlow.asStateFlow()

    var schedule: PreviewSchedule? = null
        private set

    private var main: PreviewPlayer? = null
    private var tail: PreviewPlayer? = null
    private var tailSurface: TextureView? = null
    private var tailAttached = false
    private val overlays = mutableListOf<PreviewPlayer?>()
    private val sounds = mutableListOf<PreviewPlayer?>()
    private var ticker: Job? = null
    private var loads = 0

    /** Whether the current players have been given the surfaces above. */
    private var mainAttached = false
    private val overlaysAttached = mutableSetOf<Int>()
    private var mainSurface: TextureView? = null
    private val overlaySurfaces = mutableMapOf<Int, TextureView>()

    /**
     * Takes [next] in place of the loaded schedule when the two differ only in
     * their captions (a caption typed, styled, moved or added), without
     * reloading any player, so the change shows at once and the picture never
     * blinks. False, and nothing changes, when anything else differs: then
     * [load] it.
     */
    fun updateCaptions(next: PreviewSchedule): Boolean {
        checkMainThread()
        val now = schedule ?: return false
        if (!next.differsOnlyInCaptions(now)) return false
        schedule = next
        return true
    }

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
            players.create(PlayerRole.MAIN, null).apply {
                setSegments(mainSources)
                addListener(MainListener())
                mainSurface?.let {
                    setVideoTextureView(it)
                    mainAttached = true
                }
            }
        next.tailLane?.let { lane ->
            val sources =
                lane.entries.map { e ->
                    (e as? LaneEntry.Play)?.let { p -> uri(p.media)?.let { clipped(it, p.clipStartUs, p.clipEndUs()) } }
                }
            if (sources.any { it != null }) {
                tail =
                    players.create(PlayerRole.TAIL, null).apply {
                        setSegments(lane.entries.mapIndexed { j, e -> sources[j] ?: silence(e.durationUs) })
                        addListener(TailListener(this))
                        tailSurface?.let {
                            setVideoTextureView(it)
                            tailAttached = true
                        }
                    }
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
                    players.create(PlayerRole.OVERLAY, key).apply {
                        if (key != null) keyed += this
                        setSegments(lane.entries.mapIndexed { j, e -> sources[j] ?: silence(e.durationUs) })
                        addListener(OverlayListener(this, i))
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
                    players.create(PlayerRole.SOUND, null).apply {
                        setSegments(lane.entries.mapIndexed { j, e -> sources[j] ?: silence(e.durationUs) })
                        addListener(FollowerListener(this))
                    }
                }
        }
        // The MAIN surface outlives its players: the picture it holds from the player before stays
        // shown until the new one draws, as it did before an edit reloaded them, so an edit never blinks.
        val surfaceReady = stateFlow.value.mainSurfaceReady && mainSurface != null
        stateFlow.value =
            PreviewState(
                durationUs = next.durationUs,
                overlayVisible = List(next.overlays.size) { false },
                overlaySizes = List(next.overlays.size) { null },
                unavailable = missing,
                loads = loads,
                mainSurfaceReady = surfaceReady,
            )
        allPlayers().forEach { it.prepare() }
        seekTo(startUs)
        if (play) play() else publish()
    }

    fun play() {
        checkMainThread()
        val p = main ?: return
        if (p.playbackState == Player.STATE_ENDED) seekTo(0)
        // After the seek, which (stopped at the end) shows the frames' stills as a paused seek does.
        stillPending.clear()
        p.playWhenReady = true
        // Each follower plays now, or waits for its next part (see keepInStep).
        sync(force = false)
        startTicker()
        publish()
    }

    fun pause() {
        checkMainThread()
        wake?.cancel()
        wake = null
        allPlayers().forEach { it.playWhenReady = false }
        sync(force = true)
        publish()
    }

    fun togglePlay() = if (isPlaying) pause() else play()

    /**
     * Moves every player to [us] of the video's time. Stopped at the end, the
     * preview stays paused there: ExoPlayer keeps playWhenReady set at its end,
     * and a seek from there would otherwise play on its own.
     */
    fun seekTo(us: Long) {
        checkMainThread()
        val s = schedule ?: return
        val p = main ?: return
        if (p.playbackState == Player.STATE_ENDED && p.playWhenReady) allPlayers().forEach { it.playWhenReady = false }
        val t = us.coerceIn(0, s.durationUs)
        val i = s.mainIndexAt(t)
        val e = s.main[i]
        p.seekTo(i, mediaOffsetMs(e.content, t - e.startUs))
        applySpeed(i)
        surfaceLeftVideo(i)
        followTo(t)
        publish(t)
    }

    val positionUs: Long get() = mainTimeUs()

    /** Playing, and not stopped at the end (ExoPlayer keeps playWhenReady set there). */
    val isPlaying: Boolean get() = main?.let { it.playWhenReady && it.playbackState != Player.STATE_ENDED } == true

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

    /** Shows the transition tail on [view]; like [setMainSurface], safe to call on every update. */
    fun setTailSurface(view: TextureView?) {
        checkMainThread()
        if (view === tailSurface && view != null && tailAttached) return
        tailSurface?.let { old -> if (old !== view) tail?.clearVideoTextureView(old) }
        tailSurface = view
        tailAttached = view != null && tail != null
        view?.let { tail?.setVideoTextureView(it) }
    }

    fun releaseTailSurface(view: TextureView) {
        checkMainThread()
        if (tailSurface === view) setTailSurface(null)
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
        tailSurface = null
        overlaySurfaces.clear()
        schedule = null
    }

    // ---------------------------------------------------------------- internals

    private fun releasePlayers() {
        stillPending.clear()
        parked.clear()
        keyed.clear()
        wake?.cancel()
        wake = null
        mainAttached = false
        tailAttached = false
        overlaysAttached.clear()
        ticker?.cancel()
        ticker = null
        allPlayers().forEach { it.release() }
        main = null
        tail = null
        overlays.clear()
        sounds.clear()
    }

    private fun allPlayers(): List<PreviewPlayer> = listOfNotNull(main, tail) + overlays.filterNotNull() + sounds.filterNotNull()

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker =
            scope.launch {
                while (isActive) {
                    sync(force = false)
                    publish()
                    if (!isPlaying) break
                    // Between two syncs only the volumes, every VOLUME_TICK_MS, so a 300 ms duck ramps in small
                    // steps, as the export's does sample by sample (cm-voice-ducking).
                    repeat((TICK_MS / VOLUME_TICK_MS - 1).toInt()) {
                        delay(VOLUME_TICK_MS)
                        if (isPlaying) schedule?.let { applyVolumes(it, mainTimeUs()) }
                    }
                    delay(VOLUME_TICK_MS)
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
        applyVolumes(s, t)
    }

    private fun applyVolumes(
        s: PreviewSchedule,
        t: Long,
    ) {
        main?.volume = gainOf(s.mainGainDbAt(t)) * monitorGain
        tail?.volume = gainOf(s.tailGainDbAt(t)) * monitorGain
        s.sounds.forEachIndexed { i, lane -> sounds.getOrNull(i)?.volume = gainOf(lane.gainDbAt(t)) * monitorGain }
    }

    /**
     * How loud the preview plays, 0 to 1, on top of the plan's own levels:
     * 0 while a voice is recorded, so the video's sound never goes back into
     * the microphone (cm-record-voice). The export is never affected.
     */
    var monitorGain: Float = 1f
        set(value) {
            checkMainThread()
            field = value.coerceIn(0f, 1f)
            schedule?.let { applyVolumes(it, mainTimeUs()) }
        }

    private fun followTo(t: Long) {
        val s = schedule ?: return
        val paused = main?.playWhenReady != true
        parked.clear()
        wake?.cancel()
        wake = null
        s.overlays.forEachIndexed { i, lane ->
            val player = overlays.getOrNull(i) ?: return@forEachIndexed
            // A player with video effects shows nothing after a seek until it plays, so a paused
            // preview would lose the frame. It plays (silent: its sound is off) until the first
            // frame is on screen, then stops again.
            if (place(player, lane.entries, t) && paused) showStill(player)
        }
        s.sounds.forEachIndexed { i, lane -> sounds.getOrNull(i)?.let { place(it, lane.entries, t) } }
        s.tailLane?.let { lane -> tail?.let { place(it, lane.entries, t) } }
        applyTailSpeed()
        // Playing on, each starts with MAIN, or waits for its next part (see keepInStep).
        if (!paused) keepInStep(t)
    }

    /** Followers playing only to show their first frame after a seek, with the request each is for. */
    private val stillPending = mutableMapOf<PreviewPlayer, Int>()
    private var stillRequests = 0

    private fun showStill(player: PreviewPlayer) {
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

    /** Followers waiting, paused, at the start of their next part: the entry each waits at. */
    private val parked = mutableMapOf<PreviewPlayer, Int>()

    /** The frame players that key out a green (see [place]). */
    private val keyed = mutableSetOf<PreviewPlayer>()

    /** The check that parks or starts a waiting follower in time, and when it runs (uptime, ms). */
    private var wake: Job? = null
    private var wakeAtMs = 0L

    /**
     * Keeps every follower at MAIN's time [t] while playing. In the last
     * [PARK_AHEAD_US] before one of its parts, a follower waits, paused, at
     * that part's start ([park]), and starts when MAIN reaches it, its picture
     * already decoded (a picture player: the frame, a picture in picture, the
     * outgoing video; a sound row plays through its silence as before, so its
     * music never pauses and jumps). Crossing from its silence into the file while playing,
     * it started late: the outgoing video of a transition (and the frame after
     * the intro) showed late, then was moved back in step and was late again.
     * Further from its next part it plays its silence, as before, holding no
     * decoder. A follower that drifts more than [MAX_DRIFT_US] while playing
     * is moved back in step.
     */
    private fun keepInStep(t: Long) {
        val s = schedule ?: return
        val playing = isPlaying
        var soonest = Long.MAX_VALUE

        fun check(
            player: PreviewPlayer,
            entries: List<LaneEntry>,
        ) {
            val i = entries.indexOfLast { it.startUs <= t }
            if (entries.getOrNull(i) !is LaneEntry.Play) {
                val next = (i + 1 until entries.size).firstOrNull { entries[it] is LaneEntry.Play }
                val until = next?.let { entries[it].startUs - t }
                if (playing && next != null && until != null && until <= PARK_AHEAD_US && player !in sounds) {
                    player.playWhenReady = false
                    park(player, entries, next)
                    soonest = minOf(soonest, until)
                    return
                }
                if (until != null && player !in sounds) soonest = minOf(soonest, until - PARK_AHEAD_US)
            }
            player.playWhenReady = playing
            // One that waited at this entry's start is there and starts now: in step, unless MAIN got here late.
            parked.remove(player)
            val at = laneTimeUs(player, entries) ?: return seekLane(player, entries, t)
            if (abs(at - t) > MAX_DRIFT_US) seekLane(player, entries, t)
        }
        s.overlays.forEachIndexed { i, lane -> overlays.getOrNull(i)?.let { check(it, lane.entries) } }
        s.sounds.forEachIndexed { i, lane -> sounds.getOrNull(i)?.let { check(it, lane.entries) } }
        s.tailLane?.let { lane -> tail?.let { check(it, lane.entries) } }
        val wakeAt = if (playing && soonest != Long.MAX_VALUE) SystemClock.uptimeMillis() + (soonest / 1000).coerceAtLeast(1) else null
        // The same moment as the check already waiting (give or take a tick's drift): it stays.
        if (wakeAt != null && wake?.isActive == true && abs(wakeAt - wakeAtMs) < WAKE_SLACK_MS) return
        wake?.cancel()
        wakeAtMs = wakeAt ?: 0
        wake =
            wakeAt?.let { at ->
                scope.launch {
                    delay(at - SystemClock.uptimeMillis())
                    sync(force = false)
                }
            }
    }

    /**
     * Puts [player] at [t] (a seek, or MAIN paused); true when it plays
     * something then. A keyed frame player already in its silence is left
     * where it is when [t] is in a gap too: one moved within its silence
     * while paused, then to its file, stayed buffering (for ten seconds and
     * more, even while played to put its picture up), so the frame did not
     * show in the paused preview (cm-preview-smooth).
     */
    private fun place(
        player: PreviewPlayer,
        entries: List<LaneEntry>,
        t: Long,
    ): Boolean {
        val i = entries.indexOfLast { it.startUs <= t }
        if (entries.getOrNull(i) is LaneEntry.Play) {
            seekLane(player, entries, t)
            return true
        }
        // In a gap: not playing on to put up a picture asked for before.
        stillPending -= player
        player.playWhenReady = false
        val inSilence = entries.getOrNull(player.currentMediaItemIndex) !is LaneEntry.Play
        if (!(player in keyed && inSilence)) seekLane(player, entries, t)
        return false
    }

    /** Puts [player] at the start of entry [at], unless it waits there already. */
    private fun park(
        player: PreviewPlayer,
        entries: List<LaneEntry>,
        at: Int,
    ) {
        if (parked[player] == at) return
        parked[player] = at
        seekLane(player, entries, entries[at].startUs)
    }

    private fun laneTimeUs(
        player: PreviewPlayer,
        entries: List<LaneEntry>,
    ): Long? {
        val e = entries.getOrNull(player.currentMediaItemIndex) ?: return null
        val pos = player.currentPosition * 1000
        val offset = if (e is LaneEntry.Play) pos * e.speed.denominator / e.speed.numerator else pos
        return e.startUs + offset
    }

    private fun seekLane(
        player: PreviewPlayer,
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

    /** The tail plays its entry at the entry's speed (a sped-up outgoing video stays sped up). */
    private fun applyTailSpeed() {
        val p = tail ?: return
        val e = schedule?.tailLane?.entries?.getOrNull(p.currentMediaItemIndex) as? LaneEntry.Play
        val speed = e?.speed ?: Speed.X1
        val wanted = speed.numerator.toFloat() / speed.denominator
        if (p.speed != wanted) p.speed = wanted
    }

    /** MAIN is on item [index]: when it is not a video, the surface holds no picture of what plays (see PreviewState). */
    private fun surfaceLeftVideo(index: Int) {
        val video = schedule?.main?.getOrNull(index)?.content is MainContent.Video
        if (!video && stateFlow.value.mainSurfaceReady) stateFlow.value = stateFlow.value.copy(mainSurfaceReady = false)
    }

    private fun applySpeed(index: Int) {
        val e = schedule?.main?.getOrNull(index) ?: return
        val speed = (e.content as? MainContent.Video)?.speed ?: Speed.X1
        val wanted = speed.numerator.toFloat() / speed.denominator
        main?.let { if (it.speed != wanted) it.speed = wanted }
    }

    private fun publish(at: Long? = null) {
        val s = schedule ?: return
        val p = main
        val t = at ?: mainTimeUs()
        // By the video's time, not the player's item: a follower parked for its next part is already on it.
        val overlayShown =
            s.overlays.mapIndexed { i, lane ->
                overlays.getOrNull(i) != null && lane.entries.getOrNull(lane.indexAt(t)) is LaneEntry.Play
            }
        stateFlow.value =
            stateFlow.value.copy(
                positionUs = t,
                durationUs = s.durationUs,
                playing = p?.playWhenReady == true && p.playbackState != Player.STATE_ENDED,
                suppressed = p?.playbackSuppressed == true,
                mainIndex = p?.currentMediaItemIndex ?: -1,
                overlayVisible = overlayShown,
            )
    }

    private inner class MainListener : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            main?.let {
                applySpeed(it.currentMediaItemIndex)
                surfaceLeftVideo(it.currentMediaItemIndex)
            }
            sync(force = false)
            publish()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) stateFlow.value = stateFlow.value.copy(mainVideoSize = videoSize)
        }

        override fun onRenderedFirstFrame() {
            if (!stateFlow.value.mainSurfaceReady) stateFlow.value = stateFlow.value.copy(mainSurfaceReady = true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                (overlays + sounds + tail).filterNotNull().forEach { it.playWhenReady = false }
            }
            publish()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = publish()

        override fun onPlayerError(error: PlaybackException) {
            // A file that fails mid-play never stops the app: the preview pauses and moves on to
            // the next item, so Play does not hit the same file again (the UI says a part is missing).
            val p = main ?: return
            val s = schedule ?: return
            allPlayers().forEach { it.playWhenReady = false }
            stateFlow.value = stateFlow.value.copy(unavailable = stateFlow.value.unavailable + 1)
            val next = (p.currentMediaItemIndex + 1).coerceAtMost(s.main.lastIndex)
            p.prepare()
            seekTo(s.main[next].startUs)
            publish()
        }
    }

    private inner class TailListener(
        private val player: PreviewPlayer,
    ) : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            applyTailSpeed()
            publish()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) stateFlow.value = stateFlow.value.copy(tailVideoSize = videoSize)
        }

        override fun onPlayerError(error: PlaybackException) {
            // An outgoing item that can't play is left out of that blend; MAIN keeps playing, and the
            // tail player is made ready again at the video's time, so the next transitions still blend.
            if (player !== tail) return
            stateFlow.value = stateFlow.value.copy(unavailable = stateFlow.value.unavailable + 1)
            val lane = schedule?.tailLane ?: return
            player.prepare()
            seekLane(player, lane.entries, mainTimeUs())
        }
    }

    private open inner class FollowerListener(
        private val player: PreviewPlayer,
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

    /** An overlay lane's listener: also keeps its picture size, which a picture in picture is cropped by. */
    private inner class OverlayListener(
        player: PreviewPlayer,
        private val index: Int,
    ) : FollowerListener(player) {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val now = stateFlow.value
            if (index !in now.overlaySizes.indices) return
            stateFlow.value = now.copy(overlaySizes = now.overlaySizes.toMutableList().also { it[index] = videoSize })
        }
    }

    private fun clipped(
        uri: Uri,
        startUs: Long,
        endUs: Long,
    ): Segment = Segment.Media(uri, startUs, endUs)

    private fun silence(durationUs: Long): Segment = Segment.Silence(durationUs)

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "Use the preview on the main thread" }

    companion object {
        const val TICK_MS = 100L

        /** How often the volumes follow the plan's gain envelopes while playing: 5 steps across 100 ms. */
        const val VOLUME_TICK_MS = 20L
        const val MAX_DRIFT_US = 200_000L

        /** How long before its next part a follower playing its silence is put, paused, at that part's start (see keepInStep). */
        const val PARK_AHEAD_US = 1_500_000L
        private const val WAKE_SLACK_MS = 20L
        private const val STILL_TIMEOUT_MS = 10_000L
        private const val STILL_SETTLE_MS = 300L

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
