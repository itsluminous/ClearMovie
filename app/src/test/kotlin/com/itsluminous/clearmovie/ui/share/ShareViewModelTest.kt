package com.itsluminous.clearmovie.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.media.CaptureTimeReader
import com.itsluminous.clearmovie.data.media.MediaAccess
import com.itsluminous.clearmovie.data.media.MediaAccessState
import com.itsluminous.clearmovie.data.media.MediaCopier
import com.itsluminous.clearmovie.data.media.MediaFacts
import com.itsluminous.clearmovie.data.media.MediaFactsProbe
import com.itsluminous.clearmovie.data.media.MediaResolver
import com.itsluminous.clearmovie.data.media.MediaStoreGateway
import com.itsluminous.clearmovie.data.media.PendingShare
import com.itsluminous.clearmovie.data.media.ResolverShareProbe
import com.itsluminous.clearmovie.data.media.ShareLinks
import com.itsluminous.clearmovie.data.media.ShareProbe
import com.itsluminous.clearmovie.data.preset.FrameSample
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.data.preset.PresetStoreTest
import com.itsluminous.clearmovie.data.project.LaunchDecision
import com.itsluminous.clearmovie.data.project.ProjectRepository
import com.itsluminous.clearmovie.data.project.ShareDrafts
import com.itsluminous.clearmovie.data.settings.SettingsRepository
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.EndingClip
import com.itsluminous.clearmovie.domain.model.IntroClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.PresetId
import com.itsluminous.clearmovie.domain.model.PresetSlot
import com.itsluminous.clearmovie.domain.model.ProjectFactory
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeOtherAppProvider
import com.itsluminous.clearmovie.testing.FakeUriGrants
import com.itsluminous.clearmovie.testing.fileDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * ROADMAP cm-share acceptance (Robolectric): a single photo; several; a mix
 * of photos and videos in capture order; a PDF in a mixed share skipped and
 * counted; a revoked link reported, not crashed on; another app's file
 * matched by name and size or copied; a share while a video is open asks
 * first; the preset pick and a half-done intake survive a process death; the
 * 100+ notice; and the preset's intro and ending applied to the shared items.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeMediaProvider
    private lateinit var chat: FakeOtherAppProvider
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private lateinit var presets: PresetStore
    private val presetProbe = PresetStoreTest.FakeProbe()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()
    private val grants = FakeUriGrants()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        store = Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        chat = Robolectric.setupContentProvider(FakeOtherAppProvider::class.java, CHAT)
        db = fileDatabase(context, File(tmp.root, "db"))
        files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
        presets =
            PresetStore(
                context.assets,
                context.contentResolver,
                MediaStoreGateway(context),
                db,
                files,
                MediaCopier(Dispatchers.IO, bufferBytes = 4096),
                presetProbe,
                { 50_000_000_000L },
                dispatcher = Dispatchers.IO,
            )
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
        Dispatchers.resetMain()
        main.close()
    }

    private fun repository() = ProjectRepository(db, files, scope())

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private fun access(): MediaAccess {
        val gateway = MediaStoreGateway(context)
        return MediaAccess(
            context.contentResolver,
            MediaResolver(gateway, grants, context.packageName),
            gateway,
            grants,
            MediaCopier(),
            files,
            db,
            { 50_000_000_000L },
        )
    }

    /** Photos by type; a video plays 5 s. */
    private val probe =
        MediaFactsProbe { _, _, mime ->
            when {
                mime?.startsWith("image/") == true -> MediaFacts(null, hasVideo = false, hasAudio = false, isImage = true)
                mime?.startsWith("video/") == true -> MediaFacts(5_000, hasVideo = true, hasAudio = true, isImage = false)
                else -> null
            }
        }

    /** The provider's capture date, as `AndroidCaptureTimeReader` reads first. */
    private val times = CaptureTimeReader { _, item -> item.info?.dateTakenMs }

    private val settings =
        object : SettingsRepository {
            override val settings = flowOf(AppSettings())
            override val mediaAccessAsked = flowOf(true)

            override suspend fun setMediaAccessAsked() = Unit

            override suspend fun setThemeMode(mode: ThemeMode) = Unit

            override suspend fun setDynamicColor(enabled: Boolean) = Unit
        }

    private val pendingFile get() = File(tmp.root, "share/pending.links")

    private class Made(
        val vm: ShareViewModel,
        val nav: MutableList<ShareNav>,
    )

    private fun make(
        repo: ProjectRepository,
        saved: SavedStateHandle = SavedStateHandle(),
        shareProbe: ShareProbe = ResolverShareProbe(context.contentResolver),
    ): Made {
        val vm =
            runBlocking(main) {
                ShareViewModel(
                    saved,
                    repo,
                    access(),
                    probe,
                    times,
                    shareProbe,
                    ShareDrafts(presets, repo),
                    PendingShare(pendingFile),
                    settings,
                    Dispatchers.IO,
                )
            }
        val nav = CopyOnWriteArrayList<ShareNav>()
        scope().launch { vm.nav.collect { nav += it } }
        return Made(vm, nav)
    }

    private fun send(vararg uris: Uri): Intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
            clipData = ClipData.newRawUri(null, uris.first()).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        }

    private inline fun <reified T : ShareState> Made.await(): T =
        runBlocking { withTimeout(TIMEOUT_MS) { vm.state.first { it is T } } as T }

    private fun Made.awaitNav(predicate: (ShareNav) -> Boolean) =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                while (nav.none(predicate)) kotlinx.coroutines.delay(10)
            }
        }

    private fun Made.on(block: ShareViewModel.() -> Unit) = runBlocking(main) { vm.block() }

    /** Receives [intent] and, for a new video, settles the access question as the preset pick does. */
    private fun Made.share(
        intent: Intent,
        access: MediaAccessState = MediaAccessState.Full,
    ): ShareState {
        val before = vm.state.value
        on { receive(intent) }
        val first = runBlocking { withTimeout(TIMEOUT_MS) { vm.state.first { it != before } } }
        if (first is ShareState.Waiting) on { begin(access) }
        return first
    }

    private fun photo(
        name: String,
        taken: Long? = null,
    ) = store.addMedia(name, 10, dateTaken = taken)

    private fun video(
        name: String,
        taken: Long? = null,
    ) = store.addMedia(name, 10, video = true, dateTaken = taken)

    @Test
    fun `a single photo is taken in at once and waits on the share's preset pick`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = make(repo)
            val a = photo("a.jpg", taken = 1)

            assertThat(m.share(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, a))).isEqualTo(ShareState.Waiting(1, null))
            val ready = m.await<ShareState.Ready>()

            assertThat(ready.items).isEqualTo(1)
            assertThat(ready.notes.any).isFalse()
            assertThat(m.nav).contains(ShareNav.ToPick)
            val draft = repo.projectList().single()
            assertThat(draft.id).isEqualTo(ready.draft)
            assertThat(
                repo.open.value!!
                    .mainClips
                    .map { (it as PhotoClip).media },
            ).containsExactly(MediaRef.Store(a.toString()))
            // cm-flow D5: only the editor puts a video in the session.
            assertThat(repo.launchDecision()).isEqualTo(LaunchDecision.Home)
            assertThat(pendingFile.exists()).isFalse()
        }

    @Test
    fun `a mix of photos and videos goes in capture order, undated items keep their place, and a PDF is skipped and counted`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = make(repo)
            val v20 = video("v20.mp4", taken = 20)
            val p30 = photo("p30.jpg", taken = 30)
            val pdf = Uri.parse("content://$CHAT/files/doc.pdf")
            chat.add(FakeMediaProvider.Row(pdf, "doc.pdf", 10, "application/pdf", bytes = ByteArray(10)))
            val undated = photo("undated.jpg")
            val p10 = photo("p10.jpg", taken = 10)

            m.share(send(v20, p30, pdf, undated, p10))
            val ready = m.await<ShareState.Ready>()

            assertThat(ready.items).isEqualTo(4)
            assertThat(ready.notes).isEqualTo(ShareNotes(notMedia = 1))
            val clips = repo.open.value!!.mainClips
            assertThat(clips.map { (it as? PhotoClip)?.media ?: (it as VideoClip).media })
                .containsExactly(
                    MediaRef.Store(p10.toString()),
                    MediaRef.Store(v20.toString()),
                    MediaRef.Store(undated.toString()),
                    MediaRef.Store(p30.toString()),
                ).inOrder()
            assertThat(clips[1]).isInstanceOf(VideoClip::class.java)
        }

    @Test
    fun `a link whose grant is gone is counted, and a share of only such links adds nothing and leaves no draft`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = make(repo)
            val revoked = Uri.parse("content://$CHAT/files/gone.jpg")
            chat.add(FakeMediaProvider.Row(revoked, "gone.jpg", 10, "image/jpeg", bytes = ByteArray(10)))
            chat.forbidden += revoked.toString()

            m.share(send(revoked))
            val nothing = m.await<ShareState.Nothing>()

            assertThat(nothing.notes.unreadable).isEqualTo(1)
            assertThat(repo.projectList()).isEmpty()
            m.on { dismiss() }
            m.awaitNav { it == ShareNav.Home }

            // Beside a good one, the revoked link is reported on the pick.
            val good = photo("ok.jpg")
            m.share(send(good, revoked))
            val ready = m.await<ShareState.Ready>()
            assertThat(ready.items).isEqualTo(1)
            assertThat(ready.notes.unreadable).isEqualTo(1)
        }

    @Test
    fun `another app's file is linked to the same item in MediaStore, else copied while the share's grant lasts`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = make(repo)
            val inGallery = photo("party.jpg")
            val viaChat = Uri.parse("content://$CHAT/files/party.jpg")
            chat.add(FakeMediaProvider.Row(viaChat, "party.jpg", 10, "image/jpeg", bytes = ByteArray(10) { 7 }))
            val chatOnly = Uri.parse("content://$CHAT/files/forwarded.jpg")
            chat.add(FakeMediaProvider.Row(chatOnly, "forwarded.jpg", 12, "image/jpeg", bytes = ByteArray(12) { 9 }))

            m.share(send(viaChat, chatOnly))
            val ready = m.await<ShareState.Ready>()

            val media =
                repo.open.value!!
                    .mainClips
                    .map { (it as PhotoClip).media }
            assertThat(media[0]).isEqualTo(MediaRef.Store(inGallery.toString()))
            val copied = media[1] as MediaRef.Copied
            assertThat(files.copiedFile(ready.draft, copied).readBytes()).isEqualTo(ByteArray(12) { 9 })
        }

    @Test
    fun `a share while a video is open in the editor asks, and Add puts the items at its end`() =
        runBlocking<Unit> {
            val repo = repository()
            val open = ProjectId("open1")
            val first = photo("first.jpg")
            val project =
                ProjectFactory
                    .blank(
                        open,
                    ).addToMain(
                        listOf(
                            PhotoClip(
                                com.itsluminous.clearmovie.domain.model
                                    .ClipId("c1"),
                                MediaRef.Store(first.toString()),
                                3000,
                            ),
                        ),
                    )
            assertThat(repo.createDraft(project, inEditor = true)).isTrue()
            val m = make(repo)
            val added = photo("added.jpg")

            assertThat(m.share(send(added))).isEqualTo(ShareState.Asking(1, open))
            m.on { addToOpen() }
            m.on { begin(MediaAccessState.Full) }
            val done = m.await<ShareState.Added>()

            assertThat(done.added).isEqualTo(1)
            m.awaitNav { it == ShareNav.ToEditor(open) }
            assertThat(m.nav).doesNotContain(ShareNav.ToPick)
            val clips =
                repo.open.value!!
                    .mainClips
                    .map { (it as PhotoClip).media }
            assertThat(clips).containsExactly(MediaRef.Store(first.toString()), MediaRef.Store(added.toString())).inOrder()
            assertThat(repo.projectList().map { it.id }).containsExactly(open)
            m.on { dismiss() }
            assertThat(m.vm.state.value).isEqualTo(ShareState.Idle)
        }

    @Test
    fun `Start a new video keeps the open video, saved`() =
        runBlocking<Unit> {
            val repo = repository()
            val open = ProjectId("open2")
            assertThat(repo.createDraft(ProjectFactory.blank(open), inEditor = true)).isTrue()
            val m = make(repo)

            m.share(send(photo("n.jpg")))
            m.on { startNewVideo() }
            m.awaitNav { it == ShareNav.ToPick }
            m.on { begin(MediaAccessState.Full) }
            val ready = m.await<ShareState.Ready>()

            assertThat(ready.draft).isNotEqualTo(open)
            assertThat(repo.projectList().map { it.id }).containsExactly(open, ready.draft)
        }

    @Test
    fun `Cancel on the question drops the share and changes nothing`() =
        runBlocking<Unit> {
            val repo = repository()
            val open = ProjectId("open3")
            assertThat(repo.createDraft(ProjectFactory.blank(open), inEditor = true)).isTrue()
            val m = make(repo)

            m.share(send(photo("x.jpg")))
            m.on { cancel() }

            assertThat(m.vm.state.value).isEqualTo(ShareState.Idle)
            assertThat(repo.projectList().map { it.id }).containsExactly(open)
            assertThat(m.nav).doesNotContain(ShareNav.Home)
            assertThat(pendingFile.exists()).isFalse()
        }

    @Test
    fun `the share's preset pick survives a process death with its items`() =
        runBlocking<Unit> {
            val saved = SavedStateHandle()
            val m = make(repository(), saved)
            m.share(send(photo("a.jpg"), photo("b.jpg")))
            val ready = m.await<ShareState.Ready>()

            // The new process: a new repository with nothing open, the saved state as Android hands it back, no new intent.
            val again = make(repository(), SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
            val restored = again.await<ShareState.Ready>()

            assertThat(restored.draft).isEqualTo(ready.draft)
            assertThat(restored.items).isEqualTo(2)
        }

    @Test
    fun `a process death while taking the items in takes them in again, without the half-made draft`() =
        runBlocking<Unit> {
            val repo = repository()
            val stale = ProjectId("stale")
            assertThat(repo.createDraft(ProjectFactory.blank(stale), inEditor = false)).isTrue()
            files.projectMediaDir(stale).mkdirs()
            File(files.projectMediaDir(stale), "half.jpg.part").writeText("x")
            val a = photo("a.jpg")
            PendingShare(pendingFile).save(listOf(a))
            val saved =
                SavedStateHandle(
                    mapOf(
                        ShareViewModel.KEY_PHASE to ShareViewModel.PHASE_TAKING_IN,
                        ShareViewModel.KEY_DRAFT to stale.value,
                        ShareViewModel.KEY_ON_PICK to true,
                    ),
                )
            val m = make(repository(), saved)

            assertThat(m.await<ShareState.Waiting>()).isEqualTo(ShareState.Waiting(1, null))
            m.on { begin(MediaAccessState.Full) }
            val ready = m.await<ShareState.Ready>()

            assertThat(ready.draft).isNotEqualTo(stale)
            assertThat(ready.items).isEqualTo(1)
            assertThat(repository().projectList().map { it.id }).containsExactly(ready.draft)
            assertThat(files.projectDir(stale).exists()).isFalse()
        }

    @Test
    fun `more than 100 items say how long the video gets first, and Cancel adds nothing`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = make(repo)
            val many = (1..101).map { photo("p$it.jpg") }.toTypedArray()

            m.share(send(*many))
            val notice = m.await<ShareState.ManyItems>()

            assertThat(notice.count).isEqualTo(101)
            // 101 photos at 3 s: about 5 minutes.
            assertThat(notice.minutes).isEqualTo(5)
            assertThat(repo.projectList()).isEmpty()
            m.on { cancel() }
            m.awaitNav { it == ShareNav.Home }
            assertThat(m.vm.state.value).isEqualTo(ShareState.Idle)
            assertThat(repo.projectList()).isEmpty()

            m.share(send(*many))
            m.await<ShareState.ManyItems>()
            m.on { continueMany() }
            assertThat(m.await<ShareState.Ready>().items).isEqualTo(101)
        }

    @Test
    fun `links past 500 are left out and counted`() =
        runBlocking<Unit> {
            val m = make(repository())
            val uris = (1..503).map { Uri.parse("content://media/external/images/media/${10_000 + it}") }
            uris.forEach { store.add(FakeMediaProvider.Row(it, "${it.lastPathSegment}.jpg", 10, "image/jpeg")) }

            m.share(send(*uris.toTypedArray()))
            m.await<ShareState.ManyItems>().let { assertThat(it.count).isEqualTo(ShareLinks.MAX_ITEMS) }
            m.on { continueMany() }
            val ready = m.await<ShareState.Ready>()

            assertThat(ready.items).isEqualTo(ShareLinks.MAX_ITEMS)
            assertThat(ready.notes.overLimit).isEqualTo(3)
        }

    @Test
    fun `Start without a preset opens the editor, and a preset not set up goes to its setup first`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = make(repo)
            m.share(send(photo("a.jpg")))
            val ready = m.await<ShareState.Ready>()

            m.on { choose(PresetId("event-frame")) }
            m.awaitNav { it == ShareNav.ToSetup(PresetId("event-frame"), ready.draft) }
            m.on { choose(null) }
            m.awaitNav { it == ShareNav.ToEditor(ready.draft) }
            assertThat(repo.open.value!!.presetId).isNull()

            m.on { editorShown(ready.draft) }
            assertThat(m.vm.state.value).isEqualTo(ShareState.Idle)
        }

    @Test
    fun `a chosen preset brings its intro, frame and ending around the shared items, which keep their order`() =
        runBlocking<Unit> {
            bindPreset()
            val repo = repository()
            val m = make(repo)
            val a = photo("a.jpg", taken = 1)
            val b = video("b.mp4", taken = 2)
            m.share(send(b, a))
            val ready = m.await<ShareState.Ready>()

            m.on { choose(PresetId("event-frame")) }
            m.awaitNav { it == ShareNav.ToEditor(ready.draft) }

            val project = repo.open.value!!
            assertThat(project.id).isEqualTo(ready.draft)
            assertThat(project.presetId).isEqualTo(PresetId("event-frame"))
            val main = project.main.clips
            assertThat(main.first()).isInstanceOf(IntroClip::class.java)
            assertThat(main.last()).isInstanceOf(EndingClip::class.java)
            assertThat(main.drop(1).dropLast(1).map { (it as? PhotoClip)?.media ?: (it as VideoClip).media })
                .containsExactly(MediaRef.Store(a.toString()), MediaRef.Store(b.toString()))
                .inOrder()
            assertThat(project.tracks.flatMap { it.clips }.any { it is com.itsluminous.clearmovie.domain.model.OverlayClip }).isTrue()
            // Choosing again (Back from setup, then another choice) starts from the items, never two intros.
            assertThat(
                com.itsluminous.clearmovie.data.project
                    .ShareDrafts(presets, repo)
                    .choose(ready.draft, PresetId("event-frame")),
            ).isEqualTo(
                com.itsluminous.clearmovie.data.project.ShareChoice
                    .Ready(ready.draft),
            )
            assertThat(
                repo.open.value!!
                    .main.clips
                    .count { it is IntroClip },
            ).isEqualTo(1)
            assertThat(
                repo.open.value!!
                    .mainClips
                    .count { it is PhotoClip || it is VideoClip },
            ).isEqualTo(2)
        }

    @Test
    fun `Cancel on the preset pick throws the draft and its copies away`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = make(repo)
            val chatOnly = Uri.parse("content://$CHAT/files/only.jpg")
            chat.add(FakeMediaProvider.Row(chatOnly, "only.jpg", 12, "image/jpeg", bytes = ByteArray(12)))
            m.share(send(chatOnly))
            val ready = m.await<ShareState.Ready>()
            assertThat(files.projectMediaDir(ready.draft).listFiles()!!.toList()).hasSize(1)

            m.on { cancel() }
            m.awaitNav { it == ShareNav.Home }

            assertThat(repository().projectList()).isEmpty()
            assertThat(files.projectDir(ready.draft).exists()).isFalse()
        }

    @Test
    fun `a second share while one waits on its pick asks, Add joins them, and Cancel goes back to the first`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = make(repo)
            m.share(send(photo("a.jpg", taken = 5)))
            val first = m.await<ShareState.Ready>()

            assertThat(m.share(send(photo("b.jpg", taken = 1)))).isEqualTo(ShareState.Asking(1, first.draft, toWaitingShare = true))
            m.on { cancel() }
            assertThat(m.vm.state.value).isEqualTo(first)

            m.share(send(photo("c.jpg", taken = 9)))
            m.on { addToOpen() }
            m.on { begin(MediaAccessState.Full) }
            runBlocking { withTimeout(TIMEOUT_MS) { m.vm.state.first { it is ShareState.Ready && it.items == 2 } } }
            assertThat(repo.projectList().map { it.id }).containsExactly(first.draft)
            assertThat(m.nav).doesNotContain(ShareNav.Home)
        }

    @Test
    fun `a share with nothing usable says so where the user is, and a replay from Recents or a non-share is ignored`() =
        runBlocking<Unit> {
            val m = make(repository())

            m.share(Intent(Intent.ACTION_SEND))
            assertThat(m.await<ShareState.Nothing>().notes).isEqualTo(ShareNotes())
            m.on { dismiss() }
            assertThat(m.nav).doesNotContain(ShareNav.Home)

            m.on { receive(send(photo("a.jpg")).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)) }
            m.on { receive(Intent(Intent.ACTION_MAIN)) }
            Thread.sleep(300)
            assertThat(m.vm.state.value).isEqualTo(ShareState.Idle)
        }

    @Test
    fun `Start a new video from the editor closes it, so neither a later share nor a restart takes it for open`() =
        runBlocking<Unit> {
            val repo = repository()
            val open = ProjectId("open4")
            assertThat(repo.createDraft(ProjectFactory.blank(open), inEditor = true)).isTrue()
            val m = make(repo)
            val many = (1..101).map { photo("m$it.jpg") }.toTypedArray()

            m.share(send(*many))
            m.on { startNewVideo() }
            m.awaitNav { it == ShareNav.ToPick }
            m.on { begin(MediaAccessState.Full) }
            m.await<ShareState.ManyItems>()
            m.on { cancel() }
            m.awaitNav { it == ShareNav.Home }

            assertThat(repo.launchDecision()).isEqualTo(LaunchDecision.Home)
            // The next share starts a new video straight away instead of asking about the closed one.
            assertThat(m.share(send(photo("next.jpg")))).isEqualTo(ShareState.Waiting(1, null))
            assertThat(repo.projectList().map { it.id }).contains(open)
        }

    @Test
    fun `a share arriving on a launcher video's review or photo screen does not leave that empty video behind`() =
        runBlocking<Unit> {
            val repo = repository()
            val launcher = ProjectId("fromlauncher")
            assertThat(repo.createDraft(ProjectFactory.blank(launcher), inEditor = false)).isTrue()
            val m = make(repo)

            m.share(send(photo("a.jpg")))
            val ready = m.await<ShareState.Ready>()

            assertThat(repo.projectList().map { it.id }).containsExactly(ready.draft)
        }

    @Test
    fun `shares that arrive while one is being taken in are handled after it, in order, none lost`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = make(repo)
            val many = (1..101).map { photo("q$it.jpg") }.toTypedArray()
            m.share(send(*many))
            m.await<ShareState.ManyItems>()
            // Two more shares come in while the notice is up.
            m.on { receive(send(photo("second.jpg"))) }
            m.on { receive(send(photo("third.jpg"))) }
            Thread.sleep(200)
            assertThat(m.vm.state.value).isInstanceOf(ShareState.ManyItems::class.java)

            m.on { continueMany() }
            val first = m.await<ShareState.Ready>()
            // The second is asked about at once, against the waiting share.
            val asking = m.await<ShareState.Asking>()
            assertThat(asking).isEqualTo(ShareState.Asking(1, first.draft, toWaitingShare = true))
            m.on { addToOpen() }
            m.on { begin(MediaAccessState.Full) }
            runBlocking { withTimeout(TIMEOUT_MS) { m.vm.state.first { it is ShareState.Asking } } }
            m.on { addToOpen() }
            m.on { begin(MediaAccessState.Full) }
            val all = runBlocking { withTimeout(TIMEOUT_MS) { m.vm.state.first { it is ShareState.Ready && it.items == 103 } } }
            assertThat((all as ShareState.Ready).draft).isEqualTo(first.draft)
        }

    @Test
    fun `Choose fewer on the copy question says how to share fewer, and the draft and its copies go`() =
        runBlocking<Unit> {
            val repo = repository()
            val m = makeWithFree(repo, free = 3_000_000_000L)
            val big = Uri.parse("content://$CHAT/files/big.mp4")
            chat.add(FakeMediaProvider.Row(big, "big.mp4", 1_500_000_000L, "video/mp4", bytes = ByteArray(10)))

            m.share(send(big))
            runBlocking {
                withTimeout(
                    TIMEOUT_MS,
                ) {
                    m.vm.intake.state
                        .first { it is com.itsluminous.clearmovie.ui.media.IntakeState.Confirm }
                }
            }
            m.on { chooseFewer() }
            val failed = m.await<ShareState.Failed>()

            assertThat(failed.reason).isEqualTo(FailReason.CHOOSE_FEWER)
            assertThat(repo.projectList()).isEmpty()
            m.on { dismiss() }
            m.awaitNav { it == ShareNav.Home }
        }

    @Test
    fun `a provider that never answers is counted as could not be opened, and the rest go on`() =
        runBlocking<Unit> {
            val stuck = Uri.parse("content://$CHAT/files/stuck.jpg")
            val hanging =
                object : ShareProbe {
                    val real = ResolverShareProbe(context.contentResolver)

                    override fun typeOf(uri: Uri): String? {
                        if (uri == stuck) Thread.sleep(60_000)
                        return real.typeOf(uri)
                    }

                    override fun durationMs(uri: Uri): Long? = real.durationMs(uri)
                }
            val repo = repository()
            val m = make(repo, shareProbe = hanging)
            val good = photo("good.jpg")

            val started = System.currentTimeMillis()
            m.share(send(stuck, good))
            val ready = m.await<ShareState.Ready>()

            assertThat(System.currentTimeMillis() - started).isLessThan(ShareViewModel.PROVIDER_TIMEOUT_MS + 10_000)
            assertThat(ready.items).isEqualTo(1)
            assertThat(ready.notes.unreadable).isEqualTo(1)
        }

    @Test
    fun `a process death on the add-or-new question asks again`() =
        runBlocking<Unit> {
            val repo = repository()
            val open = ProjectId("open5")
            assertThat(repo.createDraft(ProjectFactory.blank(open), inEditor = true)).isTrue()
            val saved = SavedStateHandle()
            val m = make(repo, saved)
            m.share(send(photo("a.jpg"), photo("b.jpg")))
            assertThat(m.vm.state.value).isEqualTo(ShareState.Asking(2, open))

            val again = make(repository(), SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
            assertThat(again.await<ShareState.Asking>()).isEqualTo(ShareState.Asking(2, open))
            again.on { addToOpen() }
            again.on { begin(MediaAccessState.Full) }
            assertThat(again.await<ShareState.Added>().added).isEqualTo(2)
        }

    @Test
    fun `a list of other parcelables in place of links is refused, never a crash`() {
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf<android.os.Parcelable>(Intent(), android.os.Bundle(), photo("ok.jpg")),
            )
        val shared = ShareLinks.readSafely(intent)!!
        assertThat(shared.uris).hasSize(1)
        assertThat(shared.refused).isEqualTo(2)
    }

    private fun makeWithFree(
        repo: ProjectRepository,
        free: Long,
    ): Made {
        val gateway = MediaStoreGateway(context)
        val tight =
            MediaAccess(
                context.contentResolver,
                MediaResolver(
                    gateway,
                    grants,
                    context.packageName,
                ),
                gateway,
                grants,
                MediaCopier(),
                files,
                db,
                {
                    free
                },
            )
        val vm =
            runBlocking(main) {
                ShareViewModel(
                    SavedStateHandle(),
                    repo,
                    tight,
                    probe,
                    times,
                    ResolverShareProbe(context.contentResolver),
                    ShareDrafts(presets, repo),
                    PendingShare(pendingFile),
                    settings,
                    Dispatchers.IO,
                )
            }
        val nav = CopyOnWriteArrayList<ShareNav>()
        scope().launch { vm.nav.collect { nav += it } }
        return Made(vm, nav)
    }

    private fun bindPreset() =
        runBlocking {
            presetProbe.frames =
                listOf(10, 50, 90).map { pct ->
                    val image =
                        javaClass.classLoader!!
                            .getResourceAsStream(
                                "fixtures/media/frame-original-$pct.png",
                            ).use { ImageIO.read(it) }
                    FrameSample(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
                }
            for ((slot, name) in listOf(PresetSlot.INTRO to "Intro.mp4", PresetSlot.FRAME to "Frame.mp4", PresetSlot.ENDING to "end.mp3")) {
                val uri = "content://$CHAT/preset/$name".toUri()
                chat.add(
                    FakeMediaProvider.Row(
                        uri,
                        name,
                        5000,
                        if (name.endsWith("mp3")) "audio/mpeg" else "video/mp4",
                        bytes = ByteArray(5000) { 1 },
                    ),
                )
                presets.bindSlot(PresetId("event-frame"), slot, uri)
            }
            check(presets.status(PresetId("event-frame")).isReady)
        }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val CHAT = "com.example.chat.files"
    }
}
