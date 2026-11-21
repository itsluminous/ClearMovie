package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The playback strip's Play and Export (kupraki's request), with real text
 * measurement: each keeps its word while the row fits it, Play gives up its
 * word first, and either one without its word is still named for TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1300dp-h1300dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackStripTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val project =
        ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, Fixtures.preset, Fixtures.presetMedia, SequentialIds("e"))
            .addToMain(listOf(Fixtures.photo("p1"), Fixtures.video("v2", 4000)))

    private var shown by mutableStateOf(DpSize(411.dp, 891.dp) to 1f)

    private fun showAll(block: (Dp, Float) -> Unit) {
        compose.setContent {
            val (size, scale) = shown
            key(shown) {
                val h = remember { EditorHarness(project, Fixtures.preset) }
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(size) then DeviceConfigurationOverride.FontScale(scale),
                ) {
                    ClearMovieTheme(dynamicColor = false) { h.Content() }
                }
            }
        }
        for (width in WIDTHS) {
            for (scale in SCALES) {
                shown = DpSize(width, 891.dp) to scale
                compose.waitForIdle()
                block(width, scale)
            }
        }
    }

    /** True when [tag] shows [label] as its word; false when it shows only its icon, named [label] for TalkBack. */
    private fun hasWord(
        tag: String,
        label: String,
        where: String,
    ): Boolean {
        val config = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        val name = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertWithMessage("$where $tag: text=$text name=$name").that(text == label || name == label).isTrue()
        return text == label
    }

    @Test
    fun `Play and Export keep their words while they fit, Play gives up its own first, and each is always named`() {
        val play = context.getString(R.string.action_play)
        val export = context.getString(R.string.editor_export)
        val seen = mutableSetOf<Pair<Boolean, Boolean>>()
        showAll { width, scale ->
            val where = "$width at $scale x"
            val playWord = hasWord(PLAY_TAG, play, where)
            val exportWord = hasWord(EDITOR_EXPORT_TAG, export, where)
            // Play gives up its word before Export does.
            if (!exportWord) assertWithMessage(where).that(playWord).isFalse()
            // One row of 48 dp buttons, all inside the strip.
            val strip = compose.onNodeWithTag(TRANSPORT_TAG).getUnclippedBoundsInRoot()
            for (tag in listOf(PLAY_TAG, TIME_TAG, EDITOR_EXPORT_TAG)) {
                val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                if (tag != TIME_TAG) assertWithMessage("$where $tag").that((b.bottom - b.top).value).isWithin(0.5f).of(48f)
                assertWithMessage("$where $tag").that(b.left.value).isAtLeast(strip.left.value - 0.5f)
                assertWithMessage("$where $tag").that(b.right.value).isAtMost(strip.right.value + 0.5f)
            }
            seen += playWord to exportWord
        }
        // The widths and sizes above reach every form: both words, Export's word only, and icons only.
        assertThat(seen).containsAtLeast(true to true, false to true, false to false)
    }

    @Test
    fun `on a usual phone at twice the text size Export keeps its word`() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 891.dp)) then DeviceConfigurationOverride.FontScale(2f),
            ) {
                ClearMovieTheme(dynamicColor = false) { remember { EditorHarness(project, Fixtures.preset) }.Content() }
            }
        }
        compose.waitForIdle()
        assertThat(hasWord(EDITOR_EXPORT_TAG, context.getString(R.string.editor_export), "411 dp at 2x")).isTrue()
    }

    private companion object {
        val WIDTHS = listOf(200.dp, 240.dp, 280.dp, 320.dp, 360.dp, 411.dp, 600.dp)
        val SCALES = listOf(1f, 1.5f, 2f)
    }
}
