package com.itsluminous.clearmovie.ui.editor

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CaptionFont
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.CaptionStyleOverride
import com.itsluminous.clearmovie.domain.model.CaptionWords
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectCodec
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
import com.itsluminous.clearmovie.render.media3.CaptionBitmapRenderer
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.components.MIN_LABEL_FONT_SIZE
import com.itsluminous.clearmovie.ui.components.MoreBelow
import com.itsluminous.clearmovie.ui.components.RectAnchor
import com.itsluminous.clearmovie.ui.components.RectGrip
import com.itsluminous.clearmovie.ui.components.ResizableRect
import com.itsluminous.clearmovie.ui.components.labelFits
import com.itsluminous.clearmovie.ui.components.rememberLabelLines
import com.itsluminous.clearmovie.ui.preset.PresetNames
import java.util.Locale
import kotlin.math.ceil
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

/**
 * The caption editors' tabs, in order. A caption's own editor has [EDITOR]
 * (its animation is the bar's Animation, as for any item); a preset's
 * caption style editor has every one, [ANIMATION] being the start animation
 * new captions get (cm-caption-box).
 */
enum class CaptionTab {
    WORDS,
    PLACE,
    SIZE,
    FONT,
    COLOUR,
    ANIMATION,
    ;

    companion object {
        val EDITOR = listOf(WORDS, PLACE, SIZE, FONT, COLOUR)
        val PRESET = entries.toList()
    }
}

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

/** The tallest tab's content (Colour: the swatches and two switch rows), at the base text size. */
private val TAB_CONTENT_MIN = 176.dp

/** The size sample's height: the middle of the caption, larger than in the preview. */
private val SAMPLE_HEIGHT = 80.dp

/** Room a tab label keeps clear of its tab's edges: 8 dp each side. */
private val TAB_LABEL_ROOM = 16.dp

/** A chip's padding around its label (16 dp each side), plus a little air. */
private val CHIP_LABEL_ROOM = 36.dp

/** How strongly a caption with no words shows its sample in the preview. */
private const val BLANK_SAMPLE_ALPHA = 0.6f

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
 * with the preset's default words (or the app's own, [CaptionWords.DEFAULT]) and in the preset's style, with the preset's start animation, then
 * the editor on it. The caption is part of the editor visit's undo step.
 * When the item already has a caption covering it whole, the editor opens on
 * that one instead: a second would cover the same time in the same place,
 * drawn over the first. (A caption moved to a part of the item leaves room.)
 */
fun startCaption(
    itemId: ClipId,
    project: Project?,
    actions: EditorCallbacks,
    state: EditorScreenState,
) {
    // An item with its own caption already: that one opens, so a second is never drawn over the first.
    project
        ?.tracks
        ?.flatMap { it.clips }
        ?.filterIsInstance<TextClip>()
        ?.firstOrNull { it.attachedTo?.clip == itemId && it.attachedTo.whole }
        ?.let { return openCaption(it.id, state) }
    val id = RandomIds.clipId()
    val words = project?.newCaptionWords() ?: CaptionWords.DEFAULT
    val made = actions.editMerged(captionMergeKey(id)) { addCaptionFor(itemId, words, captionId = id).withEffectDefaultsFor(listOf(id)) }
    if (made) openCaption(id, state)
}

/**
 * The words a new caption starts with: the preset's default words
 * ([Project.captionDefaultText]) in the app's language, or the nearest
 * language there is; [CaptionWords.DEFAULT] when the preset has none (or
 * when it is a video without a preset).
 */
fun Project.newCaptionWords(locale: Locale = appLocale()): String =
    CaptionWords
        .withoutRetired(captionDefaultText)
        ?.let { PresetNames.pick(it, locale) }
        ?.let(::usableCaptionText)
        ?.takeIf { it.isNotBlank() }
        ?: CaptionWords.DEFAULT

/** The language the app shows: the one chosen in Settings, else the phone's. */
private fun appLocale(): Locale = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()

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
 * window, where the caption may go; [roomy] is false in a short panel.
 */
@Composable
internal fun ColumnScope.CaptionPanel(
    caption: TextClip,
    project: Project,
    window: NormRect,
    callbacks: EditorCallbacks,
    onDone: () -> Unit,
    roomy: Boolean = true,
    visit: EditorScreenState? = null,
) {
    val key = captionMergeKey(caption.id)
    val defaults = project.captionDefaults
    val style = caption.style.resolve(defaults)
    val aspect = LocalCanvasShape.current.aspect
    val fit =
        rememberCaptionFit(
            caption.text.ifBlank { stringResource(R.string.caption_font_sample) },
            defaults.box,
            CaptionPlaces.safeArea(window, defaults.box, aspect),
            CaptionPlaces.safeArea(window, null, aspect),
        )

    val latestStyle by rememberUpdatedState(style)

    fun restyle(wanted: CaptionStyle) {
        val fitted = fit(latestStyle, wanted)
        callbacks.editMerged(key) { setCaptionStyle(caption.id, CaptionStyleOverride.between(defaults, fitted)) }
    }
    // The caption as the visit found it, kept with the editor screen's state (so a rotation that moves this
    // panel, or the system closing the app, keeps it): Cancel puts it back even when the visit's undo step
    // is gone.
    val ownText = rememberSaveable(caption.id.value) { caption.text }
    val ownStyle =
        rememberSaveable(caption.id.value) { ProjectCodec.json.encodeToString(CaptionStyleOverride.serializer(), caption.style) }
    val startText = visit?.captionStartText ?: ownText
    val startStyle = visit?.captionStartStyle ?: ownStyle
    PanelTitle(
        stringResource(R.string.caption_title),
        onDone = onDone,
        onCancel = {
            if (!callbacks.revertMerge(key)) {
                val style0 = runCatching { ProjectCodec.json.decodeFromString(CaptionStyleOverride.serializer(), startStyle) }.getOrNull()
                callbacks.edit {
                    if (clip(caption.id) == null) return@edit this
                    val words = setText(caption.id, startText)
                    if (style0 != null) words.setCaptionStyle(caption.id, style0) else words
                }
            }
            onDone()
        },
    )
    var ownTab by rememberSaveable(caption.id.value) { mutableStateOf(CaptionTab.WORDS.name) }
    val tabName = visit?.captionTab ?: ownTab
    val tab = CaptionTab.EDITOR.firstOrNull { it.name == tabName } ?: CaptionTab.WORDS
    CaptionTabBar(tab) { if (visit != null) visit.captionTab = it.name else ownTab = it.name }
    // Below the title and the tabs, which stay put, the tab's content and Reset scroll when the room is short.
    val scroll = rememberScrollState()
    Box(Modifier.weight(1f, fill = false)) {
        Column(Modifier.verticalScroll(scroll)) {
            Spacer(Modifier.height(8.dp))
            // Every tab takes the same room, so changing tabs never makes the panel, and the rows above it, jump;
            // in a short panel (a phone held sideways) Reset stays in sight instead.
            Column(Modifier.fillMaxWidth().heightIn(min = if (roomy) TAB_CONTENT_MIN else 0.dp)) {
                when (tab) {
                    CaptionTab.WORDS -> {
                        WordsTab(caption, callbacks, key)
                    }

                    CaptionTab.PLACE -> {
                        PlaceTab(
                            style,
                            defaults.box,
                            CaptionPlaces.safeArea(window, defaults.box, aspect),
                            ::restyle,
                            inner = CaptionPlaces.safeArea(window, null, aspect),
                        )
                    }

                    CaptionTab.SIZE -> {
                        SizeTab(caption.id, caption.text, style, defaults, ::restyle)
                    }

                    CaptionTab.FONT -> {
                        FontTab(style, ::restyle)
                    }

                    CaptionTab.COLOUR -> {
                        ColourTab(style, ::restyle)
                    }

                    // A caption's animation is the bar's Animation (not one of this editor's tabs).
                    CaptionTab.ANIMATION -> {
                        Unit
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ResetButton(caption, callbacks, key)
            Spacer(Modifier.height(8.dp))
        }
        MoreBelow(scroll)
    }
}

/**
 * The style after a change from [old] to [wanted]: when the change makes the
 * words bigger (size, font or outline) or puts the box elsewhere (a Place
 * chip), its box grows to hold [words] at their size, the way a resize does
 * (cm-caption-box); otherwise [wanted] as it is. It only grows: a box the
 * user made taller stays so. [usual], [safe] and [inner] are as for [CaptionBoxDrag].
 */
@Composable
internal fun rememberCaptionFit(
    words: String,
    usual: NormRect,
    safe: NormRect,
    inner: NormRect,
): (old: CaptionStyle, wanted: CaptionStyle) -> CaptionStyle {
    val renderer = rememberCaptionRenderer()
    val latestWords by rememberUpdatedState(words)
    return remember(renderer, usual, safe, inner) {
        { old, wanted ->
            val bigger =
                wanted.sizePx != old.sizePx || wanted.font != old.font || wanted.outline?.widthPx != old.outline?.widthPx
            // Bigger words, or a box put somewhere else (a Place chip): the box holds the words at their size.
            if (!bigger && wanted.box == old.box) {
                wanted
            } else {
                val need = renderer.boxHeightFor(latestWords, wanted, wanted.box.w)
                val bounds = CaptionPlaces.dragBounds(wanted.box, RectGrip.BOTTOM, inner, safe, usual)
                wanted.copy(box = CaptionPlaces.grown(wanted.box, need, bounds))
            }
        }
    }
}

/**
 * The caption editor's tab bar, sized to the room it has: icon over label in the bar's sizes (24 dp,
 * labelMedium). A long label is drawn smaller to fit its tab, then on two lines (AutoSizeLabel), every
 * tab as tall. When a single word would not fit even at the smallest size (a narrow phone, large text)
 * the tabs keep their names and scroll sideways instead. The editor of a caption and the editor of a
 * preset's caption style both use it.
 */
@Composable
internal fun CaptionTabBar(
    tab: CaptionTab,
    tabs: List<CaptionTab> = CaptionTab.EDITOR,
    onTab: (CaptionTab) -> Unit,
) {
    val labels = tabs.map { stringResource(tabLabel(it)) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = maxWidth / tabs.size - TAB_LABEL_ROOM
        val roomPx = with(density) { room.roundToPx() }
        val scrolls =
            labels.flatMap { it.split(' ') }.any { word ->
                !labelFits(measurer, word, labelStyle.copy(fontSize = MIN_LABEL_FONT_SIZE), roomPx)
            }
        val lines = if (scrolls) 1 else rememberLabelLines(labels, labelStyle, room, density)
        CaptionTabs(tab, labels, scrolls, lines, scrollTabWidth = maxWidth / SCROLLING_TABS_SHOWN, tabs = tabs, onTab = onTab)
    }
}

/**
 * The caption editor's [tabs] ([labels] in the same order): equal tabs across the width; or, when a name cannot fit its share,
 * a scrolling row of equal tabs, [scrollTabWidth] each, three and a half in sight so the cut one says there
 * are more, every name kept (drawn smaller to fit when it must).
 */
@Composable
internal fun CaptionTabs(
    tab: CaptionTab,
    labels: List<String>,
    scrolls: Boolean,
    lines: Int,
    scrollTabWidth: Dp = 96.dp,
    tabs: List<CaptionTab> = CaptionTab.EDITOR,
    onTab: (CaptionTab) -> Unit,
) {
    val shown = tabs.indexOf(tab).coerceAtLeast(0)
    val row: @Composable () -> Unit = {
        for ((i, t) in tabs.withIndex()) {
            val label = labels[i]
            Tab(
                selected = t == tab,
                onClick = { onTab(t) },
                modifier =
                    Modifier
                        .heightIn(min = 64.dp)
                        .then(if (scrolls) Modifier.width(scrollTabWidth) else Modifier)
                        .testTag(captionTabTag(t)),
            ) {
                Column(
                    Modifier.padding(horizontal = TAB_LABEL_ROOM / 2, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(painterResource(tabIcon(t)), contentDescription = null, modifier = Modifier.size(24.dp))
                    AutoSizeLabel(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lines = lines,
                    )
                }
            }
        }
    }
    if (scrolls) {
        PrimaryScrollableTabRow(
            selectedTabIndex = shown,
            containerColor = Color.Transparent,
            edgePadding = 0.dp,
            modifier = Modifier.testTag(CAPTION_PANEL_TAG),
        ) { row() }
    } else {
        PrimaryTabRow(selectedTabIndex = shown, containerColor = Color.Transparent, modifier = Modifier.testTag(CAPTION_PANEL_TAG)) {
            row()
        }
    }
}

@Composable
private fun ResetButton(
    caption: TextClip,
    callbacks: EditorCallbacks,
    key: String,
) {
    OutlinedButton(
        onClick = { callbacks.editMerged(key) { setCaptionStyle(caption.id, CaptionStyleOverride.NONE) } },
        enabled = !caption.style.isEmpty,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CAPTION_RESET_TAG),
    ) {
        Icon(painterResource(R.drawable.ic_restore), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        AutoSizeLabel(stringResource(R.string.caption_reset))
    }
}

/** Tabs in sight when they scroll: the half one shows there are more. */
private const val SCROLLING_TABS_SHOWN = 3.5f

private fun tabLabel(tab: CaptionTab): Int =
    when (tab) {
        CaptionTab.WORDS -> R.string.caption_tab_words
        CaptionTab.PLACE -> R.string.caption_tab_place
        CaptionTab.SIZE -> R.string.caption_tab_size
        CaptionTab.FONT -> R.string.caption_tab_font
        CaptionTab.COLOUR -> R.string.caption_tab_colour
        CaptionTab.ANIMATION -> R.string.action_animation
    }

private fun tabIcon(tab: CaptionTab): Int =
    when (tab) {
        CaptionTab.WORDS -> R.drawable.ic_text_fields
        CaptionTab.PLACE -> R.drawable.ic_open_with
        CaptionTab.SIZE -> R.drawable.ic_format_size
        CaptionTab.FONT -> R.drawable.ic_font_download
        CaptionTab.COLOUR -> R.drawable.ic_format_color_text
        CaptionTab.ANIMATION -> R.drawable.ic_animation
    }

/** Characters a caption may not hold (see TextRules) are left out as they are typed or pasted. */
internal fun usableCaptionText(typed: String): String {
    val kept = StringBuilder()
    var i = 0
    while (i < typed.length) {
        val cp = typed.codePointAt(i)
        val chars = String(Character.toChars(cp))
        // Whole code points only, so a cut never leaves half an emoji.
        if (kept.length + chars.length > Limits.MAX_TEXT_LENGTH) break
        if (TextRules.isAllowed(chars, allowNewlines = true)) kept.append(chars)
        i += Character.charCount(cp)
    }
    return kept.toString()
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
    // The words this field has sent to the project and not yet seen come back. The project can show an
    // older one while typing is fast (it catches up a moment later); only words the field never sent (an
    // undo or redo while the editor is open) put the field back in step, so no keystroke is ever dropped.
    val pending = remember(caption.id) { ArrayDeque<String>() }
    var sent by rememberSaveable(caption.id.value) { mutableStateOf(caption.text) }
    LaunchedEffect(caption.text) {
        val back = pending.indexOf(caption.text)
        when {
            back >= 0 -> {
                repeat(back + 1) { pending.removeFirst() }
            }

            caption.text != sent -> {
                pending.clear()
                sent = caption.text
                text = TextFieldValue(caption.text, TextRange(caption.text.length))
            }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            val usable = usableCaptionText(typed.text)
            // Left-out characters move the cursor back with them.
            val cut = typed.text.length - usable.length
            val cursor = (typed.selection.end - cut).coerceIn(0, usable.length)
            text = if (cut == 0) typed else TextFieldValue(usable, TextRange(cursor))
            if (usable != sent) {
                sent = usable
                pending.addLast(usable)
                callbacks.editMerged(key) { setText(caption.id, usable) }
            }
        },
        label = { Text(stringResource(R.string.caption_text_hint)) },
        minLines = 2,
        maxLines = 4,
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().testTag(TEXT_FIELD_TAG),
    )
}

/**
 * The place chips ([places], the preset's own place first) and the hint to drag the box. [usual] is the
 * preset's box, [safe] where a caption may go.
 */
@Composable
internal fun PlaceTab(
    style: CaptionStyle,
    usual: NormRect,
    safe: NormRect,
    restyle: (CaptionStyle) -> Unit,
    places: List<CaptionPlace> = CaptionPlace.entries,
    inner: NormRect = safe,
) {
    // A place that gives the same box as an earlier one (Bottom when the preset's own place is at the bottom)
    // is left out, so no two chips do the same thing.
    val places =
        places.distinctBy { place ->
            // Compared as stored (to 1/10000 of the canvas), so a preset box with more digits still matches.
            // At the preset's own size, so the chips shown stay the same whatever the caption's size.
            CaptionPlaces.box(place, usual, safe, usual, inner).let { b -> listOf(b.x, b.y, b.w, b.h).map { Math.round(it * 10000) } }
        }
    val current = CaptionPlaces.placeOf(style.box, usual, safe, places, inner)
    // Across when every name fits its chip; two by two otherwise, so no name is ever cut short.
    val labels = places.map { stringResource(placeLabel(it)) }
    val measurer = rememberTextMeasurer()
    val chipStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    // The width is known before the first frame is drawn, so the chips never jump from two rows to one.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perChip = (maxWidth - 8.dp * (places.size - 1)) / places.size
        val chipRoomPx = with(density) { (perChip - CHIP_LABEL_ROOM).roundToPx() }
        val fitsInOneRow = labels.all { label -> labelFits(measurer, label, chipStyle, chipRoomPx) }
        val columns = if (fitsInOneRow) places.size else 2
        // Two by two a name may still need a smaller size or two lines: then every chip takes two, all as tall.
        val lines = rememberLabelLines(labels, chipStyle, (maxWidth - 8.dp * (columns - 1)) / columns - CHIP_LABEL_ROOM, density)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in places.chunked(columns)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (place in row) {
                        val label = labels[places.indexOf(place)]
                        FilterChip(
                            selected = place == current,
                            onClick = { restyle(style.copy(box = CaptionPlaces.box(place, usual, safe, style.box, inner))) },
                            label = {
                                AutoSizeLabel(
                                    label,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lines = lines,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            modifier = Modifier.weight(1f).testTag(captionPlaceTag(place)),
                        )
                    }
                }
            }
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

/**
 * The size slider, in steps of 4 from [defaults]' size (shown as 100%), and a large sample of the
 * caption ([words], or a sample when blank). [key] is what the tab is editing: the slider's range is
 * worked out again only for another one.
 */
@Composable
internal fun SizeTab(
    key: Any,
    words: String,
    style: CaptionStyle,
    defaults: CaptionStyle,
    restyle: (CaptionStyle) -> Unit,
) {
    val percent = style.sizePx * 100 / defaults.sizePx.coerceAtLeast(1)
    // The range always holds the preset's own size and the size the tab opened with, and stays put while
    // the thumb moves.
    val opened = remember(key) { style.sizePx }
    val low = minOf(CAPTION_MIN_SIZE, defaults.sizePx, opened).toFloat()
    val high = maxOf(CAPTION_MAX_SIZE, defaults.sizePx, opened).toFloat()
    val shown = stringResource(R.string.caption_size_value, percent)
    val label = stringResource(R.string.caption_tab_size)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.caption_size_smaller), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Slider(
            value = style.sizePx.toFloat(),
            onValueChange = { v ->
                // Steps of 4 counted from the preset's size, so its own size (100%) is always one of them.
                val steps = ((v - defaults.sizePx) / CAPTION_SIZE_STEP).roundToInt()
                val px = (defaults.sizePx + steps * CAPTION_SIZE_STEP).coerceIn(CaptionStyle.MIN_SIZE_PX, CaptionStyle.MAX_SIZE_PX)
                if (px != style.sizePx) restyle(style.copy(sizePx = px))
            },
            // No tick marks (24 of them only clutter the track); the size still moves in steps of 4.
            valueRange = low..high,
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
    // The caption as the video will show it, as wide as the panel (larger than in the preview), on the checkerboard.
    val sampleText = words.ifBlank { stringResource(R.string.caption_font_sample) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(SAMPLE_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .checkerboard()
            .testTag(CAPTION_SAMPLE_TAG),
    ) {
        CaptionImage(sampleText, style, contentScale = ContentScale.FillWidth)
    }
}

@Composable
internal fun FontTab(
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
internal fun ColourTab(
    style: CaptionStyle,
    restyle: (CaptionStyle) -> Unit,
) {
    // Six across; three by two where six would be under 48 dp each.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .checkerboard()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        val perRow = if (maxWidth / CAPTION_COLOURS.size >= 48.dp) CAPTION_COLOURS.size else CAPTION_COLOURS.size / 2
        Column {
            for (chunk in CAPTION_COLOURS.withIndex().chunked(perRow)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    for ((i, pair) in chunk) {
                        val (colour, name) = pair
                        val label = stringResource(R.string.caption_colour_choice, stringResource(name))
                        val on = style.color == colour
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
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
                                        BorderStroke(
                                            if (on) 3.dp else 1.dp,
                                            if (on) MaterialTheme.colorScheme.primary else Color(0x99000000),
                                        ),
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
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
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
 * The undo step a drag of caption [id]'s box makes while the caption is only
 * selected (its editor closed): each drag is a step of its own.
 */
fun captionBoxMergeKey(id: ClipId) = "caption-box:${id.value}"

/**
 * The caption's box over the preview: while its editor is open ([editing],
 * part of the visit's undo step), or while it is selected and shows at the
 * playhead (each drag one undo step). A dashed outline the user drags to
 * move the caption, inside the frame window's safe area, snapping to the
 * preset's place, with handles on its edges and corners to change its size
 * (cm-caption-box). The Place tab moves it without dragging.
 */
@Composable
internal fun CaptionBoxOverlay(
    caption: TextClip,
    project: Project,
    window: NormRect,
    callbacks: EditorCallbacks,
    modifier: Modifier = Modifier,
    draggable: Boolean = true,
    editing: Boolean = true,
) {
    val defaults = project.captionDefaults
    val style = caption.style.resolve(defaults)
    val latest by rememberUpdatedState(style)
    val key = if (editing) captionMergeKey(caption.id) else captionBoxMergeKey(caption.id)
    CaptionBoxDrag(
        style = style,
        text = caption.text,
        usual = defaults.box,
        safe = CaptionPlaces.safeArea(window, defaults.box, LocalCanvasShape.current.aspect),
        inner = CaptionPlaces.safeArea(window, null, LocalCanvasShape.current.aspect),
        showSample = caption.text.isBlank(),
        dragKey = caption.id,
        modifier = modifier,
        draggable = draggable,
        // A drag while the caption is only selected is one undo step; in the editor it joins the visit.
        onDragEnd = { if (!editing) callbacks.endMerge() },
    ) { next ->
        callbacks.editMerged(key) {
            setCaptionStyle(caption.id, CaptionStyleOverride.between(defaults, latest.copy(box = next)))
        }
    }
}

/**
 * A caption box drawn over a picture of the canvas, moved and resized: the
 * box of [style], kept inside [safe] (or the frame window's [inner] safe
 * area, see [CaptionPlaces.dragBounds]) and snapping to [usual], each change
 * handed to [onBox] and [onDragEnd] called when a drag (or an accessibility
 * action) ends. A resize keeps the words ([text], or the sample when blank)
 * at their size: the box is never made shorter than they need at its new
 * width ([CaptionPlaces.resized]), measured by the export's own renderer.
 * With [showSample] (no words yet) a faint sample shows the style. The
 * caption editor and the preset's caption style editor both use it.
 */
@Composable
internal fun CaptionBoxDrag(
    style: CaptionStyle,
    usual: NormRect,
    safe: NormRect,
    showSample: Boolean,
    dragKey: Any,
    modifier: Modifier = Modifier,
    inner: NormRect = safe,
    text: String = "",
    draggable: Boolean = true,
    onDragEnd: () -> Unit = {},
    onBox: (NormRect) -> Unit,
) {
    val latest by rememberUpdatedState(style)
    val move by rememberUpdatedState(onBox)
    val end by rememberUpdatedState(onDragEnd)
    var start by remember { mutableStateOf<NormRect?>(null) }
    var held by remember { mutableStateOf<RectGrip?>(null) }
    var bounds by remember { mutableStateOf(safe) }
    // The narrowest the box may go in this drag, worked out once for the box it started from.
    var narrowest by remember { mutableStateOf<Pair<NormRect, Double>?>(null) }
    var snapped by remember { mutableStateOf(false) }
    val description = stringResource(R.string.caption_box_description)
    val wider = stringResource(R.string.caption_box_wider)
    val narrower = stringResource(R.string.caption_box_narrower)
    val sample = stringResource(R.string.caption_font_sample)
    val renderer = rememberCaptionRenderer()
    val shape = LocalCanvasShape.current
    val words = text.ifBlank { sample }
    // The words' height at a width, kept while the words and style stay (a drag asks once per pixel of width).
    val heights = remember(words, style.sizePx, style.font, style.outline, renderer) { HashMap<Long, Double>() }

    fun need(w: Double): Double = heights.getOrPut(Math.round(w * 10_000)) { renderer.boxHeightFor(words, latest, w) }

    fun change(
        from: NormRect,
        grip: RectGrip,
        dx: Double,
        dy: Double,
    ): Pair<NormRect, Boolean> {
        val area = CaptionPlaces.dragBounds(from, grip, inner, safe, usual)
        bounds = area
        if (grip == RectGrip.BODY) return CaptionPlaces.dragged(from, dx, dy, area, usual)
        val minW =
            narrowest?.takeIf { it.first == from }?.second ?: run {
                // Never so narrow that the words, drawn as small as a caption gets, no longer fit (on the strip).
                val smallest = latest.copy(sizePx = ceil(latest.sizePx * CaptionBitmapRenderer.MIN_FIT).toInt())
                val m =
                    CaptionPlaces.narrowestFitting(CaptionPlaces.minWidth(latest.sizePx, shape), from.w, area.h) { w ->
                        renderer.boxHeightFor(words, smallest, w)
                    }
                narrowest = from to m
                m
            }
        return CaptionPlaces.resized(from, grip, dx, dy, area, usual, minW) { w -> need(w) }
    }

    /** Wider or narrower by [step] of the canvas, both sides alike: for TalkBack, which cannot drag. */
    fun widen(step: Double): Boolean {
        val one = change(latest.box, RectGrip.RIGHT, step / 2, 0.0).first
        val both = change(one, RectGrip.LEFT, -step / 2, 0.0).first
        if (both != latest.box) move(both)
        end()
        return true
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val box = style.box
        if (start != null) {
            // While dragging: where the box may go, and the preset's place when the box sits on it.
            Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                val area = bounds
                drawRect(
                    Color.White.copy(alpha = 0.5f),
                    Offset((area.x * size.width).toFloat(), (area.y * size.height).toFloat()),
                    Size((area.w * size.width).toFloat(), (area.h * size.height).toFloat()),
                    style = Stroke(1.dp.toPx(), pathEffect = dash),
                )
                if (snapped) {
                    val u = usual
                    drawRect(
                        Color.White,
                        Offset((u.x * size.width).toFloat(), (u.y * size.height).toFloat()),
                        Size((u.w * size.width).toFloat(), (u.h * size.height).toFloat()),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
        }
        // A caption with no words yet shows a faint sample, so its place, size, font and colour show at once.
        if (showSample) {
            Box(
                Modifier
                    .offset(w * box.x.toFloat(), h * box.y.toFloat())
                    .size(w * box.w.toFloat(), h * box.h.toFloat())
                    .alpha(BLANK_SAMPLE_ALPHA),
            ) { CaptionImage(sample, style) }
        }
        // The box as TalkBack and tests find it (at least 48 dp tall); the touches go to the handles below.
        RectAnchor(
            box,
            w,
            h,
            if (draggable) {
                Modifier
                    .semantics {
                        contentDescription = description
                        customActions =
                            listOf(
                                CustomAccessibilityAction(wider) { widen(A11Y_STEP) },
                                CustomAccessibilityAction(narrower) { widen(-A11Y_STEP) },
                            )
                    }.testTag(CAPTION_BOX_TAG)
            } else {
                Modifier
            },
        )
        ResizableRect(
            rect = box,
            enabled = draggable,
            dashed = true,
            gestureKey = dragKey,
            vertical = CaptionPlaces.dragBounds(box, RectGrip.BOTTOM, inner, safe, usual).h > box.h + 1e-4,
            onDragStart = {
                start = latest.box
                held = it
                snapped = false
            },
            onDragEnd = {
                // Moved somewhere with more room (off the strip into the picture): the box holds its words again.
                if (held == RectGrip.BODY && start != null) {
                    val b = latest.box
                    val grown = CaptionPlaces.grown(b, need(b.w), CaptionPlaces.dragBounds(b, RectGrip.BOTTOM, inner, safe, usual))
                    if (grown != b) move(grown)
                }
                start = null
                held = null
                snapped = false
                end()
            },
        ) { grip, dx, dy ->
            val from = start ?: return@ResizableRect
            val (next, snap) = change(from, grip, dx, dy)
            snapped = snap
            if (next != latest.box) move(next)
        }
    }
}

/** How much wider or narrower one TalkBack action makes the box, in units of the canvas width. */
private const val A11Y_STEP = 0.1
