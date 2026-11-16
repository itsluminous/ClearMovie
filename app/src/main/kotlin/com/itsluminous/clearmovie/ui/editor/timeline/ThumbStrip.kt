package com.itsluminous.clearmovie.ui.editor.timeline

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MainClip
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.VideoClip
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Which pictures a thumbnail strip shows (ARCHITECTURE section 11.7):
 * one per cell, at the file time under the cell's middle. The zoom is
 * rounded to whole steps of [BUCKET_RATIO] so a small change reuses the same
 * pictures, and at most [MAX_FRAMES] distinct pictures are asked for per item.
 */
object ThumbPlan {
    const val BUCKET_RATIO = 1.5
    const val MAX_FRAMES = 16
    const val MAX_CELLS = 64
    const val SETTLE_MS = 150L

    fun bucketOf(msPerDp: Double): Int = (ln(msPerDp) / ln(BUCKET_RATIO)).roundToInt()

    fun msPerDpOf(bucket: Int): Double = BUCKET_RATIO.pow(bucket)

    fun cells(
        widthDp: Double,
        cellDp: Double,
    ): Int = ceil(widthDp / cellDp).toInt().coerceIn(1, MAX_CELLS)

    /** The file time (ms) each cell shows; photos and cards have one picture (0). */
    fun frameTimes(
        clip: MainClip,
        cells: Int,
        cellDp: Double,
        msPerDp: Double,
    ): List<Long> {
        val range: Triple<Long, Long, Double> =
            when (clip) {
                is VideoClip -> Triple(clip.trimStartMs, clip.trimEndMs, clip.speed.numerator.toDouble() / clip.speed.denominator)
                is IntroClip -> Triple(0L, clip.durationMs, 1.0)
                is EndingClip -> if (clip.kind == EndingKind.VIDEO) Triple(0L, clip.durationMs, 1.0) else return List(cells) { 0L }
                else -> return List(cells) { 0L }
            }
        val (from, to, speed) = range
        val length = (to - from).coerceAtLeast(1)
        val loops = (clip as? VideoClip)?.loops ?: 1
        val loopTimeline = clip.durationMs.toDouble() / loops
        val quantum = maxOf(100.0, cellDp * msPerDp * speed, length.toDouble() / MAX_FRAMES)
        return List(cells) { i ->
            val timeline = ((i + 0.5) * cellDp * msPerDp) % loopTimeline
            val source = (timeline * speed).coerceIn(0.0, length - 1.0)
            val q = floor(source / quantum) * quantum + quantum / 2
            from + q.coerceAtMost(length - 1.0).toLong()
        }
    }
}

/**
 * An item's pictures across its tile. While a pinch is under way the
 * pictures already loaded (for the previous zoom) are drawn stretched; once
 * the zoom has held still for [ThumbPlan.SETTLE_MS] the strip asks for the
 * pictures of the new zoom.
 */
@Composable
fun ThumbStripContent(
    clip: MainClip,
    widthDp: Double,
    msPerDp: Double,
    thumbnail: ThumbSource,
    modifier: Modifier,
) {
    if (clip is CardClip) {
        CardThumb(clip, modifier)
        return
    }
    if (clip is EndingClip && clip.kind == EndingKind.AUDIO) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_music_note), contentDescription = null, modifier = Modifier.size(24.dp))
        }
        return
    }
    BoxWithConstraints(modifier) {
        val cellDp = (maxHeight.value * 4.0 / 3).coerceAtLeast(32.0)
        val cells = ThumbPlan.cells(widthDp, cellDp)
        val bucket = ThumbPlan.bucketOf(msPerDp)
        var settled by remember(clip.id) { mutableStateOf(bucket) }
        LaunchedEffect(bucket) {
            delay(ThumbPlan.SETTLE_MS)
            settled = bucket
        }
        val times = ThumbPlan.frameTimes(clip, cells, cellDp, ThumbPlan.msPerDpOf(settled))
        val loaded = remember(clip) { mutableStateMapOf<Long, Bitmap>() }
        LaunchedEffect(clip, times) {
            for (t in times.distinct()) {
                if (t !in loaded) thumbnail(clip, t)?.let { loaded[t] = it }
            }
        }
        val plan = "$settled:$cells"
        Row(Modifier.fillMaxSize().semantics { thumbPlan = plan }) {
            val cellWidth = (widthDp / cells).coerceAtLeast(1.0)
            for (i in 0 until cells) {
                val bitmap = loaded[times[i]] ?: nearest(loaded, times[i])
                Box(Modifier.width(cellWidth.dp).fillMaxHeight()) {
                    if (bitmap != null) {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** The loaded picture nearest [t]: the coarser strip drawn until the new one arrives. */
private fun nearest(
    loaded: Map<Long, Bitmap>,
    t: Long,
): Bitmap? = loaded.minByOrNull { kotlin.math.abs(it.key - t) }?.value

@Composable
private fun CardThumb(
    clip: CardClip,
    modifier: Modifier,
) {
    val colour = Color(clip.color.argb.toInt())
    val textColour = if (colour.luminance() > 0.5f) Color.Black else Color.White
    Box(modifier.background(colour), contentAlignment = Alignment.Center) {
        val text = clip.text
        if (!text.isNullOrBlank()) {
            Text(
                text,
                color = textColour,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/** The zoom step and cell count a strip is drawn for ("bucket:cells"): for tests, never read aloud. */
val ThumbPlanKey = SemanticsPropertyKey<String>("ThumbPlan")
var SemanticsPropertyReceiver.thumbPlan by ThumbPlanKey
