package com.itsluminous.clearmovie.ui.export

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.work.ExportFailure
import org.junit.Test
import java.util.UUID

class ExportStepsTest {
    private val project = ProjectId("p1")
    private val choose = ExportStep.Choose(sizes = mapOf(ExportQuality.GOOD to 1L))
    private val id = UUID.randomUUID()

    private fun work(
        state: ExportWork.State,
        projectId: String? = "p1",
        workId: UUID = id,
        percent: Int = 0,
        startedAt: Long = 0,
    ) = ExportWork(workId, state, projectId, percent = percent, startedAt = startedAt)

    @Test
    fun `nothing running and nothing watched shows the sizes`() {
        assertThat(ExportSteps.step(emptyList(), null, project, choose, 0)).isEqualTo(choose)
        // An old finished export the sheet never watched is not shown again.
        assertThat(ExportSteps.step(listOf(work(ExportWork.State.SUCCEEDED)), null, project, choose, 0)).isEqualTo(choose)
    }

    @Test
    fun `a running export of this video shows its progress and time left`() {
        val step = ExportSteps.step(listOf(work(ExportWork.State.RUNNING, percent = 25, startedAt = 1_000)), null, project, choose, 61_000)
        assertThat(step).isEqualTo(ExportStep.Running(25, 3, restarted = false))
        val waiting = ExportSteps.step(listOf(work(ExportWork.State.WAITING)), id, project, choose, 0)
        assertThat(waiting).isEqualTo(ExportStep.Running(0, null, restarted = false))
    }

    @Test
    fun `an export just asked for counts as running at zero`() {
        assertThat(ExportSteps.step(emptyList(), id, project, choose, 0)).isEqualTo(ExportStep.Running(0, null, false))
    }

    @Test
    fun `another video's export offers to stop it, and once agreed shows the sizes`() {
        val other = listOf(work(ExportWork.State.RUNNING, projectId = "p2"))
        assertThat(ExportSteps.step(other, null, project, choose, 0)).isEqualTo(ExportStep.OtherRunning)
        assertThat(ExportSteps.step(other, null, project, choose, 0, replaceAgreed = true)).isEqualTo(choose)
    }

    @Test
    fun `the watched export's result shows`() {
        val done =
            work(
                ExportWork.State.SUCCEEDED,
            ).copy(uri = "content://media/external_primary/video/media/5", displayName = "A.mp4", sizeBytes = 9)
        assertThat(ExportSteps.step(listOf(done), id, project, choose, 0))
            .isEqualTo(ExportStep.Done("content://media/external_primary/video/media/5", "A.mp4", 9))
        val failed = work(ExportWork.State.FAILED).copy(failure = "NO_SPACE", missingBytes = 7)
        assertThat(ExportSteps.step(listOf(failed), id, project, choose, 0)).isEqualTo(ExportStep.Failed(ExportFailure.NO_SPACE, 7))
        val unknown = work(ExportWork.State.FAILED).copy(failure = "SOMETHING_NEW")
        assertThat(ExportSteps.step(listOf(unknown), id, project, choose, 0)).isEqualTo(ExportStep.Failed(ExportFailure.FAILED))
        val noUri = work(ExportWork.State.SUCCEEDED)
        assertThat(ExportSteps.step(listOf(noUri), id, project, choose, 0)).isEqualTo(ExportStep.Failed(ExportFailure.FAILED))
        assertThat(
            ExportSteps.step(listOf(work(ExportWork.State.CANCELLED)), id, project, choose, 0),
        ).isEqualTo(choose.copy(stopped = true))
    }

    @Test
    fun `sizes say which choices do not fit`() {
        val step = ExportSteps.choose(durationUs = 60_000_000, freeBytes = 30_000_000)
        assertThat(step.sizes!!.keys).containsExactlyElementsIn(ExportQuality.entries)
        // Small (about 20 MB) fits directly; Good and Best do not.
        assertThat(step.shortBy.keys).containsExactly(ExportQuality.GOOD, ExportQuality.BEST)
        assertThat(
            step.shortBy.getValue(ExportQuality.GOOD),
        ).isEqualTo((ExportQuality.GOOD.estimatedBytes(60_000_000) * 1.2).toLong() - 30_000_000)
        assertThat(ExportSteps.choose(null, 5)).isEqualTo(ExportStep.Choose())
        assertThat(ExportSteps.choose(1_000_000, null).shortBy).isEmpty()
    }
}
