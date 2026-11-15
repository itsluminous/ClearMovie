package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.render.plan.AudioSegment
import com.itsluminous.clearmovie.render.plan.RenderPlan
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The factory plays silence for a sound whose file has no sound track,
 * instead of handing Media3 an item that fails the whole export.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class CompositionFactorySilenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "media").apply { mkdirs() }

    /** Two 3 s videos on MAIN; [silent] names the files without a sound track. */
    private fun build(
        silent: Set<String>,
        firstClipMs: Long = 3000,
        voiceMs: Long? = null,
    ): Pair<RenderPlan, Composition> {
        listOf("a.mp4", "b.mp4", "c.m4a").forEach { File(root, it).writeBytes(byteArrayOf(0)) }
        val ids = SequentialIds("t")
        val project =
            ProjectFactory
                .blank(ProjectId("silence"), ids)
                .addToMain(
                    listOf(
                        VideoClip(ids.clipId(), MediaRef.Copied("a.mp4"), sourceDurationMs = firstClipMs),
                        VideoClip(ids.clipId(), MediaRef.Copied("b.mp4"), sourceDurationMs = 3000),
                    ),
                    ids = ids,
                ).let { p ->
                    // A voice item exists only on a sound row, so only the sound path checks its length.
                    voiceMs?.let {
                        p.addTimedClip(
                            AudioClip(ClipId("voice"), MediaRef.Copied("c.m4a"), startMs = 0, durationMs = it, sourceDurationMs = it),
                            role = AudioRole.VOICE,
                            ids = ids,
                        )
                    } ?: p
                }
        val plan = RenderPlanBuilder().build(project)
        val probe = SourceProbe { uri: Uri -> SourceInfo(3_000_000, uri.lastPathSegment !in silent) }
        val result = AtomicReference<Any>()
        // build() refuses the main thread, which is the test thread here.
        Thread {
            result.set(
                runCatching { CompositionFactory(context, FileMediaResolver(emptyMap(), root), probe).build(plan) }.fold({ it }, { it }),
            )
        }.apply {
            start()
            join()
        }
        val value = result.get()
        if (value is Throwable) throw value
        return plan to value as Composition
    }

    private fun Composition.audioSequences() = sequences.filter { it.trackTypes == setOf(C.TRACK_TYPE_AUDIO) }

    @Test
    fun `a silent clip's sound becomes a gap of the same length`() {
        val (plan, composition) = build(silent = setOf("a.mp4"))
        val planned = plan.audio.single().segments
        val audio = composition.audioSequences().single().editedMediaItems
        // One item per planned segment, in order.
        assertThat(audio).hasSize(planned.size)
        val sounds = planned.withIndex().filter { it.value is AudioSegment.Sound }
        assertThat(sounds).hasSize(2)
        // The silent file's sound is a gap (no uri) exactly as long as its segment; the other plays its file.
        val gap = audio[sounds[0].index]
        assertThat(gap.mediaItem.localConfiguration).isNull()
        assertThat(gap.durationUs).isEqualTo(sounds[0].value.durationUs)
        assertThat(
            audio[sounds[1].index]
                .mediaItem.localConfiguration
                ?.uri
                ?.lastPathSegment,
        ).isEqualTo("b.mp4")
    }

    @Test
    fun `a row with no sound left is left out`() {
        val (plan, composition) = build(silent = setOf("a.mp4", "b.mp4"))
        assertThat(plan.audio).hasSize(1)
        assertThat(composition.audioSequences()).isEmpty()
    }

    @Test
    fun `clips with sound are unchanged`() {
        val (plan, composition) = build(silent = emptySet())
        val audio = composition.audioSequences().single().editedMediaItems
        assertThat(audio).hasSize(
            plan.audio
                .single()
                .segments.size,
        )
        assertThat(
            audio.mapNotNull {
                it.mediaItem.localConfiguration
                    ?.uri
                    ?.lastPathSegment
            },
        ).containsExactly("a.mp4", "b.mp4").inOrder()
    }

    @Test(expected = MediaUnavailableException::class)
    fun `a silent sound longer than its file is still refused`() {
        build(silent = setOf("c.m4a"), voiceMs = 5000)
    }

    @Test
    fun `a silent sound within its file's length becomes a gap`() {
        val (plan, composition) = build(silent = setOf("c.m4a"), voiceMs = 3000)
        assertThat(plan.audio).hasSize(2)
        // The voice row had only the silent sound, so only MAIN's row is left.
        assertThat(composition.audioSequences()).hasSize(1)
    }
}
