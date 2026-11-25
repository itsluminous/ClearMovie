package com.itsluminous.clearmovie.data.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.voice.HeardWord
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.roundToInt

/**
 * The voice pack's engines, in the app's own process (cm-voice-choice):
 * Vosk recognizes speech and Kokoro (through sherpa-onnx) speaks. Their
 * native libraries come from the pack, never the APK, and are loaded by path
 * once the pack's files were checked against the app's manifest. Each engine
 * is loaded for one job and released after it: together they would take
 * about a gigabyte.
 */
class VoiceEngine(
    private val store: VoicePackStore,
) {
    /** Whether the engine loaded on this phone: null until it was tried. */
    val works: Boolean? get() = tried

    /** Loads the pack's native libraries; false when this phone cannot (they are missing, or the wrong kind). */
    fun load(): Boolean =
        synchronized(LOCK) {
            if (loaded) return true
            val lib = store.libDir
            runCatching {
                System.load(File(lib, "libonnxruntime.so").path)
                System.load(File(lib, "libsherpa-onnx-jni.so").path)
                // JNA (Vosk's bridge) finds libvosk.so here.
                System.setProperty("jna.library.path", lib.path)
                LibVosk.setLogLevel(LogLevel.WARNINGS)
            }.onFailure { Log.w(TAG, "The voice pack's engine could not be loaded", it) }
                .isSuccess
                .also {
                    loaded = it
                    tried = it
                }
        }

    /**
     * The words in [pcm] (16-bit mono at [rate]) with their times, by the recognizer for [language].
     * [progress] gets the share of the sound heard so far, from 0 once the recognizer is ready to 1;
     * [onModel] the time the recognizer took to load.
     */
    fun recognize(
        pcm: ShortArray,
        rate: Int,
        language: SpokenLanguage,
        progress: (Float) -> Unit = {},
        onModel: (Long) -> Unit = {},
    ): List<HeardWord> {
        check(load()) { "engine not loaded" }
        val words = mutableListOf<HeardWord>()
        val t0 = System.nanoTime()
        store.prepareAsr(language)
        Model(store.asrDir(language).path).use { model ->
            // A Stop asked for while the model loaded takes effect here.
            progress(Float.NaN)
            Recognizer(model, rate.toFloat()).use { rec ->
                onModel((System.nanoTime() - t0) / 1_000_000)
                progress(0f)
                rec.setWords(true)

                fun take(json: String) {
                    val arr = JSONObject(json).optJSONArray("result") ?: return
                    for (i in 0 until arr.length()) {
                        val w = arr.getJSONObject(i)
                        words +=
                            HeardWord(
                                w.optString("word"),
                                (w.optDouble("start") * 1000).roundToInt().toLong(),
                                (w.optDouble("end") * 1000).roundToInt().toLong(),
                            )
                    }
                }
                var i = 0
                val chunk = rate / 4
                while (i < pcm.size) {
                    val n = minOf(chunk, pcm.size - i)
                    if (rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) take(rec.result)
                    i += n
                    progress(i.toFloat() / pcm.size)
                }
                take(rec.finalResult)
            }
        }
        return words
    }

    /** Opens the voices for one job; release it after. */
    fun speaker(): Speaker {
        check(load()) { "engine not loaded" }
        return Speaker(store.ttsDir, store.voicesFile)
    }

    /** Kokoro, speaking phonemes in one of the pack's voices. */
    class Speaker(
        dir: File,
        /** The voices file: the pack's, or Hindi HD's with two more Indian voices (cm-hindi-voices). */
        voicesFile: File = File(dir, "voices.bin"),
    ) : AutoCloseable {
        private val tts =
            OfflineTts(
                null,
                OfflineTtsConfig(
                    model =
                        OfflineTtsModelConfig(
                            kokoro =
                                OfflineTtsKokoroModelConfig(
                                    model = File(dir, "model.int8.onnx").path,
                                    voices = voicesFile.path,
                                    tokens = File(dir, "tokens.txt").path,
                                    dataDir = "",
                                    lexicon = File(dir, LEXICON).path,
                                    lang = "",
                                    dictDir = "",
                                    lengthScale = 1f,
                                ),
                            numThreads = THREADS,
                            debug = false,
                            provider = "cpu",
                        ),
                ),
            )

        /** The engine's sample rate (24 kHz). */
        val rate: Int get() = tts.sampleRate()

        /** [phonemes] spoken by [speaker] at [speed], as 16-bit samples at [rate]. */
        fun speak(
            phonemes: String,
            speaker: Int,
            speed: Float,
        ): ShortArray {
            if (phonemes.isBlank()) return ShortArray(0)
            val config = GenerationConfig()
            config.sid = speaker
            config.speed = speed
            config.phonemeCodepoints = arrayOf(phonemes.codePoints().toArray())
            val samples = tts.generateWithConfig("", config).samples
            return ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort() }
        }

        /**
         * [phonemes] spoken in pieces of at most [MAX_TOKENS] phonemes, split at a
         * space or a mark, since the engine takes a limited context at a time.
         */
        fun speakLong(
            phonemes: String,
            speaker: Int,
            speed: Float,
        ): ShortArray {
            val parts = mutableListOf<String>()
            var rest = phonemes.trim()
            while (rest.codePointCount(0, rest.length) > MAX_TOKENS) {
                val limit = rest.offsetByCodePoints(0, MAX_TOKENS)
                val cut = maxOf(rest.lastIndexOfAny(charArrayOf(',', '.', '!', '?', ';', ':'), limit), rest.lastIndexOf(' ', limit))
                val at = if (cut > 0) cut + 1 else limit
                parts += rest.substring(0, at).trim()
                rest = rest.substring(at).trim()
            }
            if (rest.isNotEmpty()) parts += rest
            val pieces = parts.map { speak(it, speaker, speed) }
            return ShortArray(pieces.sumOf { it.size }).also { out ->
                var at = 0
                pieces.forEach { p ->
                    p.copyInto(out, at)
                    at += p.size
                }
            }
        }

        override fun close() = tts.release()
    }

    companion object {
        private const val TAG = "VoiceEngine"
        private const val THREADS = 2

        /** Phonemes per engine call: Kokoro's context is 510 tokens. */
        const val MAX_TOKENS = 400
        const val LEXICON = "lexicon-gb-en.txt"
        private val LOCK = Any()

        @Volatile
        private var loaded = false

        @Volatile
        private var tried: Boolean? = null

        /** The pack's English dictionary (word to phonemes) and the engine's phoneme set. */
        fun lexicon(dir: File): Map<String, String> {
            val map = HashMap<String, String>(200_000)
            File(dir, LEXICON).forEachLine { line ->
                val at = line.indexOf(' ')
                if (at > 0) map.putIfAbsent(line.substring(0, at).lowercase(), line.substring(at + 1).replace(" ", ""))
            }
            return map
        }

        fun tokens(dir: File): Set<String> =
            File(dir, "tokens.txt").readLines().mapNotNull { l -> l.substringBeforeLast(' ', "").takeIf { it.isNotEmpty() } }.toSet()
    }
}

/** Decodes a sound file to 16-bit mono samples at [rate] (for the recognizer). */
internal object AudioPcm {
    /** Null when the file is longer than [maxMs]. */
    fun decode(
        file: File,
        rate: Int,
        maxMs: Long,
    ): ShortArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track =
                (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") ==
                        true
                }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > maxMs * 1000) return null
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec.configure(format, null, null, 0)
            codec.start()
            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mono = ShortArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            try {
                while (true) {
                    // A decoder that never ends its stream must not hang the voice tools.
                    check(idle < MAX_IDLE) { "the decoder stopped" }
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val n = extractor.readSampleData(requireNotNull(codec.getInputBuffer(i)), 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o < 0 && inputDone) {
                        idle++
                    } else if (o >= 0) {
                        idle = 0
                    }
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        srcRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        val buf = requireNotNull(codec.getOutputBuffer(o)).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = info.size / 2 / channels
                        for (f in 0 until frames) {
                            var sum = 0
                            for (c in 0 until channels) sum += buf.get(f * channels + c)
                            mono.add((sum / channels).toShort())
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return resample(mono.toArray(), srcRate, rate)
        } finally {
            extractor.release()
        }
    }

    /** Linear resampling, enough for speech recognition. */
    fun resample(
        s: ShortArray,
        from: Int,
        to: Int,
    ): ShortArray {
        if (from == to || s.isEmpty()) return s
        val n = (s.size.toLong() * to / from).toInt()
        return ShortArray(n) {
            val x = it.toDouble() * from / to
            val i = x.toInt().coerceAtMost(s.size - 1)
            val j = (i + 1).coerceAtMost(s.size - 1)
            (s[i] + (s[j] - s[i]) * (x - i)).roundToInt().toShort()
        }
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE = 500

    private class ShortArrayBuilder {
        private var a = ShortArray(1 shl 16)
        private var n = 0

        fun add(v: Short) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }

        fun toArray(): ShortArray = a.copyOf(n)
    }
}
