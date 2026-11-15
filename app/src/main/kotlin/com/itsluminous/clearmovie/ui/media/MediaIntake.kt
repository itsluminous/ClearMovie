package com.itsluminous.clearmovie.ui.media

import android.net.Uri
import androidx.compose.runtime.Composable
import com.itsluminous.clearmovie.data.media.CopyBudget
import com.itsluminous.clearmovie.data.media.IntakeItem
import com.itsluminous.clearmovie.data.media.IntakePlan
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.domain.model.ProjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Where an intake of picked or shared photos and videos is. */
sealed interface IntakeState {
    data object Idle : IntakeState

    /** Resolving links (quick, but it touches other apps' providers). */
    data object Planning : IntakeState

    /** The copy budget asks first (ARCHITECTURE section 9). */
    data class Confirm(
        val verdict: CopyBudget.Verdict.NeedsConfirmation,
    ) : IntakeState

    /** The copies cannot fit at all. */
    data class NoSpace(
        val verdict: CopyBudget.Verdict.NotEnoughSpace,
    ) : IntakeState

    data class Copying(
        val copiedBytes: Long,
        val totalBytes: Long,
    ) : IntakeState

    /** Finished. [items] keeps the order given; an item with a null ref could not be kept. */
    data class Done(
        val items: List<IntakeItem>,
    ) : IntakeState

    /** The user turned it down or cancelled; nothing was added and no copy is left. */
    data object Cancelled : IntakeState

    /** The phone filled up during the copy; nothing was added and no copy is left. */
    data object OutOfSpace : IntakeState

    /** The user chose Allow all photos: ask for access, then start again. */
    data object AllowAllRequested : IntakeState
}

/**
 * Runs one intake for the Photo Picker today and for shares from cm-share:
 * [MediaAccess.plan], then the copy budget question when the plan needs it,
 * then [MediaAccess.execute] with progress, and Cancel. It lives in a
 * ViewModel's scope, so a rotation never restarts it; the result is consumed
 * once from [IntakeState.Done].
 */
class MediaIntake(
    private val media: MediaAccess,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow<IntakeState>(IntakeState.Idle)
    val state: StateFlow<IntakeState> = stateFlow.asStateFlow()

    private var job: Job? = null

    /** Which intake is current; a job started for an older one never publishes a state. */
    @Volatile private var current = 0
    private var plan: IntakePlan? = null
    private var projectId: ProjectId? = null

    /**
     * Starts an intake of [uris] into project [project], which must already
     * have a row. An intake still running is cancelled first, and its plan given up.
     */
    fun start(
        project: ProjectId,
        uris: List<Uri>,
        access: MediaAccessState,
    ) {
        val previous = job
        val previousPlan = plan
        val id = ++current
        previous?.cancel()
        plan = null
        projectId = project
        stateFlow.value = IntakeState.Planning
        job =
            scope.launch {
                previous?.join()
                if (previousPlan != null && previous?.isCancelled != true) media.abandon(previousPlan)
                var planned: IntakePlan? = null
                try {
                    planned = media.plan(uris, access)
                    plan = planned
                    when (val verdict = planned.budget) {
                        is CopyBudget.Verdict.NeedsConfirmation -> publish(id, IntakeState.Confirm(verdict))
                        is CopyBudget.Verdict.NotEnoughSpace -> publish(id, IntakeState.NoSpace(verdict))
                        CopyBudget.Verdict.Fits -> copy(id, planned)
                    }
                } catch (e: CancellationException) {
                    // Cancelled while planning: give back any grant the plan persisted.
                    if (planned != null && plan === planned) withContext(NonCancellable) { media.abandon(planned) }
                    publish(id, IntakeState.Cancelled)
                    throw e
                }
            }
    }

    fun copyAnyway() {
        val planned = plan ?: return
        if (stateFlow.value !is IntakeState.Confirm) return
        // Set at once, on the caller's thread, so a second tap does nothing.
        stateFlow.value = IntakeState.Copying(0, planned.copyBytes)
        val id = current
        job = scope.launch { copy(id, planned) }
    }

    fun chooseFewer() = abandon(IntakeState.Cancelled)

    fun allowAll() = abandon(IntakeState.AllowAllRequested)

    /** Stops a copy or a plan; every copy this intake made is removed. A finished intake stays finished. */
    fun cancel() {
        job?.cancel()
    }

    /** Call after reading [IntakeState.Done], [IntakeState.Cancelled] or [IntakeState.OutOfSpace]. */
    fun consumed() {
        stateFlow.value = IntakeState.Idle
        plan = null
    }

    private suspend fun copy(
        id: Int,
        planned: IntakePlan,
    ) {
        val project = projectId ?: return
        publish(id, IntakeState.Copying(0, planned.copyBytes))
        try {
            val items = media.execute(project, planned) { copied, total -> publish(id, IntakeState.Copying(copied, total)) }
            if (plan === planned) plan = null
            publish(id, IntakeState.Done(items))
        } catch (e: CancellationException) {
            // MediaAccess already removed this intake's copies and grants.
            if (plan === planned) plan = null
            publish(id, IntakeState.Cancelled)
            throw e
        } catch (e: IOException) {
            // The phone filled up mid-copy: MediaAccess already removed this intake's copies.
            if (plan === planned) plan = null
            publish(id, IntakeState.OutOfSpace)
        }
    }

    private fun publish(
        id: Int,
        state: IntakeState,
    ) {
        if (id == current) stateFlow.value = state
    }

    private fun abandon(next: IntakeState) {
        val planned = plan ?: return
        if (stateFlow.value !is IntakeState.Confirm && stateFlow.value !is IntakeState.NoSpace) return
        plan = null
        scope.launch {
            media.abandon(planned)
            stateFlow.value = next
        }
    }
}

/** The dialogs for [state]: the copy budget question, no space, and copy progress with Cancel. */
@Composable
fun MediaIntakeDialogs(
    state: IntakeState,
    onCopyAnyway: () -> Unit,
    onChooseFewer: () -> Unit,
    onAllowAll: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        is IntakeState.Confirm -> CopyBudgetDialog(state.verdict, onCopyAnyway, onChooseFewer, onAllowAll)
        is IntakeState.NoSpace -> NotEnoughSpaceDialog(state.verdict, onChooseFewer)
        is IntakeState.Copying -> if (state.totalBytes > 0) CopyProgressDialog(state.copiedBytes, state.totalBytes, onCancel)
        else -> Unit
    }
}
