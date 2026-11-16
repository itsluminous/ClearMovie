package com.itsluminous.clearmovie.domain.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExportRulesTest {
    @Test
    fun `each choice has its own height and bitrate class`() {
        assertThat(ExportQuality.SMALL.height).isEqualTo(480)
        assertThat(ExportQuality.GOOD.height).isEqualTo(720)
        assertThat(ExportQuality.BEST.height).isEqualTo(1080)
        assertThat(ExportQuality.SMALL.videoBitrate).isEqualTo(2_500_000)
        assertThat(ExportQuality.GOOD.videoBitrate).isEqualTo(6_000_000)
        assertThat(ExportQuality.BEST.videoBitrate).isEqualTo(12_000_000)
        assertThat(ExportQuality.entries.map { it.audioBitrate }.toSet()).containsExactly(192_000)
        assertThat(ExportQuality.shown).containsExactly(ExportQuality.BEST, ExportQuality.GOOD, ExportQuality.SMALL).inOrder()
        assertThat(ExportQuality.suggested).isEqualTo(ExportQuality.GOOD)
    }

    @Test
    fun `the estimate is both rates over the length plus the container`() {
        // 60 s at Good: (6 Mb/s + 192 kb/s) / 8 * 60 * 1.03 = 47.8 MB.
        assertThat(ExportQuality.GOOD.estimatedBytes(60_000_000)).isEqualTo(47_833_200)
        assertThat(ExportQuality.SMALL.estimatedBytes(60_000_000)).isLessThan(ExportQuality.GOOD.estimatedBytes(60_000_000))
        assertThat(ExportQuality.BEST.estimatedBytes(60_000_000)).isGreaterThan(ExportQuality.GOOD.estimatedBytes(60_000_000))
        assertThat(ExportQuality.BEST.estimatedBytes(-5)).isEqualTo(0)
    }

    @Test
    fun `parse accepts only a known name`() {
        assertThat(ExportQuality.parse("GOOD")).isEqualTo(ExportQuality.GOOD)
        assertThat(ExportQuality.parse("good")).isNull()
        assertThat(ExportQuality.parse(null)).isNull()
    }

    @Test
    fun `the space rule picks temp, direct or not enough at the documented boundaries`() {
        val est = 100_000_000L
        val temp = (est * 2.2).toLong() + 200L * 1024 * 1024
        val direct = (est * 1.2).toLong()
        assertThat(ExportSpace.decide(est, temp)).isEqualTo(ExportSpace.TempThenCopy)
        assertThat(ExportSpace.decide(est, temp - 1)).isEqualTo(ExportSpace.Direct)
        assertThat(ExportSpace.decide(est, direct)).isEqualTo(ExportSpace.Direct)
        assertThat(ExportSpace.decide(est, direct - 10)).isEqualTo(ExportSpace.NotEnough(10))
        assertThat(ExportSpace.decide(est, -1)).isEqualTo(ExportSpace.NotEnough(direct))
    }

    @Test
    fun `a file name never holds a path, a hidden name or invisible characters`() {
        val fb = "New video"
        assertThat(ExportFileName.displayName("Spasht Dhaara", fb)).isEqualTo("Spasht Dhaara.mp4")
        assertThat(ExportFileName.displayName("../../Android/data/x", fb)).isEqualTo("Android data x.mp4")
        assertThat(ExportFileName.displayName("a/b\\c:d*e?f\"g<h>i|j", fb)).isEqualTo("a b c d e f g h i j.mp4")
        assertThat(ExportFileName.displayName("...hidden", fb)).isEqualTo("hidden.mp4")
        assertThat(ExportFileName.displayName("trailing dots...", fb)).isEqualTo("trailing dots.mp4")
        assertThat(ExportFileName.displayName("tab\tand\nnew  line", fb)).isEqualTo("tab and new line.mp4")
        // A right-to-left override would make "evil<RLO>4pm.exe" read as an mp4.
        assertThat(ExportFileName.displayName("evil\u202E4pm.exe", fb)).isEqualTo("evil4pm.exe.mp4")
        assertThat(ExportFileName.displayName("zero\u200Bwidth\u0000null", fb)).isEqualTo("zerowidthnull.mp4")
        assertThat(ExportFileName.displayName("x.apk", fb)).isEqualTo("x.apk.mp4")
    }

    @Test
    fun `Hindi names stay Hindi and an empty one falls back`() {
        assertThat(ExportFileName.displayName("स्पष्ट धारा", "x")).isEqualTo("स्पष्ट धारा.mp4")
        assertThat(ExportFileName.displayName(null, "Naya video")).isEqualTo("Naya video.mp4")
        assertThat(ExportFileName.displayName("  /// ", "नया वीडियो")).isEqualTo("नया वीडियो.mp4")
        assertThat(ExportFileName.displayName(null, "///")).isEqualTo("ClearMovie.mp4")
        assertThat(ExportFileName.displayName("\u202E", "...")).isEqualTo("ClearMovie.mp4")
    }

    @Test
    fun `a long name is cut without splitting a letter from its marks or a surrogate pair`() {
        val latin = "a".repeat(80)
        assertThat(ExportFileName.stem(latin)).hasLength(ExportFileName.MAX_LENGTH)
        // 59 letters, then a consonant with a vowel sign that would be split at 60.
        val hindi = "क".repeat(59) + "कि"
        val cut = ExportFileName.stem(hindi)!!
        assertThat(cut).isEqualTo("क".repeat(59))
        val emoji = "a".repeat(59) + "\uD83D\uDE00" + "b"
        val cutEmoji = ExportFileName.stem(emoji)!!
        assertThat(cutEmoji.codePointCount(0, cutEmoji.length)).isEqualTo(60)
        assertThat(cutEmoji).endsWith("\uD83D\uDE00")
    }

    @Test
    fun `time left needs a few seconds and percent first`() {
        assertThat(ExportTimeLeft.estimateMs(2_000, 50)).isNull()
        assertThat(ExportTimeLeft.estimateMs(10_000, 2)).isNull()
        assertThat(ExportTimeLeft.estimateMs(10_000, 100)).isNull()
        assertThat(ExportTimeLeft.estimateMs(60_000, 25)).isEqualTo(180_000)
        assertThat(ExportTimeLeft.minutes(180_000)).isEqualTo(3)
        assertThat(ExportTimeLeft.minutes(61_000)).isEqualTo(2)
        assertThat(ExportTimeLeft.minutes(5_000)).isEqualTo(1)
    }
}
