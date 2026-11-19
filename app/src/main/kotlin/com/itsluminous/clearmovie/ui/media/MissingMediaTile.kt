package com.itsluminous.clearmovie.ui.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.media.MediaStatus

/**
 * The placeholder for a clip whose photo or video cannot be opened
 * (`MediaAccess.check`): what happened, in plain words, and Replace. The
 * timeline (cm-timeline) draws it in place of the clip's thumbnail; export
 * waits until it is replaced or removed.
 */
@Composable
fun MissingMediaTile(
    status: MediaStatus,
    onReplace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status == MediaStatus.AVAILABLE) return
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painter = painterResource(R.drawable.ic_broken_image), contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.media_missing_label), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(if (status == MediaStatus.NO_ACCESS) R.string.media_no_access_body else R.string.media_missing_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            // The error colours, so the button stands out on the error card in light and dark (a tonal
            // button's own colours were dark on dark red in the dark theme).
            Button(
                onClick = onReplace,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_replace))
            }
        }
    }
}
