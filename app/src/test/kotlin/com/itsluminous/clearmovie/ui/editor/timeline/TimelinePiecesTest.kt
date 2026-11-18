package com.itsluminous.clearmovie.ui.editor.timeline

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.Speed
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.ui.editor.BAR_MAX
import com.itsluminous.clearmovie.ui.editor.BAR_MIN
import com.itsluminous.clearmovie.ui.editor.BAR_TARGET
import com.itsluminous.clearmovie.ui.editor.IntakePurpose
import com.itsluminous.clearmovie.ui.editor.barItemWidth
import com.itsluminous.clearmovie.ui.editor.dbToPercent
import com.itsluminous.clearmovie.ui.editor.percentToDb
import org.junit.Test
import kotlin.math.abs

/** The bar's per-type order, the trim maths, the thumbnail plan and other small pieces of the timeline. */
class TimelinePiecesTest {
    @Test
    fun `each kind of item shows its actions most-used first`() {
        fun order(kind: SelectionKind) = ClipActions.forSelection(kind).map { it.action }
        assertThat(order(SelectionKind.PHOTO))
            .containsExactly(
                EditAction.DURATION,
                EditAction.ADD_CAPTION,
                EditAction.TRANSITION,
                EditAction.ANIMATION,
                EditAction.FIT,
                EditAction.ROTATE,
                EditAction.REPLACE,
                EditAction.DUPLICATE,
                EditAction.MOVE_LEFT,
                EditAction.MOVE_RIGHT,
                EditAction.DELETE,
            ).inOrder()
        assertThat(order(SelectionKind.VIDEO))
            .containsExactly(
                EditAction.TRIM,
                EditAction.ADD_CAPTION,
                EditAction.TRANSITION,
                EditAction.ANIMATION,
                EditAction.VOLUME,
                EditAction.SPLIT,
                EditAction.SPEED,
                EditAction.LOOP,
                EditAction.FIT,
                EditAction.REPLACE,
                EditAction.DUPLICATE,
                EditAction.MOVE_LEFT,
                EditAction.MOVE_RIGHT,
                EditAction.DELETE,
            ).inOrder()
        assertThat(order(SelectionKind.TEXT_CARD).first()).isEqualTo(EditAction.EDIT_TEXT)
        assertThat(order(SelectionKind.COLOUR_CARD).first()).isEqualTo(EditAction.COLOUR)
        assertThat(order(SelectionKind.TEXT_CARD)).contains(EditAction.ANIMATION)
        assertThat(order(SelectionKind.INTRO))
            .containsExactly(EditAction.PLAY, EditAction.TRANSITION, EditAction.REPLACE, EditAction.SKIP)
            .inOrder()
        // Nothing after it: Transition is shown, greyed out.
        assertThat(
            ClipActions.forSelection(SelectionKind.PHOTO, hasNext = false).first { it.action == EditAction.TRANSITION }.enabled,
        ).isFalse()
        assertThat(order(SelectionKind.ENDING)).containsExactly(EditAction.PLAY, EditAction.REPLACE, EditAction.SKIP).inOrder()
        assertThat(
            order(SelectionKind.NOTHING),
        ).containsExactly(EditAction.ADD_MEDIA, EditAction.ZOOM_OUT, EditAction.ZOOM_IN, EditAction.FIT_ALL, EditAction.ADD_ROW).inOrder()
    }

    @Test
    fun `Join shows only next to a split partner, Move is disabled at the ends, and a missing item offers Replace first`() {
        val video = ClipActions.forSelection(SelectionKind.VIDEO, canMoveLeft = false, canJoin = true)
        assertThat(video.map { it.action }).contains(EditAction.JOIN)
        assertThat(video.first { it.action == EditAction.MOVE_LEFT }.enabled).isFalse()
        assertThat(video.first { it.action == EditAction.MOVE_RIGHT }.enabled).isTrue()
        val missing = ClipActions.forSelection(SelectionKind.PHOTO, missing = true).map { it.action }
        assertThat(missing.take(2)).containsExactly(EditAction.REPLACE, EditAction.DELETE).inOrder()
        assertThat(missing.toSet()).isEqualTo(ClipActions.forSelection(SelectionKind.PHOTO).map { it.action }.toSet())
    }

    @Test
    fun `the bar's buttons share the width when they fit, and are compact with half of one cut off when not`() {
        // Six at 64 dp fit in 411 dp: they share it.
        assertThat(barItemWidth(411.dp, 6).value).isWithin(0.01f).of(411f / 6)
        for ((bar, count) in listOf(360.dp to 6, 360.dp to 11, 411.dp to 7, 411.dp to 11, 460.dp to 9, 840.dp to 14)) {
            val w = barItemWidth(bar, count)
            val visible = bar / w
            // Compact (never a third of the screen), 4.5 to 5.5 on a phone, and the last one half shown.
            assertThat(w).isAtLeast(BAR_MIN)
            assertThat(w).isAtMost(BAR_MAX)
            assertThat(abs(visible - visible.toInt() - 0.5f)).isLessThan(0.001f)
            if (bar <= 411.dp) {
                assertThat(visible).isIn(
                    com.google.common.collect.Range
                        .closed(4.5f, 5.5f),
                )
            }
        }
        // Larger text widens them, still half of one cut off.
        val big = barItemWidth(411.dp, 11, BAR_MIN * 1.5f, BAR_TARGET * 1.5f, BAR_MAX * 1.5f)
        assertThat(big).isAtLeast(BAR_MIN * 1.5f)
        assertThat(411.dp / big % 1f).isWithin(0.001f).of(0.5f)
        // Between whole-and-a-half widths that fall in the range, the nearest to it: never a 100 dp gap.
        for (bar in listOf(150.dp, 250.dp, 320.dp)) {
            val w = barItemWidth(bar, 9)
            assertThat((bar / w) % 1f).isWithin(0.001f).of(0.5f)
            assertThat(w).isAtMost(BAR_MAX + 4.dp)
            assertThat(w).isAtLeast(BAR_MIN)
        }
        // Narrow: one and a half while that keeps [BAR_MIN]; narrower still, [BAR_MIN] itself.
        assertThat(100.dp / barItemWidth(100.dp, 4)).isWithin(0.001f).of(1.5f)
        assertThat(barItemWidth(90.dp, 4)).isEqualTo(BAR_MIN)
        // Larger text never makes a button narrower than its own minimum.
        assertThat(barItemWidth(120.dp, 5, BAR_MIN * 1.5f, BAR_TARGET * 1.5f, BAR_MAX * 1.5f)).isEqualTo(BAR_MIN * 1.5f)
    }

    @Test
    fun `trimming a video moves through its file at its speed, within the file and a minimum length`() {
        val v =
            VideoClip(ClipId("v"), Fixtures.media("v"), sourceDurationMs = 10_000, trimStartMs = 2000, trimEndMs = 8000, speed = Speed.X2)
        // 1 s of timeline at 2x is 2 s of file.
        assertThat((TrimMath.dragged(v, TrimEdge.START, 1000.0) as VideoClip).trimStartMs).isEqualTo(4000)
        assertThat((TrimMath.dragged(v, TrimEdge.END, -1000.0) as VideoClip).trimEndMs).isEqualTo(6000)
        assertThat((TrimMath.dragged(v, TrimEdge.START, -9999.0) as VideoClip).trimStartMs).isEqualTo(0)
        assertThat((TrimMath.dragged(v, TrimEdge.END, 9999.0) as VideoClip).trimEndMs).isEqualTo(10_000)
        val squeezed = TrimMath.dragged(v, TrimEdge.START, 99_999.0) as VideoClip
        assertThat(squeezed.durationMs).isAtLeast(TrimMath.MIN_VIDEO_MS)
    }

    @Test
    fun `dragging a photo's or card's edge changes how long it shows`() {
        val p = Fixtures.photo("p", 3000)
        assertThat(TrimMath.dragged(p, TrimEdge.END, 1500.0).durationMs).isEqualTo(4500)
        assertThat(TrimMath.dragged(p, TrimEdge.START, 1500.0).durationMs).isEqualTo(1500)
        assertThat(TrimMath.dragged(p, TrimEdge.START, 9000.0).durationMs).isEqualTo(TrimMath.MIN_STILL_MS)
        val card = CardClip(ClipId("c"), 3000, ArgbColor(0xFF000000))
        assertThat(TrimMath.dragged(card, TrimEdge.END, -500.0).durationMs).isEqualTo(2500)
    }

    @Test
    fun `thumbnails are re-planned per zoom step and stay inside the trimmed range`() {
        val v = VideoClip(ClipId("v"), Fixtures.media("v"), sourceDurationMs = 20_000, trimStartMs = 4000, trimEndMs = 12_000)
        val coarse = ThumbPlan.frameTimes(v, cells = 4, cellDp = 64.0, msPerDp = 31.25)
        val fine = ThumbPlan.frameTimes(v, cells = 16, cellDp = 64.0, msPerDp = 7.8125)
        assertThat(coarse).isNotEqualTo(fine)
        for (t in coarse + fine) assertThat(t).isIn(4000L until 12_000L)
        assertThat(fine.distinct().size).isAtMost(ThumbPlan.MAX_FRAMES)
        // Close zoom levels share a step, so a small pinch reuses the pictures.
        assertThat(ThumbPlan.bucketOf(10.0)).isEqualTo(ThumbPlan.bucketOf(10.6))
        assertThat(ThumbPlan.bucketOf(10.0)).isNotEqualTo(ThumbPlan.bucketOf(16.0))
        assertThat(ThumbPlan.frameTimes(Fixtures.photo("p"), 5, 64.0, 10.0).toSet()).containsExactly(0L)
    }

    @Test
    fun `a looped video's thumbnails repeat with the loop`() {
        val v = VideoClip(ClipId("v"), Fixtures.media("v"), sourceDurationMs = 2000, loops = 3)
        val times = ThumbPlan.frameTimes(v, cells = 6, cellDp = 50.0, msPerDp = 20.0)
        // 6 cells of 1 s across 6 s: each 2 s loop shows the same two pictures.
        assertThat(times.subList(0, 2)).isEqualTo(times.subList(2, 4))
        assertThat(times.subList(2, 4)).isEqualTo(times.subList(4, 6))
    }

    @Test
    fun `volume percent and decibels map both ways`() {
        assertThat(dbToPercent(0.0)).isWithin(0.01f).of(100f)
        assertThat(percentToDb(100f)).isWithin(1e-9).of(0.0)
        assertThat(percentToDb(0f)).isEqualTo(-60.0)
        assertThat(dbToPercent(-60.0)).isEqualTo(0f)
        assertThat(dbToPercent(percentToDb(50f).toDouble())).isWithin(0.01f).of(50f)
        assertThat(percentToDb(200f)).isWithin(0.01).of(6.02)
    }

    @Test
    fun `an intake's purpose survives being saved as text`() {
        val purposes =
            listOf(
                IntakePurpose.Add,
                IntakePurpose.Insert(ClipId("abc"), com.itsluminous.clearmovie.domain.model.Side.BEFORE),
                IntakePurpose.Replace(ClipId("x1")),
                IntakePurpose.ReplacePart(ClipId("intro")),
            )
        for (p in purposes) assertThat(IntakePurpose.decode(p.encode())).isEqualTo(p)
        assertThat(IntakePurpose.decode("insert:SIDEWAYS:abc")).isNull()
        assertThat(IntakePurpose.decode("nonsense")).isNull()
    }
}
