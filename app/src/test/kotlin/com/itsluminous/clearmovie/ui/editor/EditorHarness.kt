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
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EditResult
import com.itsluminous.clearmovie.domain.model.Limits
import com.itsluminous.clearmovie.domain.model.Preset
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.Side
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
    private val session = EditSession().apply { adopt(initial) }
    var project by mutableStateOf(initial)
        private set
    var playheadUs by mutableStateOf(0L)
    var state: EditorScreenState? = null

    /** What the photo picker "returns" for a "+": put in by the test's stand-in for the view model's intake. */
    var galleryAnswer: List<com.itsluminous.clearmovie.domain.model.MainClip> = emptyList()

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

            override fun exact() {
                calls += "exact"
            }

            override fun fixPreset() {
                calls += "fix"
            }

            override fun edit(change: Project.() -> Project): Boolean {
                val result = session.edit(change)
                val ok = result == EditResult.Applied
                if (ok) project = session.current!!
                if (result is EditResult.Refused) calls += "refused"
                return ok
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
        }

    @Composable
    fun Content(screen: EditorScreenState = rememberEditorScreenState()) {
        state = screen
        val ui = ui()
        EditorScreen(
            ui = ui,
            title = "Party",
            playhead = PlayheadUi(playheadUs, (ui.slots.lastOrNull()?.endMs ?: 0) * 1000, false),
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
