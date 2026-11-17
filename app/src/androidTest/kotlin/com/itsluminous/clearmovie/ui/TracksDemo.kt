package com.itsluminous.clearmovie.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.EditResult
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.RandomIds
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.Side
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addText
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.addToTrack
import com.itsluminous.clearmovie.domain.model.insertAt
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.ui.editor.EditSession
import com.itsluminous.clearmovie.ui.editor.EditorActions
import com.itsluminous.clearmovie.ui.editor.EditorScreen
import com.itsluminous.clearmovie.ui.editor.EditorUi
import com.itsluminous.clearmovie.ui.editor.PlayheadUi
import com.itsluminous.clearmovie.ui.editor.rememberEditorScreenState
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor with the real model behind it, held open for a screen
 * recording driven by `adb shell input motionevent` (cm-tracks evidence:
 * row reorder, the stacking menu, "+" on each kind of row). Runs only when
 * asked (`-e demo <seconds>`), so CI skips it. Test tags are exposed as
 * resource ids, so `uiautomator dump` gives the coordinates. A "+" that would
 * open the gallery or Files puts in a ready-made item of that row's kind.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class TracksDemo {
    private fun media(n: String) = MediaRef.Store("content://media/external/images/media/$n")

    @Test
    fun demo() {
        val seconds = InstrumentationRegistry.getArguments().getString("demo")?.toLongOrNull()
        assumeTrue("the demo runs only when asked", seconds != null)
        val ids = SequentialIds("d")
        val start =
            ProjectFactory
                .fromPreset(ProjectId("demo"), RenderHarness.preset, PresetMedia(introMs = 3000, frameMs = 20_000, endingMs = 3000), ids)
                .addToMain(
                    listOf(
                        PhotoClip(ClipId("p1"), media("p1"), 3000),
                        VideoClip(ClipId("v1"), media("v1"), sourceDurationMs = 4000),
                        PhotoClip(ClipId("p2"), media("p2"), 3000),
                    ),
                    ids = ids,
                ).addText("Happy birthday", 3000, 3000, ids)
                .addTimedClip(PhotoClip(ClipId("pip"), media("pip"), 3000, pip = PipPlacement.DEFAULT, startMs = 4000), ids = ids)
                .addTimedClip(AudioClip(ClipId("m"), media("m"), 3000, 4000, 4000), AudioRole.MUSIC, ids)
        val session = EditSession().apply { adopt(start) }
        var project by mutableStateOf(start)
        var playheadUs by mutableStateOf(6_500_000L)

        fun edit(change: Project.() -> Project): Boolean {
            val ok = session.edit(change = change) == EditResult.Applied
            if (ok) project = session.current!!
            return ok
        }

        /** What a picked file becomes on the row of [anchor] (or row [row]). */
        fun picked(row: TrackId): com.itsluminous.clearmovie.domain.model.Clip? {
            val t = project.track(row) ?: return null
            val id = RandomIds.clipId()
            return when (t.type) {
                com.itsluminous.clearmovie.domain.model.TrackType.VISUAL -> {
                    PhotoClip(id, media("new"), 2000, pip = PipPlacement.DEFAULT)
                }

                com.itsluminous.clearmovie.domain.model.TrackType.AUDIO -> {
                    AudioClip(id, media("new"), 0, 2000, 2000)
                }

                com.itsluminous.clearmovie.domain.model.TrackType.OVERLAY -> {
                    com.itsluminous.clearmovie.domain.model.OverlayClip(
                        id,
                        media("new"),
                        0,
                        2000,
                        2000,
                        key = RenderHarness.preset.frame!!.key,
                        repeat = false,
                    )
                }

                else -> {
                    null
                }
            }
        }
        val actions =
            object : EditorActions {
                override fun back() = Unit

                override fun export() = Unit

                override fun togglePlay() = Unit

                override fun exact() = Unit

                override fun fixPreset() = Unit

                override fun edit(change: Project.() -> Project): Boolean = edit(change)

                override fun undo() {
                    session.undo()?.let { project = it }
                }

                override fun redo() {
                    session.redo()?.let { project = it }
                }

                override fun seek(ms: Long) {
                    playheadUs = ms * 1000
                }

                override fun pickForInsert(
                    anchor: ClipId,
                    side: InsertSide,
                ) {
                    val row = project.trackOf(anchor)?.id ?: return
                    val c = picked(row) ?: PhotoClip(RandomIds.clipId(), media("new"), 2000)
                    edit { insertAt(row, anchor, if (side == InsertSide.BEFORE) Side.BEFORE else Side.AFTER, c) }
                }

                override fun pickForReplace(clipId: ClipId) = Unit

                override fun pickToAdd() = Unit

                override fun insertCard(
                    anchor: ClipId,
                    side: InsertSide,
                    text: String?,
                    color: ArgbColor,
                ) = Unit

                override fun playPart(clipId: ClipId) = Unit

                override fun pickForRow(
                    trackId: TrackId,
                    atMs: Long,
                ) {
                    val c = picked(trackId) ?: return
                    edit { addToTrack(trackId, c, atMs) }
                }
            }
        // A plain activity with the real frame clock (a Compose test rule's clock would freeze while this waits).
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    ClearMovieTheme(dynamicColor = false) {
                        val schedule = PreviewSchedule.of(RenderPlanBuilder(RenderHarness.preset).build(project))
                        EditorScreen(
                            ui =
                                EditorUi(
                                    project = project,
                                    preset = RenderHarness.preset,
                                    schedule = schedule,
                                    canUndo = session.canUndo,
                                    canRedo = session.canRedo,
                                ),
                            title = "Birthday",
                            playhead = PlayheadUi(playheadUs, schedule.durationUs, false),
                            preview = { m -> Box(m.background(Color(0xFF3A6EA5))) },
                            thumbnail = { _, _ -> null },
                            actions = actions,
                            state = rememberEditorScreenState(),
                        )
                    }
                }
            }
        }
        // Held open in real time while the recording is driven from outside.
        Thread.sleep(seconds!! * 1000)
        scenario.close()
    }
}
