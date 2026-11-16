package com.itsluminous.clearmovie.data.export

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.export.ExportFileName
import org.junit.Test

/** The gallery store's own check on a display name: what ExportFileName makes always passes; a path never does. */
class ExportStoreNameTest {
    @Test
    fun `cleaned names pass and paths, hidden names and other extensions do not`() {
        for (name in listOf("Spasht Dhaara", "../x", "स्पष्ट धारा", null, "a".repeat(200), "evil\u202E4pm.exe")) {
            assertThat(ExportStore.isSafeDisplayName(ExportFileName.displayName(name, "New video"))).isTrue()
        }
        for (bad in listOf("a/b.mp4", "..\\b.mp4", ".hidden.mp4", "x.apk", "", "a\u0000.mp4", "tab\t.mp4")) {
            assertThat(ExportStore.isSafeDisplayName(bad)).isFalse()
        }
        assertThat(ExportStore.RELATIVE_PATH).isEqualTo("Movies/ClearMovie/")
    }
}
