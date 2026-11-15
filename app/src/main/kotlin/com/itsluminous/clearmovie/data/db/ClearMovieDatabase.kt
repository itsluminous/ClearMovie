package com.itsluminous.clearmovie.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

/** A row of "My videos", without the model JSON. */
data class ProjectSummaryRow(
    val id: String,
    val name: String?,
    val presetId: String?,
    val thumbPath: String?,
    val updatedAt: Long,
)

@Dao
interface ProjectDao {
    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT updatedAt FROM projects WHERE id = :id")
    suspend fun updatedAt(id: String): Long?

    @Query("SELECT id, name, presetId, thumbPath, updatedAt FROM projects ORDER BY updatedAt DESC, id")
    fun summaries(): Flow<List<ProjectSummaryRow>>

    @Query("SELECT id, name, presetId, thumbPath, updatedAt FROM projects ORDER BY updatedAt DESC, id")
    suspend fun summaryList(): List<ProjectSummaryRow>

    @Query("UPDATE projects SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(
        id: String,
        name: String?,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM session WHERE id = 0")
    suspend fun get(): SessionEntity?

    @Upsert
    suspend fun set(session: SessionEntity)

    @Query("DELETE FROM session")
    suspend fun clear()
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM preset_bindings WHERE presetId = :presetId")
    suspend fun bindings(presetId: String): List<PresetBindingEntity>

    @Query("SELECT * FROM preset_bindings WHERE presetId = :presetId AND slot = :slot")
    suspend fun binding(
        presetId: String,
        slot: String,
    ): PresetBindingEntity?

    @Upsert
    suspend fun upsertBinding(binding: PresetBindingEntity)

    @Query("DELETE FROM preset_bindings WHERE presetId = :presetId AND slot = :slot")
    suspend fun deleteBinding(
        presetId: String,
        slot: String,
    )

    @Query("SELECT * FROM preset_definitions ORDER BY presetId")
    suspend fun definitions(): List<PresetDefinitionEntity>

    @Query("SELECT * FROM preset_definitions WHERE presetId = :presetId")
    suspend fun definition(presetId: String): PresetDefinitionEntity?

    @Upsert
    suspend fun upsertDefinition(definition: PresetDefinitionEntity)

    @Query("SELECT DISTINCT presetId FROM preset_bindings ORDER BY presetId")
    suspend fun boundPresetIds(): List<String>
}

@Dao
interface MediaGrantDao {
    @Upsert
    suspend fun add(grant: MediaGrantEntity)

    @Query("SELECT uri FROM media_grants WHERE projectId = :projectId")
    suspend fun urisFor(projectId: String): List<String>

    @Query("DELETE FROM media_grants WHERE projectId = :projectId")
    suspend fun deleteFor(projectId: String)

    @Query("SELECT COUNT(*) FROM media_grants WHERE uri = :uri")
    suspend fun usersOf(uri: String): Int

    @Query("SELECT COUNT(*) FROM media_grants WHERE uri = :uri AND projectId = :projectId")
    suspend fun usersOf(
        uri: String,
        projectId: String,
    ): Int

    @Query("DELETE FROM media_grants WHERE uri = :uri AND projectId = :projectId")
    suspend fun remove(
        uri: String,
        projectId: String,
    )
}

/**
 * The app database (ARCHITECTURE section 10). Schemas are exported to
 * `app/schemas/` and every version step needs an entry in [MIGRATIONS] with
 * a `room-testing` test; destructive fallback is never enabled, so a missing
 * migration fails loudly instead of wiping the user's videos.
 */
@Database(
    entities = [
        ProjectEntity::class,
        SessionEntity::class,
        PresetBindingEntity::class,
        PresetDefinitionEntity::class,
        MediaGrantEntity::class,
    ],
    version = ClearMovieDatabase.VERSION,
    exportSchema = true,
)
abstract class ClearMovieDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao

    abstract fun session(): SessionDao

    abstract fun presets(): PresetDao

    abstract fun grants(): MediaGrantDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "clearmovie.db"

        /** Version n to n + 1, for every n from 1 to [VERSION] - 1. Version 1 is the first. */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
