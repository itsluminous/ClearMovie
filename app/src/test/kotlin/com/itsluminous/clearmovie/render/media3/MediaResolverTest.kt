package com.itsluminous.clearmovie.render.media3

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PresetSlot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class MediaResolverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "projects/p1/media").apply { mkdirs() }
    private val clip = File(root, "clip.mp4").apply { writeText("x") }
    private val preset =
        File(context.filesDir, "presets/event-frame/frame.mp4").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
    private val resolver = FileMediaResolver(mapOf(PresetSlot.FRAME to preset), root)

    private fun fails(block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (e: MediaUnavailableException) {
            true
        }

    @Test
    fun `copied media resolves inside its folder only`() {
        assertThat(resolver.resolve(MediaRef.Copied("clip.mp4")).path).isEqualTo(clip.canonicalPath)
        assertThat(fails { resolver.resolve(MediaRef.Copied("missing.mp4")) }).isTrue()
        // A symlink inside the folder that points outside it is refused.
        val outside = File(context.filesDir, "secret.db").apply { writeText("x") }
        Files.createSymbolicLink(File(root, "link.mp4").toPath(), outside.toPath())
        assertThat(fails { resolver.resolve(MediaRef.Copied("link.mp4")) }).isTrue()
        assertThat(fails { FileMediaResolver(emptyMap(), null).resolve(MediaRef.Copied("clip.mp4")) }).isTrue()
    }

    @Test
    fun `preset slots resolve to their bound files, and a missing one is unavailable`() {
        assertThat(resolver.resolve(MediaRef.Preset(PresetSlot.FRAME)).path).isEqualTo(preset.path)
        assertThat(fails { resolver.resolve(MediaRef.Preset(PresetSlot.INTRO)) }).isTrue()
        preset.delete()
        assertThat(fails { resolver.resolve(MediaRef.Preset(PresetSlot.FRAME)) }).isTrue()
    }

    @Test
    fun `store media stays a content uri`() {
        val uri = resolver.resolve(MediaRef.Store("content://media/external/video/media/42"))
        assertThat(uri.scheme).isEqualTo("content")
    }

    @Test
    fun `the uri guard allows only content uris and the app's own files`() {
        val guard = UriGuard(context)
        guard.check(Uri.fromFile(clip))
        guard.check(Uri.fromFile(File(context.cacheDir, "render/a.png")))
        guard.check(Uri.parse("content://media/external/images/media/1"))
        assertThat(fails { guard.check(Uri.parse("file:///sdcard/DCIM/a.jpg")) }).isTrue()
        assertThat(fails { guard.check(Uri.parse("file:///data/data/other.app/files/x")) }).isTrue()
        assertThat(fails { guard.check(Uri.fromFile(File(context.filesDir, "../../other/x"))) }).isTrue()
        assertThat(fails { guard.check(Uri.parse("http://example.com/a.mp4")) }).isTrue()
        assertThat(fails { guard.check(Uri.parse("/sdcard/a.mp4")) }).isTrue()
        assertThat(fails { guard.check(Uri.parse("content://")) }).isTrue()
    }
}
