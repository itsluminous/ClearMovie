package com.itsluminous.clearmovie.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.itsluminous.clearmovie.domain.model.PipPlacement
import com.itsluminous.clearmovie.domain.model.PresetMedia
import com.itsluminous.clearmovie.domain.model.Project
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addText
import com.itsluminous.clearmovie.domain.model.addTimedClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.render.RenderHarness
import com.itsluminous.clearmovie.render.plan.PreviewSchedule
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.ui.editor.EditorActions
import com.itsluminous.clearmovie.ui.editor.EditorScreen
import com.itsluminous.clearmovie.ui.editor.EditorUi
import com.itsluminous.clearmovie.ui.editor.PlayheadUi
import com.itsluminous.clearmovie.ui.editor.rememberEditorScreenState
import com.itsluminous.clearmovie.ui.editor.timeline.InsertSide
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Evidence screenshots of the editor (rows, the playback strip, the bar),
 * upright and sideways, light and dark, at font scale 1.0 and 1.3. Runs
 * only when asked (`-e screenshots <name>`), so CI skips it; the name is the
 * files' prefix (`before`, `after`). Pictures go to the app's files/screens
 * folder.
 */
@RunWith(AndroidJUnit4::class)
class EditorScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var screen: (@Composable () -> Unit) by mutableStateOf({})

    private fun media(n: String) = MediaRef.Store("content://media/external/images/media/$n")

    private val project: Project by lazy {
        val ids = SequentialIds("s")
        ProjectFactory
            .fromPreset(ProjectId("shots"), RenderHarness.preset, PresetMedia(introMs = 4000, frameMs = 20_000, endingMs = 4000), ids)
            .addToMain(
                listOf(
                    PhotoClip(ClipId("p1"), media("p1"), 3000),
                    VideoClip(ClipId("v1"), media("v1"), sourceDurationMs = 5000),
                    PhotoClip(ClipId("p2"), media("p2"), 3000),
                ),
                ids = ids,
            ).addText("Happy birthday", 5000, 3000, ids)
            .addTimedClip(PhotoClip(ClipId("pip"), media("pip"), 3000, pip = PipPlacement.DEFAULT, startMs = 6000), ids = ids)
            .addTimedClip(AudioClip(ClipId("m"), media("m"), 4000, 6000, 6000), AudioRole.MUSIC, ids)
    }

    private val actions =
        object : EditorActions {
            override fun back() = Unit

            override fun export() = Unit

            override fun togglePlay() = Unit

            override fun exact() = Unit

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
                trackId: com.itsluminous.clearmovie.domain.model.TrackId,
                atMs: Long,
            ) = Unit
        }

    @Test
    fun editor() {
        val prefix = InstrumentationRegistry.getArguments().getString("screenshots")
        assumeTrue("screenshots run only when asked", prefix != null)
        compose.setContent { screen() }
        val schedule = PreviewSchedule.of(RenderPlanBuilder(RenderHarness.preset).build(project))
        val ui = EditorUi(project = project, preset = RenderHarness.preset, schedule = schedule, canUndo = true)
        val out = File(checkNotNull(compose.activity.getExternalFilesDir(null)), "screens").apply { mkdirs() }
        for ((orientation, size) in listOf("portrait" to DpSize(411.dp, 891.dp), "landscape" to DpSize(891.dp, 411.dp))) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                for (scale in listOf(1.0f, 1.3f)) {
                    screen = {
                        DeviceConfigurationOverride(
                            DeviceConfigurationOverride.ForcedSize(size) then DeviceConfigurationOverride.FontScale(scale),
                        ) {
                            ClearMovieTheme(themeMode = mode, dynamicColor = false) {
                                EditorScreen(
                                    ui = ui,
                                    title = "Birthday",
                                    playhead = PlayheadUi(7_000_000, schedule.durationUs, false),
                                    preview = { m -> Box(m.background(Color(0xFF3A6EA5))) },
                                    thumbnail = { _, _ -> null },
                                    actions = actions,
                                    state = rememberEditorScreenState(),
                                )
                            }
                        }
                    }
                    compose.waitForIdle()
                    Thread.sleep(300)
                    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                    val name = "$prefix-editor-$orientation-${mode.name.lowercase()}-font$scale.png"
                    FileOutputStream(File(out, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    /** The row menu (what shows on top) open on the text row, in English, Hindi and Hinglish. */
    @Test
    fun rowMenu() {
        val prefix = InstrumentationRegistry.getArguments().getString("screenshots")
        assumeTrue("screenshots run only when asked", prefix != null)
        compose.setContent { screen() }
        val schedule = PreviewSchedule.of(RenderPlanBuilder(RenderHarness.preset).build(project))
        val ui = EditorUi(project = project, preset = RenderHarness.preset, schedule = schedule, canUndo = true)
        val text = project.tracks.first { it.type == com.itsluminous.clearmovie.domain.model.TrackType.TEXT && it.clips.isNotEmpty() }.id
        val out = File(checkNotNull(compose.activity.getExternalFilesDir(null)), "screens").apply { mkdirs() }
        for (tag in listOf("en", "hi", "hi-Latn")) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                screen = {
                    DeviceConfigurationOverride(
                        DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 891.dp)) then
                            DeviceConfigurationOverride.Locales(
                                androidx.compose.ui.text.intl
                                    .LocaleList(tag),
                            ),
                    ) {
                        ClearMovieTheme(themeMode = mode, dynamicColor = false) {
                            val state =
                                androidx.compose.runtime.remember {
                                    com.itsluminous.clearmovie.ui.editor.EditorScreenState(
                                        panel =
                                            com.itsluminous.clearmovie.ui.editor.OpenPanel(
                                                com.itsluminous.clearmovie.ui.editor.Panel.ROW_MENU,
                                                trackId = text,
                                            ),
                                        selectedRow = text,
                                    )
                                }
                            EditorScreen(
                                ui = ui,
                                title = "Birthday",
                                playhead = PlayheadUi(7_000_000, schedule.durationUs, false),
                                preview = { m -> Box(m.background(Color(0xFF3A6EA5))) },
                                thumbnail = { _, _ -> null },
                                actions = actions,
                                state = state,
                            )
                        }
                    }
                }
                compose.waitForIdle()
                Thread.sleep(300)
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                FileOutputStream(
                    File(out, "$prefix-rowmenu-$tag-${mode.name.lowercase()}.png"),
                ).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
