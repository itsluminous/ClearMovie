package com.itsluminous.clearmovie.data.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.itsluminous.clearmovie.domain.record.RecordingSync
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One voice recording: the microphone's sound, encoded as AAC into an M4A
 * file ([target]) on a thread of its own while it is captured.
 *
 * The microphone starts at once ([start]), before the picture moves, and
 * what it hears is held back until the editor says when the first picture
 * showed ([startAt]); the frames before that moment are dropped, so the
 * file begins with what was said as the picture started (see
 * [RecordingSync]). [finish] ends it at a moment (the tap on Stop, the end
 * of the video, an interruption), keeping every frame captured up to then.
 *
 * Only [target] is written; the caller moves it to its final name.
 */
class VoiceRecorder(
    private val mic: MicSource,
    private val target: File,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** What a recording came to. */
    data class Result(
        /** Frames written to the file. */
        val frames: Long,
        val sampleRate: Int,
        /** Whether the file is complete and playable. */
        val ok: Boolean,
        /** How the capture timestamps were known: from the microphone, or estimated from arrival times. */
        val timestamps: String,
    ) {
        val durationMs: Long get() = RecordingSync.msOf(frames, sampleRate)
    }

    @Volatile
    private var startNs: Long? = null

    @Volatile
    private var endNs: Long? = null

    @Volatile
    private var running = false

    @Volatile
    private var result: Result? = null
    private var thread: Thread? = null

    /** True once the microphone failed while recording, or Android silenced it (a call). */
    val interrupted: Boolean get() = micFailed || mic.silenced

    @Volatile
    private var micFailed = false

    private val lock = Any()
    private var cancelled = false

    /**
     * Opens the microphone and starts capturing. False when it cannot be
     * opened, or the recording was cancelled first (nothing is written then,
     * and the microphone stays closed).
     */
    fun start(): Boolean =
        synchronized(lock) {
            check(thread == null) { "A recorder records once" }
            if (cancelled) return false
            if (!mic.start()) {
                mic.release()
                return false
            }
            running = true
            thread =
                Thread({ record() }, "voice-recorder").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
            true
        }

    /** The moment, on the monotonic clock, the first picture showed: the file starts with the sound captured then. */
    fun startAt(wallNs: Long) {
        if (startNs == null) startNs = wallNs
    }

    /**
     * Ends the recording at [wallNs] (by default now) and waits for the file
     * to be written. Frames captured up to that moment are kept, even those
     * still in the microphone's buffer. When [startAt] was never called the
     * file starts at [fallbackStartNs], or at the first frame.
     */
    fun finish(
        wallNs: Long = nanoTime(),
        fallbackStartNs: Long? = null,
    ): Result {
        if (startNs == null) startNs = fallbackStartNs ?: Long.MIN_VALUE
        endNs = wallNs
        val t = thread
        if (t != null) {
            t.join(JOIN_MS)
            if (t.isAlive) {
                running = false
                t.join(JOIN_MS)
            }
        }
        return result ?: Result(0, mic.sampleRate, ok = false, timestamps = "none")
    }

    /** Stops at once (or keeps the microphone from opening, if [start] has not run yet) and deletes what was written. */
    fun cancel() {
        val t =
            synchronized(lock) {
                cancelled = true
                running = false
                endNs = nanoTime()
                thread
            }
        t?.join(JOIN_MS)
        target.delete()
    }

    // ---------------------------------------------------------------- the recording thread

    private val rate = mic.sampleRate

    /** Frame and capture time from the microphone, or the earliest-arrival estimate. */
    private var anchorFrame = 0L
    private var anchorNs = 0L
    private var haveAnchor = false
    private var micStamps = false
    private var bestArrival = Long.MAX_VALUE

    private fun record() {
        val buffer = ShortArray(CHUNK_FRAMES)
        val pending = ArrayDeque<Pair<Long, ShortArray>>()
        var read = 0L
        var keepFrom: Long? = null
        val encoder = AacEncoder(target, rate)
        var ok = false
        try {
            encoder.open()
            while (running) {
                val n = mic.read(buffer)
                if (n < 0) {
                    micFailed = true
                    break
                }
                if (n == 0) continue
                val first = read
                read += n
                noteArrival(read, nanoTime())
                val chunk = buffer.copyOf(n)
                val start = startNs
                if (keepFrom == null && start != null && haveAnchor) {
                    keepFrom = if (start == Long.MIN_VALUE) 0 else frameAt(start) + ENCODER_DELAY_FRAMES
                }
                val end = endNs?.let { frameAt(it) + ENCODER_DELAY_FRAMES }
                val from = keepFrom
                if (from == null) {
                    pending.addLast(first to chunk)
                    // At most a few seconds are held back: the countdown and the player's start.
                    while (pending.size * CHUNK_FRAMES > MAX_PENDING_FRAMES) pending.removeFirst()
                } else {
                    while (pending.isNotEmpty()) {
                        val (f, c) = pending.removeFirst()
                        encoder.feed(f, c, from, end)
                    }
                    encoder.feed(first, chunk, from, end)
                }
                if (end != null && read >= end) break
                // A wrong timestamp must never keep the recording going long after Stop.
                if (endNs?.let { nanoTime() - it > LATE_STOP_NS } == true) break
                if (mic.silenced) break
            }
            // Stopped before the start was known (Stop in the first moment): keep what can be placed.
            val start = startNs
            if (keepFrom == null && start != null) {
                val from = if (start == Long.MIN_VALUE || !haveAnchor) 0 else frameAt(start) + ENCODER_DELAY_FRAMES
                val end = endNs?.let { if (haveAnchor) frameAt(it) + ENCODER_DELAY_FRAMES else null }
                for ((f, c) in pending) encoder.feed(f, c, from, end)
            }
            ok = encoder.close()
        } catch (e: IOException) {
            Log.w(TAG, "Recording failed", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Recording failed", e)
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "Recording failed", e)
        } finally {
            encoder.release()
            mic.release()
        }
        val timestamps = if (micStamps) "microphone" else "arrival"
        Log.i(TAG, "Recorded ${encoder.written} frames at $rate Hz; timestamps from $timestamps; ok=$ok")
        result = Result(if (ok) encoder.written else 0, rate, ok && encoder.written > 0, timestamps)
    }

    private fun noteArrival(
        framesRead: Long,
        now: Long,
    ) {
        val stamp = mic.timestamp()
        if (stamp != null) {
            anchorFrame = stamp.first
            anchorNs = stamp.second
            haveAnchor = true
            micStamps = true
            return
        }
        if (micStamps) return
        // No timestamp from the microphone: the last frame read reached it no later than now. The
        // earliest such estimate (the read that waited least) is the closest.
        val zero = now - (framesRead * NANOS / rate)
        if (zero < bestArrival) {
            bestArrival = zero
            anchorFrame = 0
            anchorNs = zero
            haveAnchor = true
        }
    }

    private fun frameAt(wallNs: Long): Long = RecordingSync.frameAt(wallNs, anchorFrame, anchorNs, rate)

    /** AAC-LC into an M4A file, fed frames in order. */
    private class AacEncoder(
        private val file: File,
        private val rate: Int,
    ) {
        private var codec: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var track = -1
        private val info = MediaCodec.BufferInfo()
        var written = 0L
            private set
        private var muxed = 0

        fun open() {
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 2 * 4)
                }
            file.parentFile?.mkdirs()
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec =
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
        }

        /** Encodes the frames of [chunk] (which starts at frame [first]) from [from] and before [end]. */
        fun feed(
            first: Long,
            chunk: ShortArray,
            from: Long,
            end: Long?,
        ) {
            val skip = (from - first).coerceIn(0, chunk.size.toLong()).toInt()
            val stop = (if (end == null) chunk.size.toLong() else (end - first)).coerceIn(skip.toLong(), chunk.size.toLong()).toInt()
            if (stop <= skip) return
            val bytes = ByteBuffer.allocate((stop - skip) * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in skip until stop) bytes.putShort(chunk[i])
            bytes.flip()
            val c = requireNotNull(codec)
            while (bytes.hasRemaining()) {
                val index = c.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val input = requireNotNull(c.getInputBuffer(index))
                    input.clear()
                    val n = minOf(input.remaining(), bytes.remaining()) and 1.inv()
                    val slice = bytes.duplicate()
                    slice.limit(slice.position() + n)
                    input.put(slice)
                    bytes.position(bytes.position() + n)
                    c.queueInputBuffer(index, 0, n, RecordingSync.ptsUs(written, rate), 0)
                    written += n / 2
                }
                drain(endOfStream = false)
            }
        }

        /** Ends the stream and writes the file; true when it is complete. */
        fun close(): Boolean {
            val c = codec ?: return false
            var queued = false
            repeat(EOS_TRIES) {
                if (!queued) {
                    val index = c.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        c.queueInputBuffer(index, 0, 0, RecordingSync.ptsUs(written, rate), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true
                    }
                }
            }
            if (!queued) return false
            val done = drain(endOfStream = true)
            val m = muxer ?: return false
            if (track < 0 || muxed == 0) return false
            m.stop()
            return done
        }

        /** Moves encoded frames to the file; with [endOfStream], until the encoder says it is done. */
        private fun drain(endOfStream: Boolean): Boolean {
            val c = codec ?: return false
            var idle = 0
            while (true) {
                val index = c.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream || ++idle > EOS_TRIES) return false
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val m = requireNotNull(muxer)
                        track = m.addTrack(c.outputFormat)
                        m.start()
                    }

                    index >= 0 -> {
                        val out = requireNotNull(c.getOutputBuffer(index))
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && track >= 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            requireNotNull(muxer).writeSampleData(track, out, info)
                            muxed++
                        }
                        c.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        }

        fun release() {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            codec = null
            muxer?.let { runCatching { it.release() } }
            muxer = null
        }
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val NANOS = 1_000_000_000L
        const val CHUNK_FRAMES = 1024
        private const val MAX_PENDING_FRAMES = 10 * AudioRecordMic.SAMPLE_RATE
        private const val BIT_RATE = 96_000
        private const val TIMEOUT_US = 10_000L
        private const val EOS_TRIES = 200
        private const val JOIN_MS = 5_000L
        private const val LATE_STOP_NS = 1_500_000_000L

        /**
         * Frames the AAC encoder puts before the sound (its priming), which
         * players then play as silence: the file starts this many frames
         * later, so the words still land on their picture. Measured on the
         * export (see docs/architecture.md, Voice recording).
         */
        const val ENCODER_DELAY_FRAMES = 2048L
    }
}
