package com.itsluminous.clearmovie.render.media3

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer

/**
 * The preview player (ARCHITECTURE section 7): a `CompositionPlayer` with
 * the multi-input video graph, so it composites the same layers through the
 * same compositor as the export. `CompositionFactory` always emits at least
 * two visual sequences, so this graph is always the right one.
 */
@UnstableApi
object PreviewPlayer {
    /** Must be called on the thread that will use the player (one with a Looper). */
    fun create(context: Context): CompositionPlayer =
        CompositionPlayer
            .Builder(context)
            .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
            .build()
}
