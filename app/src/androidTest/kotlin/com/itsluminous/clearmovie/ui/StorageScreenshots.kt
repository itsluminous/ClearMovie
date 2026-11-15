package com.itsluminous.clearmovie.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.clearmovie.data.media.CopyBudget
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaStatus
import com.itsluminous.clearmovie.data.preset.PresetBinding
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.ui.media.CopyBudgetDialog
import com.itsluminous.clearmovie.ui.media.CopyProgressDialog
import com.itsluminous.clearmovie.ui.media.MediaAccessExplainer
import com.itsluminous.clearmovie.ui.media.MissingMediaTile
import com.itsluminous.clearmovie.ui.media.PartialAccessBanner
import com.itsluminous.clearmovie.ui.preset.PresetSlotList
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Evidence screenshots of the storage milestone's visible states. Runs only
 * when asked (`-Pandroid.testInstrumentationRunnerArguments.screenshots=light`
 * or `=dark`, after `cmd uimode night no|yes`, so the system bars match), so
 * CI skips it. Pictures go to the app's files/screens folder.
 */
@RunWith(AndroidJUnit4::class)
class StorageScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var screen: (@Composable () -> Unit) by mutableStateOf({})

    private fun binding(
        slot: PresetSlot,
        adjusted: Boolean = false,
    ) = PresetBinding(slot, File("/x"), "00", 1, null, null, 1_000_000, null, if (adjusted) ArgbColor(0xFF05C925) else null, null, null, 0)

    private val repair =
        PresetStatus(
            PresetId("event-frame"),
            mapOf(
                PresetSlot.INTRO to SlotStatus.Ready(binding(PresetSlot.INTRO)),
                PresetSlot.FRAME to SlotStatus.Missing(binding(PresetSlot.FRAME)),
                PresetSlot.ENDING to SlotStatus.Ready(binding(PresetSlot.ENDING)),
            ),
        )
    private val adjusted =
        PresetStatus(
            PresetId("event-frame"),
            mapOf(
                PresetSlot.INTRO to SlotStatus.Ready(binding(PresetSlot.INTRO)),
                PresetSlot.FRAME to SlotStatus.Ready(binding(PresetSlot.FRAME, adjusted = true)),
                PresetSlot.ENDING to SlotStatus.NotSet,
            ),
        )

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                content()
            }
        }
    }

    @Test
    fun capture() {
        val suffix = InstrumentationRegistry.getArguments().getString("screenshots")
        assumeTrue(suffix == "light" || suffix == "dark")
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { ClearMovieTheme(themeMode = ThemeMode.SYSTEM, dynamicColor = false) { screen() } }
        val shots =
            listOf<Pair<String, @Composable () -> Unit>>(
                "access-explainer" to { MediaAccessExplainer(MediaAccessState.NotAsked, {}, {}, {}) },
                "access-denied" to { MediaAccessExplainer(MediaAccessState.Denied, {}, {}, {}) },
                "access-blocked" to { MediaAccessExplainer(MediaAccessState.DeniedForGood, {}, {}, {}) },
                "preset-repair" to { Page { PresetSlotList(repair, onChoose = {}) } },
                "preset-frame-adjusted" to { Page { PresetSlotList(adjusted, onChoose = {}) } },
                "missing-clip" to {
                    Page {
                        Column {
                            MissingMediaTile(MediaStatus.MISSING, onReplace = {})
                            Box(Modifier.padding(top = 16.dp)) { PartialAccessBanner(onAllowAll = {}) }
                        }
                    }
                },
                "copy-budget" to {
                    Page {}
                    CopyBudgetDialog(
                        CopyBudget.Verdict.NeedsConfirmation(12, 1_400_000_000, 3_200_000_000, offerAllowAll = true),
                        {},
                        {},
                        {},
                    )
                },
                "copy-progress" to {
                    Page {}
                    CopyProgressDialog(37L * 1024 * 1024, 100L * 1024 * 1024, onCancel = {})
                },
            )
        val out = File(compose.activity.filesDir, "screens").apply { mkdirs() }
        for ((name, content) in shots) {
            compose.runOnUiThread { screen = content }
            compose.waitForIdle()
            Thread.sleep(700)
            val bitmap: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(out, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
