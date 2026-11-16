package com.itsluminous.clearmovie.render.preview

import android.net.Uri
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A player that only records what it is told and reports what a test sets:
 * the stand-in for ExoPlayer that lets the engine's behaviour be checked
 * without decoding anything (cm-flow residual).
 */
class FakePreviewPlayer(
    val role: PlayerRole,
    val key: KeySpec?,
) : PreviewPlayer {
    override var playWhenReady = false
    override var playbackState = Player.STATE_IDLE
    override var currentMediaItemIndex = 0
    override var currentPosition = 0L
    override var volume = 1f
    override var speed = 1f
    var playlist: List<Segment> = emptyList()
    val listeners = mutableListOf<Player.Listener>()
    val seeks = mutableListOf<Pair<Int, Long>>()
    var surface: TextureView? = null
    var prepared = false
    var released = false

    override fun setSegments(segments: List<Segment>) {
        playlist = segments
    }

    override fun addListener(listener: Player.Listener) {
        listeners += listener
    }

    override fun setVideoTextureView(view: TextureView) {
        surface = view
    }

    override fun clearVideoTextureView(view: TextureView) {
        if (surface === view) surface = null
    }

    override fun prepare() {
        prepared = true
        playbackState = Player.STATE_READY
    }

    override fun seekTo(
        index: Int,
        positionMs: Long,
    ) {
        seeks += index to positionMs
        currentMediaItemIndex = index
        currentPosition = positionMs
    }

    override fun release() {
        released = true
    }

    /** As if playback crossed into item [index]. */
    fun transitionTo(index: Int) {
        currentMediaItemIndex = index
        currentPosition = 0
        listeners.forEach { it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }

    fun fail() = listeners.forEach { it.onPlayerError(PlaybackException("test", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)) }

    fun firstFrame() = listeners.forEach { it.onRenderedFirstFrame() }
}

/** [LayeredPreviewEngine] driven with fake players: playlists, seeks with speed, follow and drift, volume, errors. */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LayeredPreviewEngineTest {
    private val made = mutableListOf<FakePreviewPlayer>()
    private val scope = TestScope(StandardTestDispatcher())
    private val engine =
        LayeredPreviewEngine(ApplicationProvider.getApplicationContext(), scope) { role, key ->
            FakePreviewPlayer(role, key).also { made += it }
        }
    private val resolver =
        MediaResolver { ref ->
            Uri.parse("content://test/${(ref as? MediaRef.Store)?.uri?.substringAfterLast('/') ?: ref.toString()}")
        }

    // Preset project: intro 6.5 s, a 3 s photo, a 4 s video trimmed to 1..3 s at 2x (1 s), a 6 s video, the 6 s spoken ending.
    private val project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("e"))
            .addToMain(
                listOf(
                    Fixtures.photo("p1"),
                    VideoClip(
                        ClipId("v2"),
                        Fixtures.media("v2"),
                        sourceDurationMs = 4000,
                        trimStartMs = 1000,
                        trimEndMs = 3000,
                        speed = Speed.X2,
                    ),
                    Fixtures.video("v3", 6000),
                ),
            )
    private val schedule = PreviewSchedule.of(RenderPlanBuilder(Fixtures.preset).build(project))

    /** The players of the latest load. */
    private fun live() = made.filter { !it.released }

    private fun main() = live().single { it.role == PlayerRole.MAIN }

    private fun overlays() = live().filter { it.role == PlayerRole.OVERLAY }

    private fun sounds() = live().filter { it.role == PlayerRole.SOUND }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        scope.runCurrent()
    }

    @Test
    fun `MAIN plays each video's trimmed part, and silence for photos, the intro file aside`() {
        engine.load(schedule, resolver, startUs = 0, play = false)
        val segments = main().playlist
        assertThat(segments).hasSize(schedule.main.size)
        val v2 = schedule.main.indexOfFirst { (it.content as? PreviewSchedule.MainContent.Video)?.media == Fixtures.media("v2") }
        val media = segments[v2] as Segment.Media
        // 1 s of timeline at 2x plays 2 s of the file: 1.0 s to 3.0 s.
        assertThat(media.startUs).isEqualTo(1_000_000)
        assertThat(media.endUs).isEqualTo(3_000_000)
        val photo = schedule.main.indexOfFirst { it.content is PreviewSchedule.MainContent.Still && it.durationUs == 3_000_000L }
        assertThat(segments[photo]).isEqualTo(Segment.Silence(3_000_000))
        assertThat(main().prepared).isTrue()
        // The frame row gets a keyed player of its own; the music and the spoken ending play in sound players.
        assertThat(overlays()).hasSize(1)
        assertThat(overlays().single().key).isEqualTo(Fixtures.preset.frame!!.key)
        assertThat(sounds()).hasSize(schedule.sounds.size)
        assertThat(engine.state.value.durationUs).isEqualTo(schedule.durationUs)
    }

    @Test
    fun `a seek lands on the item and file position the model says, at the item's speed`() {
        engine.load(schedule, resolver, startUs = 0, play = false)
        val v2 = project.mainSlot(ClipId("v2"))!!
        // 0.5 s into v2 at 2x: 1 s into the clipped part (2.0 s of the file).
        engine.seekTo((v2.startMs + 500) * 1000)
        val index = schedule.mainIndexAt((v2.startMs + 500) * 1000)
        assertThat(main().seeks.last()).isEqualTo(index to 1000L)
        assertThat(main().speed).isEqualTo(2f)
        assertThat(engine.positionUs).isEqualTo((v2.startMs + 500) * 1000)
        // Back on a normal-speed item the speed is reset.
        val v3 = project.mainSlot(ClipId("v3"))!!
        engine.seekTo((v3.startMs + 2000) * 1000)
        assertThat(main().speed).isEqualTo(1f)
        assertThat(main().seeks.last().second).isEqualTo(2000L)
        // The followers are moved to the same moment.
        assertThat(overlays().single().seeks).isNotEmpty()
    }

    @Test
    fun `play and pause reach every player, and a follower that drifts is put back in step`() {
        engine.load(schedule, resolver, startUs = 0, play = false)
        engine.play()
        assertThat(made.all { it.playWhenReady }).isTrue()
        assertThat(engine.state.value.playing).isTrue()
        // MAIN is 3 s into the photo; the frame player has fallen 0.5 s behind.
        val p1 = project.mainSlot(ClipId("p1"))!!
        val i = schedule.mainIndexAt(p1.startMs * 1000)
        main().currentMediaItemIndex = i
        main().currentPosition = 1000
        val frame = overlays().single()
        val lane = schedule.overlays.single()
        val laneIndex = lane.indexAt((p1.startMs + 1000) * 1000)
        frame.currentMediaItemIndex = laneIndex
        frame.currentPosition = (p1.startMs + 1000 - lane.entries[laneIndex].startUs / 1000) - 500
        val seeksBefore = frame.seeks.size
        scope.advanceTimeBy(LayeredPreviewEngine.TICK_MS + 1)
        idle()
        assertThat(frame.seeks.size).isGreaterThan(seeksBefore)
        engine.pause()
        // Paused: MAIN and the sound stop; the frame plays on only to put its picture up (see below).
        assertThat(main().playWhenReady).isFalse()
        assertThat(sounds().none { it.playWhenReady }).isTrue()
        assertThat(engine.state.value.playing).isFalse()
    }

    @Test
    fun `the music is lowered under a video's own sound, as in the export`() {
        engine.load(schedule, resolver, startUs = 0, play = false)
        val music = schedule.sounds.indexOfFirst { it.role.name == "MUSIC" }
        val v3 = project.mainSlot(ClipId("v3"))!!
        val p1 = project.mainSlot(ClipId("p1"))!!
        engine.seekTo((p1.startMs + 1500) * 1000)
        engine.pause()
        val underPhoto = sounds()[music].volume
        engine.seekTo((v3.startMs + 3000) * 1000)
        engine.pause()
        val underVideo = sounds()[music].volume
        assertThat(underVideo).isLessThan(underPhoto)
        assertThat(LayeredPreviewEngine.gainOf(schedule.sounds[music].gainDbAt((v3.startMs + 3000) * 1000))).isWithin(1e-4f).of(underVideo)
    }

    @Test
    fun `a file that fails while playing pauses and moves on to the next item, and says so`() {
        engine.load(schedule, resolver, startUs = 0, play = true)
        val p1 = project.mainSlot(ClipId("p1"))!!
        engine.seekTo(p1.startMs * 1000)
        val failing = schedule.mainIndexAt(p1.startMs * 1000)
        main().fail()
        assertThat(engine.state.value.unavailable).isEqualTo(1)
        assertThat(main().playWhenReady).isFalse()
        assertThat(sounds().none { it.playWhenReady }).isTrue()
        assertThat(main().seeks.last().first).isEqualTo(failing + 1)
    }

    @Test
    fun `media that can't be found plays as silence and is counted`() {
        val broken =
            MediaResolver { ref ->
                if (ref ==
                    Fixtures.media("v3")
                ) {
                    throw IllegalArgumentException("gone")
                } else {
                    resolver.resolve(ref)
                }
            }
        engine.load(schedule, broken, startUs = 0, play = false)
        assertThat(engine.state.value.unavailable).isEqualTo(1)
        val v3 = schedule.main.indexOfFirst { (it.content as? PreviewSchedule.MainContent.Video)?.media == Fixtures.media("v3") }
        assertThat(main().playlist[v3]).isInstanceOf(Segment.Silence::class.java)
    }

    @Test
    fun `a paused seek plays the frame player only until its first frame is on screen`() {
        engine.load(schedule, resolver, startUs = 0, play = false)
        val p1 = project.mainSlot(ClipId("p1"))!!
        engine.seekTo((p1.startMs + 500) * 1000)
        val frame = overlays().single()
        assertThat(frame.playWhenReady).isTrue()
        assertThat(main().playWhenReady).isFalse()
        frame.firstFrame()
        scope.advanceTimeBy(400)
        idle()
        assertThat(frame.playWhenReady).isFalse()
    }

    @Test
    fun `surfaces attach to the players, a new load makes new players, and release frees them all`() {
        val view = TextureView(ApplicationProvider.getApplicationContext())
        engine.setMainSurface(view)
        engine.load(schedule, resolver, startUs = 0, play = false)
        assertThat(main().surface).isSameInstanceAs(view)
        val first = made.toList()
        engine.load(schedule, resolver, startUs = 2_000_000, play = false)
        assertThat(first.all { it.released }).isTrue()
        assertThat(main().surface).isSameInstanceAs(view)
        assertThat(engine.state.value.loads).isEqualTo(2)
        engine.release()
        assertThat(made.all { it.released }).isTrue()
    }

    @Test
    fun `the position reported follows MAIN's item and position, at its speed`() {
        engine.load(schedule, resolver, startUs = 0, play = false)
        val v2 = project.mainSlot(ClipId("v2"))!!
        val i = schedule.mainIndexAt(v2.startMs * 1000)
        main().transitionTo(i)
        main().currentPosition = 1000 // 1 s of file at 2x = 0.5 s of video
        assertThat(engine.positionUs).isEqualTo((v2.startMs + 500) * 1000)
    }
}
