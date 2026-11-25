package com.itsluminous.clearmovie.ui.preset

import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.data.preset.PresetParts
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.PresetStoreTest
import com.itsluminous.clearmovie.data.voice.VoiceTools
import com.itsluminous.clearmovie.domain.model.AnimKind
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.Transition
import com.itsluminous.clearmovie.domain.model.TransitionKind
import com.itsluminous.clearmovie.domain.model.VoiceChoice
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeOtherAppProvider
import com.itsluminous.clearmovie.testing.fileDatabase
import com.itsluminous.clearmovie.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executors

/**
 * cm-preset-motion-defaults (Robolectric): the preset editor's view model over
 * a real preset store. The default animation and transition are a draft that
 * survives the system closing the app, and Save stores them. So is the
 * default voice (cm-preset-voice-default), which is off where the voice
 * pack cannot run.
 */
@RunWith(RobolectricTestRunner::class)
class PresetEditMotionViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var db: ClearMovieDatabase
    private lateinit var store: PresetStore

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        Robolectric.setupContentProvider(FakeOtherAppProvider::class.java, PresetStoreTest.DOCS)
        db = fileDatabase(context, File(tmp.root, "db"))
        var n = 0
        store =
            PresetStore(
                assets = context.assets,
                resolver = context.contentResolver,
                gateway = MediaStoreGateway(context),
                db = db,
                files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache")),
                copier = MediaCopier(Dispatchers.IO, bufferBytes = 4096),
                probe = PresetStoreTest.FakeProbe(),
                freeBytes = { 10_000_000_000L },
                dispatcher = Dispatchers.IO,
                randomToken = { "tok" + (++n).toString().padStart(5, '0') },
            )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        main.close()
    }

    private fun viewModel(
        state: SavedStateHandle,
        voices: VoiceTools = VOICES,
    ): PresetEditViewModel {
        val vm = runBlocking(main) { PresetEditViewModel(state, store, { _, _, _ -> null }, voices) }
        await(vm) { it.preset != null }
        return vm
    }

    private fun await(
        vm: PresetEditViewModel,
        done: (PresetEditUi) -> Boolean,
    ): PresetEditUi =
        runBlocking {
            withTimeout(10_000) {
                while (!done(vm.ui.value)) delay(10)
                vm.ui.value
            }
        }

    private fun made(): PresetId =
        runBlocking {
            requireNotNull(store.create("Shaadi", PresetParts(intro = false, frame = false, ending = null)))
        }

    @Test
    fun `the default animation and transition are a draft that survives the app being closed, and Save stores them`() {
        val id = made()
        val state = SavedStateHandle(mapOf(Routes.ARG_PRESET to id.value))
        val vm = viewModel(state)
        // A new preset starts with the template's.
        assertThat(vm.ui.value.photoAnimation).isEqualTo(AnimKind.KEN_BURNS_IN)
        assertThat(vm.ui.value.transition).isEqualTo(Transition(TransitionKind.CROSSFADE, 600))
        runBlocking(main) {
            vm.setPhotoAnimation(AnimKind.KEN_BURNS_OUT)
            // Not a photo animation all through: refused, the draft stays.
            vm.setPhotoAnimation(AnimKind.FADE_IN)
            vm.setTransition(TransitionKind.NONE)
            vm.setTransition(TransitionKind.WIPE_LEFT)
        }
        assertThat(vm.ui.value.photoAnimation).isEqualTo(AnimKind.KEN_BURNS_OUT)
        assertThat(vm.ui.value.transition).isEqualTo(Transition(TransitionKind.WIPE_LEFT, 600))
        // The system closes the app: a new view model on the same saved state has the draft.
        val again = viewModel(state)
        assertThat(again.ui.value.photoAnimation).isEqualTo(AnimKind.KEN_BURNS_OUT)
        assertThat(again.ui.value.transition).isEqualTo(Transition(TransitionKind.WIPE_LEFT, 600))
        // Still only a draft.
        assertThat(runBlocking { store.definition(id) }!!.preset.effects.photoDuring).isEqualTo(AnimKind.KEN_BURNS_IN)
        runBlocking(main) { again.save("Shaadi", "") }
        await(again) { it.draftEffects == null && !it.busy }
        val saved = runBlocking { store.definition(id) }!!.preset.effects
        assertThat(saved.photoDuring).isEqualTo(AnimKind.KEN_BURNS_OUT)
        assertThat(saved.mainTransition).isEqualTo(Transition(TransitionKind.WIPE_LEFT, 600))
        // The caption animation was not touched.
        assertThat(saved.textStart).isEqualTo(AnimKind.FADE_IN)
    }

    @Test
    fun `reverting the effects puts the draft back and leaves the caption draft alone`() {
        val vm = viewModel(SavedStateHandle(mapOf(Routes.ARG_PRESET to made().value)))
        runBlocking(main) {
            vm.setWords("Swagat")
            vm.setTransition(TransitionKind.CIRCLE)
            vm.revertEffects(null)
        }
        assertThat(vm.ui.value.transition).isEqualTo(Transition(TransitionKind.CROSSFADE, 600))
        assertThat(vm.ui.value.draftEffects).isNull()
        assertThat(vm.ui.value.draftWords).isEqualTo("Swagat")
    }

    @Test
    fun `the default voice is a draft that survives the app being closed, and Save stores it`() {
        val id = made()
        val state = SavedStateHandle(mapOf(Routes.ARG_PRESET to id.value))
        val vm = viewModel(state)
        // A new preset starts with No change.
        assertThat(vm.ui.value.voice).isEqualTo(VoiceChoice.NONE)
        assertThat(vm.ui.value.voiceAvailable).isTrue()
        runBlocking(main) { vm.setVoice(VoiceChoice.INDIAN_FEMALE) }
        assertThat(vm.ui.value.voice).isEqualTo(VoiceChoice.INDIAN_FEMALE)
        val again = viewModel(state)
        assertThat(again.ui.value.voice).isEqualTo(VoiceChoice.INDIAN_FEMALE)
        assertThat(runBlocking { store.definition(id) }!!.preset.voice).isEqualTo(VoiceChoice.NONE)
        runBlocking(main) { again.save("Shaadi", "") }
        await(again) { it.draftVoice == null && !it.busy }
        val saved = runBlocking { store.definition(id) }!!.preset
        assertThat(saved.voice).isEqualTo(VoiceChoice.INDIAN_FEMALE)
        // The rest of the preset is as it was.
        assertThat(saved.effects.photoDuring).isEqualTo(AnimKind.KEN_BURNS_IN)
        // Back to No change, and saved again (the screen reads the preset again when it shows).
        runBlocking(main) { again.refresh() }
        await(again) { it.preset?.voice == VoiceChoice.INDIAN_FEMALE }
        runBlocking(main) { again.setVoice(VoiceChoice.NONE) }
        assertThat(PresetEditViewModel.changedPreset(saved, again.ui.value, "Shaadi", "")?.voice).isEqualTo(VoiceChoice.NONE)
        runBlocking(main) { again.save("Shaadi", "") }
        await(again) { it.draftVoice == null && !it.busy }
        assertThat(runBlocking { store.definition(id) }!!.preset.voice).isEqualTo(VoiceChoice.NONE)
    }

    @Test
    fun `choosing the voice it already has changes nothing to save`() {
        val vm = viewModel(SavedStateHandle(mapOf(Routes.ARG_PRESET to made().value)))
        runBlocking(main) { vm.setVoice(VoiceChoice.NONE) }
        val preset = vm.ui.value.preset!!
        assertThat(PresetEditViewModel.changedPreset(preset, vm.ui.value, "Shaadi", "")).isNull()
    }

    @Test
    fun `where the voice pack cannot run the default voice is off and cannot be changed`() {
        val vm = viewModel(SavedStateHandle(mapOf(Routes.ARG_PRESET to made().value)), VoiceTools.NONE)
        assertThat(vm.ui.value.voiceAvailable).isFalse()
        runBlocking(main) { vm.setVoice(VoiceChoice.BRITISH_MALE) }
        assertThat(vm.ui.value.draftVoice).isNull()
        assertThat(vm.ui.value.voice).isEqualTo(VoiceChoice.NONE)
    }

    private companion object {
        /** Voices that can run (the pack's files need not be there: the choice never asks for them). */
        val VOICES: VoiceTools =
            object : VoiceTools by VoiceTools.NONE {
                override val supported = true
            }
    }
}
