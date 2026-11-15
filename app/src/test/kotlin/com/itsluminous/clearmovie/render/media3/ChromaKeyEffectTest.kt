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
}
