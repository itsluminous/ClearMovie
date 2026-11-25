package com.itsluminous.clearmovie.ui.preset

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.ui.editor.VoiceChoiceField

const val PRESET_VOICE_TAG = "preset_voice"
const val PRESET_VOICE_CHOICE_TAG = "preset_default_voice"

/**
 * The preset's default voice (cm-preset-voice-default): the voice a new
 * voice recording in its videos starts in, chosen from the Record voice
 * sheet's own list. Where the voice pack cannot run on this phone the
 * choice is off and shows No change, which is what recordings get here;
 * the preset keeps the voice it has.
 */
@Composable
internal fun VoiceDefaultCard(
    voice: VoiceChoice,
    available: Boolean,
    onVoice: (VoiceChoice) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.testTag(PRESET_VOICE_TAG),
    ) {
        // 24dp under the field, as under the last row of the Animation and transition card.
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp).fillMaxWidth()) {
            Text(stringResource(R.string.preset_voice_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.preset_voice_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            VoiceChoiceField(
                label = stringResource(R.string.voice_in_video),
                voice = if (available) voice else VoiceChoice.NONE,
                available = available,
                tag = PRESET_VOICE_CHOICE_TAG,
                onVoice = onVoice,
            )
        }
    }
}
