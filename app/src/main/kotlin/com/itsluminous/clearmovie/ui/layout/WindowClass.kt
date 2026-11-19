package com.itsluminous.clearmovie.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The Material window size classes, worked out from the space the app has (so split screen counts too). */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

enum class HeightClass { COMPACT, MEDIUM, EXPANDED }

data class WindowClass(
    val width: WidthClass,
    val height: HeightClass,
    /** The window is at least as wide as it is tall. */
    val wide: Boolean = true,
) {
    /**
     * Two panes side by side (ARCHITECTURE section 11.1): compact height (a
     * phone held sideways), or medium or expanded width in a window wider
     * than tall (a tablet held sideways, a wide split screen). A tablet held
     * upright stacks like a phone (cm-ux-review: side by side it left most
     * of both panes empty). The window's own shape decides, never the
     * device's orientation, so split screen counts too.
     */
    val twoPane: Boolean get() = height == HeightClass.COMPACT || (width != WidthClass.COMPACT && wide)

    /** Columns for lists of cards. */
    val columns: Int
        get() =
            when (width) {
                WidthClass.COMPACT -> 1
                WidthClass.MEDIUM -> 2
                WidthClass.EXPANDED -> 3
            }

    companion object {
        /** Material's breakpoints. */
        val MEDIUM_WIDTH: Dp = 600.dp
        val EXPANDED_WIDTH: Dp = 840.dp
        val MEDIUM_HEIGHT: Dp = 480.dp
        val EXPANDED_HEIGHT: Dp = 900.dp

        fun of(
            width: Dp,
            height: Dp,
        ): WindowClass =
            WindowClass(
                width =
                    when {
                        width < MEDIUM_WIDTH -> WidthClass.COMPACT
                        width < EXPANDED_WIDTH -> WidthClass.MEDIUM
                        else -> WidthClass.EXPANDED
                    },
                height =
                    when {
                        height < MEDIUM_HEIGHT -> HeightClass.COMPACT
                        height < EXPANDED_HEIGHT -> HeightClass.MEDIUM
                        else -> HeightClass.EXPANDED
                    },
                wide = width >= height,
            )
    }
}

/** [content] with the [WindowClass] of the space this screen fills. */
@Composable
fun AdaptiveScreen(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(WindowClass) -> Unit,
) {
    BoxWithConstraints(modifier) {
        content(WindowClass.of(maxWidth, maxHeight))
    }
}
