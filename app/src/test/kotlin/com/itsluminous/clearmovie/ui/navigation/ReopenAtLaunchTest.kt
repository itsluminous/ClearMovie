package com.itsluminous.clearmovie.ui.navigation

import android.os.Looper
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.project.LaunchDecision
import com.itsluminous.clearmovie.domain.model.ProjectId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * The launch-time reopen of the editor (storage D2, flow D5): acted on once,
 * on the main thread, however the decision's database read returns, and
 * neither lost nor repeated when the activity is destroyed around it.
 */
@RunWith(RobolectricTestRunner::class)
class ReopenAtLaunchTest {
    @get:Rule
    val compose = createComposeRule()

    private val reopenEditor = LaunchDecision.ReopenEditor(ProjectId("p1"), broughtBack = true)
    private val asked = AtomicInteger()
    private val reopened = mutableListOf<LaunchDecision.ReopenEditor>()
    private val onMain = mutableListOf<Boolean>()

    /** Waits for [condition], running what was posted to the main thread meanwhile (Robolectric's looper is paused). */
    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }

    private fun reopen(decision: LaunchDecision.ReopenEditor) {
        onMain += Looper.getMainLooper().isCurrentThread
        reopened += decision
    }

    @Test
    fun aDecisionReadOnAnotherThreadIsActedOnOnTheMainThread() {
        compose.setContent {
            ReopenAtLaunch({
                asked.incrementAndGet()
                // As the real decision does: Room and the codec dispatcher, off the main thread.
                withContext(Dispatchers.IO) {
                    Thread.sleep(20)
                    reopenEditor
                }
            }, ::reopen)
        }
        waitFor { reopened.isNotEmpty() }
        compose.waitForIdle()

        assertThat(reopened).containsExactly(reopenEditor)
        assertThat(onMain).containsExactly(true)
        assertThat(asked.get()).isEqualTo(1)
    }

    @Test
    fun homeOpensNothing() {
        val done = CompletableDeferred<Unit>()
        compose.setContent {
            ReopenAtLaunch({
                withContext(Dispatchers.IO) { LaunchDecision.Home }.also { done.complete(Unit) }
            }, ::reopen)
        }
        waitFor { done.isCompleted }
        compose.waitForIdle()

        assertThat(reopened).isEmpty()
    }

    @Test
    fun aRecreationAfterTheReopenDoesNotReopenAgain() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ReopenAtLaunch({
                asked.incrementAndGet()
                withContext(Dispatchers.IO) { reopenEditor }
            }, ::reopen)
        }
        waitFor { reopened.isNotEmpty() }

        // A rotation, or a process death: the restored back stack already has the editor.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        Thread.sleep(100)
        compose.waitForIdle()

        assertThat(reopened).containsExactly(reopenEditor)
        assertThat(asked.get()).isEqualTo(1)
    }

    @Test
    fun aRecreationWhileTheDecisionIsStillBeingReadStillReopens() {
        val gate = CompletableDeferred<Unit>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ReopenAtLaunch({
                asked.incrementAndGet()
                withContext(Dispatchers.IO) {
                    gate.await()
                    reopenEditor
                }
            }, ::reopen)
        }
        waitFor { asked.get() == 1 }

        // The activity is destroyed (a rotation at launch) before the read returns.
        restoration.emulateSavedInstanceStateRestore()
        waitFor { asked.get() == 2 }
        gate.complete(Unit)
        waitFor { reopened.isNotEmpty() }
        compose.waitForIdle()

        assertThat(reopened).containsExactly(reopenEditor)
        assertThat(onMain).containsExactly(true)
    }
}
