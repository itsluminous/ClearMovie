package com.itsluminous.clearmovie.ui.preset

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.ui.components.ShapeOptions
import com.itsluminous.clearmovie.ui.components.shapeName

const val PRESET_SHAPE_TAG = "preset_shape"

/**
 * A preset's video shape (cm-aspect-ratio), on the new-preset and preset
 * editor screens: the shapes to choose from, or, once videos use the preset
 * ([enabled] false), the shape it keeps and why.
 */
@Composable
internal fun PresetShapeCard(
    shape: CanvasShape,
    enabled: Boolean,
    onShape: (CanvasShape) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown under the shapes when the preset's chosen files were made for its old shape. */
    filesNote: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.testTag(PRESET_SHAPE_TAG),
    ) {
        Column(Modifier.padding(vertical = 16.dp).fillMaxWidth()) {
            Text(
                stringResource(R.string.preset_shape_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                if (enabled) {
                    stringResource(R.string.preset_shape_body)
                } else {
                    stringResource(R.string.preset_shape_fixed, stringResource(shapeName(shape)))
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ShapeOptions(shape, onSelect = onShape, enabled = enabled)
            if (filesNote) {
                Text(
                    stringResource(R.string.preset_shape_files_note),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
