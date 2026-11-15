package com.itsluminous.clearmovie.testing

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** The window sizes every UI milestone tests (ROADMAP global criteria). */
enum class ScreenSize(
    val size: DpSize,
) {
    COMPACT(DpSize(411.dp, 891.dp)),

    /** A phone held sideways. */
    MEDIUM(DpSize(700.dp, 480.dp)),
    EXPANDED(DpSize(1280.dp, 800.dp)),
}

/** [content] laid out at [size] and [fontScale], whatever the test device is. */
@Composable
fun AtSize(
    size: ScreenSize,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    DeviceConfigurationOverride(
        DeviceConfigurationOverride.ForcedSize(size.size) then DeviceConfigurationOverride.FontScale(fontScale),
        content = content,
    )
}
