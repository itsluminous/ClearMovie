package com.itsluminous.clearmovie.data.media

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.media.MediaPermissions.READ_EXTERNAL_STORAGE
import com.itsluminous.clearmovie.data.media.MediaPermissions.READ_MEDIA_IMAGES
import com.itsluminous.clearmovie.data.media.MediaPermissions.READ_MEDIA_VIDEO
import com.itsluminous.clearmovie.data.media.MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The permission state machine, the copy budget and the copier. */
class MediaRulesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `each Android version asks for its own permissions`() {
        assertThat(MediaPermissions.toRequest(29)).containsExactly(READ_EXTERNAL_STORAGE)
        assertThat(MediaPermissions.toRequest(32)).containsExactly(READ_EXTERNAL_STORAGE)
        assertThat(MediaPermissions.toRequest(33)).containsExactly(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
        assertThat(MediaPermissions.toRequest(36)).containsExactly(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED)
    }

    @Test
    fun `the access state follows what was granted and whether the app asked`() {
        fun s(
            sdk: Int,
            granted: Set<String>,
            asked: Boolean = true,
            again: Boolean = false,
        ) = MediaPermissions.state(sdk, granted, asked, again)
        assertThat(s(36, emptySet(), asked = false)).isEqualTo(MediaAccessState.NotAsked)
        assertThat(s(36, emptySet(), again = true)).isEqualTo(MediaAccessState.Denied)
        assertThat(s(36, emptySet())).isEqualTo(MediaAccessState.DeniedForGood)
        assertThat(s(36, setOf(READ_MEDIA_VISUAL_USER_SELECTED))).isEqualTo(MediaAccessState.Partial)
        assertThat(s(36, setOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED))).isEqualTo(MediaAccessState.Full)
        assertThat(s(33, setOf(READ_MEDIA_IMAGES))).isEqualTo(MediaAccessState.DeniedForGood)
        assertThat(s(33, setOf(READ_MEDIA_VISUAL_USER_SELECTED))).isEqualTo(MediaAccessState.DeniedForGood)
        assertThat(s(29, setOf(READ_EXTERNAL_STORAGE))).isEqualTo(MediaAccessState.Full)
        assertThat(MediaAccessState.NotAsked.shouldExplain).isTrue()
        assertThat(MediaAccessState.Denied.shouldExplain).isFalse()
    }

    @Test
    fun `the copy budget asks above 1 GB or below 2 GB free, and refuses what cannot fit`() {
        val gb = 1_000_000_000L
        assertThat(CopyBudget.evaluate(emptyList(), 0, false)).isEqualTo(CopyBudget.Verdict.Fits)
        assertThat(CopyBudget.evaluate(listOf(gb / 2), 10 * gb, false)).isEqualTo(CopyBudget.Verdict.Fits)
        assertThat(CopyBudget.evaluate(listOf(gb / 2, gb / 2 + 1), 10 * gb, true))
            .isEqualTo(CopyBudget.Verdict.NeedsConfirmation(2, gb + 1, 10 * gb, offerAllowAll = true))
        assertThat(CopyBudget.evaluate(listOf(gb / 2), 2 * gb, false))
            .isEqualTo(CopyBudget.Verdict.NeedsConfirmation(1, gb / 2, 2 * gb, offerAllowAll = false))
        assertThat(CopyBudget.evaluate(listOf(gb), gb, false)).isEqualTo(CopyBudget.Verdict.NotEnoughSpace(1, gb, gb))
        // An unknown size counts as a short video.
        assertThat(CopyBudget.evaluate(List(11) { -1L }, 100 * gb, false)).isInstanceOf(CopyBudget.Verdict.NeedsConfirmation::class.java)
    }

    @Test
    fun `a copy writes the file, its hash and progress, and a failure leaves nothing`() =
        runBlocking<Unit> {
            val copier = MediaCopier(Dispatchers.IO, bufferBytes = 1000)
            val target = File(tmp.root, "out/a.bin")
            val progress = mutableListOf<Long>()
            val done = copier.copy({ ByteArray(10_000) { 1 }.inputStream() }, target, 10_000, Long.MAX_VALUE) { c, _ -> progress += c }
            assertThat(done.sizeBytes).isEqualTo(10_000)
            assertThat(done.sha256).isEqualTo(MediaCopier.sha256(target))
            assertThat(progress.last()).isEqualTo(10_000)
            assertThat(File(tmp.root, "out").list()!!.toList()).containsExactly("a.bin")

            val tooBig = File(tmp.root, "out/b.bin")
            val error =
                assertThrows(
                    CopyFailedException::class.java,
                ) { runBlocking { copier.copy({ ByteArray(5000).inputStream() }, tooBig, null, 4000) } }
            assertThat(error.reason).isEqualTo(CopyFailedException.Reason.TOO_LARGE)

            val broken =
                object : InputStream() {
                    var n = 0

                    override fun read(): Int = if (n++ < 3000) 0 else throw IOException("gone")
                }
            val readError =
                assertThrows(
                    CopyFailedException::class.java,
                ) { runBlocking { copier.copy({ broken }, File(tmp.root, "out/c.bin"), null, Long.MAX_VALUE) } }
            assertThat(readError.reason).isEqualTo(CopyFailedException.Reason.UNREADABLE)
            val openError =
                assertThrows(CopyFailedException::class.java) {
                    runBlocking { copier.copy({ throw SecurityException() }, File(tmp.root, "out/d.bin"), null, 1) }
                }
            assertThat(openError.reason).isEqualTo(CopyFailedException.Reason.UNREADABLE)
            assertThat(File(tmp.root, "out").list()!!.toList()).containsExactly("a.bin")
        }
}
