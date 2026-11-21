package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.ui.components.LocalLabelSizeProbe
import com.itsluminous.clearmovie.ui.components.MIN_LABEL_FONT_SIZE
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The playback strip's Play and Export (kupraki's request), with real text
 * measurement: their words shrink together and stay one size, are never cut,
 * Play gives up its word before Export does, and either one without its word
 * is still named for TalkBack.
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

    /** The size each label last settled on, by its words. */
    private val sizes = mutableMapOf<String, TextUnit>()

    private fun showAll(block: (Dp, Float) -> Unit) {
        compose.setContent {
            val (size, scale) = shown
            key(shown) {
                val h = remember { EditorHarness(project, Fixtures.preset) }
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(size) then DeviceConfigurationOverride.FontScale(scale),
                ) {
                    CompositionLocalProvider(LocalLabelSizeProbe provides { text, s -> sizes[text] = s }) {
                        ClearMovieTheme(dynamicColor = false) { h.Content() }
                    }
                }
            }
        }
        for (width in WIDTHS) {
            for (scale in SCALES) {
                sizes.clear()
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

    /** How [text] (a label, or the time with [tag]) was laid out. */
    private fun layoutOf(
        text: String? = null,
        tag: String? = null,
    ): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node =
            if (tag != null) {
                compose.onNodeWithTag(tag).fetchSemanticsNode()
            } else {
                compose.onNode(hasText(text!!), useUnmergedTree = true).fetchSemanticsNode()
            }
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /** The strip at every width and text size: what it shows, and that nothing in it is cut or squeezed. */
    private fun checkStrip(): Set<Pair<Boolean, Boolean>> {
        val play = context.getString(R.string.action_play)
        val export = context.getString(R.string.editor_export)
        val seen = mutableSetOf<Pair<Boolean, Boolean>>()
        showAll { width, scale ->
            val where = "$width at $scale x"
            val playWord = hasWord(PLAY_TAG, play, where)
            val exportWord = hasWord(EDITOR_EXPORT_TAG, export, where)
            // Play gives up its word before Export does.
            if (!exportWord) assertWithMessage(where).that(playWord).isFalse()
            // Two words are one size, and never cut: on one line, or Export's on two.
            if (playWord && exportWord) assertWithMessage(where).that(sizes[play]).isEqualTo(sizes[export])
            for ((shown, label) in listOf(playWord to play, exportWord to export)) {
                if (!shown) continue
                val layout = layoutOf(label)
                // At most two lines, and a one-word label on one: never broken inside a word.
                assertWithMessage("$where $label lines").that(layout.lineCount).isAtMost(minOf(2, label.split(' ').size))
                assertWithMessage("$where $label cut").that(layout.isLineEllipsized(layout.lineCount - 1)).isFalse()
                assertWithMessage("$where $label overflow").that(layout.hasVisualOverflow).isFalse()
                assertWithMessage("$where $label size").that(sizes[label]!!.value).isAtLeast(MIN_LABEL_FONT_SIZE.value)
            }
            // With room to spare, both words at the label style's own size.
            if (width == 600.dp && scale == 1f) {
                assertWithMessage(where).that(sizes[play]?.value).isEqualTo(LABEL_SIZE)
                assertWithMessage(where).that(sizes[export]?.value).isEqualTo(LABEL_SIZE)
            }
            // The time is never squeezed.
            val time = layoutOf(tag = TIME_TAG)
            assertWithMessage("$where time")
                .that(time.size.width.toFloat())
                .isAtLeast(time.multiParagraph.intrinsics.maxIntrinsicWidth - 1f)
            // One row of 48 dp buttons, side by side inside the strip.
            val strip = compose.onNodeWithTag(TRANSPORT_TAG).getUnclippedBoundsInRoot()
            val p = compose.onNodeWithTag(PLAY_TAG).getUnclippedBoundsInRoot()
            val t = compose.onNodeWithTag(TIME_TAG).getUnclippedBoundsInRoot()
            val e = compose.onNodeWithTag(EDITOR_EXPORT_TAG).getUnclippedBoundsInRoot()
            for ((name, b) in listOf("play" to p, "export" to e)) {
                assertWithMessage("$where $name height").that((b.bottom - b.top).value).isWithin(0.5f).of(48f)
                assertWithMessage("$where $name width").that((b.right - b.left).value).isAtLeast(47.5f)
            }
            assertWithMessage(where).that(p.left.value).isAtLeast(strip.left.value - 0.5f)
            assertWithMessage(where).that(t.left.value).isAtLeast(p.right.value - 0.5f)
            assertWithMessage(where).that(e.left.value).isAtLeast(t.right.value - 0.5f)
            assertWithMessage(where).that(e.right.value).isAtMost(strip.right.value + 0.5f)
            seen += playWord to exportWord
        }
        return seen
    }

    @Test
    fun `in English the words shrink together, uncut, then Play and last Export keep only their named icons`() {
        // Both words, Export's only and icons only all come up across these widths and sizes.
        assertThat(checkStrip()).containsExactly(true to true, false to true, false to false)
    }

    @Test
    @Config(qualifiers = "+hi")
    fun `in Hindi Play gives up its word first, then Export, and each is always named and uncut`() {
        assertThat(checkStrip()).containsAtLeast(true to true, false to true)
    }

    @Test
    @Config(qualifiers = "+b+hi+Latn")
    fun `in Hinglish the strip keeps one row of uncut words or named icons`() {
        assertThat(checkStrip()).contains(true to true)
    }

    @Test
    fun `on a usual phone at twice the text size both words stay`() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 891.dp)) then DeviceConfigurationOverride.FontScale(2f),
            ) {
                ClearMovieTheme(dynamicColor = false) { remember { EditorHarness(project, Fixtures.preset) }.Content() }
            }
        }
        compose.waitForIdle()
        assertThat(hasWord(PLAY_TAG, context.getString(R.string.action_play), "411 dp at 2x")).isTrue()
        assertThat(hasWord(EDITOR_EXPORT_TAG, context.getString(R.string.editor_export), "411 dp at 2x")).isTrue()
    }

    private companion object {
        // From the narrowest phone (320 dp); a narrower split-screen window can squeeze the time at large text.
        val WIDTHS = listOf(320.dp, 360.dp, 411.dp, 600.dp)

        /** Material's labelLarge, the strip's label style. */
        const val LABEL_SIZE = 14f
        val SCALES = listOf(1f, 1.5f, 2f)
    }
}
