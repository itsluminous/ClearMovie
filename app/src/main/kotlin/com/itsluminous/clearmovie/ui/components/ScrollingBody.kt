package com.itsluminous.clearmovie.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R

/** Test tag of the fade [MoreBelow] draws while there is more to scroll to. */
const val MORE_BELOW_TAG = "more_below"

/**
 * A screen or sheet whose [body] scrolls while its [actions] stay in sight
 * at the bottom: on a short window (a phone held sideways, very large text)
 * the buttons are never pushed off the screen. The body takes only the
 * height it needs, so a short body leaves the actions right under it.
 * A fade with a down arrow at the body's foot, in [surface] (the colour
 * behind the body), shows there is more.
 */
@Composable
fun ScrollingBody(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    surface: Color = MaterialTheme.colorScheme.surfaceContainer,
    actions: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(modifier, horizontalAlignment = horizontalAlignment) {
        Box(Modifier.weight(1f, fill = false)) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll), horizontalAlignment = horizontalAlignment, content = body)
            MoreBelow(scroll, surface)
        }
        actions()
    }
}

/**
 * The fade and down arrow at the foot of a scrolling area while [scroll] can
 * go further down, so a list cut off at the bottom reads as one that
 * scrolls. Drawn over the area's own colour ([surface]); decoration only.
 */
@Composable
fun BoxScope.MoreBelow(
    scroll: ScrollState,
    surface: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    if (!scroll.canScrollForward) return
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(32.dp)
            .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface)))
            .testTag(MORE_BELOW_TAG)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Icon(
            painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
