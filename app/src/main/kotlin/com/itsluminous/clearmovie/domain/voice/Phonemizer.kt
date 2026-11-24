package com.itsluminous.clearmovie.domain.voice

/**
 * Words to the voice engine's phonemes (cm-voice-choice). The voice pack's
 * engine (Kokoro) reads phonemes, not letters, and it silently skips any word
 * its dictionary lacks, so the app turns words into phonemes itself:
 * English words through the pack's dictionary ([lexicon]), with simple
 * letter-to-sound rules for a word it does not have (names: "Priya",
 * "Arjun"), and numbers as words; Hindi through [HindiPhonemes]. Only
 * [tokens] the engine knows are kept.
 */
class Phonemizer(
    private val lexicon: (String) -> String?,
    private val tokens: Set<String>,
) {
    /** [text]'s phonemes, and the English words read by rule because the dictionary lacks them. */
    data class Result(
        val phonemes: String,
        val byRule: List<String>,
    )

    fun english(text: String): Result {
        val byRule = mutableListOf<String>()
        val parts =
            WORD_OR_MARK.findAll(text).flatMap { m ->
                val t = m.value
                when {
                    t.first().isDigit() -> numberWords(t).asSequence().map { word(it, byRule) }
                    t.first() in MARKS -> sequenceOf(t)
                    else -> sequenceOf(word(t, byRule))
                }
            }
        return Result(join(parts.toList()), byRule)
    }

    fun hindi(text: String): Result {
        val byRule = mutableListOf<String>()
        val parts =
            HINDI_WORD_OR_MARK.findAll(text).flatMap { m ->
                val t = m.value
                when {
                    t.first() in '०'..'९' || t.first().isDigit() -> hindiDigits(t).asSequence().map(HindiPhonemes::word)
                    t.first() in MARKS || t.first() == '\u0964' -> sequenceOf(if (t.first() == '\u0964') "." else t)
                    t.first().isLatinLetter() -> sequenceOf(word(t, byRule))
                    else -> sequenceOf(HindiPhonemes.word(t))
                }
            }
        return Result(join(parts.map(HindiPhonemes::forEngine).toList()), byRule)
    }

    private fun word(
        w: String,
        byRule: MutableList<String>,
    ): String =
        lexicon(w.lowercase()) ?: lexicon(w.lowercase().trimEnd('\'').removeSuffix("'s"))?.let { it + "z" } ?: run {
            byRule += w
            EnglishRules.of(w)
        }

    private fun join(parts: List<String>): String {
        val sb = StringBuilder()
        for (p in parts) {
            if (p.isEmpty()) continue
            if (p.first() in MARKS) {
                sb.append(p.first())
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(p)
            }
        }
        return sb
            .toString()
            .codePoints()
            .toArray()
            .filter { cp -> cp == ' '.code || String(Character.toChars(cp)) in tokens }
            .joinToString("") { String(Character.toChars(it)) }
    }

    companion object {
        private const val MARKS = ".,!?;:"
        private val WORD_OR_MARK = Regex("[A-Za-z][A-Za-z']*|\\d+|[.,!?;:]")
        private val HINDI_WORD_OR_MARK =
            Regex("[\\u0900-\\u0963\\u0970-\\u097F\\u200C\\u200D]+|[०-९]+|[A-Za-z][A-Za-z']*|\\d+|[.,!?;:\\u0964]")

        private fun Char.isLatinLetter() = this in 'a'..'z' || this in 'A'..'Z'

        private val ONES =
            listOf(
                "zero",
                "one",
                "two",
                "three",
                "four",
                "five",
                "six",
                "seven",
                "eight",
                "nine",
                "ten",
                "eleven",
                "twelve",
                "thirteen",
                "fourteen",
                "fifteen",
                "sixteen",
                "seventeen",
                "eighteen",
                "nineteen",
            )
        private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

        /** An English number in words ("2026" is "two thousand twenty six"); a long digit string is read digit by digit. */
        fun numberWords(digits: String): List<String> {
            val n = digits.toLongOrNull()
            if (n == null || digits.length > 9) return digits.map { ONES[it - '0'] }
            return below(n).ifEmpty { listOf("zero") }
        }

        private fun below(n: Long): List<String> =
            when {
                n == 0L -> emptyList()
                n < 20 -> listOf(ONES[n.toInt()])
                n < 100 -> listOf(TENS[(n / 10).toInt()]) + below(n % 10)
                n < 1000 -> listOf(ONES[(n / 100).toInt()], "hundred") + below(n % 100)
                n < 1_000_000 -> below(n / 1000) + "thousand" + below(n % 1000)
                else -> below(n / 1_000_000) + "million" + below(n % 1_000_000)
            }

        private val HINDI_DIGITS = listOf("शून्य", "एक", "दो", "तीन", "चार", "पांच", "छह", "सात", "आठ", "नौ")

        /** Hindi digits (Devanagari or Latin), read one by one. */
        fun hindiDigits(digits: String): List<String> =
            digits.mapNotNull { c ->
                when (c) {
                    in '0'..'9' -> HINDI_DIGITS[c - '0']
                    in '०'..'९' -> HINDI_DIGITS[c - '०']
                    else -> null
                }
            }
    }
}

/** Letter-to-sound rules for an English word the dictionary lacks; kind to Indian names. Phonemes in Kokoro's set. */
internal object EnglishRules {
    private val PAIRS =
        listOf(
            "sh" to "ʃ",
            "ch" to "ʧ",
            "th" to "t",
            "ph" to "f",
            "kh" to "k",
            "gh" to "ɡ",
            "bh" to "b",
            "dh" to "d",
            "jh" to "ʤ",
            "aa" to "ɑː",
            "ee" to "iː",
            "ii" to "iː",
            "oo" to "uː",
            "ai" to "I",
            "au" to "W",
            "ou" to "W",
            "ei" to "A",
            "ey" to "A",
            "ck" to "k",
            "ng" to "ŋ",
        )

    fun of(word: String): String {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        val sb = StringBuilder("ˈ")
        var i = 0
        while (i < w.length) {
            val pair = if (i + 1 < w.length) PAIRS.firstOrNull { it.first == w.substring(i, i + 2) } else null
            if (pair != null) {
                sb.append(pair.second)
                i += 2
                continue
            }
            val last = i == w.length - 1
            sb.append(
                when (val c = w[i]) {
                    'a' -> if (last) "ə" else "ʌ"
                    'e' -> if (last) "eɪ" else "ɛ"
                    'i' -> if (last) "iː" else "ɪ"
                    'o' -> "O"
                    'u' -> "ʊ"
                    'y' -> if (i == 0 || (i + 1 < w.length && w[i + 1] in "aeiou")) "j" else "iː"
                    'j' -> "ʤ"
                    'r' -> "ɹ"
                    'c', 'q' -> "k"
                    'x' -> "ks"
                    'g' -> "ɡ"
                    else -> c.toString()
                },
            )
            i++
        }
        return sb.toString()
    }
}

/**
 * Devanagari to phonemes: consonants with their inherent schwa, vowel signs,
 * halant, nukta, anusvara, chandrabindu and visarga, and Hindi's schwa
 * deletion (at the end of a word of more than one letter, and between two
 * consonants that have a vowel on each side: "समझ" keeps its first, "बचपन"
 * loses its middle one).
 */
object HindiPhonemes {
    private val CONSONANTS =
        mapOf(
            'क' to "k",
            'ख' to "kʰ",
            'ग' to "ɡ",
            'घ' to "ɡʱ",
            'ङ' to "ŋ",
            'च' to "tʃ",
            'छ' to "tʃʰ",
            'ज' to "dʒ",
            'झ' to "dʒʱ",
            'ञ' to "ɲ",
            'ट' to "ʈ",
            'ठ' to "ʈʰ",
            'ड' to "ɖ",
            'ढ' to "ɖʱ",
            'ण' to "ɳ",
            'त' to "t̪",
            'थ' to "t̪ʰ",
            'द' to "d̪",
            'ध' to "d̪ʱ",
            'न' to "n",
            'प' to "p",
            'फ' to "pʰ",
            'ब' to "b",
            'भ' to "bʱ",
            'म' to "m",
            'य' to "j",
            'र' to "ɾ",
            'ल' to "l",
            'व' to "ʋ",
            'श' to "ʃ",
            'ष' to "ʂ",
            'स' to "s",
            'ह' to "ɦ",
            'ळ' to "ɭ",
        )
    private val NUKTA_FORMS = mapOf('ज' to "z", 'फ' to "f", 'ड' to "ɽ", 'ढ' to "ɽʱ", 'क' to "q", 'ख' to "x", 'ग' to "ɣ")
    private val PRECOMPOSED =
        mapOf('\u0958' to "q", '\u0959' to "x", '\u095A' to "ɣ", '\u095B' to "z", '\u095C' to "ɽ", '\u095D' to "ɽʱ", '\u095E' to "f")
    private val VOWELS =
        mapOf(
            'अ' to "ə",
            'आ' to "aː",
            'इ' to "ɪ",
            'ई' to "iː",
            'उ' to "ʊ",
            'ऊ' to "uː",
            'ऋ' to "ɾɪ",
            'ए' to "eː",
            'ऐ' to "ɛː",
            'ओ' to "oː",
            'औ' to "ɔː",
            'ऍ' to "ɛ",
            'ऑ' to "ɔ",
        )
    private val SIGNS =
        mapOf(
            'ा' to "aː",
            'ि' to "ɪ",
            'ी' to "iː",
            'ु' to "ʊ",
            'ू' to "uː",
            'ृ' to "ɾɪ",
            'े' to "eː",
            'ै' to "ɛː",
            'ो' to "oː",
            'ौ' to "ɔː",
            'ॅ' to "ɛ",
            'ॉ' to "ɔ",
        )
    private const val HALANT = '्'
    private const val NUKTA = '़'

    private data class Part(
        val sound: String,
        val vowel: Boolean,
        val schwa: Boolean = false,
    )

    /** One Devanagari word's phonemes (IPA, espeak-style). */
    fun word(w: String): String {
        val units = mutableListOf<Part>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val consonant = PRECOMPOSED[c] ?: CONSONANTS[c]
            when {
                consonant != null -> {
                    var sound = consonant
                    if (w.getOrNull(i + 1) == NUKTA) {
                        sound = NUKTA_FORMS[c] ?: sound
                        i++
                    }
                    units += Part(sound, vowel = false)
                    val next = w.getOrNull(i + 1)
                    when {
                        next == HALANT -> {
                            i++
                        }

                        next != null && next in SIGNS -> {
                            units += Part(SIGNS.getValue(next), vowel = true)
                            i++
                        }

                        else -> {
                            units += Part("ə", vowel = true, schwa = true)
                        }
                    }
                }

                c in VOWELS -> {
                    units += Part(VOWELS.getValue(c), vowel = true)
                }

                c == 'ं' || c == 'ँ' -> {
                    if (units.lastOrNull()?.vowel ==
                        true
                    ) {
                        units[units.size - 1] = units.last().let { it.copy(sound = it.sound + "̃") }
                    }
                }

                c == 'ः' -> {
                    units += Part("ɦ", vowel = false)
                }
            }
            i++
        }
        val keep = BooleanArray(units.size) { true }
        // The last schwa goes in a word of more than one letter.
        if (units.size > 2 && units.last().schwa) keep[units.size - 1] = false

        fun kept(
            from: Int,
            step: Int,
        ): Part? {
            var j = from
            while (j in units.indices) {
                if (keep[j]) return units[j]
                j += step
            }
            return null
        }
        // Between consonants with a vowel on both sides (V C ə C V), from the end of the word backwards.
        for (k in units.size - 2 downTo 2) {
            if (!units[k].schwa || !keep[k]) continue
            val before = units[k - 1]
            val after = kept(k + 1, 1)
            val afterNext =
                units.indices
                    .drop(k + 2)
                    .firstOrNull { keep[it] }
                    ?.let { units[it] }
            val beforePrev = kept(k - 2, -1)
            if (!before.vowel && after != null && !after.vowel && afterNext?.vowel == true && beforePrev?.vowel == true) keep[k] = false
        }
        return units.filterIndexed { k, _ -> keep[k] }.joinToString("") { it.sound }
    }

    /** espeak-style phonemes in Kokoro's symbol set: it has no breathy voice, no dental mark and joins affricates. */
    fun forEngine(phonemes: String): String =
        phonemes
            .replace("tʃ", "ʧ")
            .replace("dʒ", "ʤ")
            .replace("ɦ", "h")
            .replace("ʱ", "ʰ")
            .replace("\u032A", "")
}
