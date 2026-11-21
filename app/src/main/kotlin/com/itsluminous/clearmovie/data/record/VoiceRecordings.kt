package com.itsluminous.clearmovie.data.record

import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.VoiceRecordingFiles
import com.itsluminous.clearmovie.domain.model.recordingsInUse
import java.io.File
import java.security.SecureRandom

/**
 * The files of voice recordings (cm-record-voice). A recording is written
 * as `voice-<token>.m4a.part` in the project's own media folder (private to
 * the app, never in the gallery) and renamed when it is complete, so a file
 * by its final name is always whole. A recording no longer used by its
 * video goes when the editor opens or leaves the video ([sweepUnused]),
 * since only then is there no undo that could bring its clip back; deleting
 * the video removes its whole folder.
 */
class VoiceRecordings(
    private val files: AppFiles,
    private val mics: MicSources,
    /** True while an export may still be reading this video's files: nothing is removed then. */
    private val exportBusy: () -> Boolean,
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A recording being made: [part] is written, [ref] names the finished file. */
    data class Pending(
        val ref: MediaRef.Copied,
        val part: File,
        val file: File,
    )

    /** Where a new recording for [projectId] goes. */
    fun newRecording(projectId: ProjectId): Pending {
        val token = (1..TOKEN_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        val ref = MediaRef.Copied(VoiceRecordingFiles.nameFor(token))
        val file = files.copiedFile(projectId, ref)
        return Pending(ref, AppFiles.partFileFor(file), file)
    }

    /** A recorder writing [pending]'s part file from a new microphone source. */
    fun recorder(pending: Pending): VoiceRecorder = VoiceRecorder(mics.create(), pending.part)

    /** Gives a complete recording its final name. False when it could not (the part file is then deleted). */
    fun keep(pending: Pending): Boolean {
        val ok = pending.part.isFile && pending.part.renameTo(pending.file)
        if (!ok) pending.part.delete()
        return ok
    }

    fun discard(pending: Pending) {
        pending.part.delete()
        pending.file.delete()
    }

    /**
     * Removes the recordings in [project]'s folder that it no longer plays,
     * and any recording left half-written (an app killed while recording,
     * so its part file is over [PART_AGE_MS] old).
     * Only files named as recordings are touched; nothing at all while an
     * export is running. Returns how many went.
     */
    fun sweepUnused(project: Project): Int {
        if (exportBusy()) return 0
        val dir = runCatching { files.projectMediaDir(project.id) }.getOrNull() ?: return 0
        val inUse = project.recordingsInUse()
        val names = dir.list().orEmpty()
        var removed = 0
        for (name in names) {
            val recording = VoiceRecordingFiles.isRecording(name)
            val file = File(dir, name)
            // A part file still being written (a recording just started) is young: only an old one is left over.
            val half =
                name.endsWith(AppFiles.PART) &&
                    VoiceRecordingFiles.isRecording(name.removeSuffix(AppFiles.PART)) &&
                    now() - file.lastModified() > PART_AGE_MS
            if ((recording && name !in inUse) || half) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    private companion object {
        const val TOKEN_LENGTH = 16

        /** A recording's part file older than this was left by an app that was stopped while recording. */
        const val PART_AGE_MS = 10L * 60 * 1000
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
