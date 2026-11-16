package com.itsluminous.clearmovie.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.media.MediaStatus
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MainSlot
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.ui.components.durationText
import com.itsluminous.clearmovie.ui.layout.AdaptiveScreen
import com.itsluminous.clearmovie.ui.theme.LocalMediaColors

/** Test tags. */
const val EDITOR_TIMELINE_TAG = "editor_timeline"
const val EDITOR_EXPORT_TAG = "editor_export"

/** The playhead and play state the controls show. */
data class PlayheadUi(
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val playing: Boolean = false,
)

/**
 * The editor shell (requirement 4, ARCHITECTURE section 11.1): the top bar
 * with Back and the always-visible Export (top right), the preview with
 * Play/Pause, the time, a position slider and Play exactly as exported, and
 * the read-only MAIN row. On a phone held upright the preview is on top; in
 * two panes (medium or expanded width, or a phone held sideways) the
 * preview is on the left at full height and the bar, banners and row on the
 * right. The full timeline arrives with cm-timeline. Stateless: [preview]
 * draws the picture.
 */
@Composable
fun EditorScreen(
    ui: EditorUi,
    title: String,
    playhead: PlayheadUi,
    preview: @Composable (Modifier) -> Unit,
    thumbnail: suspend (MainSlot) -> Bitmap?,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onExact: () -> Unit,
    onAddMedia: () -> Unit,
    onFixPreset: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        AdaptiveScreen(Modifier.fillMaxSize().padding(padding)) { window ->
            val canPlay = ui.schedule?.main?.isNotEmpty() == true
            if (window.twoPane) {
                Row(Modifier.fillMaxSize()) {
                    // The picture may run under the status bar on the left; no control does.
                    Column(
                        Modifier
                            .weight(1.4f)
                            .fillMaxHeight()
                            .background(LocalMediaColors.current.previewBackground)
                            .windowInsetsPadding(WindowInsets.safeDrawing.pane(left = true)),
                    ) {
                        // As large as the pane allows, at 16:9: full width, or full height when that is the limit.
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            preview(Modifier.aspectRatio(16f / 9f))
                        }
                        Surface(Modifier.fillMaxWidth()) { Controls(playhead, canPlay, onTogglePlay, onSeek, onExact) }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing.pane(left = false)),
                    ) {
                        TopBar(title, onBack, onExport)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Banners(ui, onFixPreset)
                            Timeline(ui, playhead.positionUs, thumbnail, onSeek, onAddMedia)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TopBar(title, onBack, onExport)
                    Box(Modifier.fillMaxWidth().background(LocalMediaColors.current.previewBackground)) {
                        preview(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    }
                    Controls(playhead, canPlay, onTogglePlay, onSeek, onExact)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Banners(ui, onFixPreset)
                        Timeline(ui, playhead.positionUs, thumbnail, onSeek, onAddMedia)
                    }
                }
            }
        }
    }
}

/** Insets for one of the two panes: the left pane takes the start edge, the right pane the end edge; both take the top and bottom. */
private fun WindowInsets.pane(left: Boolean): WindowInsets =
    only(if (left) WindowInsetsSides.Start + WindowInsetsSides.Vertical else WindowInsetsSides.End + WindowInsetsSides.Vertical)

@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Button(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp).padding(end = 8.dp).testTag(EDITOR_EXPORT_TAG)) {
            Icon(painterResource(R.drawable.ic_export), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editor_export))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    playhead: PlayheadUi,
    enabled: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onExact: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val duration = playhead.durationUs.coerceAtLeast(1)
        // While the thumb is dragged it follows the finger, and the preview moves only on release:
        // seeking every player on every frame of a drag makes the thumb jump back under a slow hand.
        var dragging by remember { mutableStateOf<Float?>(null) }
        val shownUs = dragging?.let { (it * duration).toLong() } ?: playhead.positionUs
        val position = durationText(shownUs)
        val total = durationText(playhead.durationUs)
        val seekLabel = stringResource(R.string.editor_seek)
        val timeLabel = stringResource(R.string.editor_time, position, total)
        Slider(
            value = dragging ?: (playhead.positionUs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            enabled = enabled,
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription = seekLabel
                    stateDescription = timeLabel
                },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onTogglePlay, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(painterResource(if (playhead.playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (playhead.playing) R.string.action_pause else R.string.action_play))
            }
            Text(timeLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
            OutlinedButton(onClick = onExact, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(painterResource(R.drawable.ic_visibility), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.editor_exact))
            }
        }
    }
}

@Composable
private fun Banners(
    ui: EditorUi,
    onFixPreset: () -> Unit,
) {
    if (!ui.presetNeedsRepair) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.preset_repair_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.editor_preset_repair_body), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onFixPreset, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.editor_fix_preset))
            }
        }
    }
}

/** The read-only MAIN row: a tile per item in play order; tapping one moves the playhead there. */
@Composable
private fun Timeline(
    ui: EditorUi,
    positionUs: Long,
    thumbnail: suspend (MainSlot) -> Bitmap?,
    onSeek: (Long) -> Unit,
    onAddMedia: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(EDITOR_TIMELINE_TAG)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_photo_library),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.editor_row_main),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(8.dp))
        val slots = ui.slots
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            itemsIndexed(slots, key = { _, s -> s.clip.id.value }) { _, slot ->
                val current = positionUs / 1000 in slot.startMs until slot.endMs
                Tile(slot, current, ui.missing[slot.clip.id], thumbnail, onClick = { onSeek(slot.startMs * 1000) })
            }
            if (ui.hasContent) item(key = "add") { AddTile(onAddMedia) }
        }
        if (!ui.hasContent) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.editor_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAddMedia, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp)) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.editor_add_media))
                }
            }
        }
    }
}

@Composable
private fun Tile(
    slot: MainSlot,
    current: Boolean,
    status: MediaStatus?,
    thumbnail: suspend (MainSlot) -> Bitmap?,
    onClick: () -> Unit,
) {
    val media = LocalMediaColors.current
    val clip = slot.clip
    val (labelRes, icon) =
        when (clip) {
            is IntroClip -> {
                R.string.preset_slot_intro to R.drawable.ic_videocam
            }

            is EndingClip -> {
                R.string.preset_slot_ending to
                    if (clip.kind == EndingKind.AUDIO) R.drawable.ic_music_note else R.drawable.ic_videocam
            }

            is PhotoClip -> {
                R.string.editor_item_photo to R.drawable.ic_image
            }

            is VideoClip -> {
                R.string.editor_item_video to R.drawable.ic_videocam
            }

            is CardClip -> {
                R.string.editor_item_card to R.drawable.ic_image
            }
        }
    val label = stringResource(labelRes)
    val length = durationText(slot.durationMs * 1000)
    val missing = status != null && status != MediaStatus.AVAILABLE
    val description =
        stringResource(if (missing) R.string.editor_item_missing_description else R.string.editor_item_description, label, length)
    val picture by produceState<Bitmap?>(null, clip, missing) { value = if (missing) null else thumbnail(slot) }
    val nowPlaying = stringResource(R.string.editor_item_current)
    Column(
        Modifier
            .width(TILE_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (current) stateDescription = nowPlaying
            }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(if (missing) MaterialTheme.colorScheme.errorContainer else media.visualClip)
                .then(
                    if (current) {
                        Modifier.border(
                            BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                            RoundedCornerShape(6.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = picture
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    painterResource(if (missing) R.drawable.ic_broken_image else icon),
                    contentDescription = null,
                    tint = if (missing) MaterialTheme.colorScheme.onErrorContainer else media.onVisualClip,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text(
            if (missing) stringResource(R.string.media_missing_label) else label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
        Text(length, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val label = stringResource(R.string.editor_add_media)
    Column(
        Modifier
            .width(TILE_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 3, textAlign = TextAlign.Center)
    }
}

private val TILE_WIDTH = 112.dp
