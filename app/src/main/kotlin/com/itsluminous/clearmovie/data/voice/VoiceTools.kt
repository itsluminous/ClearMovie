package com.itsluminous.clearmovie.data.voice

import android.util.Log
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.record.VoiceRecorder
import com.itsluminous.clearmovie.data.record.VoiceRecordings
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import com.itsluminous.clearmovie.domain.voice.HeardWord
import com.itsluminous.clearmovie.domain.voice.HindiModel
import com.itsluminous.clearmovie.domain.voice.Phonemizer
import com.itsluminous.clearmovie.domain.voice.PhraseFit
import com.itsluminous.clearmovie.domain.voice.Phrases
import com.itsluminous.clearmovie.domain.voice.VoicePackFile
import com.itsluminous.clearmovie.domain.voice.VoicePackManifest
import java.io.File
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
 * Everything runs on the phone; nothing is sent anywhere. The internet is
 * used only to download the voice pack itself ([PackDownloader]). Call off
 * the main thread.
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

    /** The pack files still to add to voice words (in [voice], when it needs Hindi HD), and to recognize speech in [speech]. */
    fun missing(
        speech: SpokenLanguage?,
        voice: VoiceChoice? = null,
    ): List<VoicePackFile>

    /** The best Hindi recognizer this phone's memory runs (cm-hindi-voices). */
    val hindiModel: HindiModel get() = HindiModel.SMALL

    /** Hindi HD's files this phone runs and does not have yet, with the base files it needs (empty: none, or it cannot run it). */
    fun hindiHdMissing(): List<VoicePackFile> = emptyList()

    /** Every pack file this phone takes that is not added yet: what a download may add. */
    fun notAdded(): List<VoicePackFile> = (missing(SpokenLanguage.ENGLISH) + missing(SpokenLanguage.HINDI)).distinct()

    fun addPackFiles(
        sources: List<() -> InputStream?>,
        progress: (Float) -> Unit,
    ): PackAddResult

    /** Where a download of pack file [f] goes until it is added (cm-voice-pack-download); null without a pack. */
    fun downloadFile(f: VoicePackFile): File? = null

    /** Where piece [name] of a split zip is downloaded to and kept until its zip is added; null without a pack. */
    fun pieceFile(name: String): File? = null

    /** Whether [file] is piece [index] of [f] as the app's manifest lists it; one that is not is deleted. */
    fun checkPiece(
        f: VoicePackFile,
        index: Int,
        file: File,
    ): Boolean = false

    /**
     * Takes in [files], a finished download of [f] (the zip, or its pieces in order), if each SHA-256
     * matches the app's own manifest. Nothing is added once [stopped] (Cancel, or Settings' Remove meanwhile).
     */
    fun addDownloaded(
        f: VoicePackFile,
        files: List<File>,
        stopped: () -> Boolean = { false },
    ): PackAddResult = PackAddResult(emptyList(), 1)

    /** Removes stopped downloads. */
    fun clearDownloads() = Unit

    /** Free bytes where the pack goes. */
    fun freeBytes(): Long = Long.MAX_VALUE

    fun packBytes(): Long

    fun removePack()

    /**
     * The words of recording [media] in project [projectId], in [language], as phrases. [progress]
     * gets the share of the recording heard, from 0 once the engine is ready to 1, and NaN between
     * the steps of getting ready (no figure yet); it may throw to stop the job there.
     */
    fun recognize(
        projectId: ProjectId,
        media: MediaRef.Copied,
        language: SpokenLanguage,
        progress: (Float) -> Unit = {},
    ): List<VoicePhrase>

    /**
     * [phrases] spoken in [voice] into a new file of project [projectId]. A
     * recording's phrases keep their times and the file is [lengthMs] long
     * (the recording's own length); typed phrases ([lengthMs] null) are
     * spoken one after another and get their times here. [progress] gets the
     * share of the phrases said, from 0 once the voices are loaded to 1, and
     * NaN between the steps of getting ready (no figure yet); it may throw
     * to stop the job there.
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

                override fun missing(
                    speech: SpokenLanguage?,
                    voice: VoiceChoice?,
                ) = emptyList<VoicePackFile>()

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
                    progress: (Float) -> Unit,
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

    override fun missing(
        speech: SpokenLanguage?,
        voice: VoiceChoice?,
    ) = store.missing(speech, voice)

    override val hindiModel: HindiModel get() = store.hindiModel

    override fun hindiHdMissing() = store.hindiHdMissing()

    override fun notAdded() = if (store.supported) store.manifest.files.filter { store.fits(it) && !store.isAdded(it) } else emptyList()

    override fun addPackFiles(
        sources: List<() -> InputStream?>,
        progress: (Float) -> Unit,
    ) = synchronized(this) {
        lexiconCache = null
        store.add(sources, progress)
    }

    override fun downloadFile(f: VoicePackFile): File = store.downloadFile(f)

    override fun pieceFile(name: String): File = store.pieceFile(name)

    override fun checkPiece(
        f: VoicePackFile,
        index: Int,
        file: File,
    ) = store.checkPiece(f, index, file)

    // Under the same lock as removePack, and checked for the stop inside it: a Remove that waited for the
    // download to stop never has a file added back after it.
    override fun addDownloaded(
        f: VoicePackFile,
        files: List<File>,
        stopped: () -> Boolean,
    ) = synchronized(this) {
        if (stopped()) return@synchronized PackAddResult(emptyList(), 0)
        lexiconCache = null
        store.addDownloaded(f, files)
    }

    override fun clearDownloads() = store.clearDownloads()

    override fun freeBytes() = store.freeBytes()

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
        progress: (Float) -> Unit,
    ): List<VoicePhrase> =
        synchronized(this) {
            val t = Steps()
            if (store.missing(language).isNotEmpty()) throw VoiceFailure(VoiceFailure.Reason.NO_PACK)
            if (!engine.load()) throw VoiceFailure(VoiceFailure.Reason.NO_ENGINE)
            val engineMs = t.lap()
            progress(Float.NaN)
            val pcm =
                AudioPcm.decode(files.copiedFile(projectId, media), ASR_RATE, MAX_MS) ?: throw VoiceFailure(VoiceFailure.Reason.TOO_LONG)
            val decodeMs = t.lap()
            progress(Float.NaN)
            var modelMs = 0L
            val words: List<HeardWord> =
                engine.recognize(pcm, ASR_RATE, language, progress) { modelMs = it }.filterNot { it.text in UNKNOWN_WORDS }
            val heardMs = t.lap() - modelMs
            // Only the times and the length, never the words or how many (cm-voice-progress).
            Log.i(
                TAG,
                "Words found in ${t.totalMs} ms: engine $engineMs, decode $decodeMs, recognizer $modelMs, " +
                    "hearing $heardMs; ${pcm.size * 1000L / ASR_RATE} ms of sound",
            )
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
            val t = Steps()
            if (store.missing(null, voice).isNotEmpty()) throw VoiceFailure(VoiceFailure.Reason.NO_PACK)
            if (!engine.load()) throw VoiceFailure(VoiceFailure.Reason.NO_ENGINE)
            if ((lengthMs ?: 0) > MAX_MS) throw VoiceFailure(VoiceFailure.Reason.TOO_LONG)
            val engineMs = t.lap()
            progress(Float.NaN)
            // English words in Hindi (Hinglish) text use the dictionary too.
            val lexicon = lexicon()
            val phonemizer = Phonemizer({ lexicon[it] }, VoiceEngine.tokens(store.ttsDir))
            val byRule = mutableListOf<String>()
            val spoken = mutableListOf<Pair<ShortArray, PhraseFit>>()
            val rate: Int
            val dictionaryMs = t.lap()
            progress(Float.NaN)
            var voicesMs = 0L
            engine.speaker().use { speaker ->
                rate = speaker.rate
                voicesMs = t.lap()
                progress(0f)
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
                    progress((i + 1f) / said.size * PHRASES_SHARE)
                }
            }
            val phrasesMs = t.lap()
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
            // The last point Stop can take effect: a voice stopped now is not kept.
            val stopped = runCatching { progress(1f) }.exceptionOrNull()
            if (stopped != null || !ok || !recordings.keep(pending)) {
                recordings.discard(pending)
                throw stopped ?: VoiceFailure(VoiceFailure.Reason.FAILED)
            }
            Log.i(
                TAG,
                "Voice made in ${t.totalMs} ms: engine $engineMs, dictionary $dictionaryMs, voices $voicesMs, " +
                    "phrases $phrasesMs, file ${t.lap()}; $total ms long",
            )
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

    /** How long each step of a job took, for the log. */
    private class Steps {
        private val start = System.nanoTime()
        private var last = start

        /** Milliseconds since the last lap (or the start). */
        fun lap(): Long {
            val now = System.nanoTime()
            return ((now - last) / 1_000_000).also { last = now }
        }

        val totalMs: Long get() = (System.nanoTime() - start) / 1_000_000
    }

    companion object {
        private const val TAG = "VoiceTools"
        const val ASR_RATE = 16_000

        /** The share of a voice's progress for saying its phrases; the rest is writing its file. */
        private const val PHRASES_SHARE = 0.9f
        private const val FADE_MS = 30

        /** The longest voice item the engine takes (memory grows with length). */
        const val MAX_MS = 10L * 60 * 1000

        /** What Vosk writes for a sound it could not make a word of. */
        private val UNKNOWN_WORDS = setOf("[unk]", "<unk>")
    }
}
