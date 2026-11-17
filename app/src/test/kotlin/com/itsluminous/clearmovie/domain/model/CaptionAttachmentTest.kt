package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.domain.model.Fixtures.trackOfType
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Captions attached to a photo or video (cm-captions, superseding cm-model
 * D3 for "Add caption"): they move with the item when MAIN is reordered,
 * stay inside it when it is trimmed or sped up, are deleted with it, and
 * undo brings both back. Captions made on a text row stay free.
 */
class CaptionAttachmentTest {
    private val ids = SequentialIds("n")

    private fun Project.captions(): List<TextClip> = tracks.flatMap { it.clips }.filterIsInstance<TextClip>()

    private fun Project.caption(text: String): TextClip = captions().single { it.text == text }

    private fun Project.span(text: String): Pair<Long, Long> = caption(text).let { it.startMs to it.endMs }

    /** MAIN: photo a (3 s), video b (4 s), photo c (5 s), no transitions. */
    private fun abc(): Project =
        Fixtures.blankWith(Fixtures.photo("a", 3000), Fixtures.video("b", 4000), Fixtures.photo("c", 5000), ids = ids)

    @Test
    fun `Add caption attaches to the item and covers it whole`() {
        val p = abc().addCaptionFor(ClipId("b"), "on b", ids).assertValid()
        val c = p.caption("on b")
        assertThat(c.attachedTo).isEqualTo(CaptionAttachment(ClipId("b")))
        assertThat(c.startMs to c.endMs).isEqualTo(3000L to 7000L)
        // Its style starts as the preset's: nothing overridden.
        assertThat(c.style).isEqualTo(CaptionStyleOverride.NONE)
    }

    @Test
    fun `only photos and videos take a caption`() {
        val p = Fixtures.blankWith(CardClip(ClipId("k"), 3000, ArgbColor.BLACK, "card"), ids = ids)
        assertThrows(IllegalArgumentException::class.java) { p.addCaptionFor(ClipId("k"), "x", ids) }
        assertThrows(IllegalArgumentException::class.java) { p.addCaptionFor(ClipId("nope"), "x", ids) }
    }

    @Test
    fun `a reordered item takes its caption with it, and undo brings it back`() {
        val editor = ProjectEditor(abc().addCaptionFor(ClipId("a"), "on a", ids), ids)
        assertThat(editor.project.span("on a")).isEqualTo(0L to 3000L)
        assertThat(editor.edit { moveMainClip(ClipId("a"), 2) }).isEqualTo(EditResult.Applied)
        // a now plays last: after b (4 s) and c (5 s).
        assertThat(editor.project.span("on a")).isEqualTo(9000L to 12_000L)
        assertThat(
            editor.project
                .caption("on a")
                .attachedTo!!
                .clip,
        ).isEqualTo(ClipId("a"))
        assertThat(editor.undo()).isTrue()
        assertThat(editor.project.span("on a")).isEqualTo(0L to 3000L)
        assertThat(editor.redo()).isTrue()
        assertThat(editor.project.span("on a")).isEqualTo(9000L to 12_000L)
    }

    @Test
    fun `a free caption keeps its own time when MAIN is reordered`() {
        var p = abc().addText("free", 1000, 1500, ids)
        p = p.moveMainClip(ClipId("a"), 2).assertValid()
        assertThat(p.span("free")).isEqualTo(1000L to 2500L)
        assertThat(p.caption("free").attachedTo).isNull()
    }

    @Test
    fun `captions of two crossfaded items meet half way and share a row`() {
        var p = abc().setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 1000))
        p = p.addCaptionFor(ClipId("a"), "on a", ids).addCaptionFor(ClipId("b"), "on b", ids).assertValid()
        // a: 0..3000, b starts at 2000 (the overlap), so they meet at 2500.
        assertThat(p.span("on a")).isEqualTo(0L to 2500L)
        assertThat(p.span("on b")).isEqualTo(2500L to 6000L)
        assertThat(p.trackOf(p.caption("on a").id)).isEqualTo(p.trackOf(p.caption("on b").id))
    }

    @Test
    fun `trimming or speeding up the item keeps the caption inside it, and lengthening it again follows`() {
        var p = abc().addCaptionFor(ClipId("b"), "on b", ids)
        p = p.trimVideo(ClipId("b"), 1000, 3000).assertValid()
        assertThat(p.span("on b")).isEqualTo(3000L to 5000L)
        // A whole-item caption follows the item back out.
        p = p.trimVideo(ClipId("b"), 0, 4000).assertValid()
        assertThat(p.span("on b")).isEqualTo(3000L to 7000L)
        p = p.setSpeed(ClipId("b"), Speed.X2).assertValid()
        assertThat(p.span("on b")).isEqualTo(3000L to 5000L)
        p = p.setSpeed(ClipId("b"), Speed.X0_5).setLoops(ClipId("b"), 2).assertValid()
        assertThat(p.span("on b")).isEqualTo(3000L to 19_000L)
        // A photo's duration too.
        p = p.addCaptionFor(ClipId("c"), "on c", ids).setPhotoDuration(ClipId("c"), 1500).assertValid()
        assertThat(p.span("on c").second - p.span("on c").first).isEqualTo(1500L)
    }

    @Test
    fun `a caption placed inside its item by hand keeps its offset and is cut at the item's end`() {
        var p = abc().addCaptionFor(ClipId("c"), "on c", ids)
        val id = p.caption("on c").id
        // c plays 7000..12000; the caption moved to 9000 for 2 s.
        p = p.setTextTiming(id, 9000, 2000).assertValid()
        assertThat(p.caption("on c").attachedTo).isEqualTo(CaptionAttachment(ClipId("c"), offsetMs = 2000, whole = false, lengthMs = 2000))
        // Reordered first, c plays 0..5000: the caption still starts 2 s in.
        p = p.moveMainClip(ClipId("c"), 0).assertValid()
        assertThat(p.span("on c")).isEqualTo(2000L to 4000L)
        // Shortened to 3 s: the caption is cut at the photo's end.
        p = p.setPhotoDuration(ClipId("c"), 3000).assertValid()
        assertThat(p.span("on c")).isEqualTo(2000L to 3000L)
        // Put back over the whole photo, it follows the photo's length again.
        p = p.setTextTiming(id, 0, 3000).assertValid()
        assertThat(p.caption("on c").attachedTo!!.whole).isTrue()
        p = p.setPhotoDuration(ClipId("c"), 4000).assertValid()
        assertThat(p.span("on c")).isEqualTo(0L to 4000L)
    }

    @Test
    fun `a caption moved off its item by hand is free from then on`() {
        var p = abc().addCaptionFor(ClipId("a"), "on a", ids)
        val id = p.caption("on a").id
        p = p.moveClip(id, 8000).assertValid()
        assertThat(p.caption("on a").attachedTo).isNull()
        p = p.moveMainClip(ClipId("a"), 2).assertValid()
        assertThat(p.span("on a")).isEqualTo(8000L to 11_000L)
    }

    @Test
    fun `nudging an attached caption inside its item keeps it attached`() {
        var p = abc().addCaptionFor(ClipId("c"), "on c", ids)
        val id = p.caption("on c").id
        p = p.setTextTiming(id, 7000, 2000).nudgeClip(id, 500).assertValid()
        assertThat(p.caption("on c").attachedTo).isEqualTo(CaptionAttachment(ClipId("c"), offsetMs = 500, whole = false, lengthMs = 2000))
        assertThat(p.span("on c")).isEqualTo(7500L to 9500L)
    }

    @Test
    fun `moving an attached caption to another text row keeps it attached`() {
        var p = abc().addRow(TrackType.TEXT, id = TrackId("text2")).addCaptionFor(ClipId("b"), "on b", ids)
        val id = p.caption("on b").id
        val other = p.tracks.first { it.type == TrackType.TEXT && it.id != p.trackOf(id)!!.id }.id
        p = p.moveClip(id, 3000, other).assertValid()
        assertThat(p.trackOf(id)!!.id).isEqualTo(other)
        assertThat(p.caption("on b").attachedTo!!.whole).isTrue()
    }

    @Test
    fun `deleting the item deletes its captions, the emptied row goes, and undo restores both`() {
        val start = abc().addRow(TrackType.TEXT, id = TrackId("text2")).addCaptionFor(ClipId("b"), "on b", ids)
        val editor = ProjectEditor(start, ids)
        val row = editor.project.trackOf(editor.project.caption("on b").id)!!.id
        assertThat(editor.edit { deleteClip(ClipId("b")) }).isEqualTo(EditResult.Applied)
        assertThat(editor.project.captions()).isEmpty()
        assertThat(editor.project.track(row)).isNull()
        assertThat(editor.undo()).isTrue()
        assertThat(editor.project).isEqualTo(start)
        assertThat(editor.project.clip(ClipId("b"))).isNotNull()
        assertThat(
            editor.project
                .caption("on b")
                .attachedTo!!
                .clip,
        ).isEqualTo(ClipId("b"))
    }

    @Test
    fun `deleting a caption leaves its item alone`() {
        var p = abc().addCaptionFor(ClipId("b"), "on b", ids)
        p = p.deleteClip(p.caption("on b").id).assertValid()
        assertThat(p.clip(ClipId("b"))).isNotNull()
    }

    @Test
    fun `a caption that would land on another caption moves to another text row`() {
        // A free caption at 0..3000, and one attached to c (7000..12000) on the same row.
        var p = abc().addText("free", 0, 3000, ids).addCaptionFor(ClipId("c"), "on c", ids)
        assertThat(p.trackOf(p.caption("free").id)).isEqualTo(p.trackOf(p.caption("on c").id))
        val textRows = p.tracks.count { it.type == TrackType.TEXT }
        // c first: its caption now covers 0..5000, over the free one.
        p = p.moveMainClip(ClipId("c"), 0).assertValid()
        assertThat(p.span("free")).isEqualTo(0L to 3000L)
        assertThat(p.span("on c")).isEqualTo(0L to 5000L)
        assertThat(p.trackOf(p.caption("free").id)).isNotEqualTo(p.trackOf(p.caption("on c").id))
        assertThat(p.tracks.count { it.type == TrackType.TEXT }).isEqualTo(textRows + 1)
        // The new row is a text row on top of the stack, as Add text makes.
        val moved = p.trackOf(p.caption("on c").id)!!
        assertThat(p.stacking.last()).isEqualTo(moved.id)
    }

    @Test
    fun `normalising is idempotent and pure`() {
        var p = abc().addText("free", 0, 3000, ids).addCaptionFor(ClipId("c"), "on c", ids).moveMainClip(ClipId("c"), 0)
        assertThat(p.normalized()).isEqualTo(p)
        // A project whose attached caption is stale is put right the same way every time.
        val stale =
            p.copy(
                tracks =
                    p.tracks.map { t ->
                        t.copy(
                            clips =
                                t.clips.map {
                                    if (it is TextClip &&
                                        it.attachedTo != null
                                    ) {
                                        it.copy(startMs = 1)
                                    } else {
                                        it
                                    }
                                },
                        )
                    },
            )
        assertThat(stale.normalized()).isEqualTo(p)
    }

    @Test
    fun `split gives each part the whole-item caption, and each part keeps it when moved`() {
        var p = abc().addCaptionFor(ClipId("b"), "on b", ids)
        p = p.splitClip(ClipId("b"), 1000, ClipId("b2")).assertValid()
        val parts = p.captions().filter { it.text == "on b" }.sortedBy { it.startMs }
        assertThat(parts.map { it.attachedTo!!.clip }).containsExactly(ClipId("b"), ClipId("b2")).inOrder()
        assertThat(parts.map { it.startMs to it.endMs }).containsExactly(3000L to 4000L, 4000L to 7000L).inOrder()
        // Same row, end to end.
        assertThat(p.trackOf(parts[0].id)).isEqualTo(p.trackOf(parts[1].id))
        p = p.moveMainClip(ClipId("b2"), 0).assertValid()
        val second = p.captions().single { it.attachedTo?.clip == ClipId("b2") }
        assertThat(second.startMs to second.endMs).isEqualTo(0L to 3000L)
    }

    @Test
    fun `a caption that starts in the second part goes with the second part`() {
        var p = abc().addCaptionFor(ClipId("b"), "late", ids)
        p = p.setTextTiming(p.caption("late").id, 5500, 1000)
        p = p.splitClip(ClipId("b"), 1000, ClipId("b2")).assertValid()
        assertThat(p.caption("late").attachedTo).isEqualTo(CaptionAttachment(ClipId("b2"), offsetMs = 1500, whole = false, lengthMs = 1000))
        assertThat(p.span("late")).isEqualTo(5500L to 6500L)
    }

    @Test
    fun `join puts a split's captions back as one`() {
        var p = abc().addCaptionFor(ClipId("b"), "on b", ids)
        val before = p
        p = p.splitClip(ClipId("b"), 1000, ClipId("b2")).joinClips(ClipId("b"), ClipId("b2")).assertValid()
        assertThat(p.captions()).isEqualTo(before.captions())
    }

    @Test
    fun `join keeps different captions of the two parts where they are`() {
        var p = abc().splitClip(ClipId("b"), 1000, ClipId("b2"))
        p = p.addCaptionFor(ClipId("b"), "first", ids).addCaptionFor(ClipId("b2"), "second", ids)
        p = p.joinClips(ClipId("b"), ClipId("b2")).assertValid()
        assertThat(p.span("first")).isEqualTo(3000L to 4000L)
        assertThat(p.span("second")).isEqualTo(4000L to 7000L)
        assertThat(p.captions().map { it.attachedTo!!.clip }.toSet()).containsExactly(ClipId("b"))
        // They now move with the joined video.
        p = p.moveMainClip(ClipId("b"), 2).assertValid()
        assertThat(p.span("first")).isEqualTo(8000L to 9000L)
        assertThat(p.span("second")).isEqualTo(9000L to 12_000L)
    }

    @Test
    fun `duplicate copies the item's captions onto the copy`() {
        var p = abc().addCaptionFor(ClipId("a"), "on a", ids)
        p = p.duplicateClip(ClipId("a"), ClipId("a2")).assertValid()
        val copies = p.captions().filter { it.text == "on a" }.sortedBy { it.startMs }
        assertThat(copies.map { it.attachedTo!!.clip }).containsExactly(ClipId("a"), ClipId("a2")).inOrder()
        assertThat(copies.map { it.startMs to it.endMs }).containsExactly(0L to 3000L, 3000L to 6000L).inOrder()
        // Deleting the copy takes only its own caption.
        p = p.deleteClip(ClipId("a2")).assertValid()
        assertThat(p.captions().map { it.attachedTo!!.clip }).containsExactly(ClipId("a"))
    }

    @Test
    fun `a replaced item keeps its caption`() {
        var p = abc().addCaptionFor(ClipId("b"), "on b", ids)
        p = p.replaceMainItem(ClipId("b"), Fixtures.photo("new", 2000)).assertValid()
        assertThat(p.span("on b")).isEqualTo(3000L to 5000L)
    }

    @Test
    fun `a picture-in-picture item's caption follows it, and goes with its row`() {
        var p = abc().addRow(TrackType.VISUAL, id = TrackId("pip"))
        p = p.addToTrack(TrackId("pip"), Fixtures.photo("p", 2000, PipPlacement()), 1000, ids)
        p = p.addCaptionFor(ClipId("p"), "on pip", ids).assertValid()
        assertThat(p.span("on pip")).isEqualTo(1000L to 3000L)
        p = p.moveClip(ClipId("p"), 6000).assertValid()
        assertThat(p.span("on pip")).isEqualTo(6000L to 8000L)
        p = p.removeRow(TrackId("pip")).assertValid()
        assertThat(p.captions()).isEmpty()
    }

    @Test
    fun `an attachment to an item that is not there is invalid, and normalising frees the caption`() {
        val p = abc().addCaptionFor(ClipId("b"), "on b", ids)
        val text = p.trackOfType(TrackType.TEXT)
        val orphan =
            p.copy(
                tracks =
                    p.tracks.map { t ->
                        if (t.id !=
                            text.id
                        ) {
                            t
                        } else {
                            t.copy(clips = t.clips.map { (it as TextClip).copy(attachedTo = CaptionAttachment(ClipId("gone"))) })
                        }
                    },
            )
        assertThat(orphan.validate().map { it.rule }).contains(Violation.Rule.ANCHOR)
        val freed = orphan.normalized().assertValid()
        assertThat(freed.caption("on b").attachedTo).isNull()
        assertThat(freed.span("on b")).isEqualTo(3000L to 7000L)
    }

    @Test
    fun `a stored project with a stale or orphaned attachment loads repaired, its captions kept`() {
        val codec = ProjectCodec()
        val p = abc().addCaptionFor(ClipId("b"), "on b", ids)
        val stale = codec.encode(p).replace("\"startMs\": 3000", "\"startMs\": 2000")
        val repaired = codec.decode(stale) as ProjectDecodeResult.Ok
        assertThat(repaired.repaired).isTrue()
        assertThat(repaired.project).isEqualTo(p)
        val orphan = codec.encode(p).replace("\"clip\": \"b\"", "\"clip\": \"gone\"")
        val freed = codec.decode(orphan) as ProjectDecodeResult.Ok
        assertThat(freed.project.caption("on b").attachedTo).isNull()
    }

    @Test
    fun `the preset style is applied, overridden minimally, and reset`() {
        val defaults = CaptionStyle()
        var p = abc().addCaptionFor(ClipId("a"), "on a", ids)
        val id = p.caption("on a").id
        assertThat(p.caption("on a").style.resolve(p.captionDefaults)).isEqualTo(p.captionDefaults)
        val wanted = defaults.copy(sizePx = 80, color = ArgbColor(0xFFFFEB3BL), outline = null, font = CaptionFont.SERIF.id)
        val override = CaptionStyleOverride.between(defaults, wanted)
        assertThat(override).isEqualTo(
            CaptionStyleOverride(sizePx = 80, color = ArgbColor(0xFFFFEB3BL), font = CaptionFont.SERIF.id, clearOutline = true),
        )
        assertThat(override.resolve(defaults)).isEqualTo(wanted)
        // The preset's own value again stores nothing.
        assertThat(CaptionStyleOverride.between(defaults, defaults)).isEqualTo(CaptionStyleOverride.NONE)
        p = p.setCaptionStyle(id, override).assertValid()
        assertThat(p.caption("on a").style.resolve(p.captionDefaults)).isEqualTo(wanted)
        p = p.resetCaptionStyle(id).assertValid()
        assertThat(p.caption("on a").style.isEmpty).isTrue()
    }

    @Test
    fun `an unknown font id draws as the default font`() {
        assertThat(CaptionFont.of("noto_sans_devanagari_bold")).isEqualTo(CaptionFont.SANS_BOLD)
        assertThat(CaptionFont.of("../../etc/passwd")).isEqualTo(CaptionFont.DEFAULT)
        assertThat(CaptionFont.of("comic_sans")).isEqualTo(CaptionFont.DEFAULT)
        assertThat(CaptionFont.DEFAULT.id).isEqualTo(CaptionStyle.DEFAULT_FONT)
    }

    @Test
    fun `a whole caption nudged later and back is whole again, with its length`() {
        var p = abc().addCaptionFor(ClipId("b"), "on b", ids)
        val id = p.caption("on b").id
        p = p.nudgeClip(id, -500).assertValid()
        // Earlier than b (3000) is outside it: free.
        assertThat(p.caption("on b").attachedTo).isNull()
        p = abc().addCaptionFor(ClipId("c"), "on c", ids)
        val c = p.caption("on c").id
        p = p.moveClip(c, 7500).assertValid()
        assertThat(p.span("on c")).isEqualTo(7500L to 12_000L)
        assertThat(p.caption("on c").attachedTo).isEqualTo(CaptionAttachment(ClipId("c"), 500, whole = false, lengthMs = 5000))
        p = p.moveClip(c, 7000).assertValid()
        assertThat(p.caption("on c").attachedTo).isEqualTo(CaptionAttachment(ClipId("c")))
        assertThat(p.span("on c")).isEqualTo(7000L to 12_000L)
    }

    @Test
    fun `a caption cut by a shorter item gets its length back when the item grows again`() {
        var p = abc().addCaptionFor(ClipId("c"), "on c", ids)
        p = p.setTextTiming(p.caption("on c").id, 8000, 3000).assertValid()
        p = p.setPhotoDuration(ClipId("c"), 2000).assertValid()
        assertThat(p.span("on c")).isEqualTo(8000L to 9000L)
        p = p.setPhotoDuration(ClipId("c"), 5000).assertValid()
        assertThat(p.span("on c")).isEqualTo(8000L to 11_000L)
    }

    @Test
    fun `a caption across a split is cut at the cut, and joining gives its length back`() {
        var p = abc().addCaptionFor(ClipId("b"), "across", ids)
        p = p.setTextTiming(p.caption("across").id, 3500, 2500).assertValid()
        val before = p.span("across")
        p = p.splitClip(ClipId("b"), 1000, ClipId("b2")).assertValid()
        assertThat(p.span("across")).isEqualTo(3500L to 4000L)
        assertThat(p.caption("across").attachedTo!!.clip).isEqualTo(ClipId("b"))
        p = p.joinClips(ClipId("b"), ClipId("b2")).assertValid()
        assertThat(p.span("across")).isEqualTo(before)
    }

    @Test
    fun `captions of items joined by an odd-length transition meet without overlapping`() {
        var p = abc().setTransition(ClipId("a"), Transition(TransitionKind.CROSSFADE, 651))
        p = p.addCaptionFor(ClipId("a"), "on a", ids).addCaptionFor(ClipId("b"), "on b", ids).assertValid()
        assertThat(p.span("on a").second).isEqualTo(p.span("on b").first)
        assertThat(p.trackOf(p.caption("on a").id)).isEqualTo(p.trackOf(p.caption("on b").id))
    }

    @Test
    fun `a photo replaced by a card leaves its caption in place, free`() {
        var p = abc().addCaptionFor(ClipId("a"), "on a", ids)
        p = p.replaceMainItem(ClipId("a"), CardClip(ClipId("k"), 3000, ArgbColor.BLACK, "card")).assertValid()
        assertThat(p.caption("on a").attachedTo).isNull()
        assertThat(p.span("on a")).isEqualTo(0L to 3000L)
    }

    @Test
    fun `deleting a picture-in-picture item deletes its caption`() {
        var p = abc().addRow(TrackType.VISUAL, id = TrackId("pip"))
        p = p.addToTrack(TrackId("pip"), Fixtures.photo("p", 2000, PipPlacement()), 1000, ids).addCaptionFor(ClipId("p"), "on pip", ids)
        p = p.deleteClip(ClipId("p")).assertValid()
        assertThat(p.captions()).isEmpty()
    }

    @Test
    fun `a stored project whose stale caption must pass a free one on its row still loads`() {
        val codec = ProjectCodec()
        // Free "x" at 1000 and "on c" (c: 7000..12000) on one row; stored with "on c" stale at 0..500, before "x".
        val p = abc().addText("x", 1000, 1000, ids).addCaptionFor(ClipId("c"), "on c", ids)
        val row = p.trackOf(p.caption("x").id)!!
        val staleCaption = p.caption("on c").copy(startMs = 0, durationMs = 500)
        val stale = p.copy(tracks = p.tracks.map { t -> if (t.id != row.id) t else t.copy(clips = listOf(staleCaption, p.caption("x"))) })
        val loaded = codec.decode(codec.encode(stale)) as ProjectDecodeResult.Ok
        assertThat(loaded.repaired).isTrue()
        assertThat(loaded.project).isEqualTo(p)
    }

    @Test
    fun `an attachment's offset and length are bounded`() {
        val p = abc().addCaptionFor(ClipId("c"), "on c", ids)
        val c = p.caption("on c")

        fun with(a: CaptionAttachment) =
            p.copy(
                tracks =
                    p.tracks.map { t ->
                        t.copy(
                            clips =
                                t.clips.map {
                                    if (it.id ==
                                        c.id
                                    ) {
                                        c.copy(attachedTo = a)
                                    } else {
                                        it
                                    }
                                },
                        )
                    },
            )
        assertThat(
            with(CaptionAttachment(ClipId("c"), offsetMs = -1, whole = false)).validate().map { it.rule },
        ).contains(Violation.Rule.ANCHOR)
        assertThat(
            with(CaptionAttachment(ClipId("c"), whole = false, lengthMs = 0)).validate().map { it.rule },
        ).contains(Violation.Rule.ANCHOR)
    }
}
