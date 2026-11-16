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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
 * Home and preset pick (ARCHITECTURE section 11.1): "Make a new video", a
 * large card per preset (with "N files missing, tap to fix" when a file is
 * gone), the Start without a preset card, then "My videos". One column on a
 * phone held upright, two or three on wider windows. Stateless.
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
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.action_settings),
                        )
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
            Column(Modifier.fillMaxSize()) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = rememberLazyGridState(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().testTag(HOME_LIST_TAG),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Heading(stringResource(R.string.home_new_video)) }
                    items(presets.orEmpty(), key = { "preset-" + it.preset.id.value }) { card ->
                        PresetCardView(card, enabled = !busy, onClick = { onPreset(card.preset.id) })
                    }
                    item(key = "blank") { BlankCard(enabled = !busy, onClick = onStartBlank) }
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
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(card.thumbnail?.let { { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } })
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(presetName(card.preset), style = MaterialTheme.typography.titleLarge)
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
    }
}

@Composable
private fun BlankCard(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(null, icon = R.drawable.ic_photo_library)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_start_blank), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.home_start_blank_body),
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

/** A 16:9 picture well: the preset's frame, or an icon. */
@Composable
private fun Thumb(
    picture: (@Composable () -> Unit)?,
    icon: Int = R.drawable.ic_movie,
) {
    val media = LocalMediaColors.current
    Box(
        Modifier
            .width(112.dp)
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
