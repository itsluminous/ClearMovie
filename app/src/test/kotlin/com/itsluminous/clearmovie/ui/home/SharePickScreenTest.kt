package com.itsluminous.clearmovie.ui.home

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The share variant of the preset pick at the three sizes and font scale 2.0 (cm-share, ROADMAP global criteria). */
@RunWith(RobolectricTestRunner::class)
class SharePickScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private fun q(
        id: Int,
        n: Int,
        vararg args: Any,
    ) = context.resources.getQuantityString(id, n, *arrayOf<Any>(n, *args))

    private val ready = PresetCard(Fixtures.preset, PresetCard.State.Ready)
    private val header = ShareHeader(ready = true, items = 12, notMedia = 2, unreadable = 1, overLimit = 3, limit = 500)

    private fun showAt(
        size: ScreenSize,
        fontScale: Float = 1f,
        share: ShareHeader = header,
        onPreset: (PresetId) -> Unit = {},
        onBlank: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            AtSize(size, fontScale) {
                ClearMovieTheme(dynamicColor = false) {
                    HomeScreen(listOf(ready), emptyList(), false, onPreset, onBlank, {}, {}, share = share, onCancelShare = onCancel)
                }
            }
        }
    }

    private fun scrollTo(text: String) = compose.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(text))

    private fun assertAllShown() {
        compose.onNodeWithContentDescription(s(R.string.action_cancel)).assertIsDisplayed()
        compose.onNodeWithContentDescription(s(R.string.action_settings)).assertDoesNotExist()
        for (text in listOf(
            q(R.plurals.share_ready, 12),
            q(R.plurals.share_skipped_not_media, 2),
            q(R.plurals.share_skipped_unreadable, 1),
            q(R.plurals.share_skipped_over_limit, 3, 500),
            s(R.string.share_choose_preset),
            s(R.string.preset_event_frame_name),
            s(R.string.home_start_blank),
        )) {
            scrollTo(text)
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        // The share's pick is only for this share: no list of earlier videos.
        compose.onNodeWithText(s(R.string.home_my_videos)).assertDoesNotExist()
    }

    @Test
    fun compact() = showAt(ScreenSize.COMPACT).also { assertAllShown() }

    @Test
    fun medium() = showAt(ScreenSize.MEDIUM).also { assertAllShown() }

    @Test
    fun expanded() = showAt(ScreenSize.EXPANDED).also { assertAllShown() }

    @Test
    fun `compact at font scale 2`() = showAt(ScreenSize.COMPACT, fontScale = 2f).also { assertAllShown() }

    @Test
    fun `medium at font scale 2`() = showAt(ScreenSize.MEDIUM, fontScale = 2f).also { assertAllShown() }

    @Test
    fun `expanded at font scale 2`() = showAt(ScreenSize.EXPANDED, fontScale = 2f).also { assertAllShown() }

    @Test
    @Config(qualifiers = "hi")
    fun `in Hindi at font scale 2`() {
        showAt(ScreenSize.COMPACT, fontScale = 2f)
        assertThat(s(R.string.share_choose_preset)).isEqualTo("अपने वीडियो के लिए प्रीसेट चुनें")
        assertAllShown()
    }

    @Test
    @Config(qualifiers = "b+hi+Latn")
    fun `in Hinglish at font scale 2`() {
        showAt(ScreenSize.MEDIUM, fontScale = 2f)
        assertThat(s(R.string.share_choose_preset)).isEqualTo("Apne video ke liye preset chunein")
        assertAllShown()
    }

    @Test
    fun `throwing the items away asks first, and Keep them is the safe choice`() {
        val picked = mutableListOf<String>()
        compose.setContent {
            AtSize(ScreenSize.COMPACT, 2f) {
                ClearMovieTheme(dynamicColor = false) {
                    com.itsluminous.clearmovie.ui.share
                        .DropShareDialog(12, onKeep = { picked += "keep" }, onDrop = { picked += "drop" })
                }
            }
        }
        compose.onNodeWithText(q(R.plurals.share_drop_title, 12)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.share_drop_keep)).performClick()
        compose.onNodeWithText(s(R.string.share_drop_confirm)).performClick()
        assertThat(picked).containsExactly("keep", "drop").inOrder()
    }

    @Test
    fun `while the items are taken in the cards wait`() {
        showAt(ScreenSize.COMPACT, share = ShareHeader(ready = false, items = 0))
        compose.onNodeWithText(s(R.string.share_getting_ready)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.preset_event_frame_name)).assertIsNotEnabled()
        scrollTo(s(R.string.home_start_blank))
        compose.onNodeWithText(s(R.string.home_start_blank)).assertIsNotEnabled()
    }

    @Test
    fun `a preset, Start without a preset and Cancel each reach their callback`() {
        val picked = mutableListOf<String>()
        showAt(
            ScreenSize.COMPACT,
            onPreset = { picked += "preset:${it.value}" },
            onBlank = { picked += "blank" },
            onCancel = { picked += "cancel" },
        )
        scrollTo(s(R.string.preset_event_frame_name))
        compose.onNodeWithText(s(R.string.preset_event_frame_name)).assertIsEnabled().performClick()
        scrollTo(s(R.string.home_start_blank))
        compose.onNodeWithText(s(R.string.home_start_blank)).performClick()
        compose.onNodeWithContentDescription(s(R.string.action_cancel)).performClick()
        assertThat(picked).containsExactly("preset:event-frame", "blank", "cancel").inOrder()
    }

    @Test
    fun `the header and list position survive a rotation or a process death`() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            AtSize(ScreenSize.MEDIUM, fontScale = 2f) {
                ClearMovieTheme(dynamicColor = false) {
                    HomeScreen(listOf(ready), emptyList(), false, {}, {}, {}, {}, share = header)
                }
            }
        }
        scrollTo(s(R.string.home_start_blank))
        compose.onNodeWithText(s(R.string.home_start_blank)).assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(s(R.string.home_start_blank)).assertIsDisplayed()
        scrollTo(q(R.plurals.share_ready, 12))
        compose.onNodeWithText(q(R.plurals.share_ready, 12)).assertIsDisplayed()
    }
}
