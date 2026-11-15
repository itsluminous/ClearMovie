package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Ids are short opaque tokens: letters, digits, `_` and `-`, at most 64 characters. */
internal val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

internal fun requireValidId(
    kind: String,
    value: String,
) {
    require(ID_PATTERN.matches(value)) { "Invalid $kind id" }
}

@JvmInline
@Serializable
value class ProjectId(
    val value: String,
) {
    init {
        requireValidId("project", value)
    }
}

@JvmInline
@Serializable
value class TrackId(
    val value: String,
) {
    init {
        requireValidId("track", value)
    }
}

@JvmInline
@Serializable
value class ClipId(
    val value: String,
) {
    init {
        requireValidId("clip", value)
    }
}

/**
 * A preset id doubles as a folder name (`filesDir/presets/<id>/`), so it is
 * stricter than the other ids: lower-case letters, digits, `_` and `-`, starting
 * with a letter or digit. That rules out `.`, `..`, separators and hidden names.
 */
@JvmInline
@Serializable
value class PresetId(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Invalid preset id" }
    }

    companion object {
        val PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    }
}

/** Where new track and clip ids come from. Tests pass a deterministic source. */
fun interface IdSource {
    fun next(): String

    fun trackId(): TrackId = TrackId(next())

    fun clipId(): ClipId = ClipId(next())
}

/** Random ids for the app. */
object RandomIds : IdSource {
    override fun next(): String = UUID.randomUUID().toString()
}

/** `id1`, `id2`, ...: deterministic ids for tests and golden fixtures. */
class SequentialIds(
    private val prefix: String = "id",
) : IdSource {
    private var n = 0

    override fun next(): String = "$prefix${++n}"
}
