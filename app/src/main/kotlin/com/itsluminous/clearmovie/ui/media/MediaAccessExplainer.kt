package com.itsluminous.clearmovie.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.media.MediaAccessState

/**
 * The plain-language screen shown once before Android's own photo and video
 * request (ARCHITECTURE section 9), and again after a refusal. What it says
 * follows [state]: before asking, it explains and offers Continue; after a
 * refusal, Try again; when Android will not ask again, Open Settings.
 * [MediaAccessState.Full] and [MediaAccessState.Partial] show nothing here
 * (partial access has [PartialAccessBanner]).
 */
@Composable
fun MediaAccessExplainer(
    state: MediaAccessState,
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == MediaAccessState.Full || state == MediaAccessState.Partial) return
    val asked = state != MediaAccessState.NotAsked
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_photo_library),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(if (asked) R.string.media_access_denied_title else R.string.media_access_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text =
                        stringResource(
                            when (state) {
                                MediaAccessState.DeniedForGood -> R.string.media_access_blocked_body
                                MediaAccessState.Denied -> R.string.media_access_denied_body
                                else -> R.string.media_access_body
                            },
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.media_access_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                val primaryLabel =
                    when (state) {
                        MediaAccessState.DeniedForGood -> R.string.media_access_open_settings
                        MediaAccessState.Denied -> R.string.media_access_try_again
                        else -> R.string.media_access_continue
                    }
                Button(
                    onClick = if (state == MediaAccessState.DeniedForGood) onOpenSettings else onContinue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(primaryLabel), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(
                        stringResource(R.string.media_access_not_now),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Shown under partial access ("Select photos" on Android 14+), with the way to full access. */
@Composable
fun PartialAccessBanner(
    onAllowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp),
                )
                Text(
                    text = stringResource(R.string.media_access_partial),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAllowAll, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.media_access_allow_all))
            }
        }
    }
}
