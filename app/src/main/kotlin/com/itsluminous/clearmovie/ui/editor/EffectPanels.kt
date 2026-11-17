package com.itsluminous.clearmovie.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
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
import com.itsluminous.clearmovie.ui.editor.timeline.EffectDirection
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.ThumbSource
import com.itsluminous.clearmovie.ui.editor.timeline.TransitionGroup
import com.itsluminous.clearmovie.ui.editor.timeline.animationDirection
import com.itsluminous.clearmovie.ui.editor.timeline.animationName
import com.itsluminous.clearmovie.ui.editor.timeline.directionName
import com.itsluminous.clearmovie.ui.editor.timeline.transitionDirection
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
@OptIn(ExperimentalLayoutApi::class)
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

    val pictures = junctionPictures(slot, next, thumbnail)
    val clock = tileClock()
    val chosen = current?.kind ?: TransitionKind.NONE
    val singles = TransitionGroup.entries.filter { it.kinds.size == 1 }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (g in singles) {
            val kind = g.kinds.single()
            EffectTile(
                label = stringResource(g.label),
                description = stringResource(g.label),
                selected = chosen == kind,
                tag = transitionTileTag(kind),
                onClick = { choose(kind) },
            ) { transitionTileFrame(kind, clock, pictures) }
        }
    }
    for (g in TransitionGroup.entries.filter { it.kinds.size > 1 }) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(g.label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (kind in g.kinds) {
                val direction = transitionDirection(kind)!!
                EffectTile(
                    label = stringResource(directionName(direction)),
                    description =
                        stringResource(
                            R.string.effect_tile_with_direction,
                            stringResource(g.label),
                            stringResource(directionName(direction)),
                        ),
                    selected = chosen == kind,
                    tag = transitionTileTag(kind),
                    direction = direction,
                    onClick = { choose(kind) },
                ) { transitionTileFrame(kind, clock, pictures) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    LengthSlider(
        valueMs = wanted,
        minMs = Transition.MIN_MS,
        maxMs = maxMs,
        enabled = current != null && maxMs > Transition.MIN_MS,
        onChange = { length = it },
        onDone = {
            val t = current ?: return@LengthSlider
            val changed = t.copy(durationMs = length.coerceIn(Transition.MIN_MS, maxMs))
            val forAll = project.transitionForAll == t
            if (callbacks.edit { if (forAll) applyTransitionToAll(changed) else setTransition(slot.clip.id, changed) }) {
                preview(changed.durationMs)
            }
        },
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val forAll = current != null && project.transitionForAll == current
        FilledTonalButton(
            onClick = { current?.let { t -> callbacks.edit { applyTransitionToAll(t.copy(durationMs = wanted)) } } },
            enabled = current != null && !forAll,
            modifier = Modifier.heightIn(min = 48.dp).testTag(TRANSITION_APPLY_ALL_TAG),
        ) {
            if (forAll) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(stringResource(if (forAll) R.string.transition_applied_all else R.string.transition_apply_all))
        }
        OutlinedButton(
            onClick = { callbacks.edit { setTransition(slot.clip.id, null) } },
            enabled = current != null,
            modifier = Modifier.heightIn(min = 48.dp).testTag(TRANSITION_REMOVE_TAG),
        ) {
            Text(stringResource(R.string.transition_remove))
        }
        InsertHereButton(insertHere)
    }
}

@Composable
private fun InsertHereButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(TRANSITION_INSERT_TAG)) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.transition_insert_here))
    }
}

/**
 * The animations of the item [open] is about, in three tabs (ARCHITECTURE
 * section 11.10): Start, End and All through, each showing only what suits
 * this kind of item (gentle zooms for photos; Pop, Bounce and Pulse for
 * text), with a Length slider for Start and End. The preview plays the part
 * that changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnimationPanel(
    open: OpenPanel,
    project: Project,
    thumbnail: ThumbSource,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    PanelTitle(stringResource(R.string.animation_title), onDone = close, onCancel = null)
    val slot = project.mainSlot(open.clipId) ?: return
    val clip = slot.clip
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
        val at = if (tab == AnimSlot.END) slot.endMs - len - PREVIEW_LEAD_MS else slot.startMs
        callbacks.previewFrom(at.coerceIn(slot.startMs, slot.endMs))
    }

    fun anim(kind: AnimKind) = Anim(kind, wanted, seed = if (AnimSlot.DURING in kind.slots) kenBurnsSeed(clip.id) else 0)

    fun choose(kind: AnimKind?) {
        if (callbacks.edit { setAnimation(clip.id, tab, kind?.let(::anim)) }) preview(wanted)
    }

    val picture = itemPicture(clip, thumbnail)
    val clock = tileClock()
    val choices = clip.animationChoices(tab)
    val plain = listOf<AnimKind?>(null) + choices.filter { animationDirection(it) == null }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in plain) {
            val label = stringResource(kind?.let(::animationName) ?: R.string.effect_none)
            EffectTile(
                label = label,
                description = label,
                selected = current?.kind == kind,
                tag = animationTileTag(tab, kind),
                onClick = { choose(kind) },
            ) { animationTileFrame(clip, tab, kind, wanted, clock, picture) }
        }
    }
    val directed = choices.filter { animationDirection(it) != null }
    if (directed.isNotEmpty()) {
        val heading = stringResource(animationName(directed.first()))
        Spacer(Modifier.height(8.dp))
        Text(heading, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (kind in directed) {
                val direction = animationDirection(kind)!!
                EffectTile(
                    label = stringResource(directionName(direction)),
                    description = stringResource(R.string.effect_tile_with_direction, heading, stringResource(directionName(direction))),
                    selected = current?.kind == kind,
                    tag = animationTileTag(tab, kind),
                    direction = direction,
                    onClick = { choose(kind) },
                ) { animationTileFrame(clip, tab, kind, wanted, clock, picture) }
            }
        }
    }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.effect_length), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
    val steps = ((maxMs - minMs) / 100 - 1).toInt().coerceAtLeast(0)
    val label = stringResource(R.string.effect_length)
    Slider(
        value = valueMs.toFloat(),
        onValueChange = { onChange((it / 100).roundToLong() * 100) },
        onValueChangeFinished = onDone,
        valueRange = minMs.toFloat()..maxOf(maxMs, minMs + 1).toFloat(),
        steps = steps,
        enabled = enabled,
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(EFFECT_LENGTH_TAG).semantics {
                contentDescription = label
                stateDescription = value
            },
    )
}

/** One choice: a small moving preview, its name under it, a border when it is the one in use. */
@Composable
private fun EffectTile(
    label: String,
    description: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    direction: EffectDirection? = null,
    frame: DrawScope.() -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    val backdrop = MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier
            .width(TILE_W.dp)
            .clip(shape)
            .then(if (selected) Modifier.border(BorderStroke(3.dp, primary), shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            }.padding(4.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.width((TILE_W - 8).dp).height(((TILE_W - 8) * 9 / 16).dp).clip(RoundedCornerShape(6.dp))) {
            drawRect(backdrop)
            frame()
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (direction != null) {
                Icon(
                    painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).rotate(direction.degrees),
                )
                Spacer(Modifier.width(2.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

private const val TILE_W = 104

private val EffectDirection.degrees: Float
    get() =
        when (this) {
            EffectDirection.RIGHT -> 0f
            EffectDirection.DOWN -> 90f
            EffectDirection.LEFT -> 180f
            EffectDirection.UP -> 270f
        }

/** Milliseconds since the panel opened, every frame; it does not hold up tests (an infinite animation). */
@Composable
private fun tileClock(): Long {
    val t by produceState(0L) {
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { value = (it - start) / 1_000_000 }
        }
    }
    return t
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
    val bitmap by produceState<Bitmap?>(null, clip, sourceMs / 500) { value = runCatching { thumbnail(clip, sourceMs) }.getOrNull() }
    val color = (clip as? CardClip)?.color?.let { Color(it.argb.toInt()) } ?: fallback
    return TilePicture(bitmap?.asImageBitmap(), color)
}

/** The two items a junction joins: the end of the one leading, the start of the next. */
@Composable
private fun junctionPictures(
    lead: MainSlot,
    next: MainSlot,
    thumbnail: ThumbSource,
): Pair<TilePicture, TilePicture> {
    val outAt =
        when (val c = lead.clip) {
            is VideoClip -> (c.trimEndMs - 200).coerceAtLeast(c.trimStartMs)
            is IntroClip, is EndingClip -> (lead.durationMs - 200).coerceAtLeast(0)
            else -> 0
        }
    val inAt = (next.clip as? VideoClip)?.trimStartMs ?: 0
    return picture(lead.clip, outAt, thumbnail, OUT_COLOUR) to picture(next.clip, inAt, thumbnail, IN_COLOUR)
}

@Composable
private fun itemPicture(
    clip: Clip,
    thumbnail: ThumbSource,
): TilePicture {
    val main = clip as? MainClip ?: return TilePicture(null, IN_COLOUR)
    return picture(main, (main as? VideoClip)?.trimStartMs ?: 0, thumbnail, IN_COLOUR)
}

private val OUT_COLOUR = Color(0xFF5C6BC0)
private val IN_COLOUR = Color(0xFFFFB74D)

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
