package com.itsluminous.clearmovie.work

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.voice.PackDownloadError
import com.itsluminous.clearmovie.data.voice.PackDownloadState
import com.itsluminous.clearmovie.data.voice.PackProgress
import org.junit.Test
import java.util.UUID

/** What the voice pack download worker's state means for the screens (cm-voice-pack-download). */
class VoicePackDownloadWorkerTest {
    private val id = UUID.randomUUID()

    private fun info(
        state: WorkInfo.State,
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
    ) = WorkInfo(id, state, emptySet(), output, progress)

    @Test
    fun `each work state maps to what the dialog shows`() {
        assertThat(VoicePackDownloadWorker.stateOf(null)).isEqualTo(PackDownloadState.Idle)
        assertThat(VoicePackDownloadWorker.stateOf(info(WorkInfo.State.ENQUEUED))).isEqualTo(PackDownloadState.Waiting(id.toString()))
        val p = PackProgress(5, 10, checking = true)
        assertThat(VoicePackDownloadWorker.stateOf(info(WorkInfo.State.RUNNING, progress = VoicePackDownloadWorker.progressData(p))))
            .isEqualTo(PackDownloadState.Running(id.toString(), p))
        assertThat(VoicePackDownloadWorker.stateOf(info(WorkInfo.State.SUCCEEDED))).isEqualTo(PackDownloadState.Done(id.toString()))
        val failed =
            workDataOf(
                VoicePackDownloadWorker.KEY_FAILURE to PackDownloadError.NO_SPACE.name,
                VoicePackDownloadWorker.KEY_MISSING_BYTES to 7L,
            )
        assertThat(VoicePackDownloadWorker.stateOf(info(WorkInfo.State.FAILED, output = failed)))
            .isEqualTo(PackDownloadState.Failed(id.toString(), PackDownloadError.NO_SPACE, 7))
        assertThat(VoicePackDownloadWorker.stateOf(info(WorkInfo.State.FAILED)))
            .isEqualTo(PackDownloadState.Failed(id.toString(), PackDownloadError.FAILED, 0))
        assertThat(VoicePackDownloadWorker.stateOf(info(WorkInfo.State.CANCELLED))).isEqualTo(PackDownloadState.Idle)
    }

    @Test
    fun `progress is in whole percent`() {
        assertThat(VoicePackDownloadWorker.percent(PackProgress(0, 0))).isEqualTo(0)
        assertThat(VoicePackDownloadWorker.percent(PackProgress(1, 3))).isEqualTo(33)
        assertThat(VoicePackDownloadWorker.percent(PackProgress(3, 3))).isEqualTo(100)
    }
}
