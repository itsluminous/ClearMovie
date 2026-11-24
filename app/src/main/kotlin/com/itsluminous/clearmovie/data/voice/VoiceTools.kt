package com.itsluminous.clearmovie.data.voice

import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.record.VoiceRecorder
import com.itsluminous.clearmovie.data.record.VoiceRecordings
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.voice.HeardWord
import com.itsluminous.clearmovie.domain.voice.Phonemizer
import com.itsluminous.clearmovie.domain.voice.PhraseFit
import com.itsluminous.clearmovie.domain.voice.Phrases
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import java.io.InputStream

/** A voice made for a voice item: its file, its phrases with their times, and its length. */
data class SpokenVoice(
    val media: MediaRef.Copied,
    val phrases: List<VoicePhrase>,
    val lengthMs: Long,
    /** Words not in the dictionary, read by rule (names): the user may want to check them. */
    val byRule: List<String>,
    /** The phrases (by index) that still ran into the next one at the fastest speed, so their end was cut. */
    val cut: List<Int> = emptyList(),
)

/** Why a voice could not be made. */
class VoiceFailure(
    val reason: Reason,
) : Exception(reason.name) {
    enum class Reason { NO_PACK, NO_ENGINE, NO_WORDS, TOO_LONG, FAILED }
}

/**
 * Speech to words and words to a voice (cm-voice-choice), for the editor.
 * Everything runs on the phone; nothing is sent anywhere (the app has no
 * internet permission). Call off the main thread.
 */
interface VoiceTools {
    /** The pack this version of the app takes, or null when it has none. */
    val manifest: VoicePackManifest?

    /** Whether the phone can run the voice pack at all (its processor type has an engine). */
    val supported: Boolean

    /** Whether the pack's engine loaded on this phone: null until it was tried (after the pack was added). */
    val engineWorks: Boolean? get() = null

    /** Loads the pack's engine now; false when this phone cannot run it. */
    fun checkEngine(): Boolean = engineWorks != false

    /** The pack files still to add to voice words, and to recognize speech in [speech]. */
    fun missing(speech: SpokenLanguage?): List<VoicePackFile>

    fun addPackFiles(
        sources: List<() -> InputStream?>,
        progress: (Float) -> Unit,
    ): PackAddResult

    fun packBytes(): Long

    fun removePack()

    /** The words of recording [media] in project [projectId], in [language], as phrases. */
    fun recognize(
        projectId: ProjectId,
        media: MediaRef.Copied,
        language: SpokenLanguage,
    ): List<VoicePhrase>

    /**
     * [phrases] spoken in [voice] into a new file of project [projectId]. A
     * recording's phrases keep their times and the file is [lengthMs] long
     * (the recording's own length); typed phrases ([lengthMs] null) are
     * spoken one after another and get their times here.
     */
    fun speak(
        projectId: ProjectId,
        phrases: List<VoicePhrase>,
        voice: VoiceChoice,
        language: SpokenLanguage,
        lengthMs: Long?,
        progress: (Float) -> Unit,
    ): SpokenVoice

    companion object {
        /** No voice pack (tests, and a build without one). */
        val NONE =
            object : VoiceTools {
                override val manifest: VoicePackManifest? = null
                override val supported = false

                override fun missing(speech: SpokenLanguage?) = emptyList<VoicePackFile>()

                override fun addPackFiles(
                    sources: List<() -> InputStream?>,
                    progress: (Float) -> Unit,
                ) = PackAddResult(emptyList(), sources.size)

                override fun packBytes() = 0L

                override fun removePack() = Unit

                override fun recognize(
                    projectId: ProjectId,
                    media: MediaRef.Copied,
                    language: SpokenLanguage,
                ): List<VoicePhrase> = throw VoiceFailure(VoiceFailure.Reason.NO_ENGINE)

                override fun speak(
                    projectId: ProjectId,
                    phrases: List<VoicePhrase>,
                    voice: VoiceChoice,
                    language: SpokenLanguage,
                    lengthMs: Long?,
                    progress: (Float) -> Unit,
                ): SpokenVoice = throw VoiceFailure(VoiceFailure.Reason.NO_ENGINE)
            }
    }
}

/** [VoiceTools] with the voice pack on the phone. */
class PackVoiceTools(
    private val store: VoicePackStore,
    private val files: AppFiles,
    private val recordings: VoiceRecordings,
) : VoiceTools {
    private val engine = VoiceEngine(store)

    override val manifest: VoicePackManifest get() = store.manifest
    override val supported: Boolean get() = store.supported
    override val engineWorks: Boolean? get() = engine.works

    /** The pack's English dictionary, read once per process (about 190 000 words). */
    @Volatile
    private var lexiconCache: Map<String, String>? = null

    private fun lexicon(): Map<String, String> = lexiconCache ?: VoiceEngine.lexicon(store.ttsDir).also { lexiconCache = it }

    override fun missing(speech: SpokenLanguage?) = store.missing(speech)

    override fun addPackFiles(
        sources: List<() -> InputStream?>,
        progress: (Float) -> Unit,
    ) = synchronized(this) {
        lexiconCache = null
        store.add(sources, progress)
    }

    override fun packBytes() = store.bytesOnDisk()

    override fun removePack() =
        synchronized(this) {
            store.remove()
            lexiconCache = null
        }

    override fun checkEngine(): Boolean = synchronized(this) { store.missing(null).isEmpty() && engine.load() }

    override fun recognize(
        projectId: ProjectId,
        media: MediaRef.Copied,
        language: SpokenLanguage,
    ): List<VoicePhrase> =
        synchronized(this) {
            if (store.missing(language).isNotEmpty()) throw VoiceFailure(VoiceFailure.Reason.NO_PACK)
            if (!engine.load()) throw VoiceFailure(VoiceFailure.Reason.NO_ENGINE)
            val pcm =
                AudioPcm.decode(files.copiedFile(projectId, media), ASR_RATE, MAX_MS) ?: throw VoiceFailure(VoiceFailure.Reason.TOO_LONG)
            val words: List<HeardWord> = engine.recognize(pcm, ASR_RATE, language).filterNot { it.text in UNKNOWN_WORDS }
            Phrases.fromWords(words)
        }

    override fun speak(
        projectId: ProjectId,
        phrases: List<VoicePhrase>,
        voice: VoiceChoice,
        language: SpokenLanguage,
        lengthMs: Long?,
        progress: (Float) -> Unit,
    ): SpokenVoice =
        synchronized(this) {
            require(voice != VoiceChoice.NONE && voice.speaks(language)) { "That voice can't say these words" }
            val said = phrases.filter { it.text.isNotBlank() }
            if (said.isEmpty()) throw VoiceFailure(VoiceFailure.Reason.NO_WORDS)
            if (store.missing(null).isNotEmpty()) throw VoiceFailure(VoiceFailure.Reason.NO_PACK)
            if (!engine.load()) throw VoiceFailure(VoiceFailure.Reason.NO_ENGINE)
            if ((lengthMs ?: 0) > MAX_MS) throw VoiceFailure(VoiceFailure.Reason.TOO_LONG)
            // English words in Hindi (Hinglish) text use the dictionary too.
            val lexicon = lexicon()
            val phonemizer = Phonemizer({ lexicon[it] }, VoiceEngine.tokens(store.ttsDir))
            val byRule = mutableListOf<String>()
            val spoken = mutableListOf<Pair<ShortArray, PhraseFit>>()
            val rate: Int
            engine.speaker().use { speaker ->
                rate = speaker.rate
                val ph =
                    said.map {
                        val r = if (language == SpokenLanguage.HINDI) phonemizer.hindi(it.text) else phonemizer.english(it.text)
                        byRule += r.byRule
                        r.phonemes
                    }
                ph.forEachIndexed { i, p ->
                    var audio = speaker.speakLong(p, voice.speaker, 1f)
                    var fit = PhraseFit(1f, false)
                    if (lengthMs != null) {
                        val next = said.getOrNull(i + 1)?.startMs ?: lengthMs
                        fit = PhraseFit.of(audio.size * 1000L / rate, next - said[i].startMs - PhraseFit.BREATH_MS)
                        if (fit.speed > 1f) audio = speaker.speakLong(p, voice.speaker, fit.speed)
                    }
                    spoken += audio to fit
                    progress((i + 1f) / said.size)
                }
            }
            // Typed words start after the encoder's priming (which writeAac leaves out), so their first sound is kept.
            val lead = VoiceRecorder.ENCODER_DELAY_FRAMES * 1000 / rate + 1
            val starts =
                if (lengthMs ==
                    null
                ) {
                    Phrases.placeTyped(spoken.map { it.first.size * 1000L / rate }).map { it + lead }
                } else {
                    said.map { it.startMs }
                }
            val total =
                lengthMs ?: (starts.last() + spoken.last().first.size * 1000L / rate + Phrases.TYPED_GAP_MS)
            val buffer = ShortArray((total * rate / 1000).toInt() + 1)
            spoken.forEachIndexed { i, (audio, _) ->
                val at = (starts[i] * rate / 1000).toInt()
                // A phrase that still runs into the next one ends where it starts, with a short fade.
                val end =
                    minOf(
                        audio.size,
                        (starts.getOrNull(i + 1)?.let { (it - starts[i]) * rate / 1000 }?.toInt() ?: audio.size),
                        buffer.size - at,
                    )
                val fade = rate * FADE_MS / 1000
                for (k in 0 until end) {
                    val g = if (end < audio.size && k > end - fade) (end - k).toFloat() / fade else 1f
                    buffer[at + k] = (audio[k] * g).toInt().toShort()
                }
            }
            val pending = recordings.newRecording(projectId)
            val ok = writeAac(buffer, rate, pending.part)
            if (!ok || !recordings.keep(pending)) {
                recordings.discard(pending)
                throw VoiceFailure(VoiceFailure.Reason.FAILED)
            }
            SpokenVoice(
                pending.ref,
                said.mapIndexed { i, p -> p.copy(startMs = starts[i]) },
                total,
                byRule.distinct(),
                spoken.indices.filter { i ->
                    starts.getOrNull(i + 1)?.let { spoken[i].first.size * 1000L / rate > it - starts[i] } == true
                },
            )
        }

    /**
     * Writes [pcm] as AAC-LC in M4A. The encoder's priming plays as silence
     * at the start, so the first frames of [pcm] are left out to match:
     * the words then land at their times, as in a recording.
     */
    private fun writeAac(
        pcm: ShortArray,
        rate: Int,
        file: java.io.File,
    ): Boolean {
        val encoder = VoiceRecorder.AacEncoder(file, rate)
        return try {
            encoder.open()
            val skip = VoiceRecorder.ENCODER_DELAY_FRAMES
            var at = 0L
            val chunk = VoiceRecorder.CHUNK_FRAMES
            while (at < pcm.size) {
                val n = minOf(chunk.toLong(), pcm.size - at).toInt()
                encoder.feed(at, pcm.copyOfRange(at.toInt(), at.toInt() + n), from = skip, end = null)
                at += n
            }
            encoder.close()
        } catch (e: Exception) {
            file.delete()
            false
        } finally {
            encoder.release()
        }
    }

    companion object {
        const val ASR_RATE = 16_000
        private const val FADE_MS = 30

        /** The longest voice item the engine takes (memory grows with length). */
        const val MAX_MS = 10L * 60 * 1000

        /** What Vosk writes for a sound it could not make a word of. */
        private val UNKNOWN_WORDS = setOf("[unk]", "<unk>")
    }
}
