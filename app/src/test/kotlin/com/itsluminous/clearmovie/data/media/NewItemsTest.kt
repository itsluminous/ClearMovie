package com.itsluminous.clearmovie.data.media

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fit
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.VideoClip
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Picked items become MAIN photos and videos, in the order given; what can't be used is counted. */
@RunWith(RobolectricTestRunner::class)
class NewItemsTest {
    private val project = ProjectId("p1")

    private fun item(
        n: Int,
        mime: String?,
        kept: Boolean = true,
    ) = IntakeItem(
        Uri.parse("content://picker/$n"),
        if (kept) MediaRef.Store("content://media/external/file/$n") else null,
        SourceInfo("f$n", 10, mime, null),
    )

    private val probe =
        MediaFactsProbe { _, ref, mime ->
            val n = (ref as MediaRef.Store).uri.substringAfterLast('/').toInt()
            when {
                mime?.startsWith("image/") == true -> MediaFacts(null, hasVideo = false, hasAudio = false, isImage = true)

                n == 4 -> null

                // can't be opened
                n == 5 -> MediaFacts(30, hasVideo = true, hasAudio = true, isImage = false)

                // too short to use
                n == 6 -> MediaFacts(5000, hasVideo = false, hasAudio = true, isImage = false)

                // sound only
                else -> MediaFacts(7000, hasVideo = true, hasAudio = true, isImage = false)
            }
        }

    @Test
    fun `photos and videos keep their order, with the project's photo length and fit`() =
        runTest {
            val r =
                newMainItems(project, listOf(item(1, "image/jpeg"), item(2, "video/mp4"), item(3, "image/png")), 4000, Fit.FIT_BARS, probe)
            assertThat(r.skipped).isEqualTo(0)
            assertThat(r.clips.map { it::class }).containsExactly(PhotoClip::class, VideoClip::class, PhotoClip::class).inOrder()
            val photo = r.clips[0] as PhotoClip
            assertThat(photo.durationMs).isEqualTo(4000)
            assertThat(photo.fit).isEqualTo(Fit.FIT_BARS)
            val video = r.clips[1] as VideoClip
            assertThat(video.sourceDurationMs).isEqualTo(7000)
            assertThat(video.media).isEqualTo(MediaRef.Store("content://media/external/file/2"))
            assertThat(r.clips.map { it.id }.toSet()).hasSize(3)
        }

    @Test
    fun `items that were not kept, can't be opened, are too short or have no picture are skipped and counted`() =
        runTest {
            val r =
                newMainItems(
                    project,
                    listOf(
                        item(1, "video/mp4", kept = false),
                        item(4, "video/mp4"),
                        item(5, "video/mp4"),
                        item(6, "audio/mp4"),
                        item(7, "video/mp4"),
                    ),
                    3000,
                    Fit.FILL_CROP,
                    probe,
                )
            assertThat(r.skipped).isEqualTo(4)
            assertThat(r.clips).hasSize(1)
        }
}
