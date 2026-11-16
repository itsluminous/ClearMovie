package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.ui.editor.timeline.BarAction
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import kotlin.math.floor

/** Test tags. */
const val BAR_TAG = "contextual_bar"
const val BAR_FADE_END_TAG = "contextual_bar_fade_end"
const val BAR_FADE_START_TAG = "contextual_bar_fade_start"

fun barActionTag(action: EditAction) = "bar_action_${action.name}"

/**
 * The width of each button in a bar of [count] buttons across [barWidth]:
 * every button is at least [natural] wide (72 dp, or the longest word of the
 * longest label). When they don't all fit, the width is chosen so that the
 * bar shows a whole number of buttons plus half of one, so the last one is
 * always cut off and the bar reads as scrollable (ARCHITECTURE section 11.2).
 */
fun barItemWidth(
    barWidth: Dp,
    natural: Dp,
    count: Int,
): Dp {
    if (count <= 0) return natural
    if (natural * count <= barWidth) return barWidth / count
    val whole = floor(barWidth / natural - 0.5f).toInt().coerceAtLeast(1)
    return maxOf(natural, barWidth / (whole + 0.5f))
}

/**
 * The contextual bar (ARCHITECTURE section 11.2): one row of icon-above-label
 * buttons for what is selected, most-used first. It scrolls sideways when
 * they don't fit, and then shows it two ways: the last visible button is
 * cut off, and a fade with a chevron sits on the side with more buttons
 * (on the left in right-to-left languages). Labels wrap rather than being
 * cut short, and the bar grows with the font size.
 */
@Composable
fun ContextualBar(
    actions: List<BarAction>,
    onAction: (EditAction) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val labels = actions.map { stringResource(barLabel(it.action)) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val longestWordPx =
        remember(labels, style, density) {
            labels.flatMap { it.split(' ') }.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0
        }
    val natural = maxOf(MIN_BUTTON, with(density) { longestWordPx.toDp() } + BUTTON_PADDING * 2)
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Surface(color = surface, modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().testTag(BAR_TAG).semantics { contentDescription = description }) {
            val itemWidth = barItemWidth(maxWidth, natural, actions.size)
            val state = rememberLazyListState()
            val canBack by remember { derivedStateOf { state.canScrollBackward } }
            val canForward by remember { derivedStateOf { state.canScrollForward } }
            LazyRow(state = state, modifier = Modifier.fillMaxWidth()) {
                items(actions, key = { it.action.name }) { a ->
                    BarButton(a, itemWidth, onAction)
                }
            }
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            if (canForward) Fade(Alignment.CenterEnd, towardsEnd = true, rtl, surface, BAR_FADE_END_TAG)
            if (canBack) Fade(Alignment.CenterStart, towardsEnd = false, rtl, surface, BAR_FADE_START_TAG)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Fade(
    alignment: Alignment,
    towardsEnd: Boolean,
    rtl: Boolean,
    surface: Color,
    tag: String,
) {
    // The gradient runs from clear to the bar's colour at the edge with more buttons.
    val leftToRight = towardsEnd != rtl
    val colours = if (leftToRight) listOf(surface.copy(alpha = 0f), surface) else listOf(surface, surface.copy(alpha = 0f))
    val more = stringResource(R.string.bar_more)
    // matchParentSize: the fade takes the bar's height without making the bar any taller.
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .align(alignment)
                .width(FADE)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(colours))
                .testTag(tag)
                .semantics { contentDescription = more },
            contentAlignment = if (towardsEnd) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Icon(
                painterResource(if (towardsEnd) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun BarButton(
    action: BarAction,
    width: Dp,
    onAction: (EditAction) -> Unit,
) {
    val label = stringResource(barLabel(action.action))
    Column(
        Modifier
            .width(width)
            .heightIn(min = 72.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = action.enabled, role = Role.Button) { onAction(action.action) }
            .alpha(if (action.enabled) 1f else 0.38f)
            .padding(horizontal = BUTTON_PADDING - 2.dp, vertical = 8.dp)
            .testTag(barActionTag(action.action)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(barIcon(action.action)), contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

fun barLabel(action: EditAction): Int =
    when (action) {
        EditAction.TRIM -> R.string.action_trim
        EditAction.DURATION -> R.string.action_duration
        EditAction.VOLUME -> R.string.action_volume
        EditAction.SPLIT -> R.string.action_split
        EditAction.SPEED -> R.string.action_speed
        EditAction.LOOP -> R.string.action_loop
        EditAction.FIT -> R.string.action_fit
        EditAction.ROTATE -> R.string.action_rotate
        EditAction.REPLACE -> R.string.action_replace
        EditAction.DUPLICATE -> R.string.action_duplicate
        EditAction.MOVE_LEFT -> R.string.action_move_left
        EditAction.MOVE_RIGHT -> R.string.action_move_right
        EditAction.JOIN -> R.string.action_join
        EditAction.DELETE -> R.string.action_delete
        EditAction.EDIT_TEXT -> R.string.action_edit_text
        EditAction.COLOUR -> R.string.action_colour
        EditAction.PLAY -> R.string.action_play
        EditAction.SKIP -> R.string.review_skip
        EditAction.ADD_MEDIA -> R.string.editor_add_media
        EditAction.FIT_ALL -> R.string.zoom_fit_all
    }

fun barIcon(action: EditAction): Int =
    when (action) {
        EditAction.TRIM -> R.drawable.ic_trim
        EditAction.DURATION -> R.drawable.ic_timer
        EditAction.VOLUME -> R.drawable.ic_volume_up
        EditAction.SPLIT -> R.drawable.ic_content_cut
        EditAction.SPEED -> R.drawable.ic_speed
        EditAction.LOOP -> R.drawable.ic_repeat
        EditAction.FIT -> R.drawable.ic_aspect_ratio
        EditAction.ROTATE -> R.drawable.ic_rotate_right
        EditAction.REPLACE -> R.drawable.ic_swap_horiz
        EditAction.DUPLICATE -> R.drawable.ic_content_copy
        EditAction.MOVE_LEFT -> R.drawable.ic_chevron_left
        EditAction.MOVE_RIGHT -> R.drawable.ic_chevron_right
        EditAction.JOIN -> R.drawable.ic_call_merge
        EditAction.DELETE -> R.drawable.ic_delete
        EditAction.EDIT_TEXT -> R.drawable.ic_text_fields
        EditAction.COLOUR -> R.drawable.ic_palette
        EditAction.PLAY -> R.drawable.ic_play
        EditAction.SKIP -> R.drawable.ic_skip_next
        EditAction.ADD_MEDIA -> R.drawable.ic_add
        EditAction.FIT_ALL -> R.drawable.ic_fit_screen
    }

private val MIN_BUTTON = 72.dp
private val BUTTON_PADDING = 8.dp
private val FADE = 24.dp
