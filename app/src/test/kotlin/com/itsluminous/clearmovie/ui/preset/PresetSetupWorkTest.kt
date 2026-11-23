package com.itsluminous.clearmovie.ui.preset

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.preset.CopyReason
import com.itsluminous.clearmovie.data.preset.PresetBinding
import com.itsluminous.clearmovie.data.preset.SlotBindResult
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.PresetSlot
import org.junit.Test
import java.io.File

/**
 * cm-preset-link-copy: what setup says while a file is taken in and after.
 * "Copying" only while a copy runs, with why when the preset meant to use
 * the file where it is; a note after such a copy; nothing after a link.
 */
class PresetSetupWorkTest {
    private val copied = PresetBinding(PresetSlot.FRAME, File("/x/frame.mp4"), "00", 1, null, null, 6_000_000, null, null, null, null, 0)
    private val linked = copied.copy(file = null, linkedUri = "content://docs/document/frame")

    @Test
    fun `progress is Copying until every byte is in, then Checking`() {
        assertThat(SetupWork.copying(10, 100, null)).isEqualTo(SetupWork.Copying(10, 100, null))
        assertThat(SetupWork.copying(10, null, CopyReason.SOURCE)).isEqualTo(SetupWork.Copying(10, null, CopyReason.SOURCE))
        assertThat(SetupWork.copying(100, 100, CopyReason.SOURCE)).isEqualTo(SetupWork.Checking)
    }

    @Test
    fun `a file used where it is leaves no message`() {
        assertThat(SetupMessage.afterBound(SlotBindResult.Bound(linked), null)).isNull()
    }

    @Test
    fun `a copy the preset asked for leaves no message`() {
        assertThat(SetupMessage.afterBound(SlotBindResult.Bound(copied), null)).isNull()
    }

    @Test
    fun `a copy instead of a link says why`() {
        assertThat(SetupMessage.afterBound(SlotBindResult.Bound(copied), CopyReason.SOURCE))
            .isEqualTo(SetupMessage.KeptCopy(CopyReason.SOURCE))
        assertThat(SetupMessage.afterBound(SlotBindResult.Bound(copied), CopyReason.LIMIT))
            .isEqualTo(SetupMessage.KeptCopy(CopyReason.LIMIT))
    }

    @Test
    fun `a video of another shape is said first, over a copy`() {
        assertThat(SetupMessage.afterBound(SlotBindResult.Bound(copied, CanvasShape.PORTRAIT), CopyReason.SOURCE))
            .isEqualTo(SetupMessage.ShapeDiffers)
    }
}
