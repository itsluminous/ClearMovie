package com.itsluminous.clearmovie.ui.pick

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.media.MediaAccessController
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The photo and video step (requirements 3, 5 and 6): ask once, the
 * explainer with Try again after a refusal, the picker opened exactly once
 * and never again after a rotation or a process death.
 */
@RunWith(RobolectricTestRunner::class)
class PickStepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private var requests = 0
    private var settingsOpened = 0
    private var launches = 0

    private fun controller(state: MediaAccessState) = MediaAccessController(state, launch = { requests++ }, settings = { settingsOpened++ })

    private fun asksFirst(size: ScreenSize) {
        val access = controller(MediaAccessState.NotAsked)
        compose.setContentOnce(size, access)
        compose.onNodeWithText(s(R.string.media_access_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.media_access_privacy)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.media_access_continue)).performScrollTo().performClick()
        assertThat(requests).isEqualTo(1)
        assertThat(launches).isEqualTo(0)
    }

    @Test
    fun `before the first question the explainer asks, compact at font scale 2`() = asksFirst(ScreenSize.COMPACT)

    @Test
    fun `before the first question the explainer asks, medium at font scale 2`() = asksFirst(ScreenSize.MEDIUM)

    @Test
    fun `before the first question the explainer asks, expanded at font scale 2`() = asksFirst(ScreenSize.EXPANDED)

    private fun ComposeContentTestRule.setContentOnce(
        size: ScreenSize,
        access: MediaAccessController,
        adding: Boolean = false,
    ) {
        setContent {
            AtSize(size, 2f) {
                ClearMovieTheme(dynamicColor = false) {
                    PickStep(rememberPickStepState(), access, adding, onLaunchPicker = {
                        launches++
                        true
                    })
                }
            }
        }
    }

    @Test
    fun `a refusal shows the explainer with Try again, which asks again`() {
        val access = controller(MediaAccessState.Denied)
        compose.setContentOnce(ScreenSize.COMPACT, access)
        compose.onNodeWithText(s(R.string.media_access_denied_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.media_access_denied_body)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.media_access_try_again)).performScrollTo().performClick()
        assertThat(requests).isEqualTo(1)
        assertThat(launches).isEqualTo(0)
        // Android says yes this time: the picker opens.
        access.state = MediaAccessState.Full
        compose.waitForIdle()
        assertThat(launches).isEqualTo(1)
    }

    @Test
    fun `when Android won't ask again it offers Open Settings, and Not now still opens the picker`() {
        val access = controller(MediaAccessState.DeniedForGood)
        compose.setContentOnce(ScreenSize.MEDIUM, access)
        compose.onNodeWithText(s(R.string.media_access_open_settings)).performScrollTo().performClick()
        assertThat(settingsOpened).isEqualTo(1)
        compose.onNodeWithText(s(R.string.media_access_not_now)).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(launches).isEqualTo(1)
    }

    @Test
    fun `with access the picker opens at once, and the explainer never shows`() {
        compose.setContentOnce(ScreenSize.EXPANDED, controller(MediaAccessState.Full))
        compose.waitForIdle()
        assertThat(launches).isEqualTo(1)
        compose.onNodeWithText(s(R.string.media_access_title)).assertDoesNotExist()
    }

    @Test
    fun `the picker is not opened again after a rotation or a process death, and the adding state comes back`() {
        val tester = StateRestorationTester(compose)
        val access = controller(MediaAccessState.Partial)
        tester.setContent {
            ClearMovieTheme(dynamicColor = false) {
                PickStep(rememberPickStepState(), access, adding = true, onLaunchPicker = {
                    launches++
                    true
                })
            }
        }
        compose.waitForIdle()
        assertThat(launches).isEqualTo(1)
        compose.onNodeWithTag(PICK_ADDING_TAG).assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertThat(launches).isEqualTo(1)
        compose.onNodeWithText(s(R.string.pick_adding)).assertIsDisplayed()
    }

    @Test
    fun `Not now is remembered across a restore, so the explainer does not come back`() {
        val tester = StateRestorationTester(compose)
        val access = controller(MediaAccessState.Denied)
        tester.setContent {
            ClearMovieTheme(dynamicColor = false) {
                PickStep(rememberPickStepState(), access, adding = false, onLaunchPicker = {
                    launches++
                    true
                })
            }
        }
        compose.onNodeWithText(s(R.string.media_access_not_now)).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(launches).isEqualTo(1)
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertThat(launches).isEqualTo(1)
        compose.onNodeWithText(s(R.string.media_access_try_again)).assertDoesNotExist()
    }
}
