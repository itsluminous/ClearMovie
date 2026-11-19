package com.itsluminous.clearmovie.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One video. [modelJson] is the whole `Project` as `ProjectCodec` JSON, and
 * [schemaVersion] repeats the version written inside it, so rows that need a
 * model migration can be found without parsing. [name] is null for an
 * unnamed draft. [updatedAt] is the time of the last saved edit.
 */
@Entity(tableName = "projects", indices = [Index("updatedAt")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String?,
    val presetId: String?,
    val schemaVersion: Int,
    val modelJson: String,
    val thumbPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * The one session row (id 0): the project open in the editor, and when the
 * user last saw it there (opened, or the app went to the background). A row
 * that is still set at launch means the app was stopped while editing (a
 * crash, a low-memory kill or a swipe from recents), so the editor reopens at
 * that project; an edit newer than [lastViewedAt] is what makes it say "We
 * brought back your last video".
 */
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val id: Int = SINGLETON,
    val openProjectId: String?,
    val lastViewedAt: Long,
) {
    companion object {
        const val SINGLETON = 0
    }
}

/**
 * A preset file copied into app storage at setup (ARCHITECTURE section 8).
 * [fileName] is the copy's name inside `filesDir/presets/<presetId>/`.
 * [keyColor] is set only when the frame's measured green differed from the
 * preset's by more than the limit, and then replaces it for this binding;
 * [measuredKeyColor] and [keyDeltaE] record what was measured.
 */
@Entity(tableName = "preset_bindings", primaryKeys = ["presetId", "slot"])
data class PresetBindingEntity(
    val presetId: String,
    val slot: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val originalName: String?,
    val mime: String?,
    val durationUs: Long,
    val loudnessLufs: Double?,
    val keyColor: String?,
    val measuredKeyColor: String?,
    val keyDeltaE: Double?,
    val boundAt: Long,
)

/**
 * A preset definition (`preset.json`) in app storage: the user's own file
 * chosen at setup, or one the app wrote when the user made or edited a
 * preset. With a bundled preset's id it overrides that preset (and deleting
 * the row puts the bundled one back); any other id is a preset the user made.
 *
 * [createdAt] orders the user's presets (0 for rows from before it existed).
 * [retiredAt] is set when the user deleted a preset that videos still use:
 * it is no longer offered, but its definition and files stay until no video
 * uses it, so those videos keep working.
 */
@Entity(tableName = "preset_definitions")
data class PresetDefinitionEntity(
    @PrimaryKey val presetId: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val originalName: String?,
    val boundAt: Long,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    val retiredAt: Long? = null,
)

/**
 * A link whose read permission the app has persisted for a project, so the
 * permission can be released when no project uses the link any more
 * (Android caps persisted grants per app).
 */
@Entity(tableName = "media_grants", primaryKeys = ["uri", "projectId"], indices = [Index("projectId")])
data class MediaGrantEntity(
    @ColumnInfo(name = "uri") val uri: String,
    val projectId: String,
)
