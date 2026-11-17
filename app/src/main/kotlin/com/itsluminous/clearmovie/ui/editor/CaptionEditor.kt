package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionFont
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.CaptionStyleOverride
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.TextBackground
import com.itsluminous.clearmovie.domain.model.TextClip
import com.itsluminous.clearmovie.domain.model.TextOutline
import com.itsluminous.clearmovie.domain.model.TextRules
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.deleteClip
import com.itsluminous.clearmovie.domain.model.setCaptionStyle
import com.itsluminous.clearmovie.domain.model.setText
import com.itsluminous.clearmovie.domain.model.withEffectDefaultsFor
import kotlin.math.roundToInt

// The caption editor (cm-captions, ARCHITECTURE section 11.1): the panel and
// the box on the preview. Every change shows in the preview at once; one
// visit to the editor is one step to undo, and Cancel takes the visit back.

/** The undo step one visit to the caption editor of [id] makes. */
fun captionMergeKey(id: ClipId) = "caption:${id.value}"

const val CAPTION_PANEL_TAG = "caption_panel"
const val CAPTION_SIZE_TAG = "caption_size"
const val CAPTION_SAMPLE_TAG = "caption_sample"
const val CAPTION_OUTLINE_TAG = "caption_outline"
const val CAPTION_BACKGROUND_TAG = "caption_background"
const val CAPTION_RESET_TAG = "caption_reset"
const val CAPTION_BOX_TAG = "caption_box"

fun captionTabTag(tab: CaptionTab) = "caption_tab_${tab.name}"

fun captionPlaceTag(place: CaptionPlace) = "caption_place_${place.name}"

fun captionFontTag(font: CaptionFont) = "caption_font_${font.name}"

fun captionColourTag(index: Int) = "caption_colour_$index"

/** The editor's tabs, in order. */
enum class CaptionTab { WORDS, PLACE, SIZE, FONT, COLOUR }

/** The text colours offered, on a checkerboard; named for TalkBack. */
val CAPTION_COLOURS: List<Pair<ArgbColor, Int>> =
    listOf(
        ArgbColor.WHITE to R.string.colour_white,
        ArgbColor(0xFFFFEB3BL) to R.string.colour_yellow,
        ArgbColor(0xFF111111L) to R.string.colour_black,
        ArgbColor(0xFFE53935L) to R.string.colour_red,
        ArgbColor(0xFF42A5F5L) to R.string.colour_blue,
        ArgbColor(0xFF66BB6AL) to R.string.colour_green,
    )

/** The size slider's range and step, in pixels on the 1080-line canvas (the preset's 64 sits in it). */
const val CAPTION_MIN_SIZE = 32
const val CAPTION_MAX_SIZE = 128
const val CAPTION_SIZE_STEP = 4

private val CHECKER_LIGHT = Color(0xFFBDBDBD)
private val CHECKER_DARK = Color(0xFF8A8A8A)

/** An outline that shows against [text]: black around light letters, white around dark ones. */
fun contrastOutline(text: ArgbColor): TextOutline = TextOutline(if (isLight(text)) ArgbColor.BLACK else ArgbColor.WHITE, DEFAULT_OUTLINE_PX)

/** A band that shows [text]: dark behind light letters, light behind dark ones. */
fun contrastBand(text: ArgbColor): TextBackground =
    TextBackground(if (isLight(text)) ArgbColor(DARK_BAND) else ArgbColor(LIGHT_BAND), DEFAULT_CORNER_PX)

private fun isLight(c: ArgbColor): Boolean {
    val r = (c.argb shr 16 and 0xFF) / 255.0
    val g = (c.argb shr 8 and 0xFF) / 255.0
    val b = (c.argb and 0xFF) / 255.0
    return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.45
}

private const val DEFAULT_OUTLINE_PX = 4
private const val DEFAULT_CORNER_PX = 12
private const val DARK_BAND = 0x99000000L
private const val LIGHT_BAND = 0xCCFFFFFFL

/**
 * The style after the user picked text colour [colour]: an outline or band
 * that would hide the new colour is switched to one that shows it.
 */
fun CaptionStyle.withTextColour(colour: ArgbColor): CaptionStyle {
    val light = isLight(colour)
    val outline = outline?.let { o -> if (isLight(o.color) == light) contrastOutline(colour) else o }
    val background = background?.let { b -> if (b.color.alpha > BAND_SHOWS && isLight(b.color) == light) contrastBand(colour) else b }
    return copy(color = colour, outline = outline, background = background)
}

private const val BAND_SHOWS = 0x40

/**
 * "Add caption" on photo or video [itemId]: a new caption attached to it,
 * empty and in the preset's style, with the preset's start animation, then
 * the editor on it. The caption is part of the editor visit's undo step.
 */
fun startCaption(
    itemId: ClipId,
    actions: EditorCallbacks,
    state: EditorScreenState,
) {
    val id = RandomIds.clipId()
    val made = actions.editMerged(captionMergeKey(id)) { addCaptionFor(itemId, "", captionId = id).withEffectDefaultsFor(listOf(id)) }
    if (made) openCaption(id, state)
}

/** Selects caption [id] and opens the editor on it. */
fun openCaption(
    id: ClipId,
    state: EditorScreenState,
) {
    state.selectedRow = null
    state.selected = id
    state.panel = OpenPanel(Panel.TEXT, id)
}

/**
 * The editor of caption [id] closed, however it closed (Done, Back, a tap on
 * the timeline): a caption left without words goes (taken back with its
 * visit when it was new), and the visit's undo step is finished.
 */
fun finishCaption(
    id: ClipId,
    project: Project?,
    actions: EditorCallbacks,
) {
    val caption = project?.clip(id) as? TextClip
    if (caption != null && caption.text.isBlank()) {
        actions.revertMerge(captionMergeKey(id))
        actions.edit { if (clip(id) != null) deleteClip(id) else this }
    }
    actions.endMerge()
}

/**
 * The caption editor: tabs for the words, the place, the size, the font and
 * the colour, and Reset to preset style under them. [window] is the frame's
 * window, where the caption may go.
 */
@Composable
internal fun CaptionPanel(
    caption: TextClip,
    project: Project,
    window: NormRect,
    callbacks: EditorCallbacks,
    onDone: () -> Unit,
) {
    val key = captionMergeKey(caption.id)
    val defaults = project.captionDefaults
    val style = caption.style.resolve(defaults)

    fun restyle(wanted: CaptionStyle) {
        callbacks.editMerged(key) { setCaptionStyle(caption.id, CaptionStyleOverride.between(defaults, wanted)) }
    }
    PanelTitle(
        stringResource(R.string.caption_title),
        onDone = onDone,
        onCancel = {
            callbacks.revertMerge(key)
            onDone()
        },
    )
    var tabName by rememberSaveable(caption.id.value) { mutableStateOf(CaptionTab.WORDS.name) }
    val tab = CaptionTab.entries.firstOrNull { it.name == tabName } ?: CaptionTab.WORDS
    PrimaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.testTag(CAPTION_PANEL_TAG)) {
        for (t in CaptionTab.entries) {
            val label = stringResource(tabLabel(t))
            Tab(
                selected = t == tab,
                onClick = { tabName = t.name },
                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(painterResource(tabIcon(t)), contentDescription = null, modifier = Modifier.size(24.dp)) },
                modifier = Modifier.heightIn(min = 64.dp).testTag(captionTabTag(t)),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    when (tab) {
        CaptionTab.WORDS -> WordsTab(caption, callbacks, key)
        CaptionTab.PLACE -> PlaceTab(style, defaults.box, CaptionPlaces.safeArea(window), ::restyle)
        CaptionTab.SIZE -> SizeTab(caption, style, defaults, ::restyle)
        CaptionTab.FONT -> FontTab(style, ::restyle)
        CaptionTab.COLOUR -> ColourTab(style, ::restyle)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { callbacks.editMerged(key) { setCaptionStyle(caption.id, CaptionStyleOverride.NONE) } },
        enabled = !caption.style.isEmpty,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CAPTION_RESET_TAG),
    ) {
        Icon(painterResource(R.drawable.ic_restore), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.caption_reset), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(8.dp))
}

private fun tabLabel(tab: CaptionTab): Int =
    when (tab) {
        CaptionTab.WORDS -> R.string.caption_tab_words
        CaptionTab.PLACE -> R.string.caption_tab_place
        CaptionTab.SIZE -> R.string.caption_tab_size
        CaptionTab.FONT -> R.string.caption_tab_font
        CaptionTab.COLOUR -> R.string.caption_tab_colour
    }

private fun tabIcon(tab: CaptionTab): Int =
    when (tab) {
        CaptionTab.WORDS -> R.drawable.ic_text_fields
        CaptionTab.PLACE -> R.drawable.ic_open_with
        CaptionTab.SIZE -> R.drawable.ic_format_size
        CaptionTab.FONT -> R.drawable.ic_font_download
        CaptionTab.COLOUR -> R.drawable.ic_format_color_text
    }

/** Characters a caption may not hold (see TextRules) are left out as they are typed or pasted. */
internal fun usableCaptionText(typed: String): String {
    val kept = typed.filter { TextRules.isAllowed(it.toString(), allowNewlines = true) }
    return kept.take(Limits.MAX_TEXT_LENGTH)
}

@Composable
private fun WordsTab(
    caption: TextClip,
    callbacks: EditorCallbacks,
    key: String,
) {
    // A TextFieldValue, saved with its cursor, so typing after a rotation goes on where it was.
    var text by rememberSaveable(caption.id.value, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(caption.text, TextRange(caption.text.length)))
    }
    // An undo or redo while the editor is open puts the field back in step.
    LaunchedEffect(caption.text) { if (caption.text != text.text) text = TextFieldValue(caption.text, TextRange(caption.text.length)) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            val usable = usableCaptionText(typed.text)
            // Left-out characters move the cursor back with them.
            val cut = typed.text.length - usable.length
            val cursor = (typed.selection.end - cut).coerceIn(0, usable.length)
            text = if (cut == 0) typed else TextFieldValue(usable, TextRange(cursor))
            if (usable != caption.text) callbacks.editMerged(key) { setText(caption.id, usable) }
        },
        label = { Text(stringResource(R.string.caption_text_hint)) },
        minLines = 2,
        maxLines = 4,
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().testTag(TEXT_FIELD_TAG),
    )
}

@Composable
private fun PlaceTab(
    style: CaptionStyle,
    usual: NormRect,
    safe: NormRect,
    restyle: (CaptionStyle) -> Unit,
) {
    val current = CaptionPlaces.placeOf(style.box, usual, safe)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (place in CaptionPlace.entries) {
            val label = stringResource(placeLabel(place))
            FilterChip(
                selected = place == current,
                onClick = { restyle(style.copy(box = CaptionPlaces.box(place, usual, safe))) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(captionPlaceTag(place)),
            )
        }
    }
    Text(
        stringResource(R.string.caption_place_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun placeLabel(place: CaptionPlace): Int =
    when (place) {
        CaptionPlace.USUAL -> R.string.caption_place_usual
        CaptionPlace.TOP -> R.string.caption_place_top
        CaptionPlace.MIDDLE -> R.string.caption_place_middle
        CaptionPlace.BOTTOM -> R.string.caption_place_bottom
    }

@Composable
private fun SizeTab(
    caption: TextClip,
    style: CaptionStyle,
    defaults: CaptionStyle,
    restyle: (CaptionStyle) -> Unit,
) {
    val percent = style.sizePx * 100 / defaults.sizePx.coerceAtLeast(1)
    val shown = stringResource(R.string.caption_size_value, percent)
    val label = stringResource(R.string.caption_tab_size)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.caption_size_smaller), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Slider(
            value = style.sizePx.toFloat().coerceIn(CAPTION_MIN_SIZE.toFloat(), CAPTION_MAX_SIZE.toFloat()),
            onValueChange = { v ->
                val px = (v / CAPTION_SIZE_STEP).roundToInt() * CAPTION_SIZE_STEP
                if (px != style.sizePx) restyle(style.copy(sizePx = px))
            },
            valueRange = CAPTION_MIN_SIZE.toFloat()..CAPTION_MAX_SIZE.toFloat(),
            steps = (CAPTION_MAX_SIZE - CAPTION_MIN_SIZE) / CAPTION_SIZE_STEP - 1,
            modifier =
                Modifier.weight(1f).heightIn(min = 48.dp).testTag(CAPTION_SIZE_TAG).semantics {
                    contentDescription = label
                    stateDescription = shown
                },
        )
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.caption_size_bigger), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
    Spacer(Modifier.height(8.dp))
    // The caption as the video will show it, larger than in the preview, on the checkerboard.
    val sampleText = caption.text.ifBlank { stringResource(R.string.caption_font_sample) }
    val (w, h) = canvasPixels(style.box)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio((w.toFloat() / h).coerceIn(1f, 12f))
            .clip(RoundedCornerShape(8.dp))
            .checkerboard()
            .testTag(CAPTION_SAMPLE_TAG),
    ) {
        CaptionImage(sampleText, style)
    }
}

@Composable
private fun FontTab(
    style: CaptionStyle,
    restyle: (CaptionStyle) -> Unit,
) {
    val renderer = rememberCaptionRenderer()
    val chosen = CaptionFont.of(style.font)
    for (pair in CaptionFont.entries.chunked(2)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (font in pair) {
                val name = stringResource(fontLabel(font))
                val family =
                    remember(font) {
                        FontFamily(
                            androidx.compose.ui.text.font
                                .Typeface(renderer.typeface(font.id)),
                        )
                    }
                val on = font == chosen
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            BorderStroke(
                                if (on) 2.dp else 1.dp,
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            RoundedCornerShape(12.dp),
                        ).clickable(role = Role.RadioButton) { restyle(style.copy(font = font.id)) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = name
                            selected = on
                        }.padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(captionFontTag(font)),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = family),
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.caption_font_sample),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = family),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun fontLabel(font: CaptionFont): Int =
    when (font) {
        CaptionFont.SANS -> R.string.caption_font_sans
        CaptionFont.SANS_BOLD -> R.string.caption_font_sans_bold
        CaptionFont.SERIF -> R.string.caption_font_serif
        CaptionFont.SERIF_BOLD -> R.string.caption_font_serif_bold
    }

@Composable
private fun ColourTab(
    style: CaptionStyle,
    restyle: (CaptionStyle) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .checkerboard()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CAPTION_COLOURS.forEachIndexed { i, (colour, name) ->
            val label = stringResource(R.string.caption_colour_choice, stringResource(name))
            val on = style.color == colour
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.RadioButton) { restyle(style.withTextColour(colour)) }
                    .semantics {
                        contentDescription = label
                        selected = on
                    }.testTag(captionColourTag(i)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(colour.argb.toInt()))
                        .border(
                            BorderStroke(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color(0x99000000)),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = if (isLight(colour)) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    SwitchRow(stringResource(R.string.caption_outline), style.outline != null, CAPTION_OUTLINE_TAG) { on ->
        restyle(style.copy(outline = if (on) contrastOutline(style.color) else null))
    }
    SwitchRow(stringResource(R.string.caption_background), style.background != null, CAPTION_BACKGROUND_TAG) { on ->
        restyle(style.copy(background = if (on) contrastBand(style.color) else null))
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A fixed grey checkerboard, so caption colours are judged on neither the light nor the dark theme. */
private fun Modifier.checkerboard(cell: Dp = 8.dp): Modifier =
    drawBehind {
        val c = cell.toPx()
        drawRect(CHECKER_LIGHT)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else c
            while (x < size.width) {
                drawRect(CHECKER_DARK, Offset(x, y), Size(c, c))
                x += 2 * c
            }
            y += c
            row++
        }
    }

/**
 * The caption's box over the preview while its editor is open: a dashed
 * outline the user drags to move the caption, inside the frame window's
 * safe area, snapping to the preset's place. The Place tab does the same
 * without dragging.
 */
@Composable
internal fun CaptionBoxOverlay(
    caption: TextClip,
    project: Project,
    window: NormRect,
    callbacks: EditorCallbacks,
    modifier: Modifier = Modifier,
) {
    val defaults = project.captionDefaults
    val style = caption.style.resolve(defaults)
    val safe = CaptionPlaces.safeArea(window)
    val latest by rememberUpdatedState(style)
    var start by remember { mutableStateOf<NormRect?>(null) }
    var moved by remember { mutableStateOf(Offset.Zero) }
    var snapped by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.caption_box_description)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val box = style.box
        if (start != null) {
            // While dragging: the safe area, and the preset's place when the box sits on it.
            Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                drawRect(
                    Color.White.copy(alpha = 0.5f),
                    Offset((safe.x * size.width).toFloat(), (safe.y * size.height).toFloat()),
                    Size((safe.w * size.width).toFloat(), (safe.h * size.height).toFloat()),
                    style = Stroke(1.dp.toPx(), pathEffect = dash),
                )
                if (snapped) {
                    val u = defaults.box
                    drawRect(
                        Color.White,
                        Offset((u.x * size.width).toFloat(), (u.y * size.height).toFloat()),
                        Size((u.w * size.width).toFloat(), (u.h * size.height).toFloat()),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
        }
        val boxH = h * box.h.toFloat()
        // The hit area is at least 48 dp tall, centred on a thinner box.
        val extra = ((48.dp - boxH) / 2).coerceAtLeast(0.dp)
        Box(
            Modifier
                .offset(w * box.x.toFloat(), h * box.y.toFloat() - extra)
                .size(w * box.w.toFloat(), boxH + extra * 2)
                .semantics { contentDescription = description }
                .testTag(CAPTION_BOX_TAG)
                .pointerInput(caption.id, safe, defaults.box) {
                    detectDragGestures(
                        onDragStart = {
                            start = latest.box
                            moved = Offset.Zero
                        },
                        onDragEnd = {
                            start = null
                            snapped = false
                        },
                        onDragCancel = {
                            start = null
                            snapped = false
                        },
                    ) { change, amount ->
                        change.consume()
                        val from = start ?: return@detectDragGestures
                        moved += amount
                        val (next, snap) =
                            CaptionPlaces.dragged(
                                from,
                                moved.x / (w.toPx()).toDouble(),
                                moved.y / (h.toPx()).toDouble(),
                                safe,
                                defaults.box,
                            )
                        snapped = snap
                        if (next != latest.box) {
                            callbacks.editMerged(captionMergeKey(caption.id)) {
                                setCaptionStyle(caption.id, CaptionStyleOverride.between(defaults, latest.copy(box = next)))
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(boxH).align(Alignment.Center)) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
                drawRoundRect(Color.Black.copy(alpha = 0.6f), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(4.dp.toPx()))
                drawRoundRect(primary, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = dash))
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-12).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_open_with),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
