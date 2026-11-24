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
    ZOOM_OUT,
    ZOOM_IN,
    FIT_ALL,

    // cm-tracks: rows, and items on rows other than MAIN.
    ADD_ROW,
    ADD_HERE,
    SHOW_ON_TOP,
    BRING_FORWARD,
    SEND_BACKWARD,
    SHOW_AT_BOTTOM,
    MOVE_ROW_UP,
    MOVE_ROW_DOWN,
    REMOVE_ROW,

    /** A switch: the row's sound is off. */
    MUTE_ROW,

    /**
     * Lower for talk, on a music, frame or photo-and-video row (or an item on one): opens its popup (on or off, and how
     * low); shows whether it is on.
     */
    LOWER_MUSIC,

    /** A switch: the preset frame is shown. */
    FRAME_ON,
    MOVE_EARLIER,
    MOVE_LATER,
    MOVE_TO_ROW,
    FADE,
    POSITION,

    /** cm-captions: a caption attached to the selected photo or video. */
    ADD_CAPTION,

    /** cm-timeline-handles: a caption with a side detached covers its photo or video whole again. */
    FOLLOW_ITEM,

    /** cm-frame-area: the colour keyed out of a frame, and where photos and videos go while it shows. */
    KEY_COLOUR,
    PICTURE_AREA,
}

/** What is selected, as far as the bar is concerned. */
enum class SelectionKind {
    NOTHING,
    PHOTO,
    VIDEO,
    TEXT_CARD,
    COLOUR_CARD,
    INTRO,
    ENDING,

    /** A photo or video on a picture-in-picture row. */
    PIP_PHOTO,
    PIP_VIDEO,

    /** A caption on a text row. */
    TEXT,

    /** Music, a voice or a sound. */
    SOUND,

    /** The preset frame. */
    FRAME,

    /** An extra overlay video on a frame row. */
    OVERLAY,
}

/**
 * One button: [enabled] is false where the action can't apply here (Move
 * left on the first item). [checked] makes it a switch, on or off; with
 * [opensPanel] it only shows that state, and a tap opens its popup instead
 * of switching it.
 */
data class BarAction(
    val action: EditAction,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val opensPanel: Boolean = false,
)

/** What a selected row allows, for [ClipActions.forRow]. */
data class RowFacts(
    val isMain: Boolean,
    val isVisual: Boolean,
    val isSound: Boolean,
    val isMusic: Boolean,
    val isPresetFrame: Boolean,
    val removable: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val canGoForward: Boolean,
    val canGoBackward: Boolean,
    val muted: Boolean,
    val enabled: Boolean,
    val lowerWhenTalking: Boolean,
    /** A frame row with a frame whose video has sound (cm-frame-audio): Lower for talk applies to it. */
    val hasFrameSound: Boolean = false,
    /** A frame row of the user's. */
    val isFrameRow: Boolean = false,
    /** A photo-and-video row holding a video (cm-voice-ducking): its videos' sound is lowered under voices. */
    val hasVideoSound: Boolean = false,
)

/** What a selected item on a row other than MAIN allows, for [ClipActions.forTimed]. */
data class TimedFacts(
    val canMoveEarlier: Boolean,
    val canMoveLater: Boolean,
    /** Items on rows the preset manages stay on their row and keep their time. */
    val fixed: Boolean,
    val isMusic: Boolean = false,
    val lowerWhenTalking: Boolean = true,
    val frameOn: Boolean = true,
    /** A caption with a side detached from its photo or video (Match item). */
    val canFollow: Boolean = false,
    /** A frame whose video has its own sound (cm-frame-audio): it has a Volume and Lower for talk. */
    val hasSound: Boolean = false,
    /** A picture-in-picture row with a video that may sound (cm-voice-ducking): its videos have Lower for talk. */
    val hasVideoSound: Boolean = true,
)

/**
 * The bar's actions per selection, most-used first (ARCHITECTURE section
 * 11.2). A photo or video offers Add caption second, after its length
 * (section 11.2's "Add text"). A video's Lower for talk (its row's, under
 * voices; null [lowerWhenTalking] when its row has no video that may sound)
 * comes after its other changes, before the moves. Transition (into the next item) is greyed out on an item with
 * nothing after it ([hasNext]). A MAIN item's "Move to another row" is left
 * out: the model moves items only between rows of one kind, and MAIN is the
 * only row of its kind.
 */
object ClipActions {
    fun forSelection(
        kind: SelectionKind,
        missing: Boolean = false,
        canMoveLeft: Boolean = true,
        canMoveRight: Boolean = true,
        canJoin: Boolean = false,
        hasNext: Boolean = true,
        canZoomIn: Boolean = true,
        canZoomOut: Boolean = true,
        lowerWhenTalking: Boolean? = true,
    ): List<BarAction> {
        val moves = listOf(BarAction(EditAction.MOVE_LEFT, canMoveLeft), BarAction(EditAction.MOVE_RIGHT, canMoveRight))
        val effects = listOf(BarAction(EditAction.TRANSITION, hasNext), BarAction(EditAction.ANIMATION))
        val list =
            when (kind) {
                SelectionKind.NOTHING -> {
                    // The one place for zoom (with pinching): next to Fit all.
                    listOf(
                        BarAction(EditAction.ADD_MEDIA),
                        BarAction(EditAction.ZOOM_OUT, canZoomOut),
                        BarAction(EditAction.ZOOM_IN, canZoomIn),
                        BarAction(EditAction.FIT_ALL, canZoomOut),
                        BarAction(EditAction.ADD_ROW),
                    )
                }

                SelectionKind.PHOTO -> {
                    listOf(BarAction(EditAction.DURATION), BarAction(EditAction.ADD_CAPTION)) + effects +
                        listOf(
                            EditAction.FIT,
                            EditAction.ROTATE,
                            EditAction.REPLACE,
                            EditAction.DUPLICATE,
                        ).map(::BarAction) +
                        moves + BarAction(EditAction.DELETE)
                }

                SelectionKind.VIDEO -> {
                    listOf(BarAction(EditAction.TRIM), BarAction(EditAction.ADD_CAPTION)) + effects +
                        listOf(
                            EditAction.VOLUME,
                            EditAction.SPLIT,
                            EditAction.SPEED,
                            EditAction.LOOP,
                            EditAction.FIT,
                            EditAction.REPLACE,
                            EditAction.DUPLICATE,
                        ).map(::BarAction) + listOfNotNull(lowerWhenTalking?.let(::lowerMusic)) + moves +
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

                else -> {
                    emptyList()
                }
            }
        // A photo or video that is gone: Replace and Delete come first, the rest stay usable.
        if (!missing) return list
        val first = list.filter { it.action == EditAction.REPLACE || it.action == EditAction.DELETE }
        return first.sortedBy { it.action != EditAction.REPLACE } + (list - first.toSet())
    }

    /** The actions for an item on a row other than MAIN, most-used first. */
    fun forTimed(
        kind: SelectionKind,
        facts: TimedFacts,
    ): List<BarAction> {
        val moves =
            if (facts.fixed) {
                emptyList()
            } else {
                listOf(
                    BarAction(EditAction.MOVE_EARLIER, facts.canMoveEarlier),
                    BarAction(EditAction.MOVE_LATER, facts.canMoveLater),
                    BarAction(EditAction.MOVE_TO_ROW),
                )
            }
        val delete = listOf(BarAction(EditAction.DELETE))
        val frameLook = listOf(BarAction(EditAction.KEY_COLOUR), BarAction(EditAction.PICTURE_AREA))
        return when (kind) {
            SelectionKind.TEXT -> {
                listOf(BarAction(EditAction.EDIT_TEXT)) +
                    listOfNotNull(BarAction(EditAction.FOLLOW_ITEM).takeIf { facts.canFollow }) +
                    BarAction(EditAction.ANIMATION) + moves + delete
            }

            SelectionKind.PIP_PHOTO -> {
                listOf(EditAction.DURATION, EditAction.ADD_CAPTION, EditAction.POSITION, EditAction.ROTATE).map(::BarAction) + moves +
                    delete
            }

            SelectionKind.PIP_VIDEO -> {
                listOf(
                    EditAction.TRIM,
                    EditAction.ADD_CAPTION,
                    EditAction.VOLUME,
                    EditAction.POSITION,
                    EditAction.ROTATE,
                ).map(::BarAction) +
                    listOfNotNull(lowerMusic(facts.lowerWhenTalking).takeIf { facts.hasVideoSound }) + moves + delete
            }

            SelectionKind.SOUND -> {
                listOf(EditAction.TRIM, EditAction.VOLUME, EditAction.FADE).map(::BarAction) +
                    listOfNotNull(lowerMusic(facts.lowerWhenTalking).takeIf { facts.isMusic }) +
                    moves + delete
            }

            SelectionKind.FRAME -> {
                listOf(BarAction(EditAction.FRAME_ON, checked = facts.frameOn)) + frameLook
            }

            // A frame with sound has the sound's controls of a music item, after its look (cm-frame-audio).
            SelectionKind.OVERLAY -> {
                val sound =
                    if (facts.hasSound) listOf(BarAction(EditAction.VOLUME), lowerMusic(facts.lowerWhenTalking)) else emptyList()
                frameLook + sound + moves + delete
            }

            else -> {
                forSelection(kind)
            }
        }
    }

    /** Lower for talk: shows whether it is on, and opens its popup. */
    private fun lowerMusic(on: Boolean) = BarAction(EditAction.LOWER_MUSIC, checked = on, opensPanel = true)

    /** The actions for a selected row (its icon was tapped): Add here, its sound, where it is drawn, where it is on screen. */
    fun forRow(row: RowFacts): List<BarAction> =
        buildList {
            add(BarAction(if (row.isMain) EditAction.ADD_MEDIA else EditAction.ADD_HERE))
            // A frame row muted earlier keeps its switch, so it can be turned back on, sound or not.
            if (row.isSound || row.hasFrameSound || (row.isFrameRow && row.muted)) add(BarAction(EditAction.MUTE_ROW, checked = row.muted))
            if (row.isMusic || row.hasFrameSound || row.hasVideoSound) add(lowerMusic(row.lowerWhenTalking))
            if (row.isPresetFrame) add(BarAction(EditAction.FRAME_ON, checked = row.enabled))
            if (row.isVisual) {
                add(BarAction(EditAction.SHOW_ON_TOP, row.canGoForward))
                add(BarAction(EditAction.BRING_FORWARD, row.canGoForward))
                add(BarAction(EditAction.SEND_BACKWARD, row.canGoBackward))
                add(BarAction(EditAction.SHOW_AT_BOTTOM, row.canGoBackward))
            }
            add(BarAction(EditAction.MOVE_ROW_UP, row.canMoveUp))
            add(BarAction(EditAction.MOVE_ROW_DOWN, row.canMoveDown))
            if (row.removable) add(BarAction(EditAction.REMOVE_ROW))
        }
}
