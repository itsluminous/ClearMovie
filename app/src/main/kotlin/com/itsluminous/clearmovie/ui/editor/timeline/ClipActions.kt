package com.itsluminous.clearmovie.ui.editor.timeline

/** An action button in the contextual bar (ARCHITECTURE section 11.2). */
enum class EditAction {
    TRIM,
    DURATION,
    VOLUME,
    SPLIT,
    SPEED,
    LOOP,
    FIT,
    ROTATE,
    REPLACE,
    DUPLICATE,
    MOVE_LEFT,
    MOVE_RIGHT,
    JOIN,
    DELETE,
    EDIT_TEXT,
    COLOUR,
    TRANSITION,
    ANIMATION,
    PLAY,
    SKIP,
    ADD_MEDIA,
    FIT_ALL,
}

/** What is selected, as far as the bar is concerned. */
enum class SelectionKind { NOTHING, PHOTO, VIDEO, TEXT_CARD, COLOUR_CARD, INTRO, ENDING }

/** One button: [enabled] is false where the action can't apply here (Move left on the first item). */
data class BarAction(
    val action: EditAction,
    val enabled: Boolean = true,
)

/**
 * The bar's actions per selection, most-used first (ARCHITECTURE section
 * 11.2). Add text and Move to another row arrive with the milestones that
 * build them (cm-tracks, cm-captions); the plan does not ask for them to be
 * shown before then, so they are left out rather than shown disabled.
 * Transition (into the next item) is greyed out on an item with nothing
 * after it ([hasNext]).
 */
object ClipActions {
    fun forSelection(
        kind: SelectionKind,
        missing: Boolean = false,
        canMoveLeft: Boolean = true,
        canMoveRight: Boolean = true,
        canJoin: Boolean = false,
        hasNext: Boolean = true,
    ): List<BarAction> {
        val moves = listOf(BarAction(EditAction.MOVE_LEFT, canMoveLeft), BarAction(EditAction.MOVE_RIGHT, canMoveRight))
        val effects = listOf(BarAction(EditAction.TRANSITION, hasNext), BarAction(EditAction.ANIMATION))
        val list =
            when (kind) {
                SelectionKind.NOTHING -> {
                    listOf(BarAction(EditAction.ADD_MEDIA), BarAction(EditAction.FIT_ALL))
                }

                SelectionKind.PHOTO -> {
                    listOf(BarAction(EditAction.DURATION)) + effects +
                        listOf(
                            EditAction.FIT,
                            EditAction.ROTATE,
                            EditAction.REPLACE,
                            EditAction.DUPLICATE,
                        ).map(::BarAction) +
                        moves + BarAction(EditAction.DELETE)
                }

                SelectionKind.VIDEO -> {
                    listOf(BarAction(EditAction.TRIM)) + effects +
                        listOf(
                            EditAction.VOLUME,
                            EditAction.SPLIT,
                            EditAction.SPEED,
                            EditAction.LOOP,
                            EditAction.FIT,
                            EditAction.REPLACE,
                            EditAction.DUPLICATE,
                        ).map(::BarAction) + moves +
                        listOfNotNull(BarAction(EditAction.JOIN).takeIf { canJoin }) +
                        BarAction(EditAction.DELETE)
                }

                SelectionKind.TEXT_CARD -> {
                    listOf(EditAction.EDIT_TEXT, EditAction.DURATION).map(::BarAction) + effects +
                        listOf(EditAction.COLOUR, EditAction.DUPLICATE).map(::BarAction) +
                        moves + BarAction(EditAction.DELETE)
                }

                SelectionKind.COLOUR_CARD -> {
                    listOf(EditAction.COLOUR, EditAction.DURATION).map(::BarAction) + effects +
                        listOf(EditAction.EDIT_TEXT, EditAction.DUPLICATE).map(::BarAction) +
                        moves + BarAction(EditAction.DELETE)
                }

                // The intro leads into the first item, so it has a Transition; nothing follows the ending.
                SelectionKind.INTRO -> {
                    listOf(BarAction(EditAction.PLAY), BarAction(EditAction.TRANSITION, hasNext)) +
                        listOf(EditAction.REPLACE, EditAction.SKIP).map(::BarAction)
                }

                SelectionKind.ENDING -> {
                    listOf(EditAction.PLAY, EditAction.REPLACE, EditAction.SKIP).map(::BarAction)
                }
            }
        // A photo or video that is gone: Replace and Delete come first, the rest stay usable.
        if (!missing) return list
        val first = list.filter { it.action == EditAction.REPLACE || it.action == EditAction.DELETE }
        return first.sortedBy { it.action != EditAction.REPLACE } + (list - first.toSet())
    }
}
