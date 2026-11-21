package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Upgrades stored project JSON from older schema versions, one version at a
 * time: `steps[n]` turns version n into version n + 1 (ARCHITECTURE section
 * 6.3). The app's own steps are [APP_STEPS].
 */
class ProjectMigrations(
    val currentVersion: Int = CURRENT_VERSION,
    private val steps: Map<Int, (JsonObject) -> JsonObject> = APP_STEPS,
    val oldestVersion: Int = (steps.keys.minOrNull() ?: currentVersion).coerceAtMost(currentVersion),
) {
    init {
        require((oldestVersion until currentVersion).all { it in steps }) { "every version from the oldest needs a step" }
    }

    /** [json] at [version], brought to [currentVersion]. */
    fun upgrade(
        json: JsonObject,
        version: Int,
    ): JsonObject {
        require(version in oldestVersion..currentVersion) { "no migration from version $version" }
        var result = json
        for (v in version until currentVersion) result = steps.getValue(v)(result)
        return result
    }

    companion object {
        /**
         * 2 (cm-captions): a caption may be attached to a photo or video
         * (`TextClip.attachedTo`). 3 (cm-aspect-ratio): the video's `shape`,
         * and `askShape`.
         */
        const val CURRENT_VERSION = 3

        /**
         * 1 to 2: nothing to rewrite. A version 1 caption has no `attachedTo`,
         * so it reads as a free caption and keeps its own times, exactly as
         * version 1 played it.
         *
         * 2 to 3: every older video was 16:9, so it is written as landscape
         * (and never asks for a shape). An older app refuses a version 3
         * file rather than show an upright video sideways.
         */
        val APP_STEPS: Map<Int, (JsonObject) -> JsonObject> =
            mapOf(
                1 to { old -> old },
                2 to { old -> JsonObject(old + mapOf("shape" to JsonPrimitive("landscape"), "askShape" to JsonPrimitive(false))) },
            )
    }
}

/** The outcome of reading stored project JSON. */
sealed interface ProjectDecodeResult {
    data class Ok(
        val project: Project,
        /** The version the JSON was written in, before any migration. */
        val fromVersion: Int,
        /**
         * True when values the model derives (anchored spans, fitted
         * transitions, animations, fades) had to be worked out again; the
         * caller should save the project back.
         */
        val repaired: Boolean = false,
    ) : ProjectDecodeResult

    data class Failed(
        val reason: Reason,
        val detail: String,
    ) : ProjectDecodeResult

    enum class Reason {
        /** Larger than any real project. */
        TOO_LARGE,

        /** Not JSON, or not shaped like a project. */
        MALFORMED,

        /** Written by a newer version of the app. */
        NEWER_VERSION,

        /** An old version with no migration path. */
        UNSUPPORTED_VERSION,

        /** Readable, but breaks an invariant (see `validate`). */
        INVALID,
    }
}

/**
 * Project JSON (stored by cm-storage in Room). The form is canonical: keys in
 * declaration order, every value written (only nulls are left out),
 * four-space indentation and `schemaVersion` first, so the same project
 * always gives the same bytes and golden fixtures can be compared byte for
 * byte. When reading, optional fields may be absent and take their
 * defaults, and unknown fields are ignored. Values the model derives are
 * worked out again if they are stale (see [ProjectDecodeResult.Ok.repaired]);
 * a project that is too large, malformed, from a newer app or otherwise
 * invalid is refused with a reason, never half-loaded.
 */
class ProjectCodec(
    private val migrations: ProjectMigrations = ProjectMigrations(),
) {
    fun encode(project: Project): String {
        val body = json.encodeToJsonElement(Project.serializer(), project).jsonObject
        val versioned = JsonObject(linkedMapOf<String, JsonElement>(VERSION_KEY to JsonPrimitive(migrations.currentVersion)) + body)
        return json.encodeToString(JsonElement.serializer(), versioned) + "\n"
    }

    fun decode(text: String): ProjectDecodeResult {
        if (text.length > MAX_CHARS) return failed(ProjectDecodeResult.Reason.TOO_LARGE, "${text.length} characters")
        if (JsonGuard.nestsDeeperThan(text)) return failed(ProjectDecodeResult.Reason.MALFORMED, "nested too deeply")
        val root =
            try {
                json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                return failed(ProjectDecodeResult.Reason.MALFORMED, "not JSON")
            }
        if (root !is JsonObject) return failed(ProjectDecodeResult.Reason.MALFORMED, "not an object")
        val version =
            (root[VERSION_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: return failed(ProjectDecodeResult.Reason.MALFORMED, "no schemaVersion")
        if (version > migrations.currentVersion) return failed(ProjectDecodeResult.Reason.NEWER_VERSION, "version $version")
        if (version < migrations.oldestVersion) return failed(ProjectDecodeResult.Reason.UNSUPPORTED_VERSION, "version $version")
        val upgraded =
            try {
                migrations.upgrade(JsonObject(root - VERSION_KEY), version)
            } catch (e: RuntimeException) {
                // A migration step reads old JSON that may be damaged; whatever it
                // throws is a refusal, never a crash.
                return failed(ProjectDecodeResult.Reason.MALFORMED, "could not upgrade from version $version")
            }
        val project =
            try {
                json.decodeFromJsonElement(Project.serializer(), upgraded)
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException, and so are
                // the checks in the model's constructors (ids, media references).
                return failed(ProjectDecodeResult.Reason.MALFORMED, e.message.orEmpty().take(200))
            }
        val problems = project.validate()
        if (problems.isEmpty()) return ProjectDecodeResult.Ok(project, version)
        // Values the model derives (spans that follow MAIN, fitted transitions,
        // animations and fades) may be stale if an app update changed how they
        // are worked out. Re-deriving them repairs that. The repair never moves,
        // adds or drops a row or clip; anything else that is wrong is refused.
        val repaired =
            try {
                project.normalized(relocate = false).takeIf { it.validate().isEmpty() && it.sameItemsAs(project) }
            } catch (e: RuntimeException) {
                null
            }
        return if (repaired != null) {
            ProjectDecodeResult.Ok(repaired, version, repaired = true)
        } else {
            failed(ProjectDecodeResult.Reason.INVALID, problems.joinToString { it.detail }.take(500))
        }
    }

    // The same rows with the same items: a repair may re-sort a row by time (a caption that follows its
    // item can pass a free one), but never moves an item to another row, adds one or drops one.
    private fun Project.sameItemsAs(other: Project): Boolean =
        tracks.map { t -> t.id to t.clips.map { it.id }.toSet() } == other.tracks.map { t -> t.id to t.clips.map { it.id }.toSet() }

    private fun failed(
        reason: ProjectDecodeResult.Reason,
        detail: String,
    ) = ProjectDecodeResult.Failed(reason, detail)

    companion object {
        /** A project with thousands of clips is still well under this. */
        const val MAX_CHARS = 4 * 1024 * 1024
        private const val VERSION_KEY = "schemaVersion"

        internal val json =
            Json {
                prettyPrint = true
                // Every value is written, defaults included, so a stored project
                // never changes meaning when a default in the code changes later.
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
                classDiscriminator = "type"
            }
    }
}
