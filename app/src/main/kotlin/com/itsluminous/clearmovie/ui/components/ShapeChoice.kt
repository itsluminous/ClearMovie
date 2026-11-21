package com.itsluminous.clearmovie.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CanvasShape

/** Test tag of a shape's row in [ShapeOptions]. */
fun shapeOptionTag(shape: CanvasShape) = "shape_option_${shape.name}"

/** The shape's name: Landscape, Portrait, Square. */
fun shapeName(shape: CanvasShape): Int =
    when (shape) {
        CanvasShape.LANDSCAPE -> R.string.shape_landscape
        CanvasShape.PORTRAIT -> R.string.shape_portrait
        CanvasShape.SQUARE -> R.string.shape_square
    }

/** What the shape looks like, in plain words. */
private fun shapeBody(shape: CanvasShape): Int =
    when (shape) {
        CanvasShape.LANDSCAPE -> R.string.shape_landscape_body
        CanvasShape.PORTRAIT -> R.string.shape_portrait_body
        CanvasShape.SQUARE -> R.string.shape_square_body
    }

/**
 * The video shapes as a list of radio rows (cm-aspect-ratio): each with a
 * small outline of its shape, its name and what it looks like ("Tall, like
 * a phone held upright"), never a ratio alone. Rows are 56 dp or taller and
 * wrap their words, so they fit any text size.
 */
@Composable
fun ShapeOptions(
    selected: CanvasShape?,
    onSelect: (CanvasShape) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The rows' own side padding: none inside a dialog, which pads its content already. */
    sidePadding: Dp = 16.dp,
) {
    Column(modifier.selectableGroup()) {
        for (shape in CanvasShape.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = shape == selected, enabled = enabled, role = Role.RadioButton) { onSelect(shape) }
                    .padding(horizontal = sidePadding, vertical = 8.dp)
                    .testTag(shapeOptionTag(shape)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = shape == selected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(16.dp))
                ShapeOutline(
                    shape,
                    if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(stringResource(shapeName(shape)), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(shapeBody(shape)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A 24 dp outline of [shape]: a wide, a tall or a square rectangle. */
@Composable
fun ShapeOutline(
    shape: CanvasShape,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        val room = size.minDimension - stroke
        // The long side fills the icon (less the stroke); the short side follows the shape.
        val (w, h) =
            when (shape) {
                CanvasShape.LANDSCAPE -> room to room * 9f / 16f
                CanvasShape.PORTRAIT -> room * 9f / 16f to room
                CanvasShape.SQUARE -> room * 0.8f to room * 0.8f
            }
        drawRoundRect(
            color = color,
            topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
            size = Size(w, h),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(stroke),
        )
    }
}
