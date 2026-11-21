package com.itsluminous.clearmovie.ui.editor

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.TrackId
import org.junit.Test

/** The editor's on-screen state survives a restore, the row-end "+" chooser's time and row included (cm-plus-position). */
class EditorScreenStateSaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(panel: OpenPanel): OpenPanel? {
        val saved = with(EditorScreenState.Saver) { scope.save(EditorScreenState(panel = panel)) }!!
        return EditorScreenState.Saver.restore(saved)!!.panel
    }

    @Test
    fun `an Add here chooser keeps its time and whether it goes on another row`() {
        val full = OpenPanel(Panel.INSERT, trackId = TrackId("text"), atMs = 11_000, otherRow = true)
        assertThat(roundTrip(full)).isEqualTo(full)
        val plain = OpenPanel(Panel.INSERT, trackId = TrackId("text"), atMs = 5000)
        assertThat(roundTrip(plain)).isEqualTo(plain)
        val atPlayhead = OpenPanel(Panel.INSERT, trackId = TrackId("text"))
        assertThat(roundTrip(atPlayhead)).isEqualTo(atPlayhead)
    }
}
