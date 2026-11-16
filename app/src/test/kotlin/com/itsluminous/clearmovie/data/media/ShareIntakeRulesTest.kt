package com.itsluminous.clearmovie.data.media

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

/** Reading shares from untrusted intents, the photo-or-video check, and the capture-time order (cm-share). */
@RunWith(RobolectricTestRunner::class)
class ShareIntakeRulesTest {
    private fun media(id: Int) = "content://media/external/images/media/$id".toUri()

    private fun multiple(uris: List<Uri?>) =
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))

    @Test
    fun `a single photo`() {
        val shared = ShareLinks.read(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, media(1)))
        assertThat(shared).isEqualTo(SharedLinks(listOf(media(1))))
    }

    @Test
    fun `many items keep their shared order, once each`() {
        val shared = ShareLinks.read(multiple(listOf(media(3), media(1), media(3), media(2))))!!
        assertThat(shared.uris).containsExactly(media(3), media(1), media(2)).inOrder()
        assertThat(shared.refused).isEqualTo(0)
    }

    @Test
    fun `links only in the ClipData are read too`() {
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                clipData = ClipData.newRawUri(null, media(5)).also { it.addItem(ClipData.Item(media(6))) }
            }
        assertThat(ShareLinks.read(intent)!!.uris).containsExactly(media(5), media(6)).inOrder()
    }

    @Test
    fun `file, other schemes, null and malformed links are refused and counted`() {
        val shared =
            ShareLinks.read(
                multiple(
                    listOf(
                        "file:///data/data/com.itsluminous.ClearMovie/databases/clearmovie.db".toUri(),
                        "http://example.com/a.jpg".toUri(),
                        null,
                        "content:///no-authority".toUri(),
                        "content://media/external/images/media/1 2".toUri(),
                        media(7),
                    ),
                ),
            )!!
        assertThat(shared.uris).containsExactly(media(7))
        assertThat(shared.refused).isEqualTo(5)
    }

    @Test
    fun `a share with no extras, or an extra of the wrong type, carries nothing and never throws`() {
        assertThat(ShareLinks.read(Intent(Intent.ACTION_SEND))).isEqualTo(SharedLinks(emptyList()))
        assertThat(ShareLinks.read(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "content://media/x"))!!.isEmpty).isTrue()
        assertThat(ShareLinks.read(Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, media(1)))!!.isEmpty).isTrue()
    }

    @Test
    fun `anything but a share is not read`() {
        assertThat(ShareLinks.read(Intent(Intent.ACTION_MAIN))).isNull()
        assertThat(ShareLinks.read(Intent(Intent.ACTION_VIEW, media(1)))).isNull()
        assertThat(ShareLinks.read(null)).isNull()
    }

    @Test
    fun `a huge share keeps the first 500 and counts the rest`() {
        val shared = ShareLinks.read(multiple((1..20_050).map { media(it) }))!!
        assertThat(shared.uris).hasSize(ShareLinks.MAX_ITEMS)
        assertThat(shared.uris.first()).isEqualTo(media(1))
        assertThat(shared.overLimit).isEqualTo(20_050 - ShareLinks.MAX_ITEMS)
    }

    @Test
    fun `very long links stop at the character budget, so handing them on stays well under the binder limit`() {
        val long = (1..200).map { "content://media/external/images/media/$it?pad=" + "x".repeat(1_900) }.map { it.toUri() }
        val shared = ShareLinks.read(multiple(long))!!
        assertThat(shared.uris.sumOf { it.toString().length }).isAtMost(ShareLinks.MAX_TOTAL_CHARS)
        assertThat(shared.uris.size + shared.overLimit).isEqualTo(200)
        assertThat(shared.overLimit).isGreaterThan(0)
    }

    @Test
    fun `a hand-off token works once, and a made-up one gives nothing`() {
        val links = SharedLinks(listOf(media(1)), refused = 2, overLimit = 3)
        val token = ShareHandoff.put(links)
        assertThat(ShareHandoff.take("made-up")).isNull()
        assertThat(ShareHandoff.take(null)).isNull()
        assertThat(ShareHandoff.take(token)).isEqualTo(links)
        assertThat(ShareHandoff.take(token)).isNull()
        // The internal action is not a share to read: its links come only through the token.
        assertThat(ShareLinks.read(Intent(ShareLinks.ACTION_SHARED).putExtra(Intent.EXTRA_STREAM, media(1)))).isNull()
    }

    @Test
    fun `links with a user part in the authority are refused`() {
        val shared = ShareLinks.read(multiple(listOf("content://0@com.itsluminous.ClearMovie.androidx-startup/x".toUri(), media(2))))!!
        assertThat(shared.uris).containsExactly(media(2))
        assertThat(shared.refused).isEqualTo(1)
    }

    @Test
    fun `a provider date in seconds is read as seconds, and 0 is no date`() {
        assertThat(CaptureDates.provider(1_794_627_000_000L)).isEqualTo(1_794_627_000_000L)
        assertThat(CaptureDates.provider(1_794_627_000L)).isEqualTo(1_794_627_000_000L)
        assertThat(CaptureDates.provider(0)).isNull()
        assertThat(CaptureDates.provider(null)).isNull()
        assertThat(CaptureDates.provider(5_000L)).isNull()
    }

    @Test
    fun `photo and video types are media, other known types are not, missing types are left to the file`() {
        assertThat(ShareTypes.kindOf("image/jpeg")).isEqualTo(ShareTypes.Kind.MEDIA)
        assertThat(ShareTypes.kindOf("VIDEO/MP4")).isEqualTo(ShareTypes.Kind.MEDIA)
        assertThat(ShareTypes.kindOf("application/pdf")).isEqualTo(ShareTypes.Kind.OTHER)
        assertThat(ShareTypes.kindOf("text/plain")).isEqualTo(ShareTypes.Kind.OTHER)
        assertThat(ShareTypes.kindOf("audio/mpeg")).isEqualTo(ShareTypes.Kind.OTHER)
        assertThat(ShareTypes.kindOf(null)).isEqualTo(ShareTypes.Kind.UNKNOWN)
        assertThat(ShareTypes.kindOf("*/*")).isEqualTo(ShareTypes.Kind.UNKNOWN)
        assertThat(ShareTypes.kindOf("application/octet-stream")).isEqualTo(ShareTypes.Kind.UNKNOWN)
    }

    @Test
    fun `every date known plays in the order taken`() {
        assertThat(CaptureOrder.order(listOf("c" to 30L, "a" to 10L, "b" to 20L)) { it.second }.map { it.first })
            .containsExactly("a", "b", "c")
            .inOrder()
    }

    @Test
    fun `no dates at all keeps the shared order`() {
        assertThat(CaptureOrder.order(listOf("x", "y", "z")) { null }).containsExactly("x", "y", "z").inOrder()
    }

    @Test
    fun `an undated item keeps its shared place and the dated ones sort around it`() {
        val shared = listOf("d" to 40L, "?" to null, "b" to 20L, "a" to 10L, "?2" to null, "c" to 30L)
        assertThat(CaptureOrder.order(shared) { it.second }.map { it.first })
            .containsExactly("a", "?", "b", "c", "?2", "d")
            .inOrder()
    }

    @Test
    fun `items taken at the same moment keep their shared order`() {
        val shared = listOf("p" to 5L, "q" to 1L, "r" to 5L, "s" to 1L)
        assertThat(CaptureOrder.order(shared) { it.second }.map { it.first }).containsExactly("q", "s", "p", "r").inOrder()
    }

    @Test
    fun `EXIF dates are read in their offset, or in the phone's zone`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertThat(CaptureDates.exif("2026:11:14 09:00:00", "+05:30", ZoneId.of("UTC"))).isEqualTo(1_794_627_000_000L)
        assertThat(CaptureDates.exif("2026:11:14 09:00:00", null, kolkata)).isEqualTo(1_794_627_000_000L)
        assertThat(CaptureDates.exif("2026:11:14 09:00:00", "garbage", kolkata)).isEqualTo(1_794_627_000_000L)
        assertThat(CaptureDates.exif("0000:00:00 00:00:00", null, kolkata)).isNull()
        assertThat(CaptureDates.exif("not a date", null, kolkata)).isNull()
        assertThat(CaptureDates.exif(null, null, kolkata)).isNull()
    }

    @Test
    fun `video creation times are read, and the unset 1904 date is no date`() {
        assertThat(CaptureDates.mp4("20261114T033500.000Z")).isEqualTo(1_794_627_300_000L)
        assertThat(CaptureDates.mp4("20261114T033500Z")).isEqualTo(1_794_627_300_000L)
        assertThat(CaptureDates.mp4("19040101T000000.000Z")).isNull()
        assertThat(CaptureDates.mp4("")).isNull()
        assertThat(CaptureDates.mp4("yesterday")).isNull()
    }
}
