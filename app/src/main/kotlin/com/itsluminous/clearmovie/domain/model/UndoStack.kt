package com.itsluminous.clearmovie.domain.model

/**
 * Undo and redo over immutable states. Each [push] is one undo step; at most
 * [capacity] are kept, so the 51st edit drops the oldest. A new edit clears
 * the redo history. The history lives in memory only (section 10).
 */
class UndoStack<T>(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoDepth: Int get() = undo.size
    val redoDepth: Int get() = redo.size

    /** Records [before], the state an edit is about to replace. */
    fun push(before: T) {
        undo.addLast(before)
        if (undo.size > capacity) undo.removeFirst()
        redo.clear()
    }

    /** The state to go back to from [current], or null when there is none. */
    fun undo(current: T): T? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        return previous
    }

    /** The state to go forward to from [current], or null when there is none. */
    fun redo(current: T): T? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        if (undo.size > capacity) undo.removeFirst()
        return next
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 50
    }
}

/**
 * The editing session over one project: each [edit] is one undo step, and
 * only a valid result is accepted. An edit that throws, or whose result
 * breaks an invariant, leaves the project and the history unchanged; an
 * edit that changes nothing adds no step.
 */
class ProjectEditor(
    initial: Project,
    val ids: IdSource = RandomIds,
    capacity: Int = UndoStack.DEFAULT_CAPACITY,
) {
    private val history = UndoStack<Project>(capacity)

    var project: Project = initial
        private set

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val undoDepth: Int get() = history.undoDepth

    init {
        val problems = initial.validate()
        require(problems.isEmpty()) { "The project is not valid: $problems" }
    }

    /**
     * Applies [change] (an operation from `Edits`, given this editor's
     * [IdSource]). Returns false, and changes nothing, when the operation
     * refuses or the result is invalid.
     */
    fun edit(change: Project.(IdSource) -> Project): Boolean {
        val next =
            try {
                project.change(ids).normalized(ids)
            } catch (e: IllegalArgumentException) {
                return false
            }
        if (next == project) return false
        if (next.validate().isNotEmpty()) return false
        history.push(project)
        project = next
        return true
    }

    fun undo(): Boolean {
        project = history.undo(project) ?: return false
        return true
    }

    fun redo(): Boolean {
        project = history.redo(project) ?: return false
        return true
    }
}
