package com.itsluminous.clearmovie.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.UndoStack
import com.itsluminous.clearmovie.ui.editor.CAPTION_BOX_TAG
import com.itsluminous.clearmovie.ui.editor.CaptionBoxDrag
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * cm-caption-box on a device, with its real fonts and touch: dragging the
 * caption box's right handle makes it narrower and taller (the words wrap
 * at their size), and one undo puts it back. One short composition, for the
 * API 29 job's time.
 */
@RunWith(AndroidJUnit4::class)
class CaptionResizeDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun draggingTheRightHandleNarrowsTheBoxAndUndoPutsItBack() {
        val start = CaptionStyle.STANDARD
        var style by mutableStateOf(start)
        val history = UndoStack<CaptionStyle>()
        val safe = NormRect(0.02, 0.04, 0.96, 0.92)
        compose.setContent {
            ClearMovieTheme(dynamicColor = false) {
                Box(Modifier.width(320.dp).aspectRatio(16f / 9f)) {
                    CaptionBoxDrag(
                        style = style,
                        usual = start.box,
                        safe = safe,
                        showSample = false,
                        dragKey = "device",
                        text = "Our family trip to the hills by the sea",
                        onDragEnd = {},
                    ) { box ->
                        if (!history.canUndo) history.push(style)
                        style = style.copy(box = box)
                    }
                }
            }
        }
        compose.onNodeWithTag(CAPTION_BOX_TAG).performTouchInput {
            val handle = centerRight - Offset(2f, 0f)
            swipe(handle, handle - Offset(width * 0.55f, 0f), 400)
        }
        compose.waitForIdle()
        val after = style.box
        assertThat(after.x).isWithin(1e-4).of(start.box.x)
        assertThat(after.w).isLessThan(start.box.w * 0.6)
        assertThat(after.h).isGreaterThan(start.box.h)
        assertThat(style.sizePx).isEqualTo(start.sizePx)
        style = history.undo(style)!!
        compose.waitForIdle()
        assertThat(style.box).isEqualTo(start.box)
    }
}
