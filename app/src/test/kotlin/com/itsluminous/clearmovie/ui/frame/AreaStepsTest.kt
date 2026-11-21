package com.itsluminous.clearmovie.ui.frame

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.KeyMath
import com.itsluminous.clearmovie.domain.model.NormRect
import org.junit.Test

/** cm-frame-area: the picture area's TalkBack steps. */
class AreaStepsTest {
    private val r = NormRect(0.2, 0.2, 0.4, 0.4)

    private fun near(
        a: NormRect,
        b: NormRect,
    ) {
        for ((x, y) in listOf(a.x to b.x, a.y to b.y, a.w to b.w, a.h to b.h)) assertThat(x).isWithin(1e-9).of(y)
    }

    @Test
    fun `steps grow, shrink and move the area, clamped to the picture and the smallest area`() {
        near(AreaSteps.apply(r, AreaStep.BIGGER), NormRect(0.175, 0.175, 0.45, 0.45))
        near(AreaSteps.apply(r, AreaStep.SMALLER), NormRect(0.225, 0.225, 0.35, 0.35))
        near(AreaSteps.apply(r, AreaStep.LEFT), NormRect(0.15, 0.2, 0.4, 0.4))
        near(AreaSteps.apply(r, AreaStep.DOWN), NormRect(0.2, 0.25, 0.4, 0.4))
        near(AreaSteps.apply(NormRect.FULL, AreaStep.BIGGER), NormRect.FULL)
        near(AreaSteps.apply(NormRect(0.0, 0.0, 0.5, 0.5), AreaStep.UP), NormRect(0.0, 0.0, 0.5, 0.5))
        val tiny = AreaSteps.apply(NormRect(0.4, 0.4, 0.1, 0.1), AreaStep.SMALLER)
        assertThat(KeyMath.isUsableArea(tiny)).isTrue()
    }
}
