package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
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
        for (name in listOf("rich-v1.json", "blank-v1.json")) {
            val text = fixture(name)
            assertThat(codec.encode(ok(text).project)).isEqualTo(text)
        }
    }

    @Test
    fun `the rich fixture is the project the test builds`() {
        // If the model changes shape, this fails before the byte comparison
        // hides why; regenerate the fixture only for an intended change.
        assertThat(ok(fixture("rich-v1.json")).project).isEqualTo(Fixtures.richProject())
        assertThat(codec.encode(Fixtures.richProject())).isEqualTo(fixture("rich-v1.json"))
    }

    @Test
    fun `optional fields decode to their defaults when absent, and unknown ones are ignored`() {
        val result = ok(fixture("minimal-v1.json"))
        assertThat(result.fromVersion).isEqualTo(1)
        val p = result.project
        assertThat(p.name).isNull()
        assertThat(p.presetId).isNull()
        assertThat(p.ducking).isEqualTo(DuckingSpec())
        assertThat(p.captionDefaults).isEqualTo(CaptionStyle())
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
        assertThat(ProjectMigrations().oldestVersion).isEqualTo(ProjectMigrations.CURRENT_VERSION)
    }

    @Test
    fun `a project from a newer app is refused`() {
        val newer = fixture("blank-v1.json").replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
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
        // A frame moved off its span, and ducking out of range: readable, but invalid.
        assertThat(
            failed(
                fixture(
                    "rich-v1.json",
                ).replace(
                    "\"startMs\": 6500,\n                    \"durationMs\": 27933",
                    "\"startMs\": 0,\n                    \"durationMs\": 27933",
                ),
            ),
        ).isEqualTo(ProjectDecodeResult.Reason.INVALID)
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
