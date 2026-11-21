package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.byRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

/** Versioned, canonical project JSON: golden round trips, defaults, migration and refusals. */
class ProjectCodecTest {
    private val codec = ProjectCodec()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/project/$name")) { "missing fixture $name" }.readText()

    private fun ok(text: String): ProjectDecodeResult.Ok {
        val result = codec.decode(text)
        assertThat(result).isInstanceOf(ProjectDecodeResult.Ok::class.java)
        return result as ProjectDecodeResult.Ok
    }

    private fun failed(
        text: String,
        c: ProjectCodec = codec,
    ): ProjectDecodeResult.Reason {
        val result = c.decode(text)
        assertThat(result).isInstanceOf(ProjectDecodeResult.Failed::class.java)
        return (result as ProjectDecodeResult.Failed).reason
    }

    @Test
    fun `golden fixtures round-trip byte for byte`() {
        for (name in listOf("rich-v4.json", "blank-v4.json")) {
            val text = fixture(name)
            assertThat(codec.encode(ok(text).project)).isEqualTo(text)
        }
    }

    @Test
    fun `a schema 2 project reads as landscape, never asks for a shape, and saves at the current schema`() {
        // cm-aspect-ratio: schema 3 adds the video's shape. Every older video was 16:9.
        for (name in listOf("rich", "blank")) {
            val result = ok(fixture("$name-v2.json"))
            assertThat(result.fromVersion).isEqualTo(2)
            assertThat(result.repaired).isFalse()
            assertThat(result.project.shape).isEqualTo(CanvasShape.LANDSCAPE)
            assertThat(result.project.askShape).isFalse()
            assertThat(codec.encode(result.project)).isEqualTo(fixture("$name-v4.json"))
        }
    }

    @Test
    fun `a schema 3 project reads exactly as before and saves as schema 4`() {
        // cm-timeline-handles: schema 4 adds detached sides and a music row's talk level; schema 3 has neither.
        for (name in listOf("rich", "blank")) {
            val result = ok(fixture("$name-v3.json"))
            assertThat(result.fromVersion).isEqualTo(3)
            assertThat(result.repaired).isFalse()
            assertThat(codec.encode(result.project)).isEqualTo(fixture("$name-v4.json"))
            val captions =
                result.project.tracks
                    .flatMap { it.clips }
                    .filterIsInstance<TextClip>()
            assertThat(captions.mapNotNull { it.attachedTo }.none { it.partlyDetached }).isTrue()
            assertThat(result.project.tracks.all { it.lowerToDb == null }).isTrue()
        }
    }

    @Test
    fun `detached sides and a talk level survive a save`() {
        val rich = Fixtures.richProject()
        val music = rich.tracks.first { it.audioRole == AudioRole.MUSIC }
        var project = rich.setTalkLowering(music.id, lower = true, levelDb = -9.5)
        // A caption's start dragged, and the preset frame's end.
        val caption =
            project.tracks
                .flatMap { it.clips }
                .filterIsInstance<TextClip>()
                .first { it.attachedTo?.whole == true }
        project = project.resizeClip(caption.id, ResizeEdge.START, caption.startMs + 100)
        val frame = project.byRole(PresetRole.FRAME).clips.single()
        project = project.resizeClip(frame.id, ResizeEdge.END, frame.endMs - 100)
        assertThat(project.validate()).isEmpty()
        val text = codec.encode(project)
        assertThat(text).contains("\"lowerToDb\": -9.5")
        assertThat(text).contains("\"detachedStartMs\"")
        assertThat(text).contains("\"detachedEndMs\"")
        assertThat(ok(text).project).isEqualTo(project)
        // A default (null) is left out, so a project without one is byte for byte what schema 3 wrote.
        assertThat(codec.encode(rich)).doesNotContain("lowerToDb")
        assertThat(codec.encode(rich)).doesNotContain("detached")
    }

    @Test
    fun `an upright or square video keeps its shape and its question through a save`() {
        for (shape in CanvasShape.entries) {
            val project = Fixtures.richProject().setShape(shape).copy(askShape = true)
            val back = ok(codec.encode(project)).project
            assertThat(back.shape).isEqualTo(shape)
            assertThat(back.askShape).isTrue()
            assertThat(back).isEqualTo(project)
        }
        assertThat(codec.encode(Fixtures.richProject().setShape(CanvasShape.PORTRAIT))).contains("\"shape\": \"portrait\"")
        // A shape this app does not know is refused, never read as some other shape.
        val unknown = fixture("blank-v4.json").replace("\"shape\": \"landscape\"", "\"shape\": \"cinema\"")
        assertThat(failed(unknown)).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
    }

    @Test
    fun `the rich fixture is the project the test builds`() {
        // If the model changes shape, this fails before the byte comparison
        // hides why; regenerate the fixture only for an intended change.
        assertThat(ok(fixture("rich-v4.json")).project).isEqualTo(Fixtures.richProject())
        assertThat(codec.encode(Fixtures.richProject())).isEqualTo(fixture("rich-v4.json"))
    }

    @Test
    fun `a schema 1 project reads as before, its captions free, and saves at the current schema`() {
        // cm-captions: schema 2 adds TextClip.attachedTo. A schema 1 caption has none, so it keeps its own times.
        for (name in listOf("rich-v1.json", "blank-v1.json")) {
            val result = ok(fixture(name))
            assertThat(result.fromVersion).isEqualTo(1)
            assertThat(result.repaired).isFalse()
            assertThat(codec.encode(result.project)).startsWith("{\n    \"schemaVersion\": 4,")
        }
        val rich = ok(fixture("rich-v1.json")).project
        assertThat(rich).isEqualTo(Fixtures.richProjectV1())
        assertThat(
            rich.tracks
                .flatMap { it.clips }
                .filterIsInstance<TextClip>()
                .map { it.attachedTo },
        ).containsExactly(null, null)
        // Schema 3 written back, then read: the same project.
        assertThat(ok(codec.encode(rich)).project).isEqualTo(rich)
        // The app's chain starts at schema 1.
        assertThat(ProjectMigrations().oldestVersion).isEqualTo(1)
        assertThat(ProjectMigrations.CURRENT_VERSION).isEqualTo(4)
    }

    @Test
    fun `optional fields decode to their defaults when absent, and unknown ones are ignored`() {
        val result = ok(fixture("minimal-v1.json"))
        assertThat(result.fromVersion).isEqualTo(1)
        val p = result.project
        assertThat(p.name).isNull()
        assertThat(p.presetId).isNull()
        assertThat(p.ducking).isEqualTo(DuckingSpec())
        assertThat(p.captionDefaults).isEqualTo(CaptionStyle.STANDARD)
        assertThat(p.transitionForAll).isNull()
        val video = p.requireClip(ClipId("v")) as VideoClip
        assertThat(video.trimStartMs to video.trimEndMs).isEqualTo(0L to 5000L)
        assertThat(video.speed).isEqualTo(Speed.X1)
        assertThat(video.loops).isEqualTo(1)
        assertThat(video.transitionToNext).isNull()
        assertThat(video.animations).isEqualTo(Animations.NONE)
        assertThat(video.media).isEqualTo(MediaRef.Copied("v.mp4"))
        val photo = p.requireClip(ClipId("p")) as PhotoClip
        assertThat(photo.fit).isEqualTo(Fit.FILL_CROP)
        assertThat(photo.rotation).isEqualTo(Rotation.R0)
        val track = p.requireTrack(TrackId("t"))
        assertThat(track.muted).isFalse()
        assertThat(track.enabled).isTrue()
        assertThat((track.clips.single() as TextClip).style).isEqualTo(CaptionStyleOverride.NONE)
    }

    @Test
    fun `an older schema is migrated step by step`() {
        // Schema 1 is the first shipped one, so this uses a test-only schema 0
        // in which rows were "rows" and their clips "items".
        val renames: (JsonObject) -> JsonObject = { old ->
            val rows =
                old.getValue("rows").jsonArray.map { row ->
                    val o = row.jsonObject
                    JsonObject(o - "items" + ("clips" to o.getValue("items")))
                }
            JsonObject(old - "rows" + ("tracks" to JsonArray(rows)))
        }
        val migrating = ProjectCodec(ProjectMigrations(currentVersion = 1, steps = mapOf(0 to renames)))
        val result = migrating.decode(fixture("old-v0.json"))
        assertThat(result).isInstanceOf(ProjectDecodeResult.Ok::class.java)
        result as ProjectDecodeResult.Ok
        assertThat(result.fromVersion).isEqualTo(0)
        assertThat(
            result.project.main.clips
                .single()
                .durationMs,
        ).isEqualTo(2000)
        // Saving writes the current version.
        assertThat(migrating.encode(result.project)).contains("\"schemaVersion\": 1")
        // Without the step, the old file is refused rather than misread.
        assertThat(failed(fixture("old-v0.json"))).isEqualTo(ProjectDecodeResult.Reason.UNSUPPORTED_VERSION)
        // A damaged old file makes the step throw: that is a refusal, not a crash.
        assertThat(failed("{\"schemaVersion\": 0, \"id\": \"x\", \"stacking\": []}", migrating))
            .isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
    }

    @Test
    fun `a chain of steps runs in order`() {
        val calls = mutableListOf<Int>()
        val m =
            ProjectMigrations(
                currentVersion = 3,
                steps =
                    (0..2).associateWith { v ->
                        { o: JsonObject ->
                            calls += v
                            o
                        }
                    },
            )
        m.upgrade(JsonObject(emptyMap()), 0)
        assertThat(calls).containsExactly(0, 1, 2).inOrder()
        assertThrows(IllegalArgumentException::class.java) { m.upgrade(JsonObject(emptyMap()), 4) }
        assertThrows(IllegalArgumentException::class.java) { ProjectMigrations(currentVersion = 3, steps = mapOf(0 to { it })) }
        assertThat(ProjectMigrations().oldestVersion).isEqualTo(1)
    }

    @Test
    fun `stale derived values are worked out again on load, and flagged`() {
        // A frame whose span an older version worked out differently.
        val rich = fixture("rich-v1.json")
        val from = "\"startMs\": 6500,\n                    \"durationMs\": 27933"
        val to = "\"startMs\": 0,\n                    \"durationMs\": 27933"
        val stale = rich.replace(from, to)
        assertThat(stale).isNotEqualTo(rich)
        val result = ok(stale)
        assertThat(result.repaired).isTrue()
        assertThat(result.project).isEqualTo(Fixtures.richProjectV1())
        assertThat(ok(rich).repaired).isFalse()
    }

    @Test
    fun `loading never moves, adds or drops the user's items`() {
        val blank = fixture("blank-v1.json")
        // A caption stored on MAIN is refused, not quietly dropped.
        val caption = "{\"type\": \"text\", \"id\": \"t9\", \"startMs\": 0, \"durationMs\": 1000, \"text\": \"x\"}"
        val onMain =
            blank.replace(
                "\"type\": \"main\",\n            \"clips\": []",
                "\"type\": \"main\",\n            \"clips\": [$caption]",
            )
        assertThat(onMain).isNotEqualTo(blank)
        assertThat(failed(onMain)).isEqualTo(ProjectDecodeResult.Reason.INVALID)
        // A stale frame that would now cover the user's overlay is refused rather than moving the overlay.
        val p = Fixtures.presetProject()
        val frameRow = p.byRole(PresetRole.FRAME)
        val user = Fixtures.overlay("user", 7000, 1000)
        val stale =
            p.copy(
                tracks =
                    p.tracks.map {
                        if (it.id ==
                            frameRow.id
                        ) {
                            it.copy(clips = listOf(user) + it.clips.map { c -> c.withStartMs(9000) })
                        } else {
                            it
                        }
                    },
            )
        assertThat(failed(codec.encode(stale))).isEqualTo(ProjectDecodeResult.Reason.INVALID)
    }

    @Test(timeout = 20_000)
    fun `repairing a hostile file stays cheap`() {
        // Thousands of overlapping overlays next to an anchored frame: refused quickly.
        val p = Fixtures.presetProject()
        val frameRow = p.byRole(PresetRole.FRAME)
        val crowd = (1..3000).map { Fixtures.overlay("o$it", 6500, 1000) }
        val hostile =
            p.copy(
                tracks =
                    p.tracks.map {
                        if (it.id ==
                            frameRow.id
                        ) {
                            it.copy(clips = it.clips.map { c -> c.withStartMs(0) } + crowd)
                        } else {
                            it
                        }
                    },
            )
        val text = codec.encode(hostile)
        assertThat(text.length).isLessThan(ProjectCodec.MAX_CHARS)
        assertThat(failed(text)).isEqualTo(ProjectDecodeResult.Reason.INVALID)
        // And thousands of anchored clips are worked out in one pass.
        val many = (1..3000).map { (frameRow.clips.single() as OverlayClip).copy(id = ClipId("f$it"), startMs = it.toLong()) }
        val anchored = p.copy(tracks = p.tracks.map { if (it.id == frameRow.id) it.copy(clips = many) else it })
        assertThat(failed(codec.encode(anchored))).isEqualTo(ProjectDecodeResult.Reason.INVALID)
    }

    @Test
    fun `a project from a newer app is refused`() {
        val newer = fixture("blank-v4.json").replace("\"schemaVersion\": 4", "\"schemaVersion\": 5")
        assertThat(failed(newer)).isEqualTo(ProjectDecodeResult.Reason.NEWER_VERSION)
    }

    @Test
    fun `malformed, oversized and invalid projects are refused with a reason`() {
        val blank = fixture("blank-v1.json")
        assertThat(failed("not json")).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        assertThat(failed("[1, 2]")).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        assertThat(failed("{}")).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        assertThat(failed("{\"schemaVersion\": \"1\"}")).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        assertThat(failed("{\"schemaVersion\": 1}")).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        assertThat(failed(" ".repeat(ProjectCodec.MAX_CHARS + 1))).isEqualTo(ProjectDecodeResult.Reason.TOO_LARGE)
        // Unknown clip type.
        assertThat(failed(fixture("minimal-v1.json").replace("\"type\": \"photo\"", "\"type\": \"hologram\"")))
            .isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        // Well-formed but breaking an invariant: the stacking order lost a row.
        assertThat(failed(blank.replace("        \"b1\",\n", ""))).isEqualTo(ProjectDecodeResult.Reason.INVALID)
        // Deeply nested input does not overflow the stack.
        val deep = "{\"schemaVersion\": 1, \"x\": " + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertThat(failed(deep)).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
    }

    @Test
    fun `media references that could reach outside the project are refused`() {
        val minimal = fixture("minimal-v1.json")
        val badPaths =
            listOf("../secret.mp4", "/data/data/x.db", "a/../../b.mp4", ".hidden", "a//b.mp4", "a\\\\b.mp4", "", "a/b/c/d/e.mp4", "a b.mp4")
        for (bad in badPaths) {
            val text = minimal.replace("\"relPath\": \"v.mp4\"", "\"relPath\": \"$bad\"")
            assertThat(failed(text)).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        }
        val badUris =
            listOf(
                "file:///sdcard/DCIM/x.jpg",
                "content:///no-authority",
                "content://",
                "http://example.com/x.jpg",
                "content://media/x y",
                "content://media/" + "x".repeat(3000),
            )
        for (bad in badUris) {
            val text = minimal.replace("content://media/external/images/media/1", bad)
            assertThat(failed(text)).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        }
        // Ids are short tokens.
        assertThat(failed(minimal.replace("\"id\": \"p\"", "\"id\": \"../p\""))).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        assertThat(
            failed(minimal.replace("\"id\": \"min\"", "\"id\": \"${"x".repeat(65)}\"")),
        ).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
        // An out-of-range value the model can't work out again: invalid.
        val blank = fixture("blank-v1.json")
        assertThat(failed(blank.replace("\"attackMs\": 300", "\"attackMs\": -5"))).isEqualTo(ProjectDecodeResult.Reason.INVALID)
        // Bad colour.
        assertThat(failed(fixture("rich-v1.json").replace("\"#202040\"", "\"blue\""))).isEqualTo(ProjectDecodeResult.Reason.MALFORMED)
    }

    @Test
    fun `media reference checks`() {
        assertThat(MediaRef.isSafeRelativePath("projects_1/media/x-1.mp4")).isTrue()
        assertThat(MediaRef.isSafeRelativePath("x".repeat(256))).isFalse()
        assertThat(MediaRef.isSafeContentUri("content://media/picker/0/com.android.providers.media.photopicker/media/1000")).isTrue()
        assertThrows(IllegalArgumentException::class.java) { MediaRef.Copied("../x") }
        assertThrows(IllegalArgumentException::class.java) { MediaRef.Store("file:///x") }
        assertThrows(IllegalArgumentException::class.java) { PresetId("Event") }
        assertThrows(IllegalArgumentException::class.java) { ClipId("") }
        assertThrows(IllegalArgumentException::class.java) { TrackId("a b") }
        assertThrows(IllegalArgumentException::class.java) { ProjectId("x\n") }
    }

    @Test
    fun `the nesting guard ignores brackets inside strings`() {
        assertThat(JsonGuard.nestsDeeperThan("{\"a\": \"" + "[".repeat(500) + "\\\"\"}")).isFalse()
        assertThat(JsonGuard.nestsDeeperThan("[[[]]]", max = 2)).isTrue()
        assertThat(JsonGuard.nestsDeeperThan("[[[]]]", max = 3)).isFalse()
    }

    @Test
    fun `colours read and write as hex`() {
        assertThat(ArgbColor.parse("#00d922")!!.toString()).isEqualTo("#00D922")
        assertThat(ArgbColor.parse("#99000000")!!.toString()).isEqualTo("#99000000")
        assertThat(ArgbColor.parse("#99000000")!!.alpha).isEqualTo(0x99)
        assertThat(ArgbColor.parse("#000000")).isEqualTo(ArgbColor.BLACK)
        assertThat(ArgbColor.parse("00D922")).isNull()
        assertThat(ArgbColor.parse("#00D92")).isNull()
        assertThat(ArgbColor.parse("#GG0000")).isNull()
        assertThrows(IllegalArgumentException::class.java) { ArgbColor(-1) }
    }
}
