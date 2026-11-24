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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.components.rememberLabelLinesPx
import com.itsluminous.clearmovie.ui.editor.timeline.BarAction
import com.itsluminous.clearmovie.ui.editor.timeline.EditAction
import kotlin.math.abs
import kotlin.math.floor

/** Test tags. */
const val BAR_TAG = "contextual_bar"
const val BAR_FADE_END_TAG = "contextual_bar_fade_end"
const val BAR_FADE_START_TAG = "contextual_bar_fade_start"

fun barActionTag(action: EditAction) = "bar_action_${action.name}"

fun barLabelTag(action: EditAction) = "bar_label_${action.name}"

fun barLabelSlotTag(action: EditAction) = "bar_label_slot_${action.name}"

/**
 * The width of each button in a bar of [count] buttons across [barWidth]
 * (ARCHITECTURE section 11.2). Every button gets the same width, so the
 * icons sit at equal distances:
 * - When they all fit at [min] each, they share the whole width.
 * - When they don't, each is a compact width between [min] and [max],
 *   closest to [target], chosen so the bar shows a whole number of buttons
 *   and half of one: the last one is always cut off, so the bar reads as
 *   scrollable. On a 360 to 411 dp phone that is 4.5 or 5.5 buttons.
 * A label too long for its button is drawn smaller, then on two lines
 * (`AutoSizeLabel`); the long labels never set the width of the others.
 */
fun barItemWidth(
    barWidth: Dp,
    count: Int,
    min: Dp = BAR_MIN,
    target: Dp = BAR_TARGET,
    max: Dp = BAR_MAX,
): Dp {
    if (count <= 0) return target
    // When they all fit they share the width (the bar floors it to whole pixels, so it never scrolls).
    if (min * count <= barWidth) return barWidth / count
    // Otherwise a whole number and a half on screen, never narrower than [min]: the width nearest to
    // [min]..[max], then nearest [target]. A bar too narrow even for one and a half keeps [min].
    return (1 until count)
        .map { n -> barWidth / (n + 0.5f) }
        .filter { it >= min }
        .minWithOrNull(compareBy<Dp>({ abs((it - it.coerceIn(min, max)).value) }, { abs((it - target).value) }))
        ?: min
}

/** The compact button widths; they grow with text at 1.3x and larger ([barGrowth]). */
val BAR_MIN = 64.dp
val BAR_TARGET = 80.dp
val BAR_MAX = 96.dp

/** How much the bar's buttons widen with large text: as the text, up to half again. */
const val BAR_GROWTH = 1.5f

/** The text size from which the bar's buttons widen with it; below it they keep their width and labels shrink. */
const val BAR_GROWTH_FROM = 1.3f

/** The factor the bar's widths are multiplied by at text size [fontScale]. */
fun barGrowth(fontScale: Float): Float = if (fontScale < BAR_GROWTH_FROM) 1f else fontScale.coerceAtMost(BAR_GROWTH)

/**
 * The contextual bar (ARCHITECTURE section 11.2): one row of icon-above-label
 * buttons for what is selected, most-used first. It scrolls sideways when
 * they don't fit, and then shows it two ways: the last visible button is
 * cut off, and a fade with a chevron sits on the side with more buttons
 * (on the left in right-to-left languages). A long label is drawn smaller,
 * down to 10 sp, to stay on one line; only one too long even then takes a
 * second line, and then every button in the bar is two lines tall, so the
 * icons stay level. Buttons widen with text at 1.3x and larger. With
 * one-line labels the bar is 56 dp tall, as the Play bar (cm-timeline-scroll),
 * each button still 52 dp to touch.
 */
@Composable
fun ContextualBar(
    actions: List<BarAction>,
    onAction: (EditAction) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val grow = barGrowth(LocalDensity.current.fontScale)
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Surface(color = surface, modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().testTag(BAR_TAG).semantics { contentDescription = description }) {
            val density = LocalDensity.current
            // Whole pixels, rounded down: buttons that fit never add up to more than the bar.
            val itemWidth =
                with(density) {
                    floor(barItemWidth(maxWidth, actions.size, BAR_MIN * grow, BAR_TARGET * grow, BAR_MAX * grow).toPx()).toDp()
                }
            // When one label needs a second line every button takes two, so they are all as tall and the icons stay level.
            val labels = actions.map { stringResource(barLabel(it.action)) }
            // The label's real width: the button's, less each padding rounded to pixels as BarButton lays them out.
            val labelPx =
                with(density) { itemWidth.roundToPx() - 2 * OUTER_PADDING.roundToPx() - 2 * (BUTTON_PADDING - OUTER_PADDING).roundToPx() }
            val lines = rememberLabelLinesPx(labels, MaterialTheme.typography.labelMedium, labelPx, density)
            val state = rememberLazyListState()
            // Another selection has other buttons: they start from the first, never mid-way.
            val kinds = actions.map { it.action }
            LaunchedEffect(kinds) { state.scrollToItem(0) }
            val canBack by remember { derivedStateOf { state.canScrollBackward } }
            val canForward by remember { derivedStateOf { state.canScrollForward } }
            LazyRow(state = state, modifier = Modifier.fillMaxWidth()) {
                items(actions, key = { it.action.name }) { a ->
                    BarButton(a, itemWidth, lines, onAction)
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
    // matchParentSize: the fade takes the bar's height without making the bar any taller.
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .align(alignment)
                .width(FADE)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(colours))
                .testTag(tag)
                // Decoration: the partly shown last button already says there is more, and TalkBack scrolls the row itself.
                .clearAndSetSemantics { },
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
    lines: Int,
    onAction: (EditAction) -> Unit,
) {
    val label = stringResource(barLabel(action.action))
    val checked = action.checked
    val onLabel = stringResource(R.string.state_on)
    val offLabel = stringResource(R.string.state_off)
    // These paddings set the label's width; ContextualBar's labelPx subtracts the same ones.
    Column(
        Modifier
            .width(width)
            .heightIn(min = BAR_HEIGHT)
            .padding(OUTER_PADDING)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (checked == null) {
                    Modifier.clickable(enabled = action.enabled, role = Role.Button) { onAction(action.action) }
                } else if (action.opensPanel) {
                    // Looks like a switch, but opens its popup: TalkBack hears a button and whether it is on.
                    Modifier
                        .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .semantics { stateDescription = if (checked) onLabel else offLabel }
                        .clickable(enabled = action.enabled, role = Role.Button) { onAction(action.action) }
                } else {
                    // A switch: TalkBack says whether it is on; it looks pressed while on.
                    Modifier
                        .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .toggleable(value = checked, enabled = action.enabled, role = Role.Switch) { onAction(action.action) }
                },
            ).alpha(if (action.enabled) 1f else 0.38f)
            .padding(horizontal = BUTTON_PADDING - OUTER_PADDING, vertical = LABEL_PADDING)
            .testTag(barActionTag(action.action)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(barIcon(action.action)), contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        val style = MaterialTheme.typography.labelMedium
        // A slot of whole lines: fonts round their own ascent and descent per script and size, so a
        // Hindi label can be a pixel taller than its neighbour; the slot keeps every button's icon
        // level. The label inside is measured freely and drawn from the slot's top.
        check(style.lineHeight.isSp) { "bar labels need a line height in sp" }
        val slot = with(LocalDensity.current) { style.lineHeight.toDp() } * lines
        Box(Modifier.fillMaxWidth().height(slot).testTag(barLabelSlotTag(action.action)), contentAlignment = Alignment.TopCenter) {
            AutoSizeLabel(
                label,
                style = style,
                textAlign = TextAlign.Center,
                lines = lines,
                modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).testTag(barLabelTag(action.action)),
            )
        }
    }
}

/**
 * A bar button's label: one or two short words, the bar_ forms where the full name used in menus and
 * panels would not fit one line of a button in English, Hindi or Hinglish.
 */
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
        EditAction.DUPLICATE -> R.string.bar_duplicate
        EditAction.MOVE_LEFT -> R.string.bar_move_left
        EditAction.MOVE_RIGHT -> R.string.bar_move_right
        EditAction.JOIN -> R.string.action_join
        EditAction.DELETE -> R.string.bar_delete
        EditAction.EDIT_TEXT -> R.string.action_edit_text
        EditAction.COLOUR -> R.string.action_colour
        EditAction.TRANSITION -> R.string.action_transition
        EditAction.ANIMATION -> R.string.action_animation
        EditAction.PLAY -> R.string.action_play
        EditAction.SKIP -> R.string.bar_skip
        EditAction.ADD_MEDIA -> R.string.bar_add_media
        EditAction.ZOOM_OUT -> R.string.zoom_out
        EditAction.ZOOM_IN -> R.string.zoom_in
        EditAction.FIT_ALL -> R.string.zoom_fit_all
        EditAction.ADD_ROW -> R.string.action_add_row
        EditAction.ADD_HERE -> R.string.bar_add_here
        EditAction.SHOW_ON_TOP -> R.string.bar_show_on_top
        EditAction.BRING_FORWARD -> R.string.bar_bring_forward
        EditAction.SEND_BACKWARD -> R.string.bar_send_backward
        EditAction.SHOW_AT_BOTTOM -> R.string.bar_show_at_bottom
        EditAction.MOVE_ROW_UP -> R.string.bar_move_row_up
        EditAction.MOVE_ROW_DOWN -> R.string.bar_move_row_down
        EditAction.REMOVE_ROW -> R.string.action_remove_row
        EditAction.MUTE_ROW -> R.string.bar_mute_row
        EditAction.LOWER_MUSIC -> R.string.bar_lower_music
        EditAction.FRAME_ON -> R.string.bar_frame_on
        EditAction.KEY_COLOUR -> R.string.bar_key_colour
        EditAction.PICTURE_AREA -> R.string.bar_picture_area
        EditAction.MOVE_EARLIER -> R.string.action_move_earlier
        EditAction.MOVE_LATER -> R.string.action_move_later
        EditAction.MOVE_TO_ROW -> R.string.bar_move_to_row
        EditAction.FADE -> R.string.action_fade
        EditAction.POSITION -> R.string.action_position
        EditAction.ADD_CAPTION -> R.string.action_add_caption
        EditAction.FOLLOW_ITEM -> R.string.bar_follow_item
        EditAction.CHANGE_VOICE -> R.string.action_change_voice
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
        EditAction.TRANSITION -> R.drawable.ic_transition
        EditAction.ANIMATION -> R.drawable.ic_animation
        EditAction.PLAY -> R.drawable.ic_play
        EditAction.SKIP -> R.drawable.ic_skip_next
        EditAction.ADD_MEDIA -> R.drawable.ic_add
        EditAction.ZOOM_OUT -> R.drawable.ic_zoom_out
        EditAction.ZOOM_IN -> R.drawable.ic_zoom_in
        EditAction.FIT_ALL -> R.drawable.ic_fit_screen
        EditAction.ADD_ROW -> R.drawable.ic_playlist_add
        EditAction.ADD_HERE -> R.drawable.ic_add
        EditAction.SHOW_ON_TOP -> R.drawable.ic_flip_to_front
        EditAction.BRING_FORWARD -> R.drawable.ic_keyboard_arrow_up
        EditAction.SEND_BACKWARD -> R.drawable.ic_keyboard_arrow_down
        EditAction.SHOW_AT_BOTTOM -> R.drawable.ic_flip_to_back
        EditAction.MOVE_ROW_UP -> R.drawable.ic_arrow_upward
        EditAction.MOVE_ROW_DOWN -> R.drawable.ic_arrow_downward
        EditAction.REMOVE_ROW -> R.drawable.ic_delete
        EditAction.MUTE_ROW -> R.drawable.ic_volume_off
        EditAction.LOWER_MUSIC -> R.drawable.ic_volume_down
        EditAction.FRAME_ON -> R.drawable.ic_frame
        EditAction.KEY_COLOUR -> R.drawable.ic_colorize
        EditAction.PICTURE_AREA -> R.drawable.ic_picture_area
        EditAction.MOVE_EARLIER -> R.drawable.ic_chevron_left
        EditAction.MOVE_LATER -> R.drawable.ic_chevron_right
        EditAction.MOVE_TO_ROW -> R.drawable.ic_swap_vert
        EditAction.FADE -> R.drawable.ic_fade
        EditAction.POSITION -> R.drawable.ic_open_with
        EditAction.ADD_CAPTION -> R.drawable.ic_closed_caption
        EditAction.FOLLOW_ITEM -> R.drawable.ic_link
        EditAction.CHANGE_VOICE -> R.drawable.ic_record_voice
    }

/** Room between a label and its button's edge: [OUTER_PADDING] outside the ripple, the rest inside. */
private val BUTTON_PADDING = 4.dp
private val OUTER_PADDING = 2.dp

/** The bar's height with one-line labels: the Play bar's (a 24 dp icon, 4 dp, a 16 sp line, and 6 dp above and below). */
private val BAR_HEIGHT = 56.dp

/** Room above the icon and below the label, inside the touch area. */
private val LABEL_PADDING = 4.dp
private val FADE = 24.dp
