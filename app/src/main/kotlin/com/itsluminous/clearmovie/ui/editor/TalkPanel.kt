package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.Track
import com.itsluminous.clearmovie.domain.model.setTalkLowering
import kotlin.math.roundToInt

const val TALK_SWITCH_TAG = "talk_lower_switch"
const val TALK_SLIDER_TAG = "talk_lower_slider"
const val TALK_VALUE_TAG = "talk_lower_value"

/** The level the slider shows, as a share of the music's own volume (100% is not lowered). */
const val MAX_TALK_PERCENT = 100f

/**
 * Lower for talk on a music row (cm-timeline-handles), laid out like the
 * Volume popup: Cancel and Done, a switch for whether the music is lowered
 * while people talk, the level it goes down to (0% silent to 100%, in 5%
 * steps) and a slider. Until the slider is moved the row keeps the project's
 * own levels (the slider shows the one used under a voice); a moved slider
 * sets one level for voices and videos alike. Done is one undo step.
 */
@Composable
internal fun TalkLowerPanel(
    track: Track,
    project: Project,
    callbacks: EditorCallbacks,
    close: () -> Unit,
) {
    val key = track.id.value
    var on by rememberSaveable(key) { mutableStateOf(track.lowerWhenTalking) }
    var percent by rememberSaveable(key) {
        mutableFloatStateOf(dbToPercent(track.lowerToDb ?: project.ducking.musicUnderVoiceDb).coerceAtMost(MAX_TALK_PERCENT))
    }
    var moved by rememberSaveable(key) { mutableStateOf(false) }
    PanelTitle(
        stringResource(R.string.bar_lower_music),
        onDone = {
            val level = if (moved) percentToDb(percent).coerceAtMost(0.0) else track.lowerToDb
            if (on != track.lowerWhenTalking || level != track.lowerToDb) {
                callbacks.edit { setTalkLowering(track.id, on, level) }
            }
            close()
        },
        onCancel = close,
    )
    val switchLabel = stringResource(R.string.action_lower_music)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = { on = it })
            .testTag(TALK_SWITCH_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(switchLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = on, onCheckedChange = null)
    }
    val label = stringResource(R.string.talk_level)
    val shown = stringResource(R.string.volume_value, percent.roundToInt())
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            shown,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.End,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TALK_VALUE_TAG),
        )
    }
    Slider(
        value = percent,
        onValueChange = {
            percent = (it / 5).roundToInt() * 5f
            moved = true
        },
        enabled = on,
        valueRange = 0f..MAX_TALK_PERCENT,
        modifier =
            Modifier.fillMaxWidth().testTag(TALK_SLIDER_TAG).semantics {
                contentDescription = label
                stateDescription = shown
            },
    )
    Spacer(Modifier.height(8.dp))
}
