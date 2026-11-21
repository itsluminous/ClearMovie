package com.itsluminous.clearmovie.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.project.ProjectSummary
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.ui.layout.AdaptiveScreen
import com.itsluminous.clearmovie.ui.preset.presetName
import com.itsluminous.clearmovie.ui.theme.LocalMediaColors

/** Test tag of the home screen's scrolling grid. */
const val HOME_LIST_TAG = "home_list"

private val MIN_CARD_WIDTH = 340.dp

/**
 * What the share variant of the preset pick says at the top (cm-share):
 * "Getting your photos and videos ready" until [ready], then "N photos and
 * videos are ready", and a line for each kind of item that was left out.
 */
data class ShareHeader(
    val ready: Boolean,
    val items: Int,
    val notMedia: Int = 0,
    val unreadable: Int = 0,
    val overLimit: Int = 0,
    val limit: Int = 0,
)

/** Test tag of the share header. */
const val SHARE_HEADER_TAG = "share_header"

/**
 * Home and preset pick (ARCHITECTURE section 11.1): "Make a new video", then
 * a large card per preset (with "N files missing, tap to fix" when a file is
 * gone), Start without a preset and New preset; with no presets yet, New
 * preset comes first (cm-home-presets). Then "My videos". Every card under
 * "Make a new video" is one height (that of the tallest), so none stands out.
 * One column on a phone held upright, two or three on wider windows.
 * Stateless.
 *
 * With [share] it is the share variant (section 11.12): the shared items'
 * header above the presets, a Cancel button instead of Settings, no New
 * preset and no "My videos"; the cards wait until the items are ready. With
 * no presets it offers Start without a preset alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    presets: List<PresetCard>?,
    videos: List<ProjectSummary>,
    busy: Boolean,
    onPreset: (PresetId) -> Unit,
    onStartBlank: () -> Unit,
    onVideo: (ProjectId) -> Unit,
    onOpenSettings: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    share: ShareHeader? = null,
    onCancelShare: () -> Unit = {},
    onEditPreset: ((PresetId) -> Unit)? = null,
    onNewPreset: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    if (share != null) {
                        IconButton(onClick = onCancelShare) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
                        }
                    }
                },
                actions = {
                    if (share == null) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.action_settings),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        AdaptiveScreen(Modifier.fillMaxSize().padding(padding)) { window ->
            // Wide windows get more columns, but never so narrow that a preset's name breaks.
            val columns = (maxWidth / MIN_CARD_WIDTH).toInt().coerceIn(1, window.columns)
            val enabled = !busy && share?.ready != false
            val newPreset = onNewPreset?.takeIf { share == null }
            val edit = onEditPreset?.takeIf { share == null }
            val noPresets = presets?.isEmpty() == true
            // Every card shown under the heading, in the order shown: with no presets, New preset first.
            val cards =
                buildList {
                    presets.orEmpty().forEach {
                        add(CardWords(presetName(it.preset), presetLine(it.state), edit != null, it.state is PresetCard.State.Missing))
                    }
                    val blank = CardWords(stringResource(R.string.home_start_blank), stringResource(R.string.home_start_blank_body))
                    val new = newPreset?.let { CardWords(stringResource(R.string.preset_new), stringResource(R.string.preset_new_body)) }
                    if (noPresets && new != null) add(new)
                    add(blank)
                    if (!noPresets && new != null) add(new)
                }
            val cardWidth = (maxWidth - 32.dp - 16.dp * (columns - 1)) / columns
            val layout = rememberCardLayout(cards, cardWidth)
            Column(Modifier.fillMaxSize()) {
                // Busy, the shared items not ready, or the presets still being read.
                if (busy || share?.ready == false || presets == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = rememberLazyGridState(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().testTag(HOME_LIST_TAG),
                ) {
                    if (share != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "share") { ShareHeaderView(share) }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        // Choosing a preset for shared items, unless there is none to choose.
                        Heading(stringResource(if (share != null && !noPresets) R.string.share_choose_preset else R.string.home_new_video))
                    }
                    // The cards wait for the presets (the moving bar says they are being read), as their order depends on them.
                    if (presets != null) {
                        val newPresetCard: () -> Unit = {
                            if (newPreset != null) {
                                item(key = "new-preset") {
                                    ActionCard(
                                        title = stringResource(R.string.preset_new),
                                        body = stringResource(R.string.preset_new_body),
                                        icon = R.drawable.ic_add,
                                        enabled = !busy,
                                        layout = layout,
                                        tag = NEW_PRESET_TAG,
                                        onClick = newPreset,
                                    )
                                }
                            }
                        }
                        if (noPresets) newPresetCard()
                        items(presets, key = { "preset-" + it.preset.id.value }) { card ->
                            // Editing is offered from the launcher's pick only, never while choosing a preset for shared items.
                            PresetCardView(
                                card,
                                enabled = enabled,
                                layout = layout,
                                onClick = { onPreset(card.preset.id) },
                                onEdit = edit?.let { { it(card.preset.id) } },
                            )
                        }
                        item(key = "blank") { BlankCard(enabled = enabled, layout = layout, onClick = onStartBlank) }
                        if (!noPresets) newPresetCard()
                    }
                    if (share != null) return@LazyVerticalGrid
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Heading(stringResource(R.string.home_my_videos), Modifier.padding(top = 8.dp))
                    }
                    if (videos.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(R.string.home_empty_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(videos, key = { "video-" + it.id.value }) { video ->
                        VideoCard(video, enabled = !busy, onClick = { onVideo(video.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareHeaderView(header: ShareHeader) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(SHARE_HEADER_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_photo_library),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (header.ready) {
                        pluralStringResource(R.plurals.share_ready, header.items, header.items)
                    } else {
                        stringResource(R.string.share_getting_ready)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { heading() },
                )
            }
            val note = MaterialTheme.typography.bodyLarge
            val color = MaterialTheme.colorScheme.onPrimaryContainer
            if (header.notMedia > 0) {
                Text(pluralStringResource(R.plurals.share_skipped_not_media, header.notMedia, header.notMedia), style = note, color = color)
            }
            if (header.unreadable > 0) {
                Text(
                    pluralStringResource(R.plurals.share_skipped_unreadable, header.unreadable, header.unreadable),
                    style = note,
                    color = color,
                )
            }
            if (header.overLimit > 0) {
                Text(
                    pluralStringResource(R.plurals.share_skipped_over_limit, header.overLimit, header.overLimit, header.limit),
                    style = note,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun Heading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun PresetCardView(
    card: PresetCard,
    enabled: Boolean,
    layout: CardLayout,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        val name = presetName(card.preset)
        CardBody(layout, {
            Thumb(card.thumbnail?.let { { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } })
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PresetWords(card, name, Modifier.weight(1f))
                if (onEdit != null) {
                    IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag(presetEditTag(card.preset.id))) {
                        Icon(
                            painterResource(R.drawable.ic_edit),
                            contentDescription = stringResource(R.string.preset_edit_for, name),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetWords(
    card: PresetCard,
    name: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(WORDS_GAP))
        val line = presetLine(card.state)
        val warn = card.state is PresetCard.State.Missing
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (warn) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(WARNING_ICON),
                )
                Spacer(Modifier.width(WARNING_GAP))
            }
            Text(
                line,
                style = MaterialTheme.typography.bodyLarge,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** The line under a preset's name: ready, needs setup, or how many files are missing. */
@Composable
private fun presetLine(state: PresetCard.State): String =
    when (state) {
        PresetCard.State.Ready -> stringResource(R.string.home_preset_ready)
        PresetCard.State.NeedsSetup -> stringResource(R.string.home_preset_needs_setup)
        is PresetCard.State.Missing -> pluralStringResource(R.plurals.home_preset_missing, state.count, state.count)
    }

@Composable
private fun BlankCard(
    enabled: Boolean,
    layout: CardLayout,
    onClick: () -> Unit,
) {
    ActionCard(
        title = stringResource(R.string.home_start_blank),
        body = stringResource(R.string.home_start_blank_body),
        icon = R.drawable.ic_photo_library,
        enabled = enabled,
        layout = layout,
        tag = BLANK_TAG,
        onClick = onClick,
    )
}

/** A card with an icon instead of a picture: Start without a preset, New preset. */
@Composable
private fun ActionCard(
    title: String,
    body: String,
    icon: Int,
    enabled: Boolean,
    layout: CardLayout,
    tag: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        CardBody(layout, { Thumb(null, icon = icon) }) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(WORDS_GAP))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VideoCard(
    video: ProjectSummary,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val date = remember(video.updatedAt) { DateUtils.formatDateTime(context, video.updatedAt, DateUtils.FORMAT_SHOW_DATE) }
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_movie),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(video.name ?: stringResource(R.string.home_video_draft, date), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.home_video_edited, date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The words of one card under "Make a new video", for working out their one height. */
private data class CardWords(
    val title: String,
    val body: String,
    /** The card has an Edit button beside its words. */
    val trailing: Boolean = false,
    /** A warning sign leads the body line. */
    val warn: Boolean = false,
)

/**
 * How the cards under "Make a new video" are laid out, all alike: the
 * picture beside the words when every card's title fits beside it on one
 * line, the picture above the words otherwise (a narrow phone, large text),
 * so a name never breaks into a column of single words; and one [height],
 * the tallest card's, for all of them.
 */
private data class CardLayout(
    val stacked: Boolean,
    val height: Dp,
)

@Composable
private fun rememberCardLayout(
    cards: List<CardWords>,
    cardWidth: Dp,
): CardLayout {
    val measurer = rememberTextMeasurer()
    val title = MaterialTheme.typography.titleLarge
    val body = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    return remember(cards, cardWidth, title, body, density) {
        with(density) {
            val inner = cardWidth - CARD_PADDING * 2
            val thumbHeight = THUMB_WIDTH * 9f / 16f

            fun Dp.px() = roundToPx().coerceAtLeast(1)
            val stacked =
                cards.any { c ->
                    val titleWidth =
                        measurer
                            .measure(c.title, title)
                            .size.width
                            .toDp()
                    THUMB_WIDTH + PICTURE_GAP + titleWidth + (if (c.trailing) EDIT_BUTTON else 0.dp) > inner
                }
            val height =
                cards.maxOfOrNull { c ->
                    val words = (if (stacked) inner else inner - THUMB_WIDTH - PICTURE_GAP) - (if (c.trailing) EDIT_BUTTON else 0.dp)
                    val limit = Constraints(maxWidth = words.px())
                    val titleHeight =
                        measurer
                            .measure(c.title, title, constraints = limit)
                            .size.height
                            .toDp()
                    val bodyLimit = Constraints(maxWidth = (words - if (c.warn) WARNING_ICON + WARNING_GAP else 0.dp).px())
                    val bodyHeight =
                        measurer
                            .measure(c.body, body, constraints = bodyLimit)
                            .size.height
                            .toDp()
                    val wordsHeight = titleHeight + WORDS_GAP + maxOf(bodyHeight, WARNING_ICON)
                    CARD_PADDING * 2 + if (stacked) thumbHeight + STACK_GAP + wordsHeight else maxOf(thumbHeight, wordsHeight)
                } ?: 0.dp
            CardLayout(stacked, height)
        }
    }
}

/** A card's picture and its words, as [layout] says; centred in the card's height. */
@Composable
private fun CardBody(
    layout: CardLayout,
    picture: @Composable () -> Unit,
    words: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = layout.height).padding(CARD_PADDING), contentAlignment = Alignment.CenterStart) {
        if (!layout.stacked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                picture()
                Spacer(Modifier.width(PICTURE_GAP))
                words()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
                picture()
                words()
            }
        }
    }
}

private val CARD_PADDING = 16.dp
private val PICTURE_GAP = 16.dp
private val STACK_GAP = 16.dp
private val WORDS_GAP = 4.dp
private val WARNING_ICON = 24.dp
private val WARNING_GAP = 8.dp

private val THUMB_WIDTH = 112.dp

/** The room a preset card's Edit button takes beside its name. */
private val EDIT_BUTTON = 48.dp

/** Test tag of the New preset card. */
const val NEW_PRESET_TAG = "new_preset"

/** Test tag of a preset card's Edit button. */
fun presetEditTag(id: PresetId) = "preset_edit_${id.value}"

/** Test tag of the Start without a preset card. */
const val BLANK_TAG = "start_blank"

/** A 16:9 picture well: the preset's frame, or an icon. */
@Composable
private fun Thumb(
    picture: (@Composable () -> Unit)?,
    icon: Int = R.drawable.ic_movie,
) {
    val media = LocalMediaColors.current
    Box(
        Modifier
            .width(THUMB_WIDTH)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(media.previewBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            picture()
        } else {
            Icon(painterResource(icon), contentDescription = null, tint = media.onPreviewBackground, modifier = Modifier.size(36.dp))
        }
    }
}
