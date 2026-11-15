package com.itsluminous.clearmovie.render

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.transformer.CompositionPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.clearmovie.render.media3.CompositionFactory
import com.itsluminous.clearmovie.render.media3.MediaResolver
import com.itsluminous.clearmovie.render.media3.PreviewPlayer
import com.itsluminous.clearmovie.render.plan.GainEnvelope
import com.itsluminous.clearmovie.render.plan.RenderPlan
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the real preview player (`PreviewPlayer`) into an ImageReader, and
 * tags every shown frame with its composition time from the player's own
 * frame-metadata callback (release time -> presentation time), so a preview
 * frame can be matched with the exported frame at exactly the same time.
 */
@UnstableApi
class PreviewProbe(
    private val plan: RenderPlan,
    private val resolver: MediaResolver,
    private val width: Int = 960,
    private val height: Int = 540,
    private val gain: ((GainEnvelope) -> androidx.media3.common.audio.AudioProcessor)? = null,
    private val composition: androidx.media3.transformer.Composition? = null,
    private val singleInput: Boolean = false,
) : AutoCloseable {
    class Frame(
        val timeUs: Long,
        val bitmap: Bitmap,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val imageThread = HandlerThread("preview-images").apply { start() }
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
    private val ptsByReleaseNs = ConcurrentHashMap<Long, Long>()
    private val frames = java.util.Collections.synchronizedList(mutableListOf<Frame>())
    val frameCount = AtomicInteger()
    private val lastImageTs = java.util.concurrent.atomic.AtomicLong()
    private val player = AtomicReference<CompositionPlayer>()
    val error = AtomicReference<PlaybackException?>()

    init {
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                lastImageTs.set(image.timestamp)
                val pts = ptsByReleaseNs[image.timestamp] ?: -1L
                frames += Frame(pts, Bitmap.createBitmap(bmp, 0, 0, image.width, image.height))
                bmp.recycle()
                frameCount.incrementAndGet()
            }
        }, Handler(imageThread.looper))
        val ready = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val factory = gain?.let { CompositionFactory(RenderHarness.context, resolver, it) } ?: CompositionFactory(RenderHarness.context, resolver)
            val p = if (singleInput) CompositionPlayer.Builder(RenderHarness.context).build() else PreviewPlayer.create(RenderHarness.context)
            p.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.countDown()
                    }

                    override fun onPlayerError(e: PlaybackException) {
                        error.set(e)
                        ready.countDown()
                    }
                },
            )
            p.setVideoFrameMetadataListener(
                VideoFrameMetadataListener { pts, releaseNs, _, _ -> if (releaseNs > 0) ptsByReleaseNs[releaseNs] = pts },
            )
            p.setVideoSurface(reader.surface, Size(width, height))
            p.setComposition(composition ?: factory.build(plan))
            p.prepare()
            player.set(p)
        }
        check(ready.await(60, TimeUnit.SECONDS)) { "Preview never became ready" }
        error.get()?.let { throw AssertionError("Preview failed: ${it.errorCodeName}", it) }
    }

    fun <T> onMain(block: (CompositionPlayer) -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block(player.get()) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    /** Seeks while paused; returns ms until a new frame was shown, or -1 if none within [waitMs]. */
    fun pausedSeek(
        timeUs: Long,
        waitMs: Long = 3000,
    ): Long {
        val before = frameCount.get()
        val start = SystemClock.elapsedRealtime()
        onMain { it.seekTo(timeUs / 1000) }
        while (SystemClock.elapsedRealtime() - start < waitMs) {
            if (frameCount.get() > before) return SystemClock.elapsedRealtime() - start
            SystemClock.sleep(10)
        }
        return -1
    }

    fun lastFrame(): Frame? = synchronized(frames) { frames.lastOrNull() }

    /**
     * Plays from [fromUs] until every time in [timesUs] has been passed (or
     * [timeoutMs]), then pauses; returns, per requested time, the shown frame
     * whose composition time is closest to it.
     */
    fun playAndCapture(
        fromUs: Long,
        timesUs: List<Long>,
        timeoutMs: Long = 60_000,
    ): List<Frame?> {
        frames.clear()
        onMain {
            it.seekTo(fromUs / 1000)
            it.play()
        }
        val start = SystemClock.elapsedRealtime()
        val last = timesUs.max()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val newest = synchronized(frames) { frames.maxOfOrNull { it.timeUs } } ?: -1
            if (newest >= last + 100_000) break
            if (error.get() != null) break
            SystemClock.sleep(20)
        }
        val position = onMain { it.currentPosition }
        val state = onMain { it.playbackState }
        onMain { it.pause() }
        val all = synchronized(frames) { frames.toList() }
        RenderHarness.report(
            "probe play from $fromUs: ${all.size} frames, ${all.count { it.timeUs >= 0 }} tagged, metadata entries ${ptsByReleaseNs.size}, " +
                "position $position ms, state $state, first image ts ${lastImageTs.get()}, sample metadata ${ptsByReleaseNs.entries.take(3)}",
        )
        val captured = synchronized(frames) { frames.filter { it.timeUs >= 0 }.toList() }
        return timesUs.map { t -> captured.minByOrNull { kotlin.math.abs(it.timeUs - t) } }
    }

    override fun close() {
        instrumentation.runOnMainSync { player.get()?.release() }
        reader.close()
        imageThread.quitSafely()
    }
}
