package com.itsluminous.clearmovie.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Anim
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.AnimSlot
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.Clip
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.MainSlot
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.Timeline
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.animationChoices
import com.itsluminous.clearmovie.domain.model.applyTransitionToAll
import com.itsluminous.clearmovie.domain.model.kenBurnsSeed
import com.itsluminous.clearmovie.domain.model.mainLayout
import com.itsluminous.clearmovie.domain.model.mainSlot
import com.itsluminous.clearmovie.domain.model.setAnimation
import com.itsluminous.clearmovie.domain.model.setTransition
import com.itsluminous.clearmovie.render.plan.MotionFrame
import com.itsluminous.clearmovie.render.plan.MotionMath
import com.itsluminous.clearmovie.render.plan.PictureMotion
import com.itsluminous.clearmovie.render.plan.TransitionPart
import com.itsluminous.clearmovie.render.plan.TransitionRole
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.components.MIN_LABEL_FONT_SIZE
import com.itsluminous.clearmovie.ui.components.labelFits
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.ThumbSource
import com.itsluminous.clearmovie.ui.editor.timeline.TransitionGroup
import com.itsluminous.clearmovie.ui.editor.timeline.animationDirection
import com.itsluminous.clearmovie.ui.editor.timeline.animationName
import com.itsluminous.clearmovie.ui.editor.timeline.directionName
import com.itsluminous.clearmovie.ui.editor.timeline.transitionName
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Test tags. */
fun transitionTileTag(kind: TransitionKind) = "transition_tile_${kind.name}"

fun animationTileTag(
    slot: AnimSlot,
    kind: AnimKind?,
) = "animation_tile_${slot.name}_${kind?.name ?: "NONE"}"

fun animationTabTag(slot: AnimSlot) = "animation_tab_${slot.name}"

const val EFFECT_LENGTH_TAG = "effect_length"
const val TRANSITION_APPLY_ALL_TAG = "transition_apply_all"
const val TRANSITION_REMOVE_TAG = "transition_remove"
const val TRANSITION_INSERT_TAG = "transition_insert_here"

/** The picker's moving thumbnails replay every this many milliseconds. */
private const val TILE_LOOP_MS = 1_900L
private const val TILE_LEAD_MS = 400L
private const val TILE_EFFECT_MS = 1_000L

/** How long before a junction the preview starts when a transition is chosen (section 11.10). */
const val PREVIEW_LEAD_MS = 1_000L

/** A tenth-second length, as "0.6 s". */
@Composable
private fun secondsText(ms: Long): String {
    val tenths = (ms + 50) / 100
    return stringResource(R.string.effect_seconds, (tenths / 10).toInt(), (tenths % 10).toInt())
}

/**
 * The transition from MAIN item [open]'s clip into the next one (ARCHITECTURE
 * section 11.10): the LibreCuts-derived set under eight headings, each tile
 * a small moving preview made from the two items' own pictures with the
 * export's curves; a Length slider (0.3 s up to half the shorter item) that
 * re-applies as it is let go; Use for all items; Remove; and Add something
 * here, for when the timeline is too zoomed out to show the "+". Each choice
 * is one undo step, and the preview plays from a second before the junction.
 */
@Composable
internal fun TransitionPanel(
    open: OpenPanel,
    project: Project,
    thumbnail: ThumbSource,
    callbacks: EditorCallbacks,
    close: () -> Unit,
    onPanel: (OpenPanel?) -> Unit,
) {
    PanelTitle(stringResource(R.string.transition_title), onDone = close, onCancel = null)
    val layout = project.mainLayout()
    val i = layout.indexOfFirst { it.clip.id == open.clipId }
    val slot = layout.getOrNull(i) ?: return
    val next = layout.getOrNull(i + 1)
    if (next == null) {
        Text(stringResource(R.string.transition_last), style = MaterialTheme.typography.bodyLarge)
        return
    }
    val maxMs = Timeline.maxTransitionMs(slot.durationMs, next.durationMs)
    val insertHere = {
        onPanel(OpenPanel(Panel.INSERT, slot.clip.id, InsertSide.AFTER))
    }
    if (maxMs < Transition.MIN_MS) {
        Text(stringResource(R.string.transition_too_short), style = MaterialTheme.typography.bodyLarge)
        InsertHereButton(insertHere)
        return
    }
    val current = slot.clip.transitionToNext
    var length by rememberSaveable(open.clipId) { mutableLongStateOf(current?.durationMs ?: minOf(Transition.DEFAULT_MS, maxMs)) }
    // An undo or "Use for all" can change the stored length: the slider follows it.
    LaunchedEffect(current?.durationMs) { current?.let { length = it.durationMs } }
    val wanted = length.coerceIn(Transition.MIN_MS, maxMs)

    fun preview(len: Long) = callbacks.previewFrom((slot.endMs - len - PREVIEW_LEAD_MS).coerceAtLeast(0))

    fun choose(kind: TransitionKind) {
        val t = if (kind == TransitionKind.NONE) null else Transition(kind, wanted)
        if (callbacks.edit { setTransition(slot.clip.id, t) }) preview(t?.durationMs ?: 0)
    }

    // "Use for all" is on when this junction has the all-items kind; its stored length may be shorter
    // (fitted to these two items), so the kind is what tells.
    val forAllOn = project.transitionForAll
    val forAll = current != null && forAllOn != null && forAllOn.kind == current.kind
    // The controls first, so they are in reach without scrolling past the tiles.
    LengthSlider(
        valueMs = wanted,
        minMs = Transition.MIN_MS,
        maxMs = maxMs,
        enabled = current != null && maxMs > Transition.MIN_MS,
        onChange = { length = it },
        onDone = {
            val t = current ?: return@LengthSlider
            val len = length.coerceIn(Transition.MIN_MS, maxMs)
            // With "Use for all" on, the length is for every junction (each still fitted to its items).
            val applied =
                if (forAll) {
                    callbacks.edit { applyTransitionToAll(forAllOn!!.copy(durationMs = len)) }
                } else {
                    callbacks.edit { setTransition(slot.clip.id, t.copy(durationMs = len)) }
                }
            if (applied) preview(len)
        },
    )
    // Three equal actions across the width, all as tall as the tallest (a name on two lines), names centred.
    // When a word of one would not fit its third even at the smallest size (a narrow phone, large text),
    // they stack, each the full width, so no name is cut.
    val words =
        listOf(
            stringResource(if (forAll) R.string.transition_applied_all else R.string.transition_apply_all),
            stringResource(R.string.transition_remove),
            stringResource(R.string.transition_insert_here),
        )
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val thirdPx = with(density) { ((maxWidth - 16.dp) / 3 - ACTION_PADDING * 2).roundToPx() }
        val stacked =
            words.flatMap { it.split(' ') }.any { word ->
                !labelFits(measurer, word, labelStyle.copy(fontSize = MIN_LABEL_FONT_SIZE), thirdPx)
            }
        val apply: @Composable (Modifier) -> Unit = { m ->
            OutlinedButton(
                onClick = { current?.let { t -> callbacks.edit { applyTransitionToAll(t.copy(durationMs = wanted)) } } },
                enabled = current != null && !forAll,
                contentPadding = PaddingValues(horizontal = ACTION_PADDING),
                modifier = m.heightIn(min = 48.dp).testTag(TRANSITION_APPLY_ALL_TAG),
            ) { OneLine(words[0]) }
        }
        val remove: @Composable (Modifier) -> Unit = { m ->
            OutlinedButton(
                onClick = { callbacks.edit { setTransition(slot.clip.id, null) } },
                enabled = current != null,
                contentPadding = PaddingValues(horizontal = ACTION_PADDING),
                modifier = m.heightIn(min = 48.dp).testTag(TRANSITION_REMOVE_TAG),
            ) { OneLine(words[1]) }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                apply(Modifier.fillMaxWidth())
                remove(Modifier.fillMaxWidth())
                InsertHereButton(insertHere, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                apply(Modifier.weight(1f).fillMaxHeight())
                remove(Modifier.weight(1f).fillMaxHeight())
                InsertHereButton(insertHere, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
    val pictures =
        junctionPictures(slot, next, thumbnail, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.primaryContainer)
    val clock = tileClock()
    val chosen = current?.kind ?: TransitionKind.NONE
    for (g in TransitionGroup.entries) {
        val heading = stringResource(g.label)
        GroupHeading(heading)
        EvenGrid(g.tiles) { tile ->
            val label = stringResource(tile.label)
            val direction = g == TransitionGroup.WIPE || g == TransitionGroup.SLIDE
            EffectTile(
                label = label,
                // TalkBack reads the full name: "Fade through black", "Wipe, Left".
                description =
                    if (direction) {
                        stringResource(R.string.effect_tile_with_direction, heading, label)
                    } else {
                        stringResource(transitionName(tile.kind))
                    },
                selected = chosen == tile.kind,
                tag = transitionTileTag(tile.kind),
                onClick = { choose(tile.kind) },
            ) { transitionTileFrame(tile.kind, clock.longValue, pictures) }
        }
    }
}

@Composable
private fun InsertHereButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = ACTION_PADDING),
        modifier = modifier.heightIn(min = 48.dp).testTag(TRANSITION_INSERT_TAG),
    ) {
        OneLine(stringResource(R.string.transition_insert_here))
    }
}

/** Room between an action's name and its outline: the names get the width, the button stays 48 dp tall. */
private val ACTION_PADDING = 8.dp

/** A button or tile label: drawn smaller to stay on one line, else on two at 10 sp (AutoSizeLabel). */
@Composable
private fun OneLine(
    text: String,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    AutoSizeLabel(text, style = style, color = color, textAlign = TextAlign.Center)
}

@Composable
private fun GroupHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * [items] in rows of equal cells spread across the width, 8 dp apart: as
 * many columns as fit at [MIN_TILE_DP] each (at most four), fewer when
 * that makes the rows even, any short last row padded with empty cells so
 * every tile has the same width.
 */
@Composable
private fun <T> EvenGrid(
    items: List<T>,
    maxColumns: Int = MAX_COLUMNS,
    content: @Composable (T) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = ((maxWidth + 8.dp) / (MIN_TILE_DP + 8).dp).toInt().coerceIn(1, maxColumns)
        // Rows of equal length where possible: four tiles go 2 + 2 rather than 3 + 1. Every grid
        // of the panel gets the same column count, so every tile is the same width.
        var cols = fit
        while (cols > 1 && maxColumns % cols != 0) cols--
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in items.chunked(cols)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in row) Box(Modifier.weight(1f)) { content(item) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private const val MIN_TILE_DP = 72
private const val MAX_COLUMNS = 4

/** Animation names are longer ("Gentle zoom in"): three wider tiles a row, so none is cut short. */
private const val ANIMATION_COLUMNS = 3

/**
 * The animations of the item [open] is about, in three tabs (ARCHITECTURE
 * section 11.10): Start, End and All through, each showing only what suits
 * this kind of item (gentle zooms for photos; Pop, Bounce and Pulse for
 * text), with a Length slider for Start and End. The preview plays the part
 * that changed.
 */
@Composable
internal fun AnimationPanel(
    open: OpenPanel,
    project: Project,
    thumbnail: ThumbSource,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    PanelTitle(stringResource(R.string.animation_title), onDone = close, onCancel = null)
    // A MAIN item plays where MAIN puts it; a caption (or any timed item) where it starts.
    val clip = open.clipId?.let { project.clip(it) } ?: return
    val (spanStart, spanEnd) = project.mainSlot(clip.id)?.let { it.startMs to it.endMs } ?: (clip.startMs to clip.endMs)
    val tabs = AnimSlot.entries.filter { clip.animationChoices(it).isNotEmpty() }
    if (tabs.isEmpty()) {
        Text(stringResource(R.string.animation_nothing), style = MaterialTheme.typography.bodyLarge)
        return
    }
    var tabName by rememberSaveable(open.clipId) { mutableStateOf(tabs.first().name) }
    val tab = tabs.firstOrNull { it.name == tabName } ?: tabs.first()
    PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
        for (t in tabs) {
            Tab(
                selected = t == tab,
                onClick = { tabName = t.name },
                text = { Text(stringResource(tabLabel(t))) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(animationTabTag(t)),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    val current = clip.animations[tab]
    val maxMs = minOf(Anim.MAX_MS, clip.durationMs)
    var length by rememberSaveable(open.clipId, tab.name) { mutableLongStateOf(current?.durationMs ?: minOf(Anim.DEFAULT_MS, maxMs)) }
    LaunchedEffect(current?.durationMs) { current?.let { length = it.durationMs } }
    val wanted = length.coerceIn(Anim.MIN_MS, maxOf(Anim.MIN_MS, maxMs))

    fun preview(len: Long) {
        val at = if (tab == AnimSlot.END) spanEnd - len - PREVIEW_LEAD_MS else spanStart
        callbacks.previewFrom(at.coerceIn(spanStart, spanEnd))
    }

    fun anim(kind: AnimKind) = Anim(kind, wanted, seed = if (AnimSlot.DURING in kind.slots) kenBurnsSeed(clip.id) else 0)

    fun choose(kind: AnimKind?) {
        if (callbacks.edit { setAnimation(clip.id, tab, kind?.let(::anim)) }) preview(wanted)
    }

    AnimationChoices(clip, tab, current?.kind, wanted, thumbnail, ::choose)
    if (tab != AnimSlot.DURING) {
        Spacer(Modifier.height(12.dp))
        LengthSlider(
            valueMs = wanted,
            minMs = Anim.MIN_MS,
            maxMs = maxOf(Anim.MIN_MS, maxMs),
            enabled = current != null && maxMs > Anim.MIN_MS,
            onChange = { length = it },
            onDone = {
                // The length as the finger left it (this lambda outlives the composition that made it).
                val a = current ?: return@LengthSlider
                val len = length.coerceIn(Anim.MIN_MS, maxOf(Anim.MIN_MS, maxMs))
                if (callbacks.edit { setAnimation(clip.id, tab, a.copy(durationMs = len)) }) preview(len)
            },
        )
    }
}

/**
 * The animations that may go in [slot] on [clip], as moving tiles: None and
 * the plain ones, then the ones with a direction under their name, the one
 * in use ([current]) outlined. [lengthMs] is how long a tile plays it; an
 * item without a picture is drawn as a [blockColour] block. The
 * Animation panel uses it, and so does a preset's caption style editor for
 * the animation new captions start with (cm-caption-box).
 */
@Composable
internal fun AnimationChoices(
    clip: Clip,
    slot: AnimSlot,
    current: AnimKind?,
    lengthMs: Long,
    thumbnail: ThumbSource,
    onChoose: (AnimKind?) -> Unit,
    blockColour: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val picture = itemPicture(clip, thumbnail, blockColour)
    val clock = tileClock()
    val choices = clip.animationChoices(slot)
    val plain = listOf<AnimKind?>(null) + choices.filter { animationDirection(it) == null }
    EvenGrid(plain, ANIMATION_COLUMNS) { kind ->
        val label = stringResource(kind?.let(::animationName) ?: R.string.effect_none)
        EffectTile(
            label = label,
            description = label,
            selected = current == kind,
            tag = animationTileTag(slot, kind),
            onClick = { onChoose(kind) },
        ) { animationTileFrame(clip, slot, kind, lengthMs, clock.longValue, picture) }
    }
    val directed = choices.filter { animationDirection(it) != null }
    if (directed.isNotEmpty()) {
        val heading = stringResource(animationName(directed.first()))
        GroupHeading(heading)
        EvenGrid(directed, ANIMATION_COLUMNS) { kind ->
            val label = stringResource(directionName(animationDirection(kind)!!))
            EffectTile(
                label = label,
                description = stringResource(R.string.effect_tile_with_direction, heading, label),
                selected = current == kind,
                tag = animationTileTag(slot, kind),
                onClick = { onChoose(kind) },
            ) { animationTileFrame(clip, slot, kind, lengthMs, clock.longValue, picture) }
        }
    }
}

private fun tabLabel(slot: AnimSlot): Int =
    when (slot) {
        AnimSlot.START -> R.string.animation_tab_start
        AnimSlot.END -> R.string.animation_tab_end
        AnimSlot.DURING -> R.string.animation_tab_during
    }

/** A length in tenths of a second from [minMs] to [maxMs]; [onDone] when the finger lifts. */
@Composable
private fun LengthSlider(
    valueMs: Long,
    minMs: Long,
    maxMs: Long,
    enabled: Boolean,
    onChange: (Long) -> Unit,
    onDone: () -> Unit,
) {
    val value = secondsText(valueMs)
    // Whole tenths only: the top of the range rounds down to one, so the thumb and the value agree.
    val top = maxOf(minMs, maxMs / 100 * 100)
    val steps = ((top - minMs) / 100 - 1).toInt().coerceAtLeast(0)
    val label = stringResource(R.string.effect_length)
    // One row: the name, the slider taking the room between, the value.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        OneLine(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Slider(
            value = valueMs.toFloat(),
            onValueChange = { onChange((it / 100).roundToLong() * 100) },
            onValueChangeFinished = onDone,
            valueRange = minMs.toFloat()..maxOf(top, minMs + 1).toFloat(),
            steps = steps,
            enabled = enabled,
            modifier =
                Modifier.weight(1f).heightIn(min = 48.dp).testTag(EFFECT_LENGTH_TAG).semantics {
                    contentDescription = label
                    stateDescription = value
                },
        )
        Spacer(Modifier.width(16.dp))
        OneLine(value, style = MaterialTheme.typography.labelMedium)
    }
}

/** One choice: a small moving preview, its name under it; the one in use is outlined and named in the accent colour. */
@Composable
private fun EffectTile(
    label: String,
    description: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    frame: DrawScope.() -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val backdrop = colours.surfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            }.testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colours.primary else colours.outlineVariant), shape),
        ) {
            drawRect(backdrop)
            frame()
        }
        Box(Modifier.heightIn(min = 32.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            OneLine(label, style = MaterialTheme.typography.labelMedium, color = if (selected) colours.primary else colours.onSurface)
        }
    }
}

/**
 * Milliseconds since the panel opened, updated every frame. Read it only
 * while drawing (inside a tile's draw lambda): then a frame redraws the
 * tiles without recomposing the panel, which on a slow phone kept the main
 * thread too busy to answer a tap. It is an infinite animation, so it does
 * not hold up tests.
 */
@Composable
private fun tileClock(): androidx.compose.runtime.MutableLongState {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(clock) {
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { clock.longValue = (it - start) / 1_000_000 }
        }
    }
    return clock
}

/** What a tile draws for one item: its picture, or a plain colour (a card, or while the picture loads). */
private class TilePicture(
    val image: ImageBitmap?,
    val color: Color,
)

@Composable
private fun picture(
    clip: MainClip,
    sourceMs: Long,
    thumbnail: ThumbSource,
    fallback: Color,
): TilePicture {
    val latest by androidx.compose.runtime.rememberUpdatedState(clip)
    val media = (clip as? com.itsluminous.clearmovie.domain.model.MediaClip)?.media
    val bitmap by produceState<Bitmap?>(null, clip.id, media, sourceMs / 500) {
        value = runCatching { thumbnail(latest, sourceMs) }.getOrNull()
    }
    val color = (clip as? CardClip)?.color?.let { Color(it.argb.toInt()) } ?: fallback
    return TilePicture(bitmap?.asImageBitmap(), color)
}

/** The two items a junction joins: the end of the one leading, the start of the next. */
@Composable
private fun junctionPictures(
    lead: MainSlot,
    next: MainSlot,
    thumbnail: ThumbSource,
    outColour: Color,
    inColour: Color,
): Pair<TilePicture, TilePicture> {
    val outAt =
        when (val c = lead.clip) {
            is VideoClip -> (c.trimEndMs - 200).coerceAtLeast(c.trimStartMs)
            is IntroClip, is EndingClip -> (lead.durationMs - 200).coerceAtLeast(0)
            else -> 0
        }
    val inAt = (next.clip as? VideoClip)?.trimStartMs ?: 0
    return picture(lead.clip, outAt, thumbnail, outColour) to picture(next.clip, inAt, thumbnail, inColour)
}

@Composable
private fun itemPicture(
    clip: Clip,
    thumbnail: ThumbSource,
    colour: Color,
): TilePicture {
    val main = clip as? MainClip ?: return TilePicture(null, colour)
    return picture(main, (main as? VideoClip)?.trimStartMs ?: 0, thumbnail, colour)
}

/** A transition tile at [clockMs]: the outgoing picture under the incoming one, as lanes A and B are drawn. */
private fun DrawScope.transitionTileFrame(
    kind: TransitionKind,
    clockMs: Long,
    pictures: Pair<TilePicture, TilePicture>,
) {
    val t = clockMs % TILE_LOOP_MS - TILE_LEAD_MS
    val lengthUs = TILE_EFFECT_MS * 1000
    val us = (t * 1000).coerceIn(0, lengthUs)
    if (kind == TransitionKind.NONE) {
        // A plain cut, half way.
        drawTilePicture(if (us < lengthUs / 2) pictures.first else pictures.second, MotionFrame.STILL)
        return
    }
    val out =
        PictureMotion(lengthUs, NormRect.FULL, true, transitionOut = TransitionPart(kind, 0, lengthUs, TransitionRole.OUT, upper = false))
    val into =
        PictureMotion(lengthUs, NormRect.FULL, true, transitionIn = TransitionPart(kind, 0, lengthUs, TransitionRole.IN, upper = true))
    val aspect = (size.width / size.height).toDouble()
    drawTilePicture(pictures.first, MotionMath.frameAt(out, us, aspect))
    drawTilePicture(pictures.second, MotionMath.frameAt(into, us, aspect))
}

/** An animation tile at [clockMs], on an item a few seconds long. */
private fun DrawScope.animationTileFrame(
    clip: Clip,
    slot: AnimSlot,
    kind: AnimKind?,
    lengthMs: Long,
    clockMs: Long,
    picture: TilePicture,
) {
    if (kind == null) {
        drawTilePicture(picture, MotionFrame.STILL)
        return
    }
    val itemUs = TILE_ITEM_MS * 1000
    val anim = Anim(kind, lengthMs, seed = kenBurnsSeed(clip.id))
    val motion =
        when (slot) {
            AnimSlot.START -> PictureMotion(itemUs, NormRect.FULL, true, start = anim)
            AnimSlot.END -> PictureMotion(itemUs, NormRect.FULL, true, end = anim)
            AnimSlot.DURING -> PictureMotion(itemUs, NormRect.FULL, true, during = anim)
        }
    val loopMs = if (slot == AnimSlot.DURING) TILE_ITEM_MS else lengthMs + TILE_LOOP_MS - TILE_EFFECT_MS
    val t = clockMs % loopMs
    val clipMs =
        when (slot) {
            AnimSlot.START -> t
            AnimSlot.END -> (TILE_ITEM_MS - lengthMs - TILE_LEAD_MS + t).coerceAtMost(TILE_ITEM_MS)
            AnimSlot.DURING -> t
        }
    drawTilePicture(picture, MotionMath.frameAt(motion, clipMs * 1000, (size.width / size.height).toDouble()))
}

private const val TILE_ITEM_MS = 3_000L

/** One picture filling the tile, moved, faded, tinted and cut as [f] says (the order of the export's shader). */
private fun DrawScope.drawTilePicture(
    picture: TilePicture,
    f: MotionFrame,
) {
    val w = size.width
    val h = size.height
    clipPath(maskPath(f.mask, w, h)) {
        clipRect(0f, 0f, w, h) {
            translate(f.dx * w, f.dy * h) {
                scale(f.scale, f.scale, pivot = Offset(w / 2, h / 2)) {
                    val image = picture.image
                    if (image != null) {
                        // Cover-cropped, as an item fills the frame window.
                        val scale = maxOf(w / image.width, h / image.height)
                        val sw = (w / scale).roundToInt().coerceIn(1, image.width)
                        val sh = (h / scale).roundToInt().coerceIn(1, image.height)
                        drawImage(
                            image,
                            srcOffset = IntOffset((image.width - sw) / 2, (image.height - sh) / 2),
                            srcSize = IntSize(sw, sh),
                            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                            alpha = f.alpha,
                        )
                    } else {
                        drawRect(picture.color, alpha = f.alpha)
                    }
                    if (f.tintAmount > 0f) {
                        drawRect(Color(f.tint.argb.toInt()), alpha = f.tintAmount * f.alpha)
                    }
                }
            }
        }
    }
}
