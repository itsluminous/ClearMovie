package com.itsluminous.clearmovie.data.preset

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.db.PresetDefinitionEntity
import com.itsluminous.clearmovie.data.db.ProjectEntity
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.CanvasShape
import com.itsluminous.clearmovie.domain.model.CaptionStyle
import com.itsluminous.clearmovie.domain.model.EndingKind
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.NormRect
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.TextBackground
import com.itsluminous.clearmovie.domain.model.refitBox
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeOtherAppProvider
import com.itsluminous.clearmovie.testing.fileDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.imageio.ImageIO

/**
 * cm-preset-manager (Robolectric): making, editing, resetting and deleting
 * presets, what a deleted preset leaves for the videos made from it, and the
 * built-in preset's caption defaults reaching installs that did not edit it.
 */
@RunWith(RobolectricTestRunner::class)
class PresetManagerStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: FakeOtherAppProvider
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private val probe = PresetStoreTest.FakeProbe()
    private var free = 10_000_000_000L
    private var now = 1_000L
    private lateinit var store: PresetStore
    private val builtIn = PresetId("event-frame")

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        phone = Robolectric.setupContentProvider(FakeOtherAppProvider::class.java, PresetStoreTest.DOCS)
        db = fileDatabase(context, File(tmp.root, "db"))
        files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
        var n = 0
        store =
            PresetStore(
                assets = context.assets,
                resolver = context.contentResolver,
                gateway = MediaStoreGateway(context),
                db = db,
                files = files,
                copier = MediaCopier(Dispatchers.IO, bufferBytes = 4096),
                probe = probe,
                freeBytes = { free },
                clock = { now },
                dispatcher = Dispatchers.IO,
                randomToken = { "tok" + (++n).toString().padStart(5, '0') },
            )
        probe.frames = frames()
    }

    @After
    fun tearDown() = db.close()

    private fun file(
        name: String,
        bytes: ByteArray,
        mime: String,
    ): Uri {
        val uri = Uri.parse("content://${PresetStoreTest.DOCS}/document/${Uri.encode(name)}")
        phone.add(FakeMediaProvider.Row(uri, name, bytes.size.toLong(), mime, bytes = bytes))
        return uri
    }

    private fun frames() =
        listOf(10, 50, 90).map { pct ->
            val image = javaClass.classLoader!!.getResourceAsStream("fixtures/media/frame-original-$pct.png").use { ImageIO.read(it) }
            FrameSample(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        }

    private suspend fun bindAll(id: PresetId) {
        assertThat(
            store.bindSlot(
                id,
                PresetSlot.INTRO,
                file(
                    "i-${id.value}.mp4",
                    ByteArray(500) {
                        1
                    },
                    "video/mp4",
                ),
            ),
        ).isInstanceOf(SlotBindResult.Bound::class.java)
        assertThat(
            store.bindSlot(
                id,
                PresetSlot.FRAME,
                file(
                    "f-${id.value}.mp4",
                    ByteArray(700) {
                        2
                    },
                    "video/mp4",
                ),
            ),
        ).isInstanceOf(SlotBindResult.Bound::class.java)
        assertThat(
            store.bindSlot(
                id,
                PresetSlot.ENDING,
                file(
                    "e-${id.value}.mp3",
                    ByteArray(90) {
                        3
                    },
                    "audio/mpeg",
                ),
            ),
        ).isInstanceOf(SlotBindResult.Bound::class.java)
    }

    /** A video made from [id], as the repository stores it (only the preset column matters here). */
    private suspend fun videoFrom(
        id: PresetId,
        video: String = "v1",
    ) = db.projects().upsert(ProjectEntity(video, null, id.value, 2, "{}", null, now, now))

    @Test
    fun `a new preset has its name and parts, the built-in caption style, no files, and comes after the built-in one`() =
        runBlocking<Unit> {
            val id = store.create("  Spasht Dhaara  ", PresetParts(intro = false, frame = true, ending = EndingKind.VIDEO))!!
            assertThat(id.value).startsWith(PresetStore.OWN_PREFIX)
            val made = store.definition(id)!!
            assertThat(made.builtIn).isFalse()
            assertThat(made.preset.displayName).isEqualTo(LocalizedText.same("Spasht Dhaara"))
            assertThat(made.preset.displayNameRes).isNull()
            assertThat(made.preset.intro).isNull()
            assertThat(made.preset.ending?.kind).isEqualTo(EndingKind.VIDEO)
            assertThat(made.preset.frame).isEqualTo(store.definition(builtIn)!!.preset.frame)
            assertThat(made.preset.caption).isEqualTo(store.definition(builtIn)!!.preset.caption)
            val status = store.status(id)
            assertThat(status.needsSetup).containsExactly(PresetSlot.FRAME, PresetSlot.ENDING)
            assertThat(status.slots[PresetSlot.INTRO]).isEqualTo(SlotStatus.NotNeeded)
            now += 10
            val second = store.create("Second", PresetParts())!!
            assertThat(store.definitions().map { it.preset.id }).containsExactly(builtIn, id, second).inOrder()
        }

    @Test
    fun `editing saves the name, words and caption style, keeps the files, and refuses changing the parts`() =
        runBlocking<Unit> {
            val id = store.create("Mine", PresetParts())!!
            bindAll(id)
            val preset = store.definition(id)!!.preset
            val style = preset.caption.copy(sizePx = 80, color = ArgbColor(0xFFFFEB3BL), background = null, outline = null)
            val edited =
                preset.copy(
                    displayName = LocalizedText.same("Renamed"),
                    caption = style,
                    defaultText = LocalizedText.same("नमस्ते"),
                )
            assertThat(store.save(edited)).isEqualTo(PresetSaveResult.Saved(edited))
            assertThat(store.definition(id)!!.preset).isEqualTo(edited)
            assertThat(store.status(id).isReady).isTrue()
            // Only the one definition file is kept.
            assertThat(files.presetDir(id).listFiles()!!.count { it.name.startsWith("preset-") }).isEqualTo(1)
            assertThat(store.save(edited.copy(intro = null))).isEqualTo(PresetSaveResult.Invalid)
            assertThat(store.save(edited.copy(ending = null))).isEqualTo(PresetSaveResult.Invalid)
            assertThat(store.save(edited.copy(id = PresetId("my-unknown")))).isEqualTo(PresetSaveResult.NotSaved)
            // A value the parser would refuse never reaches the disk.
            assertThat(store.save(edited.copy(caption = style.copy(sizePx = 9999)))).isEqualTo(PresetSaveResult.Invalid)
            assertThat(store.definition(id)!!.preset).isEqualTo(edited)
        }

    @Test
    fun `a new preset takes its shape, with the caption place refitted, and keeps it until a video uses it`() =
        runBlocking<Unit> {
            val template = store.definition(builtIn)!!.preset
            assertThat(template.shape).isEqualTo(CanvasShape.LANDSCAPE)
            val id = store.create("Upright", PresetParts(shape = CanvasShape.SQUARE))!!
            val made = store.definition(id)!!.preset
            assertThat(made.shape).isEqualTo(CanvasShape.SQUARE)
            assertThat(made.caption.box).isEqualTo(refitBox(template.caption.box, CanvasShape.LANDSCAPE, CanvasShape.SQUARE))
            // Its shape is written to its preset.json, and read back.
            val json =
                files
                    .presetDir(id)
                    .listFiles()!!
                    .single { it.name.startsWith("preset-") }
                    .readText()
            assertThat(json).contains("\"shape\": \"square\"")
            // No video uses it yet: its shape may change.
            assertThat(store.save(made.copy(shape = CanvasShape.PORTRAIT))).isInstanceOf(PresetSaveResult.Saved::class.java)
            assertThat(store.definition(id)!!.preset.shape).isEqualTo(CanvasShape.PORTRAIT)
            videoFrom(id)
            assertThat(store.videosUsing(id)).isEqualTo(1)
            assertThat(store.save(made.copy(shape = CanvasShape.LANDSCAPE))).isEqualTo(PresetSaveResult.Invalid)
            assertThat(store.definition(id)!!.preset.shape).isEqualTo(CanvasShape.PORTRAIT)
            // Its other fields still save.
            assertThat(store.save(store.definition(id)!!.preset.copy(displayName = LocalizedText.same("Tall"))))
                .isInstanceOf(PresetSaveResult.Saved::class.java)
        }

    @Test
    fun `a frame of another shape than the preset's is kept, and setup is told`() =
        runBlocking<Unit> {
            val id = store.create("Upright", PresetParts(shape = CanvasShape.PORTRAIT))!!
            probe.shown = 1920 to 1080
            val wide = store.bindSlot(id, PresetSlot.FRAME, file("wide.mp4", ByteArray(700) { 2 }, "video/mp4"))
            assertThat((wide as SlotBindResult.Bound).frameShape).isEqualTo(CanvasShape.LANDSCAPE)
            assertThat(store.status(id).slots[PresetSlot.FRAME]).isInstanceOf(SlotStatus.Ready::class.java)
            probe.shown = 1080 to 1920
            val tall = store.bindSlot(id, PresetSlot.FRAME, file("tall.mp4", ByteArray(700) { 4 }, "video/mp4"))
            assertThat((tall as SlotBindResult.Bound).frameShape).isNull()
            // An intro of any shape says nothing: it is fitted whole.
            probe.shown = 1920 to 1080
            val intro = store.bindSlot(id, PresetSlot.INTRO, file("intro.mp4", ByteArray(500) { 1 }, "video/mp4"))
            assertThat((intro as SlotBindResult.Bound).frameShape).isNull()
        }

    @Test
    fun `an edited built-in preset is an override that reset removes, its files staying`() =
        runBlocking<Unit> {
            bindAll(builtIn)
            val shipped = store.definition(builtIn)!!
            assertThat(shipped.edited).isFalse()
            val edited = shipped.preset.copy(displayName = LocalizedText.same("Spasht Dhaara"))
            assertThat(store.save(edited)).isInstanceOf(PresetSaveResult.Saved::class.java)
            val current = store.definition(builtIn)!!
            assertThat(current.edited).isTrue()
            assertThat(current.builtIn).isTrue()
            assertThat(current.preset).isEqualTo(edited)
            assertThat(store.delete(builtIn)).isEqualTo(PresetDeleteResult.BuiltIn)
            assertThat(store.reset(builtIn)).isTrue()
            assertThat(store.definition(builtIn)!!.preset).isEqualTo(shipped.preset)
            assertThat(store.definition(builtIn)!!.edited).isFalse()
            assertThat(store.status(builtIn).isReady).isTrue()
            assertThat(store.reset(builtIn)).isFalse()
        }

    @Test
    fun `deleting a preset no video uses removes it and its files`() =
        runBlocking<Unit> {
            val id = store.create("Mine", PresetParts())!!
            bindAll(id)
            assertThat(files.presetDir(id).exists()).isTrue()
            assertThat(store.delete(id)).isEqualTo(PresetDeleteResult.Deleted)
            assertThat(store.definition(id)).isNull()
            assertThat(db.presets().bindings(id.value)).isEmpty()
            assertThat(files.presetDir(id).exists()).isFalse()
            assertThat(store.delete(id)).isEqualTo(PresetDeleteResult.Unknown)
        }

    @Test
    fun `a deleted preset that videos use is hidden but keeps working for them until the last one goes`() =
        runBlocking<Unit> {
            val id = store.create("Mine", PresetParts())!!
            bindAll(id)
            videoFrom(id)
            assertThat(store.delete(id)).isEqualTo(PresetDeleteResult.Retired(1))
            assertThat(store.definitions().map { it.preset.id }).doesNotContain(id)
            // The video still finds its preset, its files and their lengths.
            val definition = store.definition(id)!!
            assertThat(definition.retired).isTrue()
            assertThat(store.status(id).isReady).isTrue()
            assertThat(store.effectivePreset(id)).isNotNull()
            assertThat(store.verifyBeforeExport(id).isReady).isTrue()
            assertThat(store.sweepRetired()).isEqualTo(0)
            assertThat(files.presetDir(id).exists()).isTrue()
            db.projects().delete("v1")
            assertThat(store.sweepRetired()).isEqualTo(1)
            assertThat(store.definition(id)).isNull()
            assertThat(files.presetDir(id).exists()).isFalse()
        }

    @Test
    fun `a project made from a preset carries its caption style and words, so later edits leave it as it was`() =
        runBlocking<Unit> {
            val id = store.create("Mine", PresetParts())!!
            bindAll(id)
            val first = store.definition(id)!!.preset.copy(defaultText = LocalizedText.same("पहला"))
            store.save(first)
            val project = ProjectFactory.fromPreset(ProjectId("p"), store.effectivePreset(id)!!, store.presetMedia(id))
            assertThat(project.captionDefaults).isEqualTo(first.caption)
            assertThat(project.captionDefaultText).isEqualTo(LocalizedText.same("पहला"))
            store.save(first.copy(caption = first.caption.copy(sizePx = 100)))
            assertThat(project.captionDefaults.sizePx).isEqualTo(first.caption.sizePx)
        }

    @Test
    fun `the built-in preset puts a plain caption on the frame's blue strip, with its words`() =
        runBlocking<Unit> {
            val caption = store.definition(builtIn)!!.preset
            // Measured on old/Frame-White.mp4 (1920 x 1080): the blue strip is rows 862-993, columns 272-1901.
            assertThat(caption.caption.box).isEqualTo(NormRect(0.15, 0.7981, 0.83, 0.1222))
            assertThat(caption.caption.background).isNull()
            assertThat(caption.caption.outline).isNull()
            assertThat(caption.caption.color).isEqualTo(ArgbColor.WHITE)
            assertThat(caption.defaultText).isEqualTo(LocalizedText(hi = "सुंदर प्रकृति"))
        }

    @Test
    fun `an install with its own preset file for the built-in preset keeps it, one without gets the new defaults`() =
        runBlocking<Unit> {
            // Before: a user preset.json (the old caption, in a box) stored as an override.
            val old =
                store
                    .definition(
                        builtIn,
                    )!!
                    .preset
                    .copy(caption = CaptionStyle.STANDARD.copy(background = TextBackground(ArgbColor(0x99000000L), 12)))
            assertThat(store.save(old)).isInstanceOf(PresetSaveResult.Saved::class.java)
            assertThat(store.definition(builtIn)!!.preset.caption).isEqualTo(old.caption)
            store.reset(builtIn)
            assertThat(
                store
                    .definition(builtIn)!!
                    .preset.caption.background,
            ).isNull()
        }

    @Test
    fun `a definition row from before version 2 reads with createdAt 0 and not deleted`() =
        runBlocking<Unit> {
            val id = store.create("Mine", PresetParts())!!
            val row = db.presets().definition(id.value)!!
            db.presets().upsertDefinition(
                PresetDefinitionEntity(row.presetId, row.fileName, row.sha256, row.sizeBytes, row.originalName, row.boundAt),
            )
            val again = db.presets().definition(id.value)!!
            assertThat(again.createdAt).isEqualTo(0)
            assertThat(again.retiredAt).isNull()
            assertThat(store.definitions().map { it.preset.id }).contains(id)
        }

    @Test
    fun `a new preset is not made when the phone is full`() =
        runBlocking<Unit> {
            free = 0
            assertThat(store.create("Mine", PresetParts())).isNull()
            assertThat(store.definitions()).hasSize(1)
        }

    @Test
    fun `binding a frame to a preset the user made measures its own green window`() =
        runBlocking<Unit> {
            val id = store.create("Mine", PresetParts())!!
            val copied =
                store
                    .definition(id)!!
                    .preset.frame!!
                    .contentRect
            // A frame whose window is the left half: green on the left, grey on the right.
            probe.frames =
                List(3) {
                    FrameSample(
                        200,
                        100,
                        IntArray(200 * 100) { i ->
                            if (i % 200 <
                                100
                            ) {
                                0xFF00D922.toInt()
                            } else {
                                0xFF808080.toInt()
                            }
                        },
                    )
                }
            assertThat(
                store.bindSlot(
                    id,
                    PresetSlot.FRAME,
                    file(
                        "half.mp4",
                        ByteArray(700) {
                            2
                        },
                        "video/mp4",
                    ),
                ),
            ).isInstanceOf(SlotBindResult.Bound::class.java)
            val window =
                store
                    .definition(id)!!
                    .preset.frame!!
                    .contentRect
            assertThat(window).isEqualTo(NormRect(0.0, 0.0, 0.5, 1.0))
            assertThat(window).isNotEqualTo(copied)
            // The built-in preset keeps the window its file names, whatever its frame measures.
            assertThat(
                store.bindSlot(
                    builtIn,
                    PresetSlot.FRAME,
                    file(
                        "half2.mp4",
                        ByteArray(700) {
                            2
                        },
                        "video/mp4",
                    ),
                ),
            ).isInstanceOf(SlotBindResult.Bound::class.java)
            assertThat(
                store
                    .definition(builtIn)!!
                    .preset.frame!!
                    .contentRect,
            ).isEqualTo(copied)
            assertThat(store.definition(builtIn)!!.edited).isFalse()
        }

    @Test
    fun `the reference frame's window is measured as the window the built-in preset names`() {
        val measured = KeyColorCheck.window(frames())!!
        val named = NormRect(0.0094, 0.0222, 0.9813, 0.7741)
        for ((a, b) in listOf(measured.x to named.x, measured.y to named.y, measured.w to named.w, measured.h to named.h)) {
            assertThat(a).isWithin(0.005).of(b)
        }
        // No green: no window.
        assertThat(KeyColorCheck.window(listOf(FrameSample(10, 10, IntArray(100) { 0xFF808080.toInt() })))).isNull()
    }
}
