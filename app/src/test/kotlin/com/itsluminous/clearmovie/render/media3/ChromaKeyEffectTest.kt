package com.itsluminous.clearmovie.render.media3

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.KeySpec
import org.junit.Test
import kotlin.math.hypot

class ChromaKeyEffectTest {
    private fun distance(
        a: ArgbColor,
        b: ArgbColor,
    ): Double {
        val (cb1, cr1) = ChromaKeyEffect.chroma(a)
        val (cb2, cr2) = ChromaKeyEffect.chroma(b)
        return hypot((cb1 - cb2).toDouble(), (cr1 - cr2).toDouble())
    }

    private val key = ArgbColor.parse("#00D922")!!

    @Test
    fun `the preset tolerance keys the measured green and a re-encoded shade of it`() {
        assertThat(distance(key, key)).isEqualTo(0.0)
        // The window as decoded on the emulator and after a chat-app re-encode.
        assertThat(distance(key, ArgbColor.parse("#00D61E")!!)).isLessThan(0.18)
        assertThat(distance(key, ArgbColor.parse("#14C83C")!!)).isLessThan(0.18)
    }

    @Test
    fun `frame colours stay opaque`() {
        // Border red, strip blue, logo white, skin, a pure green, black.
        for (c in listOf("#C81414", "#1440B4", "#FFFFFF", "#E0AC69", "#000000", "#2E7D32")) {
            assertThat(distance(key, ArgbColor.parse(c)!!)).isGreaterThan(0.18 + 0.08)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out-of-range settings are refused`() {
        ChromaKeyEffect(KeySpec(key, 1.5, 0.1))
    }

    @Test
    fun `the key as a BT601 sampler shows it matches ffmpeg's decode of the reference frame`() {
        // ffmpeg -ss 1 -i Frame-White.mp4 -vf scale=in_color_matrix=bt601:in_range=tv:out_range=pc gives (16, 253, 41).
        val shown = ChromaKeyEffect.misdecodedAsBt601(key)
        val r = ((shown.argb shr 16) and 0xFF).toInt()
        val g = ((shown.argb shr 8) and 0xFF).toInt()
        val b = (shown.argb and 0xFF).toInt()
        assertThat(kotlin.math.abs(r - 16)).isAtMost(4)
        assertThat(kotlin.math.abs(g - 253)).isAtMost(4)
        assertThat(kotlin.math.abs(b - 41)).isAtMost(4)
        // Both forms are close enough that keying either is a small widening of the key.
        assertThat(distance(key, shown)).isLessThan(0.08)
        // Frame colours stay opaque against the second form too.
        for (c in listOf("#C81414", "#1440B4", "#FFFFFF", "#E0AC69", "#000000", "#2E7D32")) {
            assertThat(distance(shown, ArgbColor.parse(c)!!)).isGreaterThan(0.18 + 0.08)
        }
    }
}
