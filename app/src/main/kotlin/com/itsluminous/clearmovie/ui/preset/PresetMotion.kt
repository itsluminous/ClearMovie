package com.itsluminous.clearmovie.ui.preset

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Anim
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.AnimSlot
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.ui.editor.AnimationChoices
import com.itsluminous.clearmovie.ui.editor.PanelTitle
import com.itsluminous.clearmovie.ui.editor.TransitionChoices
import com.itsluminous.clearmovie.ui.editor.timeline.ThumbSource
import com.itsluminous.clearmovie.ui.editor.timeline.animationName
import com.itsluminous.clearmovie.ui.editor.timeline.directionName
import com.itsluminous.clearmovie.ui.editor.timeline.transitionDirection
import com.itsluminous.clearmovie.ui.editor.timeline.transitionName

const val PRESET_MOTION_TAG = "preset_motion"
const val PRESET_ANIMATION_TAG = "preset_default_animation"
const val PRESET_TRANSITION_TAG = "preset_default_transition"

/** Which of a preset's motion defaults is being chosen (cm-preset-motion-defaults). */
enum class MotionChoice { ANIMATION, TRANSITION }

/** The name of a default animation for new photos, as the card and TalkBack say it; None for null. */
@Composable
internal fun photoAnimationName(kind: AnimKind?): String = stringResource(kind?.let(::animationName) ?: R.string.effect_none)

/** The full name of a default transition ("Crossfade", "Wipe, Left"); None for null. */
@Composable
internal fun defaultTransitionName(transition: Transition?): String {
    val kind = transition?.kind ?: TransitionKind.NONE
    val name = stringResource(transitionName(kind))
    val direction = transitionDirection(kind) ?: return name
    return stringResource(R.string.effect_tile_with_direction, name, stringResource(directionName(direction)))
}

/**
 * The preset's motion defaults (cm-preset-motion-defaults): the animation a new
 * photo gets and the transition between new items, each a row that opens its
 * choices. What each row shows is what new items get.
 */
@Composable
internal fun MotionCard(
    animation: AnimKind?,
    transition: Transition?,
    onOpen: (MotionChoice) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.testTag(PRESET_MOTION_TAG),
    ) {
        Column(Modifier.padding(vertical = 16.dp).fillMaxWidth()) {
            Text(
                stringResource(R.string.preset_motion_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.preset_motion_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            MotionRow(
                icon = R.drawable.ic_animation,
                label = stringResource(R.string.preset_default_animation),
                value = photoAnimationName(animation),
                tag = PRESET_ANIMATION_TAG,
                onClick = { onOpen(MotionChoice.ANIMATION) },
            )
            MotionRow(
                icon = R.drawable.ic_transition,
                label = stringResource(R.string.preset_default_transition),
                value = defaultTransitionName(transition),
                tag = PRESET_TRANSITION_TAG,
                onClick = { onOpen(MotionChoice.TRANSITION) },
            )
        }
    }
}

@Composable
private fun MotionRow(
    icon: Int,
    label: String,
    value: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** A photo to show the gentle zooms on: only its kind and id matter (the tiles draw [samplePicture]). */
private val SAMPLE_PHOTO = PhotoClip(ClipId("preset-photo-sample"), MediaRef.Copied("sample.jpg"), SAMPLE_PHOTO_MS)

private const val SAMPLE_PHOTO_MS = 3_000L

/**
 * The choices for one motion default, with Cancel and Done: the editor's own
 * tiles on sample pictures, the one in use outlined. A choice is a draft until
 * the preset is saved.
 */
@Composable
internal fun ColumnScope.MotionEditor(
    choice: MotionChoice,
    animation: AnimKind?,
    transition: Transition?,
    onAnimation: (AnimKind?) -> Unit,
    onTransition: (TransitionKind) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val title = if (choice == MotionChoice.ANIMATION) R.string.preset_default_animation else R.string.preset_default_transition
    PanelTitle(stringResource(title), onDone = onDone, onCancel = onCancel)
    val colours = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val first = remember(colours, density) { samplePicture(colours, density, alternate = false) }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text(
            stringResource(if (choice == MotionChoice.ANIMATION) R.string.preset_animation_hint else R.string.preset_transition_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when (choice) {
            MotionChoice.ANIMATION -> {
                val photo: ThumbSource = remember(first) { { _, _ -> first } }
                // The Animation panel's own "All through" choices on a photo: what a new photo of this preset gets.
                AnimationChoices(SAMPLE_PHOTO, AnimSlot.DURING, animation, Anim.DEFAULT_MS, photo, onAnimation)
            }

            MotionChoice.TRANSITION -> {
                val pictures =
                    remember(first, colours, density) {
                        first.asImageBitmap() to samplePicture(colours, density, alternate = true).asImageBitmap()
                    }
                TransitionChoices(transition?.kind ?: TransitionKind.NONE, pictures.first, pictures.second, onTransition)
            }
        }
    }
}

/**
 * A plain picture for the tiles (a sun over two hills, the usual sign for a
 * photo) in the theme's colours, so a gentle zoom and a transition can be
 * seen moving; [alternate] swaps the colours for the item a transition goes to.
 */
private fun samplePicture(
    colours: ColorScheme,
    density: Float,
    alternate: Boolean,
): Bitmap {
    val width = (SAMPLE_WIDTH_DP * density).toInt().coerceAtLeast(1)
    val height = (width * 9 / 16).coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val image = bitmap.asImageBitmap()
    val sky = if (alternate) colours.tertiaryContainer else colours.secondaryContainer
    val sun = if (alternate) colours.primary else colours.tertiary
    val far = if (alternate) colours.onTertiaryContainer else colours.primary
    val near = if (alternate) colours.tertiary else colours.onSecondaryContainer
    CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        val w = size.width
        val h = size.height
        drawRect(sky)
        drawCircle(sun, radius = h * 0.12f, center = Offset(w * 0.74f, h * 0.3f))
        drawPath(hill(w, h, 0.05f, 0.42f, 0.8f, 0.36f), far)
        drawPath(hill(w, h, 0.35f, 0.66f, 1.05f, 0.56f), near)
    }
    return bitmap
}

/** A triangular hill from [left] to [right] (shares of the width) peaking at [peak], [top] down from the top. */
private fun hill(
    w: Float,
    h: Float,
    left: Float,
    peak: Float,
    right: Float,
    top: Float,
): Path =
    Path().apply {
        moveTo(w * left, h)
        lineTo(w * peak, h * top)
        lineTo(w * right, h)
        close()
    }

private const val SAMPLE_WIDTH_DP = 160
