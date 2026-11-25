package com.itsluminous.clearmovie.domain.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** Hindi numbers and nasals said as a person says them, and Hindi HD's pinned source (cm-hindi-voices). */
class HindiHdRulesTest {
    private fun words(n: String) = Phonemizer.hindiNumberWords(n).joinToString(" ")

    @Test
    fun `numbers are read as Hindi words, years in hundreds, and codes digit by digit`() {
        assertThat(words("25")).isEqualTo("पच्चीस")
        assertThat(words("2026")).isEqualTo("दो हज़ार छब्बीस")
        assertThat(words("1920")).isEqualTo("उन्नीस सौ बीस")
        assertThat(words("1900")).isEqualTo("उन्नीस सौ")
        assertThat(words("100")).isEqualTo("एक सौ")
        assertThat(words("850")).isEqualTo("आठ सौ पचास")
        assertThat(words("100000")).isEqualTo("एक लाख")
        assertThat(words("12500000")).isEqualTo("एक करोड़ पच्चीस लाख")
        assertThat(words("0")).isEqualTo("शून्य")
        assertThat(words("२५")).isEqualTo("पच्चीस")
        assertThat(words("098")).isEqualTo("शून्य नौ आठ")
        assertThat(words("9876543210")).isEqualTo("नौ आठ सात छह पांच चार तीन दो एक शून्य")
    }

    private val phonemizer = Phonemizer({ null }, TOKENS)

    @Test
    fun `a sentence's numbers, digit groups and decimals become words before phonemes`() {
        val withWords = phonemizer.hindi("शादी पच्चीस दिसंबर को है").phonemes
        assertThat(phonemizer.hindi("शादी 25 दिसंबर को है").phonemes).isEqualTo(withWords)
        assertThat(phonemizer.hindi("1,00,000 रुपये").phonemes).isEqualTo(phonemizer.hindi("एक लाख रुपये").phonemes)
        assertThat(phonemizer.hindi("3.5 किलो").phonemes).isEqualTo(phonemizer.hindi("तीन दशमलव पांच किलो").phonemes)
    }

    @Test
    fun `dates, times and lists are read as a person says them`() {
        fun same(
            a: String,
            b: String,
        ) = assertThat(phonemizer.hindi(a).phonemes).isEqualTo(phonemizer.hindi(b).phonemes)
        same("10.5.2026 को", "दस पाँच दो हज़ार छब्बीस को")
        same("शादी 10.5.2026.", "शादी दस पाँच दो हज़ार छब्बीस.")
        same("11:35 बजे", "ग्यारह बजकर पैंतीस मिनट बजे")
        same("05:00", "पाँच बजे")
        same("10,20 और 30", "दस, बीस और तीस")
        same("1,000,000", "दस लाख")
        same("तीन 3.5.", "तीन तीन दशमलव पांच.")
        same("00:30", "बारह बजकर तीस मिनट")
        // Not a date (month 13, or two separators that differ): read as separate numbers, never a decimal.
        same("10.13.2026", "दस. तेरह. दो हज़ार छब्बीस")
        assertThat(phonemizer.hindi("10.5/2026").phonemes).isNotEqualTo(phonemizer.hindi("दस पाँच दो हज़ार छब्बीस").phonemes)
    }

    @Test
    fun `an anusvara before a consonant is that consonant's nasal, and a nasalised schwa stays`() {
        assertThat(HindiPhonemes.word("प्रियंका")).isEqualTo("pɾɪjəŋkaː")
        assertThat(HindiPhonemes.word("हिंदी")).isEqualTo("ɦɪnd̪iː")
        assertThat(HindiPhonemes.word("पसंद")).isEqualTo("pəsənd̪")
        assertThat(HindiPhonemes.word("दिसंबर")).isEqualTo("d̪ɪsəmbəɾ")
        assertThat(HindiPhonemes.word("मुंबई")).isEqualTo("mʊmbəiː")
        // After a long vowel, or before य र ल व श स ह, the vowel is nasalised as before.
        assertThat(HindiPhonemes.word("सांप")).isEqualTo("saː̃p")
        assertThat(HindiPhonemes.word("संसार")).isEqualTo("sə̃saːɾ")
        assertThat(HindiPhonemes.word("में")).isEqualTo("meː̃")
    }

    @Test
    fun `Hindi HD is downloaded only from its pinned commit, piece by piece`() {
        val commit = VoicePackSource.COMMITS.getValue("hindi-hd-v1")
        assertThat(commit).matches("[0-9a-f]{40}")
        assertThat(commit).isNotEqualTo("0".repeat(40))
        val url = VoicePackSource.url("hindi-hd-v1", "speech-hindi-hd.zip.001")
        assertThat(
            url,
        ).isEqualTo("https://raw.githubusercontent.com/itsluminous/ClearMovie/$commit/voice-packs/hindi-hd-v1/speech-hindi-hd.zip.001")
        assertThat(VoicePackSource.allowed(url!!)).isTrue()
        assertThat(VoicePackSource.allowed(url.replace(commit, VoicePackSource.COMMITS.getValue("v1")))).isFalse()
        assertThat(VoicePackSource.allowed(url.replace("hindi-hd-v1", "hindi-hd-v2"))).isFalse()
        assertThat(VoicePackSource.url("hindi-hd-v1", "speech-hindi-hd.zip.1")).isNull()
        assertThat(VoicePackSource.url("hindi-hd-v1", "manifest.json")).isNull()
    }

    private fun repoFile(path: String) = listOf(File("../$path"), File(path)).first { it.isFile }

    @Test
    fun `the app takes exactly the Hindi HD pack in its repo folder, every piece under GitHub's 100 MB limit`() {
        val inRepo = repoFile("voice-packs/hindi-hd-v1/manifest.json")
        val asset =
            listOf(
                File("src/main/assets/voice-pack/manifest-hindi-hd-v1.json"),
                File("app/src/main/assets/voice-pack/manifest-hindi-hd-v1.json"),
            ).first { it.isFile }
        assertThat(asset.readText()).isEqualTo(inRepo.readText())
        val m = requireNotNull(VoicePackManifest.parse(inRepo.readText()))
        assertThat(m.folderName).isEqualTo("hindi-hd-v1")
        assertThat(m.files.map { it.part })
            .containsExactly(VoicePackFile.VOICES_HI_HD, VoicePackFile.SPEECH_HI_HD, VoicePackFile.SPEECH_HI_HD_RESCORE)
        val folder =
            inRepo.parentFile
                .listFiles()
                .orEmpty()
                .map { it.name }
                .toSet()
        assertThat(folder).isEqualTo(m.files.flatMap { it.downloads }.toSet() + setOf("manifest.json", "README.md"))
        for (f in m.files) {
            for ((i, name) in f.downloads.withIndex()) {
                val bytes = File(inRepo.parentFile, name).length()
                assertThat(bytes).isAtMost(VoicePackFile.MAX_FILE_BYTES)
                assertThat(bytes).isEqualTo(f.pieces.getOrNull(i)?.bytes ?: f.bytes)
            }
        }
        // With the pack it adds to, as the app loads them.
        val base = requireNotNull(VoicePackManifest.parse(repoFile("voice-packs/v1/manifest.json").readText()))
        assertThat((base + m).hindiSpeech(HindiModel.FULL).map { it.folder }).containsExactly("hindi-hd-v1", "hindi-hd-v1", "hindi-hd-v1")
    }

    private companion object {
        /** Kokoro's phoneme symbols, as in the pack's tokens.txt. */
        val TOKENS: Set<String> =
            (
                "; : , . ! ? \" ( ) \u0303 ʣ ʥ ʦ ʨ ᵝ A I O Q S T W Y ᵊ a b c d e f h i j k l m n o p q r s t u v w x y z " +
                    "ɑ ɐ ɒ æ β ɔ ɕ ç ɖ ð ʤ ə ɚ ɛ ɜ ɟ ɡ ɥ ɨ ɪ ʝ ɯ ɰ ŋ ɳ ɲ ɴ ø ɸ θ œ ɹ ɾ ɻ ʁ ɽ ʂ ʃ ʈ ʧ ʊ ʋ ʌ ɣ ɤ χ ʎ ʒ ʔ ˈ ˌ ː ʰ ʲ"
            ).split(' ').toSet()
    }
}
