package com.itsluminous.clearmovie.domain.voice

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.SpokenLanguage
import com.itsluminous.clearmovie.domain.model.VoicePhrase
import org.junit.Test
import java.io.File

/** Phrases, timing, words to phonemes, and the voice pack's manifest (cm-voice-choice). */
class VoiceRulesTest {
    private fun w(
        t: String,
        s: Long,
        e: Long,
    ) = HeardWord(t, s, e)

    @Test
    fun `words become phrases at pauses longer than 300 ms, at most 12 s each`() {
        val words = listOf(w("welcome", 510, 990), w("to", 990, 1110), w("the", 1110, 1260), w("please", 4020, 4410), w("sit", 4500, 4800))
        assertThat(Phrases.fromWords(words)).containsExactly(VoicePhrase(510, "welcome to the"), VoicePhrase(4020, "please sit")).inOrder()
        val long = (0 until 40).map { w("w$it", it * 400L, it * 400L + 350) }
        val phrases = Phrases.fromWords(long)
        assertThat(phrases.size).isGreaterThan(1)
        assertThat(phrases.zipWithNext().all { (a, b) -> b.startMs - a.startMs <= Phrases.MAX_PHRASE_MS }).isTrue()
        assertThat(Phrases.fromWords(listOf(w(" ", 0, 10)))).isEmpty()
    }

    @Test
    fun `typed words become one phrase per sentence or line, spoken one after another`() {
        assertThat(Phrases.fromTyped("Welcome everyone. Please sit!\nथोड़ा रुकिए। शुरू करें\n\n  ").map { it.text })
            .containsExactly("Welcome everyone.", "Please sit!", "थोड़ा रुकिए।", "शुरू करें")
            .inOrder()
        assertThat(Phrases.placeTyped(listOf(1000, 2000, 500))).containsExactly(0L, 1300L, 3600L).inOrder()
        assertThat(Phrases.clip("  a \n  b ")).isEqualTo("a b")
        assertThat(Phrases.clip("x".repeat(600)).length).isEqualTo(Limits.MAX_TEXT_LENGTH)
        // A sentence longer than a phrase may be is split at a comma or a space, never cut short.
        val long = (1..120).joinToString(" ") { "word$it" }
        val parts = Phrases.fromTyped(long)
        assertThat(parts.size).isGreaterThan(1)
        assertThat(parts.all { it.text.length <= Limits.MAX_TEXT_LENGTH }).isTrue()
        assertThat(parts.joinToString(" ") { it.text }).isEqualTo(long)
        // Never more phrases than an item may store.
        val many = Phrases.fromTyped((1..1500).joinToString(" ") { "Yes." })
        assertThat(many.size).isAtMost(com.itsluminous.clearmovie.domain.model.VoiceOver.MAX_PHRASES)
        assertThat(many.sumOf { it.text.split(' ').size }).isEqualTo(1500)
        val heard = Phrases.fromWords((0 until 1200).map { w("w", it * 1000L, it * 1000L + 200) })
        assertThat(heard.size).isAtMost(com.itsluminous.clearmovie.domain.model.VoiceOver.MAX_PHRASES)
        assertThat(heard.first().startMs).isEqualTo(0)
    }

    @Test
    fun `a phrase that would run into the next is said faster, up to 1_3x, and cut only when it still does not fit`() {
        assertThat(PhraseFit.of(1000, 2000)).isEqualTo(PhraseFit(1f, false))
        val faster = PhraseFit.of(1200, 1000)
        assertThat(faster.speed).isWithin(0.01f).of(1.224f)
        assertThat(faster.cut).isFalse()
        val cut = PhraseFit.of(3000, 1000)
        assertThat(cut.speed).isEqualTo(PhraseFit.MAX_SPEED)
        assertThat(cut.cut).isTrue()
        assertThat(PhraseFit.of(500, 0).cut).isTrue()
    }

    private val lexicon =
        mapOf(
            "welcome" to "wˈɛlkəm",
            "to" to "tuː",
            "the" to "ðə",
            "party" to "pˈɑːti",
            "one" to "wˈʌn",
            "two" to "tˈuː",
            "it" to "ɪt",
        )
    private val tokens =
        (
            ("abcdefghijklmnopqrstuvwxyz ".map { it.toString() }) +
                listOf(
                    "ˈ",
                    "ˌ",
                    "ː",
                    "ə",
                    "ɛ",
                    "ɑ",
                    "ʌ",
                    "ʃ",
                    "ʧ",
                    "ʤ",
                    "ɹ",
                    "ɪ",
                    "ʊ",
                    "ð",
                    "ɡ",
                    "̃",
                    "ɾ",
                    "ʋ",
                    "ʈ",
                    "ɖ",
                    "ɳ",
                    "ʂ",
                    "ʰ",
                    "ŋ",
                    "ɲ",
                    "ɔ",
                    "O",
                    "A",
                    "I",
                    "W",
                    ".",
                    ",",
                    "!",
                    "?",
                )
        ).toSet()
    private val phonemizer = Phonemizer({ lexicon[it] }, tokens)

    @Test
    fun `English words come from the dictionary, names by rule, numbers as words`() {
        val r = phonemizer.english("Welcome Priya to the party, 2!")
        assertThat(r.byRule).containsExactly("Priya")
        assertThat(r.phonemes).startsWith("wˈɛlkəm ˈpɹɪjə tuː ðə pˈɑːti,")
        assertThat(r.phonemes).contains("tˈuː!")
        assertThat(phonemizer.english("it's").phonemes).isEqualTo("ɪtz")
        assertThat(Phonemizer.numberWords("2026")).containsExactly("two", "thousand", "twenty", "six").inOrder()
        assertThat(Phonemizer.numberWords("0")).containsExactly("zero")
        assertThat(Phonemizer.numberWords("1500000")).containsExactly("one", "million", "five", "hundred", "thousand").inOrder()
        assertThat(Phonemizer.numberWords("1234567890")).hasSize(10)
        assertThat(EnglishRules.of("Arjun")).isEqualTo("ˈʌɹʤʊn")
        assertThat(EnglishRules.of("Sharma")).isEqualTo("ˈʃʌɹmə")
        assertThat(EnglishRules.of("123")).isEmpty()
    }

    @Test
    fun `Hindi follows its schwa rules and speaks in the engine's symbols`() {
        assertThat(HindiPhonemes.word("स्वागत")).isEqualTo("sʋaːɡət̪")
        assertThat(HindiPhonemes.word("समय")).isEqualTo("səməj")
        assertThat(HindiPhonemes.word("बचपन")).isEqualTo("bətʃpən")
        assertThat(HindiPhonemes.word("कमल")).isEqualTo("kəməl")
        assertThat(HindiPhonemes.word("घर")).isEqualTo("ɡʱəɾ")
        assertThat(HindiPhonemes.word("में")).isEqualTo("meː̃")
        assertThat(HindiPhonemes.word("ज़मीन")).isEqualTo("zəmiːn")
        assertThat(HindiPhonemes.word("\u095Bमीन")).isEqualTo("zəmiːn")
        assertThat(HindiPhonemes.forEngine("t̪ʰ ɦ dʒ ɡʱ")).isEqualTo("tʰ h ʤ ɡʰ")
        val r = phonemizer.hindi("आपका स्वागत है। 12 party")
        assertThat(r.phonemes).contains("aːpkaː sʋaːɡət hɛː.")
        assertThat(r.phonemes).endsWith("pˈɑːti")
        assertThat(Phonemizer.hindiDigits("1२")).containsExactly("एक", "दो").inOrder()
    }

    private val repoManifest: File =
        listOf(
            File("../voice-packs/v1/manifest.json"),
            File("voice-packs/v1/manifest.json"),
        ).first { it.isFile }
    private val assetManifest: File =
        listOf(
            File("src/main/assets/voice-pack/manifest-v1.json"),
            File("app/src/main/assets/voice-pack/manifest-v1.json"),
        ).first {
            it.isFile
        }

    @Test
    fun `the app takes exactly the pack published in the repo folder, every file under GitHub's 100 MB limit`() {
        assertThat(assetManifest.readText()).isEqualTo(repoManifest.readText())
        val m = requireNotNull(VoicePackManifest.parse(assetManifest.readText()))
        assertThat(m.version).isEqualTo(1)
        assertThat(m.files.all { it.bytes < VoicePackFile.MAX_FILE_BYTES }).isTrue()
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) assertThat(m.supports(abi)).isTrue()
        assertThat(m.supports("mips")).isFalse()
        assertThat(m.needed("arm64-v8a", null).map { it.part }).containsExactly(VoicePackFile.VOICES, VoicePackFile.ENGINE)
        assertThat(
            m.needed("x86", SpokenLanguage.HINDI).map { it.file },
        ).containsExactly("voices.zip", "speech-hindi.zip", "engine-x86.zip")
        assertThat(
            m.bySha(
                m.files
                    .first()
                    .sha256
                    .uppercase(),
            ),
        ).isEqualTo(m.files.first())
        // Every file in the folder is listed, and nothing else is there but the listing and its README.
        val folder =
            repoManifest.parentFile
                .list()
                .orEmpty()
                .toSet()
        assertThat(folder).isEqualTo(m.files.map { it.file }.toSet() + setOf("manifest.json", "README.md"))
        for (f in m.files) assertThat(File(repoManifest.parentFile, f.file).length()).isEqualTo(f.bytes)
        assertThat(
            VoicePackManifest.downloadUrl("v1", "voices.zip"),
        ).isEqualTo("https://github.com/itsluminous/ClearMovie/raw/${VoicePackSource.COMMITS.getValue("v1")}/voice-packs/v1/voices.zip")
    }

    @Test
    fun `a manifest that is not this pack's, or breaks a limit, is refused`() {
        val good = assetManifest.readText()
        assertThat(VoicePackManifest.parse("not json")).isNull()
        assertThat(VoicePackManifest.parse(good.replace("clearmovie-voice-pack", "other-pack"))).isNull()
        assertThat(VoicePackManifest.parse(good.replaceFirst("voices.zip", "../voices.zip"))).isNull()
        assertThat(VoicePackManifest.parse(good.replaceFirst("\"part\": \"voices\"", "\"part\": \"apps\""))).isNull()
        assertThat(VoicePackManifest.parse("""{"pack":"clearmovie-voice-pack","version":1,"files":[]}""")).isNull()
    }

    @Test
    fun `a zip entry may only be a plain path inside its part's folder`() {
        assertThat(VoicePackFile.safeEntry("voices", "tts/model.int8.onnx")).isTrue()
        assertThat(VoicePackFile.safeEntry("speech-en", "asr/en/am/final.mdl")).isTrue()
        assertThat(VoicePackFile.safeEntry("engine", "lib/x86/libvosk.so")).isTrue()
        for (bad in listOf(
            "../tts/x",
            "tts/../../x",
            "/tts/x",
            "tts\\x",
            "tts/.hidden",
            "asr/hi/x",
            "tts",
            "tts/",
            "",
            "tts//x",
            "tts/./x",
        )) {
            assertThat(VoicePackFile.safeEntry("voices", bad)).isFalse()
        }
        assertThat(VoicePackFile.safeEntry("speech-hi", "asr/en/am/final.mdl")).isFalse()
    }
}
