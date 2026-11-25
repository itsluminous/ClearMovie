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
            HINDI_WORD_OR_MARK.findAll(hindiNumbers(text)).flatMap { m ->
                val t = m.value
                when {
                    t.first() in '०'..'९' || t.first().isDigit() -> hindiNumberWords(t).asSequence().map(HindiPhonemes::word)
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

        /** Hindi number words from 0 to 99 (cm-hindi-voices). */
        private val HINDI_NUMBERS =
            (
                "शून्य एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस " +
                    "बीस इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस " +
                    "पैंतीस छत्तीस सैंतीस अड़तीस उनतालीस चालीस इकतालीस बयालीस तैंतालीस चवालीस पैंतालीस छियालीस सैंतालीस " +
                    "अड़तालीस उनचास पचास इक्यावन बावन तिरपन चौवन पचपन छप्पन सत्तावन अट्ठावन उनसठ साठ इकसठ बासठ तिरसठ " +
                    "चौंसठ पैंसठ छियासठ सड़सठ अड़सठ उनहत्तर सत्तर इकहत्तर बहत्तर तिहत्तर चौहत्तर पचहत्तर छिहत्तर सतहत्तर " +
                    "अठहत्तर उनासी अस्सी इक्यासी बयासी तिरासी चौरासी पचासी छियासी सत्तासी अट्ठासी नवासी नब्बे इक्यानबे " +
                    "बानबे तिरानबे चौरानबे पंचानबे छियानबे सत्तानबे अट्ठानबे निन्यानबे"
            ).split(' ')

        /** A grouped number, Indian (1,00,000) or international (100,000), and nothing like a list ("10,20"). */
        private val DIGIT_GROUP = Regex("(?<![\\d,])(\\d{1,3}(?:,\\d{2})*,\\d{3}|\\d{1,3}(?:,\\d{3})+)(?!\\d|,\\d)")

        /** A date (10.5.2026, 10/05/26, 10-5-2026), read before a decimal could take its first two numbers. */
        private val DATE = Regex("(?<![\\d./-])(\\d{1,2})([./-])(\\d{1,2})\\2(\\d{4}|\\d{2})(?!\\d|\\.\\d)")

        /** A time of day (11:35, 05:30). */
        private val TIME = Regex("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])")
        private val DECIMAL = Regex("(?<![\\d.])(\\d+)\\.(\\d+)(?!\\d|\\.\\d)")

        private fun said(n: String) = hindiNumberWords(n).joinToString(" ")

        /**
         * [text] with Devanagari digits as Latin ones and dates, times, grouped numbers and decimals in words:
         * "10.5.2026" is "दस पाँच दो हज़ार छब्बीस", "11:35" is "ग्यारह बजकर पैंतीस मिनट", "1,00,000" is "एक लाख",
         * "3.5" is "तीन दशमलव पांच".
         */
        private fun hindiNumbers(text: String): String {
            var t = text.map { if (it in '०'..'९') '0' + (it - '०') else it }.joinToString("")
            t =
                DATE.replace(t) { m ->
                    val (d, _, mo, y) = m.destructured
                    // Not a date (a version, a code): left for the rules below.
                    if (d.toInt() !in 1..31 || mo.toInt() !in 1..12) return@replace m.value
                    listOf(d, mo, y).joinToString(" ") { said(it.trimStart('0').ifEmpty { "0" }) }
                }
            t =
                TIME.replace(t) { m ->
                    // Midnight is said as twelve.
                    val h = said(m.groupValues[1].trimStart('0').ifEmpty { "12" })
                    val min = m.groupValues[2].trimStart('0').ifEmpty { "0" }
                    if (min == "0") "$h बजे" else "$h बजकर ${said(min)} मिनट"
                }
            t = DIGIT_GROUP.replace(t) { it.value.replace(",", "") }
            return DECIMAL.replace(
                t,
            ) { m -> (hindiNumberWords(m.groupValues[1]) + "दशमलव" + hindiDigits(m.groupValues[2])).joinToString(" ") }
        }

        /**
         * A number in Hindi words (cm-hindi-voices): "25" is "पच्चीस", "2026" is "दो हज़ार छब्बीस",
         * 1100 to 1999 are read in hundreds as years and prices are ("1920": "उन्नीस सौ बीस"), with
         * lakh and crore above that. A number with a leading zero or of more than nine digits (a phone
         * number, a code) is read digit by digit.
         */
        fun hindiNumberWords(digits: String): List<String> {
            val d = digits.map { if (it in '०'..'९') '0' + (it - '०') else it }.joinToString("")
            val n = d.toLongOrNull()
            if (n == null || d.length > 9 || (d.length > 1 && d.first() == '0')) return hindiDigits(d)
            if (n in 1100..1999) return listOf(HINDI_NUMBERS[(n / 100).toInt()], "सौ") + hindiBelow(n % 100)
            return hindiBelow(n).ifEmpty { listOf(HINDI_NUMBERS[0]) }
        }

        private fun hindiBelow(n: Long): List<String> =
            when {
                n == 0L -> emptyList()
                n < 100 -> listOf(HINDI_NUMBERS[n.toInt()])
                n < 1000 -> listOf(HINDI_NUMBERS[(n / 100).toInt()], "सौ") + hindiBelow(n % 100)
                n < 100_000 -> hindiBelow(n / 1000) + "हज़ार" + hindiBelow(n % 1000)
                n < 10_000_000 -> hindiBelow(n / 100_000) + "लाख" + hindiBelow(n % 100_000)
                else -> hindiBelow(n / 10_000_000) + "करोड़" + hindiBelow(n % 10_000_000)
            }

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
    private val SHORT_VOWELS = setOf("ə", "ɪ", "ʊ")

    /** The nasal of each consonant class, which an anusvara before it is said as. */
    private val NASAL_OF: Map<Char, String> =
        "कखगघ".associateWith { "ŋ" } + "चछजझ".associateWith { "ɲ" } + "टठडढ".associateWith { "ɳ" } +
            "तथदध".associateWith { "n" } + "पफबभ".associateWith { "m" }
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

                // An anusvara after a short vowel, before a consonant of a class, is that class's nasal
                // ("प्रियंका": pɾɪjəŋkaː, "हिंदी": ɦɪnd̪iː); elsewhere it nasalises the vowel (cm-hindi-voices).
                c == 'ं' && units.lastOrNull()?.sound in SHORT_VOWELS && w.getOrNull(i + 1)?.let { NASAL_OF[it] } != null -> {
                    units += Part(NASAL_OF.getValue(w[i + 1]), vowel = false)
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
        // The last schwa goes in a word of more than one letter; a nasalised one stays.
        if (units.size > 2 && units.last().schwa && units.last().sound == "ə") keep[units.size - 1] = false

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
            if (!units[k].schwa || !keep[k] || units[k].sound != "ə") continue
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
