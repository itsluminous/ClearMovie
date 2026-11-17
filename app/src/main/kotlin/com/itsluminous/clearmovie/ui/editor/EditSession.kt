package com.itsluminous.clearmovie.ui.editor

import com.itsluminous.clearmovie.domain.model.EditResult
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.UndoStack
import com.itsluminous.clearmovie.domain.model.normalized
import com.itsluminous.clearmovie.domain.model.validate

/**
 * The editor's undo and redo (ARCHITECTURE section 11.1: always visible). It
 * follows the open project: every edit made here is one step, and a change
 * that arrives from elsewhere (another screen wrote the project) becomes a
 * step too, so Undo never silently drops it. Like `ProjectEditor`, an edit
 * that throws, breaks an invariant or changes nothing adds no step. The
 * history lives in memory (cap 50); saving it across sessions is a later
 * feature.
 */
class EditSession(
    capacity: Int = UndoStack.DEFAULT_CAPACITY,
) {
    private val history = UndoStack<Project>(capacity)

    var current: Project? = null
        private set

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    /**
     * The key of the step the last edit made, while later edits with the same
     * key keep joining it (see [edit]); null once anything else happens.
     */
    private var mergeKey: String? = null

    /** The open project as the repository has it now. */
    fun adopt(project: Project) {
        val previous = current
        current = project
        if (previous == null || previous == project) return
        mergeKey = null
        if (previous.id != project.id) {
            history.clear()
            return
        }
        history.push(previous)
    }

    /**
     * Applies [change]; on [EditResult.Applied], [current] is the new project,
     * to be written. Edits that pass the same [mergeKey] one after another
     * make one step between them (a caption typed, styled and placed in one
     * visit to the caption editor is one Undo); an edit without one, an undo
     * or a redo ends the run.
     */
    fun edit(
        mergeKey: String? = null,
        change: Project.() -> Project,
    ): EditResult {
        val now = current ?: return EditResult.Refused("nothing open")
        val next =
            try {
                now.change().normalized()
            } catch (e: IllegalArgumentException) {
                return EditResult.Refused(e.message.orEmpty())
            }
        if (next == now) return EditResult.Unchanged
        val problems = next.validate()
        if (problems.isNotEmpty()) return EditResult.Refused("the result breaks an invariant", problems)
        val joins = mergeKey != null && mergeKey == this.mergeKey && history.canUndo
        if (!joins) history.push(now)
        this.mergeKey = mergeKey
        current = next
        return EditResult.Applied
    }

    /** Ends a run of merged edits, so the next edit is a step of its own. */
    fun endMerge() {
        mergeKey = null
    }

    /**
     * Takes back the run of edits made with [mergeKey] ("Cancel" in the
     * caption editor): the project before the run, now [current], or null when
     * the last step is not that run. Unlike [undo] it leaves nothing to redo.
     */
    fun revertMerge(mergeKey: String): Project? {
        if (this.mergeKey != mergeKey) return null
        this.mergeKey = null
        val now = current ?: return null
        val before = history.undo(now) ?: return null
        history.dropLastRedo()
        current = before
        return before
    }

    /** The project to go back to, now [current], or null. */
    fun undo(): Project? {
        mergeKey = null
        val now = current ?: return null
        return history.undo(now)?.also { current = it }
    }

    fun redo(): Project? {
        mergeKey = null
        val now = current ?: return null
        return history.redo(now)?.also { current = it }
    }
}
