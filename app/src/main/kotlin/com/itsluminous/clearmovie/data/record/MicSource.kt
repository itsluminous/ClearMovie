package com.itsluminous.clearmovie.data.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Where a voice recording's sound comes from: the phone's microphone
 * ([AudioRecordMic]), or a test's own source. Mono 16-bit PCM at
 * [sampleRate]. [read] blocks until frames are there, and is called from
 * one recording thread only.
 */
interface MicSource {
    val sampleRate: Int

    /** Starts capturing. False when the microphone cannot be opened (no permission, or another app holds it). */
    fun start(): Boolean

    /** Reads up to `buffer.size` frames; how many, or a negative number when the microphone failed. */
    fun read(buffer: ShortArray): Int

    /**
     * A capture timestamp: a frame number and the moment, on the monotonic
     * clock (`System.nanoTime`), that frame reached the microphone. Null when
     * the microphone gives none (the recorder then estimates it from when
     * frames arrive).
     */
    fun timestamp(): Pair<Long, Long>?

    /** True once Android silenced this recording (a phone call took the microphone). */
    val silenced: Boolean

    fun release()
}

/** Makes a [MicSource] for each recording. */
fun interface MicSources {
    fun create(): MicSource
}

/** The phone's microphone, through [AudioRecord]. Opened only when the user started a recording on screen. */
class AudioRecordMic(
    private val context: Context,
) : MicSource {
    override val sampleRate: Int = SAMPLE_RATE
    private var record: AudioRecord? = null
    private var callback: AudioManager.AudioRecordingCallback? = null
    private val stamp = AudioTimestamp()

    @Volatile
    override var silenced: Boolean = false
        private set

    override fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min * 4, SAMPLE_RATE / 2 * 2),
                )
            } catch (e: IllegalArgumentException) {
                return false
            } catch (e: SecurityException) {
                return false
            }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        record = r
        val audio = context.getSystemService(AudioManager::class.java)
        val cb =
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId } ?: return
                    if (mine.isClientSilenced) silenced = true
                }
            }
        audio?.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
        callback = cb
        try {
            r.startRecording()
        } catch (e: IllegalStateException) {
            release()
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            release()
            return false
        }
        return true
    }

    override fun read(buffer: ShortArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    override fun timestamp(): Pair<Long, Long>? {
        val r = record ?: return null
        return if (r.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            stamp.framePosition to stamp.nanoTime
        } else {
            null
        }
    }

    override fun release() {
        callback?.let { context.getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(it) }
        callback = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    companion object {
        /** The one rate every Android microphone supports. */
        const val SAMPLE_RATE = 44_100
    }
}
