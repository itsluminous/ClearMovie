package com.itsluminous.clearmovie.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CardClip
import com.itsluminous.clearmovie.domain.model.Clip
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EditResult
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.Side
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.TrackType
import com.itsluminous.clearmovie.domain.model.addFrameFrom
import com.itsluminous.clearmovie.domain.model.addToTrack
import com.itsluminous.clearmovie.domain.model.insertAt
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.testing.AtSize
import com.itsluminous.clearmovie.testing.ScreenSize
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme

/**
 * The editor screen with the real model behind it: edits go through an
 * [EditSession] exactly as the view model does, so tests read the project's
 * order after a gesture. Gallery picks are recorded; [galleryAnswer] stands
 * in for what the photo picker returns.
 */
class EditorHarness(
    initial: Project,
    private val preset: Preset?,
) {
    val calls = mutableListOf<String>()
    private var session = EditSession().apply { adopt(initial) }

    /**
     * When set, edits reach the screen only at [catchUp], one by one, as a busy phone's screen can lag
     * behind fast typing.
     */
    var lagging = false
    private val behind = ArrayDeque<Project>()

    /** Shows the next edit that is still on its way; false when none is. */
    fun catchUp(): Boolean {
        project = behind.removeFirstOrNull() ?: return false
        return true
    }

    /** Stands in for a process death: the view model's session (and its undo history) starts again. */
    fun newSession() {
        session = EditSession().apply { adopt(project) }
    }

    var project by mutableStateOf(initial)
        private set
    var playheadUs by mutableStateOf(0L)

    /** Whether the preview is playing, as the view model reports it. */
    var playing by mutableStateOf(false)

    /** The title the top bar shows for an unnamed video; a named one shows its name, as in the app. */
    var title by mutableStateOf("Party")
    var state: EditorScreenState? = null

    /** What the photo picker "returns" for a "+": put in by the test's stand-in for the view model's intake. */
    var galleryAnswer: List<com.itsluminous.clearmovie.domain.model.MainClip> = emptyList()

    /**
     * What the gallery or Files "returns" for a "+" or "Add here" on a row
     * other than MAIN, already made into that row's items (the view model's
     * intake does that from the files).
     */
    var timedAnswer: List<Clip> = emptyList()

    private fun ui(): EditorUi =
        EditorUi(
            project = project,
            preset = preset,
            schedule = runCatching { PreviewSchedule.of(RenderPlanBuilder(preset).build(project)) }.getOrNull(),
            canUndo = session.canUndo,
            canRedo = session.canRedo,
        )

    val actions =
        object : EditorActions {
            override fun back() {
                calls += "back"
            }

            override fun export() {
                calls += "export"
            }

            override fun togglePlay() {
                calls += "play"
            }

            override fun fixPreset() {
                calls += "fix"
            }

            override fun openShape() {
                calls += "shape"
            }

            override fun openSettings() {
                calls += "settings"
            }

            override fun edit(change: Project.() -> Project): Boolean = apply(null, change)

            override fun editMerged(
                mergeKey: String,
                change: Project.() -> Project,
            ): Boolean = apply(mergeKey, change)

            private fun apply(
                mergeKey: String?,
                change: Project.() -> Project,
            ): Boolean {
                val result = session.edit(mergeKey, change)
                val ok = result == EditResult.Applied
                if (ok && lagging) {
                    behind.addLast(session.current!!)
                } else if (ok) {
                    project = session.current!!
                }
                if (result is EditResult.Refused) calls += "refused"
                return ok
            }

            override fun endMerge() = session.endMerge()

            override fun revertMerge(mergeKey: String): Boolean {
                val before = session.revertMerge(mergeKey) ?: return false
                project = before
                return true
            }

            override fun undo() {
                session.undo()?.let { project = it }
                calls += "undo"
            }

            override fun redo() {
                session.redo()?.let { project = it }
            }

            override fun seek(ms: Long) {
                calls += "seek:$ms"
                playheadUs = ms * 1000
            }

            override fun pickForInsert(
                anchor: ClipId,
                side: InsertSide,
            ) {
                calls += "pick:${anchor.value}:$side"
                if (project.trackOf(anchor)?.type != TrackType.MAIN) {
                    // As the view model places them: before an item backwards, each just before the last one.
                    val s = if (side == InsertSide.BEFORE) Side.BEFORE else Side.AFTER
                    val ordered = if (s == Side.BEFORE) timedAnswer.reversed() else timedAnswer
                    edit {
                        var p = this
                        var a = anchor
                        for (c in ordered) {
                            p = p.insertAt(p.trackOf(a)!!.id, a, s, c)
                            a = c.id
                        }
                        p
                    }
                    return
                }
                // The view model's intake: the picked items go in at the "+", in order.
                edit {
                    var p = this
                    var a = anchor
                    var s = if (side == InsertSide.BEFORE) Side.BEFORE else Side.AFTER
                    for (c in galleryAnswer) {
                        p = p.insertAt(p.main.id, a, s, c)
                        a = c.id
                        s = Side.AFTER
                    }
                    p
                }
            }

            override fun previewFrom(ms: Long) {
                calls += "preview:$ms"
                playheadUs = ms * 1000
            }

            override fun pickForReplace(clipId: ClipId) {
                calls += "replace:${clipId.value}"
            }

            override fun pickToAdd() {
                calls += "add"
            }

            override fun insertCard(
                anchor: ClipId,
                side: InsertSide,
                text: String?,
                color: ArgbColor,
            ) {
                calls += "card:${text ?: color}"
                val id = ClipId("card${calls.size}")
                edit {
                    insertAt(
                        main.id,
                        anchor,
                        if (side ==
                            InsertSide.BEFORE
                        ) {
                            Side.BEFORE
                        } else {
                            Side.AFTER
                        },
                        CardClip(id, Limits.DEFAULT_CARD_MS, color, text),
                    )
                }
            }

            override fun playPart(clipId: ClipId) {
                calls += "playPart:${clipId.value}"
            }

            override fun pickFrameFrom(
                trackId: TrackId,
                atMs: Long,
                takeOver: ClipId?,
            ) {
                calls += "pickFrame:${trackId.value}:$atMs:${takeOver?.value}"
                val frame = timedAnswer.firstOrNull() as? com.itsluminous.clearmovie.domain.model.OverlayClip ?: return
                edit { addFrameFrom(trackId, frame, atMs, takeOver) }
            }

            override fun pickForRow(
                trackId: TrackId,
                atMs: Long,
            ) {
                calls += "pickRow:${trackId.value}:$atMs"
                val first = timedAnswer.firstOrNull() ?: return
                edit {
                    var p = addToTrack(trackId, first, atMs)
                    var a = first.id
                    for (c in timedAnswer.drop(1)) {
                        p = p.insertAt(p.trackOf(a)!!.id, a, Side.AFTER, c)
                        a = c.id
                    }
                    p
                }
            }
        }

    @Composable
    fun Content(screen: EditorScreenState = rememberEditorScreenState()) {
        state = screen
        val ui = ui()
        EditorScreen(
            ui = ui,
            title = project.name ?: title,
            playhead = PlayheadUi(playheadUs, (ui.slots.lastOrNull()?.endMs ?: 0) * 1000, playing),
            preview = { m -> Box(m.background(Color.Black).testTag(PREVIEW_TAG)) },
            thumbnail = { _, _ -> null },
            actions = actions,
            state = screen,
        )
    }

    @Composable
    fun At(
        size: ScreenSize,
        fontScale: Float = 1f,
    ) {
        AtSize(size, fontScale) { ClearMovieTheme(dynamicColor = false) { Content() } }
    }
}
