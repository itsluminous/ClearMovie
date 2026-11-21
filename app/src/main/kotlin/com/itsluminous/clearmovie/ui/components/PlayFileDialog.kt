package com.itsluminous.clearmovie.ui.components

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.ui.theme.LocalMediaColors
import kotlinx.coroutines.delay

/**
 * Plays one file in a dialog: a preset part on the review screen or in the
 * editor. It starts playing at once; Play/Pause and Close are large
 * labelled buttons. [audioOnly] shows a sound icon instead of a picture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayFileDialog(
    uri: Uri,
    title: String,
    audioOnly: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // Where it was, so a rotation goes on from there instead of starting again.
    var savedMs by rememberSaveable(uri) { mutableLongStateOf(0L) }
    var savedPlaying by rememberSaveable(uri) { mutableStateOf(true) }
    val player =
        remember(uri) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri), savedMs)
            }
        }
    var playing by remember(player) { mutableStateOf(savedPlaying) }
    // The picture's own shape (cm-aspect-ratio: an upright or square video too), so it is never stretched.
    var videoAspect by remember(player) { mutableFloatStateOf(16f / 9f) }
    // Kept up to date while it plays: the saved state is written before the dialog goes away.
    LaunchedEffect(player) {
        while (true) {
            savedMs = player.currentPosition
            savedPlaying = player.playWhenReady
            delay(SAVE_MS)
        }
    }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }
            }
        player.addListener(listener)
        player.prepare()
        player.playWhenReady = savedPlaying
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val media = LocalMediaColors.current
    BasicAlertDialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 720.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                // A landscape picture fills the width; an upright or square one sits in a square, and never
                // takes more than about half the screen's height (a phone held sideways).
                val maxHeight = (LocalConfiguration.current.screenHeightDp * MAX_HEIGHT_SHARE).dp
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .aspectRatio(maxOf(1f, videoAspect))
                        .background(media.previewBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    if (audioOnly) {
                        Icon(
                            painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = media.onPreviewBackground,
                            modifier = Modifier.size(64.dp),
                        )
                    } else {
                        AndroidView(
                            factory = { TextureView(it).also { v -> player.setVideoTextureView(v) } },
                            onRelease = { player.clearVideoTextureView(it) },
                            modifier = Modifier.aspectRatio(videoAspect),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.playWhenReady = !player.playWhenReady
                            playing = player.playWhenReady
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) {
                        Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = null)
                        Text(stringResource(if (playing) R.string.action_pause else R.string.action_play), Modifier.padding(start = 8.dp))
                    }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }
}

private const val SAVE_MS = 300L

/** The picture takes at most this share of the screen's height. */
private const val MAX_HEIGHT_SHARE = 0.5f
