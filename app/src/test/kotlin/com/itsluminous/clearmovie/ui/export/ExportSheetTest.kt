package com.itsluminous.clearmovie.ui.export

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import com.itsluminous.clearmovie.work.ExportFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The export sheet: three labelled sizes with estimates, Save, progress with
 * Stop and Hide, and the result with Play and Share, at the three window
 * sizes and at font scale 2, in English, Hindi and Hinglish.
 */
@RunWith(RobolectricTestRunner::class)
class ExportSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private val calls = mutableListOf<String>()

    private val actions =
        object : ExportSheetActions {
            override fun save(quality: ExportQuality) {
                calls += "save:${quality.name}"
            }

            override fun stop() {
                calls += "stop"
            }

            override fun hide() {
                calls += "hide"
            }

            override fun close() {
                calls += "close"
            }

            override fun play() {
                calls += "play"
            }

            override fun share() {
                calls += "share"
            }

            override fun tryAgain() {
                calls += "tryAgain"
            }

            override fun replaceOther() {
                calls += "replaceOther"
            }
        }

    private val sizes =
        ExportStep.Choose(
            sizes = mapOf(ExportQuality.SMALL to 20_000_000, ExportQuality.GOOD to 48_000_000, ExportQuality.BEST to 95_000_000),
        )

    @Composable
    private fun Sheet(
        step: ExportStep,
        blocked: ExportBlock? = null,
    ) {
        ClearMovieTheme(dynamicColor = false) { ExportSheetContent(step, blocked, actions, onStop = { calls += "askStop" }) }
    }

    private data class Shown(
        val step: ExportStep,
        val size: ScreenSize = ScreenSize.COMPACT,
        val fontScale: Float = 1f,
        val blocked: ExportBlock? = null,
        val locale: Context? = null,
    )

    private var shown by mutableStateOf<Shown?>(null)

    /** Shows [step]; the content is set once per test and then only its state changes. */
    private fun show(
        step: ExportStep,
        size: ScreenSize = ScreenSize.COMPACT,
        fontScale: Float = 1f,
        blocked: ExportBlock? = null,
        locale: Context? = null,
    ) {
        val first = shown == null
        shown = Shown(step, size, fontScale, blocked, locale)
        if (first) {
            compose.setContent {
                val now = shown ?: return@setContent
                AtSize(now.size, now.fontScale) {
                    val localized = now.locale
                    if (localized == null) {
                        Sheet(now.step, now.blocked)
                    } else {
                        CompositionLocalProvider(
                            LocalContext provides localized,
                            LocalConfiguration provides localized.resources.configuration,
                        ) { Sheet(now.step, now.blocked) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `three sizes with estimates, Good suggested, Save exports the chosen one at every size`() {
        show(sizes)
        compose.onNodeWithTag(exportQualityTag(ExportQuality.GOOD)).assertIsSelected()
        for (size in ScreenSize.entries) {
            calls.clear()
            show(sizes, size)
            for (label in listOf(R.string.export_quality_best, R.string.export_quality_good, R.string.export_quality_small)) {
                compose.onNodeWithText(s(label)).assertExists()
            }
            compose.onNodeWithText(s(R.string.export_suggested)).assertExists()
            compose.onNodeWithTag(exportQualityTag(ExportQuality.BEST)).performScrollTo().performClick()
            compose.onNodeWithTag(exportQualityTag(ExportQuality.BEST)).assertIsSelected()
            compose.onNodeWithTag(exportQualityTag(ExportQuality.SMALL)).performScrollTo().performClick()
            compose.onNodeWithTag(exportQualityTag(ExportQuality.SMALL)).assertIsSelected()
            compose
                .onNodeWithTag(EXPORT_SAVE_TAG)
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            assertThat(calls).containsExactly("save:SMALL")
        }
    }

    @Test
    fun `choices sit in a column on a phone held upright and in a row when wide`() {
        show(sizes, ScreenSize.COMPACT)
        val best = compose.onNodeWithTag(exportQualityTag(ExportQuality.BEST)).getUnclippedBoundsInRoot()
        val small = compose.onNodeWithTag(exportQualityTag(ExportQuality.SMALL)).getUnclippedBoundsInRoot()
        assertThat(small.top).isGreaterThan(best.bottom)
    }

    @Test
    fun `in a wide window the choices are side by side`() {
        show(sizes, ScreenSize.EXPANDED)
        val best = compose.onNodeWithTag(exportQualityTag(ExportQuality.BEST)).getUnclippedBoundsInRoot()
        val small = compose.onNodeWithTag(exportQualityTag(ExportQuality.SMALL)).getUnclippedBoundsInRoot()
        assertThat(small.left).isGreaterThan(best.right)
        assertThat(small.top).isEqualTo(best.top)
    }

    @Test
    fun `a size that does not fit says how much to free and cannot be saved`() {
        show(sizes.copy(shortBy = mapOf(ExportQuality.GOOD to 5_000_000)))
        compose.onNodeWithTag(EXPORT_SAVE_TAG).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(exportQualityTag(ExportQuality.SMALL)).performScrollTo().performClick()
        compose.onNodeWithTag(EXPORT_SAVE_TAG).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `progress shows the percentage, time left, and that leaving is fine, with Stop and Hide`() {
        for (size in ScreenSize.entries) {
            calls.clear()
            show(ExportStep.Running(42, 3, restarted = true), size, 2f)
            compose.onNodeWithText(context.getString(R.string.export_percent, 42)).assertExists()
            compose.onNodeWithText(context.resources.getQuantityString(R.plurals.export_minutes_left, 3, 3)).assertExists()
            compose.onNodeWithText(s(R.string.export_restarted)).assertExists()
            compose.onNodeWithText(s(R.string.export_leave_ok)).assertExists()
            compose
                .onNodeWithTag(EXPORT_STOP_TAG)
                .assertIsDisplayed()
                .performClick()
            compose
                .onNodeWithTag(EXPORT_HIDE_TAG)
                .assertIsDisplayed()
                .performClick()
            assertThat(calls).containsExactly("askStop", "hide").inOrder()
        }
    }

    @Test
    fun `before the first percent the bar moves and says it is getting ready`() {
        show(ExportStep.Running(0, null, restarted = false))
        compose.onNodeWithText(s(R.string.export_getting_ready)).assertExists()
        compose.onNodeWithText(context.getString(R.string.export_percent, 0)).assertDoesNotExist()
        compose.onNodeWithTag(EXPORT_STOP_TAG).assertExists()
    }

    @Test
    fun `Stop asks first, and only Stop stops`() {
        compose.setContent { ClearMovieTheme(dynamicColor = false) { ExportSheet(ExportStep.Running(10, null, false), null, actions) } }
        compose.onNodeWithTag(EXPORT_STOP_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithText(s(R.string.export_stop_confirm_title)).assertExists()
        compose.onNodeWithText(s(R.string.export_keep_saving)).performClick()
        assertThat(calls).isEmpty()
        compose.onNodeWithTag(EXPORT_STOP_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithText(s(R.string.export_stop_confirm_body)).assertExists()
        // The question's Stop button, not the sheet's.
        compose.onNodeWithText(s(R.string.export_stop_confirm_title)).assertExists()
        compose
            .onAllNodes(hasText(s(R.string.export_stop)) and !hasTestTag(EXPORT_STOP_TAG) and hasClickAction())
            .onFirst()
            .performClick()
        assertThat(calls).containsExactly("stop")
    }

    @Test
    fun `done offers Play and Share and names the file`() {
        for (size in ScreenSize.entries) {
            calls.clear()
            show(ExportStep.Done("content://media/x/video/media/1", "Spasht Dhaara.mp4", 48_000_000), size, 2f)
            compose.onNodeWithText(s(R.string.export_done_title)).assertExists()
            compose.onNodeWithText("Spasht Dhaara.mp4", substring = true).assertExists()
            compose.onNodeWithTag(EXPORT_PLAY_TAG).assertIsDisplayed().performClick()
            compose.onNodeWithTag(EXPORT_SHARE_TAG).assertIsDisplayed().performClick()
            compose.onNodeWithTag(EXPORT_CLOSE_TAG).assertIsDisplayed().performClick()
            assertThat(calls).containsExactly("play", "share", "close").inOrder()
        }
    }

    @Test
    fun `each failure has its own plain message and a way on`() {
        val messages =
            mapOf(
                ExportFailure.MISSING_MEDIA to s(R.string.export_failed_missing),
                ExportFailure.PRESET_BROKEN to s(R.string.export_failed_preset),
                ExportFailure.NOTHING to s(R.string.export_failed_nothing),
                ExportFailure.FAILED to s(R.string.export_failed_other),
                ExportFailure.GONE to s(R.string.export_failed_gone),
            )
        for ((reason, text) in messages) {
            show(ExportStep.Failed(reason))
            compose.onNodeWithText(text).assertExists()
            val tryAgain = compose.onNodeWithText(s(R.string.export_try_again))
            if (reason == ExportFailure.GONE) tryAgain.assertDoesNotExist() else tryAgain.assertExists()
        }
        show(ExportStep.Failed(ExportFailure.NO_SPACE, 3_000_000))
        compose.onNodeWithText(s(R.string.export_failed_title)).assertExists()
    }

    @Test
    fun `a known problem shows before the sizes, and another video's export can be replaced`() {
        show(sizes, blocked = ExportBlock.MISSING_MEDIA)
        compose.onNodeWithText(s(R.string.export_blocked_missing)).assertExists()
        compose.onNodeWithTag(EXPORT_SAVE_TAG).assertDoesNotExist()
        show(ExportStep.OtherRunning)
        compose.onNodeWithText(s(R.string.export_other_running_body)).assertExists()
        compose.onNodeWithText(s(R.string.export_other_replace)).performClick()
        assertThat(calls).containsExactly("replaceOther")
    }

    @Test
    fun `the chosen size survives a rotation`() {
        val restore = StateRestorationTester(compose)
        restore.setContent { Sheet(sizes) }
        compose.onNodeWithTag(exportQualityTag(ExportQuality.BEST)).performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(exportQualityTag(ExportQuality.BEST)).assertIsSelected()
    }

    @Test
    fun `the labels are translated in Hindi and Hinglish`() {
        for (tag in listOf("hi", "hi-Latn")) {
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val localized = context.createConfigurationContext(config)
            show(sizes, locale = localized)
            for (id in listOf(R.string.export_title, R.string.export_quality_best, R.string.export_quality_small, R.string.export_save)) {
                val text = localized.getString(id)
                assertThat(text).isNotEqualTo(s(id))
                compose.onNodeWithText(text).assertExists()
            }
        }
    }
}
