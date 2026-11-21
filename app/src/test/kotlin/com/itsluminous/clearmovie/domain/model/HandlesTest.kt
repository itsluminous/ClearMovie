package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import com.itsluminous.clearmovie.domain.model.Fixtures.media
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Handles on every row (cm-timeline-handles): a caption made on a photo or
 * video follows it on both sides until one side is dragged, and then only
 * that side keeps its own time; frames, music, voice and picture in picture
 * resize inside their file, their neighbours and the video.
 */
class HandlesTest {
    private val ids = SequentialIds("h")

    private fun Project.caption(text: String): TextClip =
        tracks.flatMap { it.clips }.filterIsInstance<TextClip>().single { it.text == text }

    private fun Project.span(text: String): Pair<Long, Long> = caption(text).let { it.startMs to it.endMs }

    private fun Project.spanOf(id: String): Pair<Long, Long> = requireClip(ClipId(id)).let { it.startMs to it.endMs }

    /** MAIN: photo a (3 s), video b (4 s of an 8 s file, trimmed 2..6), photo c (5 s); "on b" covers b (3..7). */
    private fun captioned(): Project =
        Fixtures
            .blankWith(
                Fixtures.photo("a", 3000),
                VideoClip(ClipId("b"), media("b"), sourceDurationMs = 8000, trimStartMs = 2000, trimEndMs = 6000),
                Fixtures.photo("c", 5000),
                ids = ids,
            ).addCaptionFor(ClipId("b"), "on b", ids)
            .assertValid()

    private fun Project.captionId() = caption("on b").id

    // ---- Captions: per-side detach ------------------------------------------------

    @Test
    fun `an untouched caption follows both sides of its item as before`() {
        var p = captioned()
        assertThat(p.span("on b")).isEqualTo(3000L to 7000L)
        p = p.trimVideo(ClipId("b"), 1000, 6000).assertValid()
        assertThat(p.span("on b")).isEqualTo(3000L to 8000L)
        p = p.moveMainClip(ClipId("a"), 2).assertValid()
        assertThat(p.span("on b")).isEqualTo(0L to 5000L)
    }

    @Test
    fun `the start handle detaches only the start`() {
        var p = captioned().let { it.resizeClip(it.captionId(), ResizeEdge.START, 4000) }.assertValid()
        assertThat(p.span("on b")).isEqualTo(4000L to 7000L)
        // 1 s into b, which starts at 3 s.
        assertThat(p.caption("on b").attachedTo).isEqualTo(CaptionAttachment(ClipId("b"), detachedStartOffsetMs = 1000))
        // The item gets longer at its end: the end follows, the start stays.
        p = p.trimVideo(ClipId("b"), 2000, 7000).assertValid()
        assertThat(p.span("on b")).isEqualTo(4000L to 8000L)
        // The item moves (a goes last): the caption goes with it, still starting 1 s into it, ending with it.
        p = p.moveMainClip(ClipId("a"), 2).assertValid()
        assertThat(p.attachSpan(ClipId("b"))).isEqualTo(0L to 5000L)
        assertThat(p.span("on b")).isEqualTo(1000L to 5000L)
        // And back: the same place on b as before.
        p = p.moveMainClip(ClipId("a"), 0).assertValid()
        assertThat(p.span("on b")).isEqualTo(4000L to 8000L)
    }

    @Test
    fun `the end handle detaches only the end`() {
        var p = captioned()
        p = p.resizeClip(p.captionId(), ResizeEdge.END, 6000).assertValid()
        assertThat(p.span("on b")).isEqualTo(3000L to 6000L)
        // 1 s before b's end.
        assertThat(p.caption("on b").attachedTo!!.detachedEndOffsetMs).isEqualTo(-1000L)
        assertThat(p.caption("on b").attachedTo!!.detachedStartOffsetMs).isNull()
        // Trimmed by a second: the start follows b's start, the end stays 1 s before b's end.
        p = p.trimVideo(ClipId("b"), 3000, 6000).assertValid()
        assertThat(p.attachSpan(ClipId("b"))).isEqualTo(3000L to 6000L)
        assertThat(p.span("on b")).isEqualTo(3000L to 5000L)
        // The photo before it gets longer: b starts and ends later, and so does the caption.
        p = p.setPhotoDuration(ClipId("a"), 4000).assertValid()
        assertThat(p.span("on b")).isEqualTo(4000L to 6000L)
    }

    @Test
    fun `a following side that passes the detached one leaves the caption at its shortest, and comes back`() {
        var p = captioned()
        p = p.resizeClip(p.captionId(), ResizeEdge.START, 6500).assertValid()
        // b now ends at 4 s: before the caption's own start.
        p = p.trimVideo(ClipId("b"), 2000, 3000).assertValid()
        assertThat(p.span("on b")).isEqualTo(6500L to 6600L)
        p = p.trimVideo(ClipId("b"), 2000, 6000).assertValid()
        assertThat(p.span("on b")).isEqualTo(6500L to 7000L)
    }

    @Test
    fun `both sides dragged make it a free caption that keeps its times and outlives its item`() {
        var p = captioned()
        p = p.resizeClip(p.captionId(), ResizeEdge.START, 4000)
        p = p.resizeClip(p.captionId(), ResizeEdge.END, 6000).assertValid()
        assertThat(p.caption("on b").attachedTo).isNull()
        p = p.moveMainClip(ClipId("a"), 2).assertValid()
        assertThat(p.span("on b")).isEqualTo(4000L to 6000L)
        p = p.deleteClip(ClipId("b")).assertValid()
        assertThat(p.span("on b")).isEqualTo(4000L to 6000L)
    }

    @Test
    fun `a caption whose end was dragged travels with its item when MAIN is reordered`() {
        var p = captioned()
        p = p.resizeClip(p.captionId(), ResizeEdge.END, 5500).assertValid()
        // b goes first: 0..4 s; the caption ends 1.5 s before b's end, as before the move.
        p = p.moveMainClip(ClipId("b"), 0).assertValid()
        assertThat(p.span("on b")).isEqualTo(0L to 2500L)
        // Put b last: 8..12 s.
        p = p.moveMainClip(ClipId("b"), 2).assertValid()
        assertThat(p.span("on b")).isEqualTo(8000L to 10_500L)
    }

    @Test
    fun `a dragged side of a picture-in-picture item's caption travels when that item is moved in time`() {
        var p =
            captioned().addTimedClip(PhotoClip(ClipId("q"), media("q"), 4000, startMs = 1000, pip = PipPlacement.DEFAULT), ids = ids)
        p = p.addCaptionFor(ClipId("q"), "on q", ids)
        val id = p.caption("on q").id
        p = p.resizeClip(id, ResizeEdge.START, 2000).assertValid()
        p = p.moveClip(ClipId("q"), 6000).assertValid()
        assertThat(p.span("on q")).isEqualTo(7000L to 10_000L)
    }

    @Test
    fun `dragging a detached side again moves only that side`() {
        var p = captioned()
        p = p.resizeClip(p.captionId(), ResizeEdge.START, 4000)
        p = p.resizeClip(p.captionId(), ResizeEdge.START, 3500).assertValid()
        assertThat(p.caption("on b").attachedTo).isEqualTo(CaptionAttachment(ClipId("b"), detachedStartOffsetMs = 500))
    }

    @Test
    fun `a caption placed by hand inside its item keeps following it from its new place`() {
        var p = captioned()
        p = p.setTextTiming(p.captionId(), 3500, 2000).assertValid()
        assertThat(p.caption("on b").attachedTo!!.whole).isFalse()
        p = p.resizeClip(p.captionId(), ResizeEdge.END, 6000).assertValid()
        assertThat(p.caption("on b").attachedTo).isEqualTo(CaptionAttachment(ClipId("b"), 500, whole = false, lengthMs = 2500))
        p = p.setPhotoDuration(ClipId("a"), 4000).assertValid()
        assertThat(p.span("on b")).isEqualTo(4500L to 7000L)
    }

    @Test
    fun `Follow item gives the caption both sides back, and undo takes each step back`() {
        val start = captioned()
        val editor = ProjectEditor(start, ids)
        val id = editor.project.captionId()
        assertThat(editor.project.canFollowItem(id)).isFalse()
        assertThat(editor.edit { resizeClip(id, ResizeEdge.START, 4000) }).isEqualTo(EditResult.Applied)
        assertThat(editor.edit { resizeClip(id, ResizeEdge.END, 6500) }).isEqualTo(EditResult.Applied)
        assertThat(editor.project.caption("on b").attachedTo).isNull()
        assertThat(editor.undo()).isTrue()
        assertThat(editor.project.span("on b")).isEqualTo(4000L to 7000L)
        assertThat(editor.project.canFollowItem(id)).isTrue()
        assertThat(editor.edit { followItem(id) }).isEqualTo(EditResult.Applied)
        assertThat(editor.project.caption("on b").attachedTo).isEqualTo(CaptionAttachment(ClipId("b")))
        assertThat(editor.project.span("on b")).isEqualTo(3000L to 7000L)
        assertThat(editor.undo()).isTrue()
        assertThat(editor.undo()).isTrue()
        assertThat(editor.project).isEqualTo(start)
    }

    @Test
    fun `split and join keep each detached side with its part`() {
        var p = captioned()
        p = p.resizeClip(p.captionId(), ResizeEdge.START, 4000).assertValid()
        // Cut b 2 s in (at 5 s): the first part keeps the detached start, the copy on the second follows it whole.
        p = p.splitClip(ClipId("b"), 2000, ClipId("b2")).assertValid()
        val captions =
            p.tracks
                .flatMap { it.clips }
                .filterIsInstance<TextClip>()
                .sortedBy { it.startMs }
        assertThat(captions.map { it.startMs to it.endMs }).containsExactly(4000L to 5000L, 5000L to 7000L).inOrder()
        assertThat(captions[0].attachedTo).isEqualTo(CaptionAttachment(ClipId("b"), detachedStartOffsetMs = 1000))
        assertThat(captions[1].attachedTo).isEqualTo(CaptionAttachment(ClipId("b2")))
        // Joined again: one caption, its start still detached.
        p = p.joinClips(ClipId("b"), ClipId("b2")).assertValid()
        assertThat(
            p.tracks
                .flatMap { it.clips }
                .filterIsInstance<TextClip>()
                .map { it.startMs to it.endMs },
        ).containsExactly(4000L to 7000L)
    }

    @Test
    fun `a join after the second part's copy was changed keeps the first part's detached start`() {
        var p = captioned()
        p = p.resizeClip(p.captionId(), ResizeEdge.START, 4000)
        p = p.splitClip(ClipId("b"), 2000, ClipId("b2")).assertValid()
        val copy =
            p.tracks
                .flatMap { it.clips }
                .filterIsInstance<TextClip>()
                .single { it.attachedTo?.clip == ClipId("b2") }
        p = p.setText(copy.id, "other words").joinClips(ClipId("b"), ClipId("b2")).assertValid()
        assertThat(p.span("on b")).isEqualTo(4000L to 5000L)
    }

    @Test
    fun `a side let go exactly on its item's edge follows it again`() {
        var p = captioned()
        p = p.resizeClip(p.captionId(), ResizeEdge.START, 4000)
        p = p.resizeClip(p.captionId(), ResizeEdge.START, 3000).assertValid()
        assertThat(p.caption("on b").attachedTo).isEqualTo(CaptionAttachment(ClipId("b")))
        assertThat(p.canFollowItem(p.captionId())).isFalse()
    }

    @Test
    fun `a preset frame that does not repeat keeps where its start was dragged, as an ordinary frame`() {
        // A frame file shorter than the video: capped at its file, its end can't follow the video, so the drag decides.
        var p = Fixtures.presetProject(ids).addToMain(listOf(Fixtures.photo("p1", 3000)), ids = ids)
        val row = p.byRole(PresetRole.FRAME)
        val f = row.clips.single() as OverlayClip
        p =
            p
                .copy(
                    tracks =
                        p.tracks.map {
                            if (it.id ==
                                row.id
                            ) {
                                it.copy(clips = listOf(f.copy(repeat = false, sourceDurationMs = 2000, durationMs = 2000)))
                            } else {
                                it
                            }
                        },
                ).assertValid()
        val before = p.requireClip(f.id)
        p = p.resizeClip(f.id, ResizeEdge.START, before.startMs + 500).assertValid()
        val after = p.requireClip(f.id) as OverlayClip
        assertThat(after.startMs to after.endMs).isEqualTo(before.startMs + 500 to before.endMs)
        assertThat(after.anchor).isNull()
    }

    @Test
    fun `a slowed picture-in-picture video is never shorter than the least of its file it can play`() {
        val p =
            captioned().addTimedClip(
                VideoClip(
                    ClipId("v"),
                    media("v"),
                    sourceDurationMs = 6000,
                    trimEndMs = 1000,
                    speed = Speed.X0_25,
                    startMs = 1000,
                    pip = PipPlacement.DEFAULT,
                ),
                ids = ids,
            )
        val r = p.resizeRange(ClipId("v"))!!
        // 0.1 s of file at a quarter speed is 0.4 s.
        assertThat(r.startMax).isEqualTo(5000L - 400)
        assertThat(p.resizeClip(ClipId("v"), ResizeEdge.START, 99_999).assertValid().spanOf("v")).isEqualTo(4600L to 5000L)
    }

    @Test
    fun `a duplicate's caption keeps its detached side at the same distance from the copy`() {
        var p = captioned()
        p = p.resizeClip(p.captionId(), ResizeEdge.START, 4000).assertValid()
        p = p.duplicateClip(ClipId("b"), ClipId("b2")).assertValid()
        val copy =
            p.tracks
                .flatMap { it.clips }
                .filterIsInstance<TextClip>()
                .single { it.attachedTo?.clip == ClipId("b2") }
        val copySpan = p.attachSpan(ClipId("b2"))!!
        assertThat(copy.startMs - copySpan.first).isEqualTo(1000L)
        assertThat(copy.endMs).isEqualTo(copySpan.second)
    }

    // ---- Limits per item type -----------------------------------------------------

    @Test
    fun `a caption stops at its neighbours, the video's start and end, and half a second`() {
        var p = captioned().addText("before", 0, 2000, ids).addText("after", 8000, 1000, ids).assertValid()
        val r = p.resizeRange(p.captionId())!!
        assertThat(r.startMin).isEqualTo(2000L)
        assertThat(r.startMax).isEqualTo(6500L)
        assertThat(r.endMin).isEqualTo(3500L)
        assertThat(r.endMax).isEqualTo(8000L)
        p = p.resizeClip(p.captionId(), ResizeEdge.START, -5000).assertValid()
        assertThat(p.span("on b").first).isEqualTo(2000L)
        val lone = captioned()
        assertThat(lone.resizeRange(lone.captionId())!!.endMax).isEqualTo(lone.totalDurationMs())
        assertThat(lone.resizeRange(lone.captionId())!!.startMin).isEqualTo(0L)
    }

    @Test
    fun `a sound that does not repeat resizes only through its file`() {
        var p =
            captioned().addTimedClip(
                AudioClip(ClipId("s"), media("s"), 2000, 3000, sourceDurationMs = 6000, trimStartMs = 1000, trimEndMs = 4000),
                role = AudioRole.VOICE,
                ids = ids,
            )
        val r = p.resizeRange(ClipId("s"))!!
        // 1 s of file before its start, 2 s after its end; at least 0.3 s.
        assertThat(r.startMin).isEqualTo(1000L)
        assertThat(r.endMax).isEqualTo(7000L)
        assertThat(r.startMax).isEqualTo(4700L)
        p = p.resizeClip(ClipId("s"), ResizeEdge.START, 0).assertValid()
        val s = p.requireClip(ClipId("s")) as AudioClip
        assertThat(s.startMs to s.trimStartMs).isEqualTo(1000L to 0L)
        assertThat(s.endMs).isEqualTo(5000L)
        p = p.resizeClip(ClipId("s"), ResizeEdge.END, 9000).assertValid()
        val t = p.requireClip(ClipId("s")) as AudioClip
        assertThat(t.endMs to t.trimEndMs).isEqualTo(7000L to 6000L)
    }

    @Test
    fun `a repeating sound and a repeating frame stretch to the video's end`() {
        val p =
            captioned()
                .addTimedClip(
                    AudioClip(ClipId("m"), media("m"), 0, 2000, sourceDurationMs = 2000, repeat = true),
                    role = AudioRole.MUSIC,
                    ids = ids,
                ).addTimedClip(OverlayClip(ClipId("f"), media("f"), 0, 2000, sourceDurationMs = 2000, repeat = true), ids = ids)
        assertThat(p.resizeRange(ClipId("m"))!!.endMax).isEqualTo(p.totalDurationMs())
        assertThat(p.resizeClip(ClipId("m"), ResizeEdge.END, 10_000).assertValid().spanOf("m")).isEqualTo(0L to 10_000L)
        assertThat(p.resizeClip(ClipId("f"), ResizeEdge.END, 10_000).assertValid().spanOf("f")).isEqualTo(0L to 10_000L)
    }

    @Test
    fun `a frame that does not repeat is at most its file`() {
        val p = captioned().addTimedClip(Fixtures.overlay("f", 1000, 2000), ids = ids)
        val r = p.resizeRange(ClipId("f"))!!
        assertThat(r.endMax).isEqualTo(3000L)
        assertThat(r.startMin).isEqualTo(1000L)
        assertThat(r.startMax).isEqualTo(2500L)
    }

    @Test
    fun `a picture-in-picture video trims through its file and keeps its other edge`() {
        var p =
            captioned().addTimedClip(
                VideoClip(
                    ClipId("v"),
                    media("v"),
                    sourceDurationMs = 6000,
                    trimStartMs = 1000,
                    trimEndMs = 3000,
                    startMs = 4000,
                    pip = PipPlacement.DEFAULT,
                ),
                ids = ids,
            )
        val r = p.resizeRange(ClipId("v"))!!
        assertThat(r.startMin).isEqualTo(3000L)
        assertThat(r.endMax).isEqualTo(9000L)
        p = p.resizeClip(ClipId("v"), ResizeEdge.START, 2000).assertValid()
        val v = p.requireClip(ClipId("v")) as VideoClip
        assertThat(v.trimStartMs).isEqualTo(0L)
        assertThat(v.startMs to v.endMs).isEqualTo(3000L to 6000L)
    }

    @Test
    fun `a picture-in-picture photo is at least half a second`() {
        val p = captioned().addTimedClip(PhotoClip(ClipId("q"), media("q"), 2000, startMs = 1000, pip = PipPlacement.DEFAULT), ids = ids)
        val shortest = p.resizeClip(ClipId("q"), ResizeEdge.END, 0).assertValid()
        assertThat(shortest.spanOf("q")).isEqualTo(1000L to 1500L)
    }

    @Test
    fun `MAIN items have no timed handles`() {
        assertThat(captioned().resizeRange(ClipId("a"))).isNull()
        assertThrows(IllegalArgumentException::class.java) { captioned().resizeClip(ClipId("a"), ResizeEdge.END, 100) }
    }

    @Test
    fun `releasing a handle where it started changes nothing`() {
        val p = captioned()
        assertThat(p.resizeClip(p.captionId(), ResizeEdge.START, 3000)).isSameInstanceAs(p)
    }

    // ---- The preset's own items ---------------------------------------------------

    @Test
    fun `the preset frame keeps following the video on the side that was not dragged`() {
        var p = Fixtures.presetProject(ids).addToMain(listOf(Fixtures.photo("p1", 3000)), ids = ids).assertValid()
        val frame = p.byRole(PresetRole.FRAME).clips.single() as OverlayClip
        val (start, end) = frame.startMs to frame.endMs
        p = p.resizeClip(frame.id, ResizeEdge.START, start + 1000).assertValid()
        assertThat(p.spanOf(frame.id.value)).isEqualTo(start + 1000 to end)
        // More photos: the end still follows the video, the start stays.
        p = p.addToMain(listOf(Fixtures.photo("p2", 4000)), ids = ids).assertValid()
        assertThat(p.spanOf(frame.id.value)).isEqualTo(start + 1000 to end + 4000)
        assertThat((p.requireClip(frame.id) as OverlayClip).anchor).isNotNull()
        // Its end dragged too: it is an ordinary frame that keeps its times.
        p = p.resizeClip(frame.id, ResizeEdge.END, end).assertValid()
        assertThat((p.requireClip(frame.id) as OverlayClip).anchor).isNull()
        p = p.addToMain(listOf(Fixtures.photo("p3", 4000)), ids = ids).assertValid()
        assertThat(p.spanOf(frame.id.value)).isEqualTo(start + 1000 to end)
    }

    @Test
    fun `the frame's music keeps following on its other side too`() {
        var p = Fixtures.presetProject(ids).addToMain(listOf(Fixtures.photo("p1", 3000)), ids = ids).assertValid()
        val music = p.byRole(PresetRole.FRAME_MUSIC).clips.single()
        p = p.resizeClip(music.id, ResizeEdge.END, music.endMs - 1000).assertValid()
        val start = p.requireClip(music.id).startMs
        p = p.setPhotoDuration(ClipId("p1"), 5000).assertValid()
        // The video is 2 s longer: the music still ends 1 s before the video's end.
        assertThat(p.spanOf(music.id.value)).isEqualTo(start to music.endMs + 1000)
        // Moved by hand, it stops following altogether, as before.
        p = p.moveClip(music.id, 500).assertValid()
        val moved = p.requireClip(music.id) as AudioClip
        assertThat(moved.anchor to moved.detachedEndOffsetMs).isEqualTo(null to null)
    }

    // ---- Validation ---------------------------------------------------------------

    @Test
    fun `a stored caption with both sides detached, or a detached side that is not whole, is refused`() {
        val p = captioned()
        val c = p.caption("on b")
        val track = p.trackOf(c.id)!!

        fun with(a: CaptionAttachment) =
            p.copy(
                tracks =
                    p.tracks.map { t ->
                        if (t.id ==
                            track.id
                        ) {
                            t.copy(clips = listOf(c.copy(attachedTo = a)))
                        } else {
                            t
                        }
                    },
            )
        assertThat(with(CaptionAttachment(ClipId("b"), detachedStartOffsetMs = 4000, detachedEndOffsetMs = 5000)).validate()).isNotEmpty()
        assertThat(
            with(CaptionAttachment(ClipId("b"), whole = false, lengthMs = 1000, detachedStartOffsetMs = 4000)).validate(),
        ).isNotEmpty()
        assertThat(with(CaptionAttachment(ClipId("b"), detachedStartOffsetMs = Limits.MAX_MEDIA_MS + 1)).validate()).isNotEmpty()
    }

    @Test
    fun `a talk level is only for music rows and only from silent to unchanged`() {
        val p = Fixtures.presetProject(ids)
        val music = p.byRole(PresetRole.FRAME_MUSIC).id
        assertThat(p.setTalkLowering(music, true, -12.0).byRole(PresetRole.FRAME_MUSIC).lowerToDb).isEqualTo(-12.0)
        assertThrows(IllegalArgumentException::class.java) { p.setTalkLowering(music, true, 3.0) }
        assertThrows(IllegalArgumentException::class.java) { p.setTalkLowering(p.byRole(PresetRole.CLOSING_MESSAGE).id, true, -12.0) }
        val bad = p.copy(tracks = p.tracks.map { if (it.presetRole == PresetRole.CLOSING_MESSAGE) it.copy(lowerToDb = -6.0) else it })
        assertThat(bad.validate()).isNotEmpty()
    }
}
