package com.itsluminous.clearmovie.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.clearmovie.data.preset.CopyReason
import com.itsluminous.clearmovie.data.preset.PresetBinding
import com.itsluminous.clearmovie.data.preset.PresetStatus
import com.itsluminous.clearmovie.data.preset.SlotStatus
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.ui.preset.PresetSetupScreen
import com.itsluminous.clearmovie.ui.preset.SetupMessage
import com.itsluminous.clearmovie.ui.preset.SetupUi
import com.itsluminous.clearmovie.ui.preset.SetupWork
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Evidence screenshots for cm-preset-link-copy: what preset setup says while
 * a file is taken in. Checking the file (a file used where it is), Copying
 * the file with the reason (a file that had to be copied after all), and the
 * note after such a copy, in light and dark, English, Hindi and Hinglish,
 * and at font scale 2. The whole screen is captured, so the dialog window
 * shows. Runs only when asked (`-e screenshots <prefix>`), so CI skips it.
 * Pictures go to the app's files/screens folder.
 */
@RunWith(AndroidJUnit4::class)
class PresetSetupWorkScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var screen: (@Composable () -> Unit) by mutableStateOf({})

    private data class Shot(
        val name: String,
        val work: SetupWork?,
        val message: SetupMessage? = null,
        val mode: ThemeMode = ThemeMode.LIGHT,
        val lang: String = "en",
        val scale: Float = 1f,
    )

    private fun binding(slot: PresetSlot) =
        PresetBinding(slot, null, "", 1, "$slot.mp4", "video/mp4", 6_000_000, null, null, null, null, 0, "content://docs/document/$slot")

    @Test
    fun setupWork() {
        val prefix = InstrumentationRegistry.getArguments().getString("screenshots")
        assumeTrue("screenshots run only when asked", prefix != null)
        compose.setContent { screen() }
        val preset = RenderHarness.preset
        val choosing =
            PresetStatus(
                preset.id,
                mapOf(
                    PresetSlot.INTRO to SlotStatus.Ready(binding(PresetSlot.INTRO)),
                    PresetSlot.FRAME to SlotStatus.NotSet,
                    PresetSlot.ENDING to SlotStatus.NotSet,
                ),
            )
        val checking = SetupWork.Checking
        val copying = SetupWork.Copying(42, 100, CopyReason.SOURCE)
        val note = SetupMessage.KeptCopy(CopyReason.SOURCE)
        val shots =
            listOf(
                Shot("checking", checking),
                Shot("checking-dark", checking, mode = ThemeMode.DARK),
                Shot("checking-hi", checking, lang = "hi"),
                Shot("copy-why", copying),
                Shot("copy-why-dark", copying, mode = ThemeMode.DARK),
                Shot("copy-why-hi", copying, lang = "hi"),
                Shot("copy-why-hinglish", copying, lang = "hi-Latn"),
                Shot("copy-why-font2", copying, scale = 2f),
                Shot("copy-why-limit", SetupWork.Copying(42, 100, CopyReason.LIMIT)),
                Shot("copy-note", null, note),
                Shot("copy-note-dark", null, note, mode = ThemeMode.DARK),
                Shot("copy-note-hi", null, note, lang = "hi"),
                Shot("copy-note-font2", null, note, scale = 2f),
            )
        val out = File(checkNotNull(compose.activity.getExternalFilesDir(null)), "screens").apply { mkdirs() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (shot in shots) {
            screen = {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.FontScale(shot.scale) then DeviceConfigurationOverride.Locales(LocaleList(shot.lang)),
                ) {
                    ClearMovieTheme(themeMode = shot.mode, dynamicColor = false) {
                        key(shot) {
                            PresetSetupScreen(
                                SetupUi(preset, choosing, work = shot.work, message = shot.message, copiesFiles = false),
                                newVideo = true,
                                onBack = {},
                                onChoose = {},
                                onChooseDefinition = {},
                                onDone = {},
                                onCancelWork = {},
                                onDismissMessage = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            Thread.sleep(600)
            val bitmap = checkNotNull(automation.takeScreenshot())
            FileOutputStream(File(out, "$prefix-${shot.name}.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
