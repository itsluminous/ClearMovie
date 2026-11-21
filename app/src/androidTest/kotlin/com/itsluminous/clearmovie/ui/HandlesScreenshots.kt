package com.itsluminous.clearmovie.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.AudioRole
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.PresetRole
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.ResizeEdge
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.domain.model.TrackId
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addCaptionFor
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.domain.model.resizeClip
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.ui.editor.EditorActions
import com.itsluminous.clearmovie.ui.editor.EditorScreen
import com.itsluminous.clearmovie.ui.editor.EditorScreenState
import com.itsluminous.clearmovie.ui.editor.EditorUi
import com.itsluminous.clearmovie.ui.editor.OpenPanel
import com.itsluminous.clearmovie.ui.editor.Panel
import com.itsluminous.clearmovie.ui.editor.PlayheadUi
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.editor.timeline.ZoomState
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Evidence screenshots for cm-timeline-handles: the handles on a selected
 * caption, frame, music and voice item, and the Lower for talk popup, in
 * light and dark, English, Hindi and Hinglish, upright and sideways, and at
 * font scale 2. Runs only when asked (`-e screenshots <prefix>`), so CI
 * skips it. Pictures go to the app's files/screens folder.
 */
@RunWith(AndroidJUnit4::class)
class HandlesScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var screen: (@Composable () -> Unit) by mutableStateOf({})

    private fun media(n: String) = MediaRef.Store("content://media/external/images/media/$n")

    /** The preset project: three items, a caption on the video with its start dragged later, and a voice clip. */
    private val project: Project by lazy {
        val ids = SequentialIds("s")
        val p =
            ProjectFactory
                .fromPreset(ProjectId("shots"), RenderHarness.preset, PresetMedia(introMs = 4000, frameMs = 20_000, endingMs = 4000), ids)
                .addToMain(
                    listOf(
                        PhotoClip(ClipId("p1"), media("p1"), 3000),
                        VideoClip(ClipId("v1"), media("v1"), sourceDurationMs = 5000),
                        PhotoClip(ClipId("p2"), media("p2"), 3000),
                    ),
                    ids = ids,
                ).addCaptionFor(ClipId("v1"), "Happy birthday", ids, ClipId("cap"))
                .addTimedClip(AudioClip(ClipId("voice"), media("voice"), 8000, 3000, 3000), AudioRole.VOICE, ids)
        return@lazy p.resizeClip(ClipId("cap"), ResizeEdge.START, p.requireClip(ClipId("cap")).startMs + 1000)
    }

    private val actions =
        object : EditorActions {
            override fun back() = Unit

            override fun export() = Unit

            override fun togglePlay() = Unit

            override fun fixPreset() = Unit

            override fun edit(change: Project.() -> Project): Boolean = false

            override fun undo() = Unit

            override fun redo() = Unit

            override fun seek(ms: Long) = Unit

            override fun pickForInsert(
                anchor: ClipId,
                side: InsertSide,
            ) = Unit

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
            ) = Unit
        }

    private data class Shot(
        val name: String,
        val selected: ClipId?,
        val panel: OpenPanel? = null,
        val size: DpSize = DpSize(411.dp, 891.dp),
        val mode: ThemeMode = ThemeMode.LIGHT,
        val lang: String = "en",
        val scale: Float = 1f,
        val allRows: Boolean = false,
    )

    @Test
    fun handlesAndTalkPopup() {
        val prefix = InstrumentationRegistry.getArguments().getString("screenshots")
        assumeTrue("screenshots run only when asked", prefix != null)
        compose.setContent { screen() }
        val schedule = PreviewSchedule.of(RenderPlanBuilder(RenderHarness.preset).build(project))
        val ui = EditorUi(project = project, preset = RenderHarness.preset, schedule = schedule, canUndo = true)
        val frame =
            project.tracks
                .single { it.presetRole == PresetRole.FRAME }
                .clips
                .single()
                .id
        val musicRow = project.tracks.single { it.presetRole == PresetRole.FRAME_MUSIC }
        val music = musicRow.clips.single().id
        val talk = OpenPanel(Panel.TALK_LOWER, music, trackId = musicRow.id)
        val sideways = DpSize(891.dp, 411.dp)
        val shots =
            listOf(
                Shot("handles-text", ClipId("cap")),
                Shot("handles-frame", frame),
                Shot("handles-music", music),
                Shot("handles-voice", ClipId("voice")),
                Shot("handles-text-dark", ClipId("cap"), mode = ThemeMode.DARK),
                Shot("handles-text-hi", ClipId("cap"), lang = "hi"),
                Shot("handles-text-landscape", ClipId("cap"), size = sideways, allRows = true),
                Shot("handles-text-font2", ClipId("cap"), scale = 2f),
                Shot("talk-popup", music, talk),
                Shot("talk-popup-dark", music, talk, mode = ThemeMode.DARK),
                Shot("talk-popup-hi", music, talk, lang = "hi"),
                Shot("talk-popup-hinglish", music, talk, lang = "hi-Latn"),
                Shot("talk-popup-landscape", music, talk, size = sideways),
                Shot("talk-popup-font2", music, talk, scale = 2f),
            )
        val out = File(checkNotNull(compose.activity.getExternalFilesDir(null)), "screens").apply { mkdirs() }
        for (shot in shots) {
            val item = project.requireClip(shot.selected!!)
            // The selected item whole on screen, both handles in view.
            val msPerDp = maxOf(20.0, item.durationMs / 220.0)
            val zoom = ZoomState(msPerDp = msPerDp, scrollMs = item.startMs - 60 * msPerDp, fitAll = false)
            screen = {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(shot.size) then DeviceConfigurationOverride.FontScale(shot.scale) then
                        DeviceConfigurationOverride.Locales(LocaleList(shot.lang)),
                ) {
                    ClearMovieTheme(themeMode = shot.mode, dynamicColor = false) {
                        key(shot) {
                            val state =
                                remember {
                                    EditorScreenState(
                                        selected = shot.selected,
                                        panel = shot.panel,
                                        zoom = zoom,
                                        allRowsRequested = shot.allRows,
                                    )
                                }
                            EditorScreen(
                                ui = ui,
                                title = "Birthday",
                                playhead = PlayheadUi(9_000_000, schedule.durationUs, false),
                                preview = { m -> Box(m.background(Color(0xFF3A6EA5))) },
                                thumbnail = { _, _ -> null },
                                actions = actions,
                                state = state,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            Thread.sleep(400)
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            FileOutputStream(File(out, "$prefix-${shot.name}.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
