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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.ui.theme.LocalMediaColors

/**
 * Plays one file in a dialog: a preset part on the review screen, or the
 * exact draft. It starts playing at once; Play/Pause and Close are large
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
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)) } }
    var playing by remember(player) { mutableStateOf(true) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
                }
            }
        player.addListener(listener)
        player.prepare()
        player.playWhenReady = true
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
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(media.previewBackground),
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
                            modifier = Modifier.fillMaxSize(),
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
