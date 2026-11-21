package com.itsluminous.clearmovie.ui.frame

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.preset.FrameSample
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.KeyMath
import com.itsluminous.clearmovie.domain.model.KeySpec
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.ui.components.AutoSizeLabel
import com.itsluminous.clearmovie.ui.components.ResizableRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val FRAME_LOOK_TAG = "frame_look"
const val FRAME_LOOK_PICTURE_TAG = "frame_look_picture"
const val FRAME_LOOK_DONE_TAG = "frame_look_done"
const val FRAME_LOOK_SWATCH_TAG = "frame_look_swatch"

fun frameLookTabTag(tab: FrameLookTab) = "frame_look_tab_${tab.name}"

/** The two things a frame's look holds: the colour keyed out, and where photos and videos go. */
enum class FrameLookTab { COLOUR, AREA }

/** How wide the frame's picture is decoded for the look screen: enough to tap and drag on, small to key on the CPU. */
const val FRAME_LOOK_STILL_WIDTH = 480

/**
 * A frame's look (cm-frame-area), full screen: a picture of the frame video
 * (the slider picks the moment), keyed as the chosen colour leaves it, over
 * a checkerboard where it is see-through.
 *
 * - Screen colour: tap the picture to pick the colour to remove (the
 *   average of a few pixels around the tap), and choose how much to remove.
 * - Picture area: a box on the same picture, dragged to move and by a
 *   corner to resize, where photos and videos go. It starts as the keyed
 *   part's bounds and follows a new colour until it is moved by hand.
 *
 * Everything is in units of the frame's own picture, so it holds for every
 * video shape and export size. [onDone] gets the key (softness fixed at the
 * preset default) and the area. All choices survive a rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameLookDialog(
    durationMs: Long,
    initialKey: KeySpec,
    initialArea: NormRect?,
    startTab: FrameLookTab,
    loadStill: suspend (atUs: Long) -> FrameSample?,
    onDone: (KeySpec, NormRect) -> Unit,
    onCancel: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var color by rememberSaveable { mutableLongStateOf(initialKey.color.argb) }
    var tolerance by rememberSaveable {
        mutableDoubleStateOf(initialKey.tolerance.coerceIn(KeyMath.MIN_TOLERANCE, KeyMath.MAX_TOLERANCE))
    }
    var areaText by rememberSaveable { mutableStateOf(initialArea?.let(::encode)) }
    // The box follows the keyed part until the user moves it (or it was set before).
    var areaByHand by rememberSaveable { mutableStateOf(initialArea != null) }
    var momentMs by rememberSaveable { mutableLongStateOf((durationMs / 2).coerceAtLeast(0)) }
    var noColour by rememberSaveable { mutableStateOf(false) }

    var sample by remember { mutableStateOf<FrameSample?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var keyed by remember { mutableStateOf<Bitmap?>(null) }
    val key = KeySpec(ArgbColor(color), tolerance, KeyMath.SOFTNESS)
    val area = areaText?.let(::decode)

    LaunchedEffect(momentMs) {
        val s = loadStill(momentMs * 1000)
        if (s != null) {
            sample = s
            unreadable = false
        } else if (sample == null) {
            unreadable = true
        }
    }
    LaunchedEffect(sample, key) {
        val s = sample ?: return@LaunchedEffect
        val (bitmap, found) = withContext(Dispatchers.Default) { keyedPicture(s, key) to KeyMath.keyedArea(s.argb, s.width, s.height, key) }
        keyed = bitmap
        if (!areaByHand) areaText = encode(found ?: NormRect.FULL)
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            modifier = Modifier.testTag(FRAME_LOOK_TAG),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.frame_look_title), maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { onDone(key, area ?: NormRect.FULL) },
                            enabled = sample != null,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(FRAME_LOOK_DONE_TAG),
                        ) { Text(stringResource(R.string.action_done)) }
                    },
                )
            },
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val roomWidth = maxWidth
                val roomHeight = maxHeight
                val twoPane = roomWidth > roomHeight && roomWidth >= 600.dp
                val aspect = sample?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
                val picture: @Composable (Modifier) -> Unit = { m ->
                    FramePicture(
                        modifier = m,
                        aspect = aspect,
                        keyed = keyed,
                        tab = tab,
                        area = area,
                        unreadable = unreadable,
                        onTap = { x, y ->
                            val s = sample ?: return@FramePicture
                            val picked = averageAround(s, x, y)
                            if (!KeyMath.canKey(picked)) {
                                noColour = true
                            } else {
                                noColour = false
                                color = picked.argb
                            }
                        },
                        onArea = {
                            areaByHand = true
                            areaText = encode(it)
                        },
                    )
                }
                val controls: @Composable () -> Unit = {
                    Controls(
                        tab = tab,
                        onTab = { tab = it },
                        color = ArgbColor(color),
                        tolerance = tolerance,
                        onTolerance = { tolerance = it },
                        noColour = noColour,
                        durationMs = durationMs,
                        momentMs = momentMs,
                        onMoment = { momentMs = it },
                        onFitArea = {
                            areaByHand = false
                            val s = sample ?: return@Controls
                            areaText = encode(KeyMath.keyedArea(s.argb, s.width, s.height, key) ?: NormRect.FULL)
                        },
                    )
                }
                if (twoPane) {
                    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                            picture(Modifier.width(minOf(maxWidth, maxHeight * aspect)))
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // An upright frame never takes more than about half the height, so the controls stay in reach.
                        picture(Modifier.width(minOf(roomWidth - 32.dp, roomHeight * PICTURE_SHARE * aspect)))
                        Spacer(Modifier.size(16.dp))
                        Column(Modifier.widthIn(max = 640.dp)) { controls() }
                    }
                }
            }
        }
    }
}

@Composable
private fun FramePicture(
    modifier: Modifier,
    aspect: Float,
    keyed: Bitmap?,
    tab: FrameLookTab,
    area: NormRect?,
    unreadable: Boolean,
    onTap: (Double, Double) -> Unit,
    onArea: (NormRect) -> Unit,
) {
    val stillDescription = stringResource(R.string.frame_look_still)
    val boxDescription = stringResource(R.string.frame_look_box)
    BoxWithConstraints(
        modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .testTag(FRAME_LOOK_PICTURE_TAG),
    ) {
        Checkerboard(Modifier.fillMaxSize())
        if (keyed != null) {
            Image(
                keyed.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!unreadable) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 32.dp))
        } else {
            Surface(Modifier.align(Alignment.Center).padding(16.dp), shape = RoundedCornerShape(8.dp)) {
                Text(
                    stringResource(R.string.frame_look_unreadable),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        when (tab) {
            FrameLookTab.COLOUR -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = stillDescription }
                        .pointerInput(w, h) {
                            detectTapGestures { p ->
                                onTap((p.x / w).toDouble().coerceIn(0.0, 1.0), (p.y / h).toDouble().coerceIn(0.0, 1.0))
                            }
                        },
                )
            }

            FrameLookTab.AREA -> {
                ResizableRect(
                    rect = area ?: NormRect.FULL,
                    onChange = onArea,
                    minWidth = KeyMath.MIN_AREA_SIDE,
                    minHeight = KeyMath.MIN_AREA_SIDE,
                    description = boxDescription,
                )
            }
        }
    }
}

@Composable
private fun Checkerboard(modifier: Modifier) {
    val light = Color(0xFF9E9E9E)
    val dark = Color(0xFF757575)
    val cell = with(LocalDensity.current) { 12.dp.toPx() }
    Canvas(modifier) {
        drawRect(light)
        var y = 0
        while (y * cell < size.height) {
            var x = y % 2
            while (x * cell < size.width) {
                drawRect(dark, Offset(x * cell, y * cell), Size(cell, cell))
                x += 2
            }
            y++
        }
    }
}

@Composable
private fun Controls(
    tab: FrameLookTab,
    onTab: (FrameLookTab) -> Unit,
    color: ArgbColor,
    tolerance: Double,
    onTolerance: (Double) -> Unit,
    noColour: Boolean,
    durationMs: Long,
    momentMs: Long,
    onMoment: (Long) -> Unit,
    onFitArea: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            for (t in FrameLookTab.entries) {
                Tab(
                    selected = t == tab,
                    onClick = { onTab(t) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(frameLookTabTag(t)),
                    icon = {
                        Icon(
                            painterResource(if (t == FrameLookTab.COLOUR) R.drawable.ic_colorize else R.drawable.ic_crop_free),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    text = {
                        AutoSizeLabel(
                            stringResource(
                                if (t ==
                                    FrameLookTab.COLOUR
                                ) {
                                    R.string.action_key_colour
                                } else {
                                    R.string.action_picture_area
                                },
                            ),
                        )
                    },
                )
            }
        }
        when (tab) {
            FrameLookTab.COLOUR -> {
                Text(stringResource(R.string.frame_look_colour_body), style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val picked = stringResource(R.string.frame_look_picked)
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(Color(color.argb.toInt()), RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .semantics { contentDescription = picked }
                            .testTag(FRAME_LOOK_SWATCH_TAG),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.frame_look_picked), style = MaterialTheme.typography.bodyLarge)
                }
                if (noColour) {
                    Text(
                        stringResource(R.string.frame_look_no_colour),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(stringResource(R.string.frame_look_amount), style = MaterialTheme.typography.titleSmall)
                LabelledSlider(
                    value = ((tolerance - KeyMath.MIN_TOLERANCE) / (KeyMath.MAX_TOLERANCE - KeyMath.MIN_TOLERANCE)).toFloat(),
                    onValue = { onTolerance(KeyMath.MIN_TOLERANCE + it * (KeyMath.MAX_TOLERANCE - KeyMath.MIN_TOLERANCE)) },
                    start = stringResource(R.string.frame_look_less),
                    end = stringResource(R.string.frame_look_more),
                )
            }

            FrameLookTab.AREA -> {
                Text(stringResource(R.string.frame_look_area_body), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = onFitArea, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_fit_screen), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    AutoSizeLabel(stringResource(R.string.frame_look_fit_area))
                }
            }
        }
        if (durationMs > 0) {
            Text(stringResource(R.string.frame_look_moment), style = MaterialTheme.typography.titleSmall)
            var dragging by remember { mutableFloatStateOf(-1f) }
            Slider(
                value = if (dragging >= 0) dragging else momentMs.toFloat() / durationMs,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    if (dragging >= 0) onMoment((dragging * durationMs).toLong())
                    dragging = -1f
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    value: Float,
    onValue: (Float) -> Unit,
    start: String,
    end: String,
) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    Column {
        Slider(
            value = if (dragging >= 0) dragging else value.coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (dragging >= 0) onValue(dragging)
                dragging = -1f
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(start, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(end, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The average colour of the few pixels around ([x], [y]) of [s] (units of the picture), so one noisy pixel does not decide. */
internal fun averageAround(
    s: FrameSample,
    x: Double,
    y: Double,
): ArgbColor {
    val cx = (x * s.width).toInt().coerceIn(0, s.width - 1)
    val cy = (y * s.height).toInt().coerceIn(0, s.height - 1)
    var r = 0
    var g = 0
    var b = 0
    var n = 0
    for (py in (cy - 2)..(cy + 2)) {
        for (px in (cx - 2)..(cx + 2)) {
            if (px !in 0 until s.width || py !in 0 until s.height) continue
            val p = s.argb[py * s.width + px]
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
        }
    }
    return ArgbColor(0xFF000000L or ((r / n).toLong() shl 16) or ((g / n).toLong() shl 8) or (b / n).toLong())
}

/** [s] keyed with [key], as an image: see-through where the key removes it. */
internal fun keyedPicture(
    s: FrameSample,
    key: KeySpec,
): Bitmap {
    val keyer = KeyMath.Keyer(key)
    val out = IntArray(s.argb.size)
    for (i in out.indices) {
        val p = s.argb[i]
        val a = (keyer.alpha(p) * 255 + 0.5).toInt().coerceIn(0, 255)
        out[i] = (a shl 24) or (p and 0xFFFFFF)
    }
    return Bitmap.createBitmap(out, s.width, s.height, Bitmap.Config.ARGB_8888)
}

private fun encode(r: NormRect) = "${r.x},${r.y},${r.w},${r.h}"

private fun decode(text: String): NormRect? =
    text
        .split(',')
        .mapNotNull { it.toDoubleOrNull() }
        .takeIf { it.size == 4 }
        ?.let { NormRect(it[0], it[1], it[2], it[3]) }
        ?.takeIf { KeyMath.isUsableArea(it) }

private const val PICTURE_SHARE = 0.5f
