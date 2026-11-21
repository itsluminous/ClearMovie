package com.itsluminous.clearmovie.data.files

import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.ProjectId
import java.io.File
import java.io.IOException

/**
 * Where the app keeps files (ARCHITECTURE section 10):
 *
 * - `filesDir/projects/<project id>/media/` for media copied into a project;
 * - `filesDir/presets/<preset id>/` for the preset files bound at setup;
 * - `cacheDir/thumbs/` for thumbnails, which can be made again.
 *
 * Every folder name comes from an id the model has already validated, and
 * every file name from [SafeFileName]. Each path is checked again here to stay
 * under its root after resolving links, so a crafted name can never escape.
 * A copy in progress is written as `<name>.part` beside its final name and
 * renamed when complete; [sweep] removes `.part` files a killed copy left behind.
 */
class AppFiles(
    filesDir: File,
    cacheDir: File,
) {
    val projectsRoot: File = File(filesDir, "projects")
    val presetsRoot: File = File(filesDir, "presets")
    val thumbsRoot: File = File(cacheDir, "thumbs")

    /** There once this install has decided which presets an older version shipped it keeps (cm-home-presets). */
    val presetSeedMarker: File = File(filesDir, "presets-seeded")

    fun projectDir(id: ProjectId): File = inside(projectsRoot, File(projectsRoot, id.value))

    fun projectMediaDir(id: ProjectId): File = inside(projectsRoot, File(projectDir(id), MEDIA))

    /** The file behind [ref] in project [id]. */
    fun copiedFile(
        id: ProjectId,
        ref: MediaRef.Copied,
    ): File = inside(projectMediaDir(id), File(projectMediaDir(id), ref.relPath))

    fun presetDir(id: PresetId): File = inside(presetsRoot, File(presetsRoot, id.value))

    /** A file in preset [id]'s folder; [name] must be one [SafeFileName.SAFE_NAME] segment. */
    fun presetFile(
        id: PresetId,
        name: String,
    ): File {
        require(SafeFileName.SAFE_NAME.matches(name)) { "unsafe preset file name" }
        return inside(presetDir(id), File(presetDir(id), name))
    }

    fun thumbFile(id: ProjectId): File = inside(thumbsRoot, File(thumbsRoot, "${id.value}.jpg"))

    /** Bytes used by the files under [dir], following no links. */
    fun sizeOf(dir: File): Long =
        if (!dir.exists()) {
            0L
        } else {
            dir
                .walkTopDown()
                .onEnter { !isLink(it) }
                .filter { it.isFile }
                .sumOf { it.length() }
        }

    /**
     * Removes `.part` files older than [olderThanMs] (an interrupted copy) from
     * the project and preset folders. Recent ones may belong to a copy that is
     * still running, so they stay.
     */
    fun sweep(
        nowMs: Long,
        olderThanMs: Long = PART_MAX_AGE_MS,
    ): Int {
        var removed = 0
        for (root in listOf(projectsRoot, presetsRoot)) {
            if (!root.exists()) continue
            root
                .walkTopDown()
                .onEnter { !isLink(it) }
                .filter { it.isFile && it.name.endsWith(PART) && nowMs - it.lastModified() > olderThanMs }
                .forEach { if (it.delete()) removed++ }
        }
        return removed
    }

    private fun isLink(file: File): Boolean =
        try {
            java.nio.file.Files
                .isSymbolicLink(file.toPath())
        } catch (e: SecurityException) {
            true
        }

    companion object {
        const val MEDIA = "media"
        const val PART = ".part"
        const val PART_MAX_AGE_MS = 60L * 60 * 1000

        /** The temporary file a copy into [target] writes first. */
        fun partFileFor(target: File): File = File(target.parentFile, target.name + PART)

        /** [file], after checking that it resolves to a path under [root]. */
        fun inside(
            root: File,
            file: File,
        ): File {
            val rootPath =
                try {
                    root.canonicalPath
                } catch (e: IOException) {
                    throw IllegalArgumentException("unusable app folder", e)
                }
            val path =
                try {
                    file.canonicalPath
                } catch (e: IOException) {
                    throw IllegalArgumentException("unusable path", e)
                }
            require(path == rootPath || path.startsWith(rootPath + File.separator)) { "path leaves its folder" }
            return file
        }
    }
}
