package com.itsluminous.clearmovie.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
 * Home and preset pick (ARCHITECTURE section 11.1): "Make a new video", a
 * large card per preset (with "N files missing, tap to fix" when a file is
 * gone), the Start without a preset card, then "My videos". One column on a
 * phone held upright, two or three on wider windows. Stateless.
 *
 * With [share] it is the share variant (section 11.12): the shared items'
 * header above the presets, a Cancel button instead of Settings, and no
 * "My videos"; the cards wait until the items are ready.
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
            // Cards side by side are given one height (a grid row cannot match them), enough for a three-line text.
            val cardHeight = if (columns > 1) SIDE_BY_SIDE_CARD else 96.dp
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
                        Heading(stringResource(if (share != null) R.string.share_choose_preset else R.string.home_new_video))
                    }
                    items(presets.orEmpty(), key = { "preset-" + it.preset.id.value }) { card ->
                        // Editing is offered from the launcher's pick only, never while choosing a preset for shared items.
                        val edit = onEditPreset?.takeIf { share == null }
                        PresetCardView(
                            card,
                            enabled = enabled,
                            minHeight = cardHeight,
                            onClick = { onPreset(card.preset.id) },
                            onEdit = edit?.let { { it(card.preset.id) } },
                        )
                    }
                    // Never waits for the presets: Start without a preset is there at once (the moving bar says
                    // the presets are still being read).
                    item(key = "blank") { BlankCard(enabled = enabled, minHeight = cardHeight, onClick = onStartBlank) }
                    if (share != null) return@LazyVerticalGrid
                    if (onNewPreset != null) {
                        item(key = "new-preset") {
                            ActionCard(
                                title = stringResource(R.string.preset_new),
                                body = stringResource(R.string.preset_new_body),
                                icon = R.drawable.ic_add,
                                enabled = !busy,
                                minHeight = cardHeight,
                                tag = NEW_PRESET_TAG,
                                onClick = onNewPreset,
                            )
                        }
                    }
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
    minHeight: Dp,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        val name = presetName(card.preset)
        CardBody(name, {
            Thumb(card.thumbnail?.let { { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } })
        }, trailing = if (onEdit != null) EDIT_BUTTON else 0.dp) {
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
        Spacer(Modifier.height(4.dp))
        val (line, warn) =
            when (val s = card.state) {
                PresetCard.State.Ready -> stringResource(R.string.home_preset_ready) to false
                PresetCard.State.NeedsSetup -> stringResource(R.string.home_preset_needs_setup) to false
                is PresetCard.State.Missing -> pluralStringResource(R.plurals.home_preset_missing, s.count, s.count) to true
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (warn) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                line,
                style = MaterialTheme.typography.bodyLarge,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun BlankCard(
    enabled: Boolean,
    minHeight: Dp,
    onClick: () -> Unit,
) {
    ActionCard(
        title = stringResource(R.string.home_start_blank),
        body = stringResource(R.string.home_start_blank_body),
        icon = R.drawable.ic_photo_library,
        enabled = enabled,
        minHeight = minHeight,
        tag = null,
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
    minHeight: Dp,
    tag: String?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        CardBody(title, { Thumb(null, icon = icon) }) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
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

/**
 * A card's picture and words: side by side when the card's [title] fits
 * beside the picture on one line, the picture above the words otherwise
 * (a narrow phone, large text), so a name never breaks into a column of
 * single words next to it.
 */
@Composable
private fun CardBody(
    title: String,
    picture: @Composable () -> Unit,
    trailing: Dp = 0.dp,
    words: @Composable () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleLarge
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
        val titleDp =
            with(density) {
                measurer
                    .measure(title, style)
                    .size.width
                    .toDp()
            }
        if (THUMB_WIDTH + 16.dp + titleDp + trailing <= maxWidth) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                picture()
                Spacer(Modifier.width(16.dp))
                words()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                picture()
                words()
            }
        }
    }
}

private val THUMB_WIDTH = 112.dp

/** The room a preset card's Edit button takes beside its name. */
private val EDIT_BUTTON = 48.dp

/** Test tag of the New preset card. */
const val NEW_PRESET_TAG = "new_preset"

/** Test tag of a preset card's Edit button. */
fun presetEditTag(id: PresetId) = "preset_edit_${id.value}"

/** The height of preset cards shown two or three to a row. */
private val SIDE_BY_SIDE_CARD = 168.dp

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
