package com.itsluminous.clearmovie.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.ui.editor.PANEL_DONE_TAG
import com.itsluminous.clearmovie.ui.editor.PanelTitle
import com.itsluminous.clearmovie.ui.export.EXPORT_SAVE_TAG
import com.itsluminous.clearmovie.ui.export.ExportSheetActions
import com.itsluminous.clearmovie.ui.export.ExportSheetContent
import com.itsluminous.clearmovie.ui.export.ExportStep
import com.itsluminous.clearmovie.ui.home.HomeScreen
import com.itsluminous.clearmovie.ui.home.PresetCard
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The UX review's layout rules with the device's real text shaping, on the
 * hardest window: a 320 dp phone at the largest text size (2.0), in English
 * and Hindi. A panel's title is never cut to a few letters and its Done
 * never wraps; a home card's name takes at most two lines; the export
 * sheet's Save button stays on screen. Three short compositions each, so the
 * slow API 29 job stays inside its limit.
 */
@RunWith(AndroidJUnit4::class)
class UxLayoutDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var content by mutableStateOf<(@Composable () -> Unit)?>(null)

    @Before
    fun show() = compose.setContent { content?.invoke() }

    private fun at(
        tag: String,
        height: Int = 640,
        body: @Composable () -> Unit,
    ) {
        content = {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, height.dp)) then
                    DeviceConfigurationOverride.FontScale(2f) then
                    DeviceConfigurationOverride.Locales(LocaleList(tag)),
            ) {
                ClearMovieTheme(dynamicColor = false) { Surface { body() } }
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.single()
    }

    @Test
    fun aPanelTitleIsNeverCutAndItsDoneNeverWraps() {
        for (tag in listOf("en", "hi", "hi-Latn")) {
            val words = localized(tag, R.string.duration_title)
            at(tag) { Column { PanelTitle(words, onDone = {}, onCancel = {}) } }
            val title = compose.onNodeWithText(words, useUnmergedTree = true).layout()
            assertWithMessage("$tag: title cut").that(title.isLineEllipsized(title.lineCount - 1)).isFalse()
            val done = compose.onNodeWithText(localized(tag, R.string.action_done), useUnmergedTree = true).layout()
            assertWithMessage("$tag: Done on one line").that(done.lineCount).isEqualTo(1)
            assertWithMessage("$tag: Done button in sight")
                .that(compose.onNodeWithTag(PANEL_DONE_TAG).getUnclippedBoundsInRoot().right)
                .isAtMost(compose.onRoot().getUnclippedBoundsInRoot().right)
        }
    }

    @Test
    fun aHomeCardNameTakesAtMostTwoLines() {
        for (tag in listOf("en", "hi")) {
            at(tag) {
                HomeScreen(listOf(PresetCard(RenderHarness.preset, PresetCard.State.Ready)), emptyList(), false, {}, {}, {}, {})
            }
            val name = localized(tag, R.string.home_start_blank)
            val lines = compose.onNodeWithText(name, useUnmergedTree = true).layout().lineCount
            assertWithMessage("$tag: '$name' lines").that(lines).isAtMost(2)
        }
    }

    @Test
    fun theExportSheetKeepsSaveInSightOnAShortWindow() {
        val none =
            object : ExportSheetActions {
                override fun save(quality: ExportQuality) = Unit

                override fun stop() = Unit

                override fun hide() = Unit

                override fun close() = Unit

                override fun play() = Unit

                override fun share() = Unit

                override fun tryAgain() = Unit

                override fun replaceOther() = Unit
            }
        at("en", height = 560) {
            ExportSheetContent(
                ExportStep.Choose(
                    sizes =
                        mapOf(
                            ExportQuality.BEST to 412_000_000L,
                            ExportQuality.GOOD to 186_000_000L,
                            ExportQuality.SMALL to 64_000_000L,
                        ),
                ),
                null,
                none,
                onStop = {},
            )
        }
        val save = compose.onNodeWithTag(EXPORT_SAVE_TAG).getUnclippedBoundsInRoot()
        assertWithMessage("Save inside the window").that(save.bottom).isAtMost(560.dp)
        assertWithMessage("Save has its full height").that(save.bottom - save.top).isAtLeast(48.dp)
    }

    /** [id] in language [tag], as the composition under test reads it. */
    private fun localized(
        tag: String,
        id: Int,
    ): String {
        val config = android.content.res.Configuration(compose.activity.resources.configuration)
        config.setLocales(android.os.LocaleList.forLanguageTags(tag))
        return compose.activity.createConfigurationContext(config).getString(id)
    }
}
