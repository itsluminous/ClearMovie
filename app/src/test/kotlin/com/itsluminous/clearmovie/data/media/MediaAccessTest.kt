package com.itsluminous.clearmovie.data.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.files.AppFiles
import com.itsluminous.clearmovie.data.files.SafeFileName
import com.itsluminous.clearmovie.data.media.MediaAccess.Companion.mediaRef
import com.itsluminous.clearmovie.domain.model.ClipId
import com.itsluminous.clearmovie.domain.model.Fixtures
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.domain.model.PhotoClip
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.domain.model.SequentialIds
import com.itsluminous.clearmovie.domain.model.VideoClip
import com.itsluminous.clearmovie.domain.model.addToMain
import com.itsluminous.clearmovie.testing.FakeDocumentsProvider
import com.itsluminous.clearmovie.testing.FakeMediaProvider
import com.itsluminous.clearmovie.testing.FakeOtherAppProvider
import com.itsluminous.clearmovie.testing.FakeUriGrants
import com.itsluminous.clearmovie.testing.fileDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * ROADMAP cm-storage acceptance (Robolectric): Google-Photos-style links
 * resolve to MediaStore, partial access copies within the copy budget, the
 * Android 10-11 `OPEN_DOCUMENT` path, missing MediaStore rows show as missing
 * clips, and a cancelled copy leaves nothing behind.
 */
@RunWith(RobolectricTestRunner::class)
class MediaAccessTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeMediaProvider
    private lateinit var photosApp: FakeOtherAppProvider
    private lateinit var db: ClearMovieDatabase
    private lateinit var files: AppFiles
    private val grants = FakeUriGrants()
    private var free = 50_000_000_000L
    private lateinit var access: MediaAccess

    private val project = ProjectId("p1")

    @Before
    fun setUp() {
        store = Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        photosApp = Robolectric.setupContentProvider(FakeOtherAppProvider::class.java, PHOTOS)
        db = fileDatabase(context, File(tmp.root, "db"))
        files = AppFiles(File(tmp.root, "files"), File(tmp.root, "cache"))
        access = build()
    }

    @After
    fun tearDown() = db.close()

    private fun build(): MediaAccess {
        val gateway = MediaStoreGateway(context)
        return MediaAccess(
            resolver = context.contentResolver,
            mediaResolver = MediaResolver(gateway, grants, context.packageName),
            gateway = gateway,
            grants = grants,
            copier = MediaCopier(Dispatchers.IO, bufferBytes = 64 * 1024),
            files = files,
            db = db,
            freeBytes = { free },
            dispatcher = Dispatchers.IO,
        )
    }

    /** A link as Google Photos shares it: its own provider, with the item's name and size. */
    private fun googlePhotosLink(
        name: String,
        size: Long,
        n: Int = 1,
    ): Uri {
        val uri = Uri.parse("content://$PHOTOS/-1/1/content%3A%2F%2Fmedia%2Fexternal%2Fimages%2Fmedia%2F$n/ORIGINAL/NONE/image%2Fjpeg/77$n")
        return photosApp.add(FakeMediaProvider.Row(uri, name, size, "image/jpeg", bytes = ByteArray(size.coerceAtMost(4096).toInt()) { 7 }))
    }

    @Test
    fun `a Google-Photos-style link with a matching name and size resolves to MediaStore instead of copying`() =
        runBlocking<Unit> {
            val inStore = store.addMedia("IMG_0042.jpg", 2_345_678, id = 42)
            store.addMedia("IMG_0042.jpg", 999, id = 43) // same name, other size: not a match
            val shared = googlePhotosLink("IMG_0042.jpg", 2_345_678)

            val plan = access.plan(listOf(shared), MediaAccessState.Full)

            assertThat(plan.items.single().resolution).isEqualTo(Resolution.Linked(inStore.toString(), false, plan.items.single().info()))
            assertThat(plan.toCopy).isEmpty()
            assertThat(plan.budget).isEqualTo(CopyBudget.Verdict.Fits)
            val result = access.execute(project, plan)
            assertThat(result.single().ref).isEqualTo(MediaRef.Store(inStore.toString()))
            assertThat(files.projectMediaDir(project).listFiles().orEmpty()).isEmpty()
        }

    @Test
    fun `several MediaStore matches are told apart by capture date, or else copied`() =
        runBlocking<Unit> {
            store.addMedia("dup.jpg", 5000, dateTaken = 100, id = 1)
            val second = store.addMedia("dup.jpg", 5000, dateTaken = 200, id = 2)
            val dated =
                photosApp.add(
                    FakeMediaProvider.Row(Uri.parse("content://$PHOTOS/a"), "dup.jpg", 5000, "image/jpeg", dateTaken = 200),
                )
            val undated = photosApp.add(FakeMediaProvider.Row(Uri.parse("content://$PHOTOS/b"), "dup.jpg", 5000, "image/jpeg"))

            val plan = access.plan(listOf(dated, undated), MediaAccessState.Full)

            assertThat((plan.items[0].resolution as Resolution.Linked).uri).isEqualTo(second.toString())
            assertThat(plan.items[1].resolution).isInstanceOf(Resolution.NeedsCopy::class.java)
        }

    @Test
    fun `without full access a share is copied while its grant lasts`() =
        runBlocking<Unit> {
            store.addMedia("IMG_1.jpg", 3000, id = 1)
            val shared = googlePhotosLink("IMG_1.jpg", 3000)
            val plan = access.plan(listOf(shared), MediaAccessState.Denied)
            assertThat(plan.items.single().resolution).isInstanceOf(Resolution.NeedsCopy::class.java)

            val result = access.execute(project, plan)

            val ref = result.single().ref as MediaRef.Copied
            assertThat(ref.relPath).matches(SafeFileName.SAFE_NAME.pattern)
            assertThat(files.copiedFile(project, ref).readBytes()).isEqualTo(ByteArray(3000) { 7 })
        }

    @Test
    fun `partial access copies, and more than 1 GB brings up the copy budget prompt`() =
        runBlocking<Unit> {
            // Shared videos from a chat app: under partial access none can be linked.
            val links =
                (1..3).map { n ->
                    photosApp.add(FakeMediaProvider.Row(Uri.parse("content://$PHOTOS/v$n"), "VID_$n.mp4", 400_000_000, "video/mp4"))
                }
            store.addMedia("VID_1.mp4", 400_000_000, video = true, id = 9)

            val plan = access.plan(links, MediaAccessState.Partial)

            assertThat(plan.toCopy).hasSize(3)
            assertThat(plan.budget).isEqualTo(CopyBudget.Verdict.NeedsConfirmation(3, 1_200_000_000, free, offerAllowAll = true))
        }

    @Test
    fun `under partial access a Photo Picker item whose grant persists is kept by link`() =
        runBlocking<Unit> {
            val picked = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000000034")
            store.add(FakeMediaProvider.Row(picked, "IMG_9.jpg", 1234, "image/jpeg"))
            // Not visible as a MediaStore item under partial access.
            grants.persistable += picked.toString()

            val plan = access.plan(listOf(picked), MediaAccessState.Partial)
            assertThat(plan.items.single().resolution).isEqualTo(Resolution.Linked(picked.toString(), true, plan.items.single().info()))
            access.execute(project, plan)

            assertThat(db.grants().urisFor(project.value)).containsExactly(picked.toString())
            assertThat(grants.held).containsExactly(picked.toString())
        }

    @Test
    fun `with full access a Photo Picker id resolves to its MediaStore item`() =
        runBlocking<Unit> {
            val item = store.addMedia("VID_7.mp4", 8_000_000, video = true, id = 1000000034)
            val picked = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000000034")
            store.add(FakeMediaProvider.Row(picked, "VID_7.mp4", 8_000_000, "video/mp4"))

            val plan = access.plan(listOf(picked), MediaAccessState.Full)

            assertThat((plan.items.single().resolution as Resolution.Linked).uri).isEqualTo(item.toString())
        }

    @Test
    @Config(sdk = [30])
    fun `on Android 10 and 11 an OPEN_DOCUMENT media document goes through getMediaUri`() =
        runBlocking<Unit> {
            val docs = Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, MediaStoreGateway.MEDIA_DOCUMENTS_AUTHORITY)
            val item = store.addMedia("IMG_5.jpg", 4321, id = 5)
            val document = Uri.parse("content://${MediaStoreGateway.MEDIA_DOCUMENTS_AUTHORITY}/document/image%3A5")
            docs.add(FakeMediaProvider.Row(document, "IMG_5.jpg", 4321, "image/jpeg"))
            store.documentToMedia[document.toString()] = item

            val plan = access.plan(listOf(document), MediaAccessState.Full)

            assertThat((plan.items.single().resolution as Resolution.Linked).uri).isEqualTo(item.toString())
            assertThat((plan.items.single().resolution as Resolution.Linked).persistedGrant).isFalse()
        }

    @Test
    @Config(sdk = [30])
    fun `without access an OPEN_DOCUMENT link keeps its persisted grant`() =
        runBlocking<Unit> {
            val docs = Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, MediaStoreGateway.MEDIA_DOCUMENTS_AUTHORITY)
            val document = Uri.parse("content://${MediaStoreGateway.MEDIA_DOCUMENTS_AUTHORITY}/document/image%3A6")
            docs.add(FakeMediaProvider.Row(document, "IMG_6.jpg", 10, "image/jpeg"))
            grants.persistable += document.toString()

            val plan = access.plan(listOf(document), MediaAccessState.Denied)

            assertThat(plan.items.single().resolution).isEqualTo(Resolution.Linked(document.toString(), true, plan.items.single().info()))
        }

    @Test
    fun `file links, unknown schemes and the app's own providers are refused`() =
        runBlocking<Unit> {
            val links =
                listOf(
                    Uri.parse("file:///data/data/${context.packageName}/databases/clearmovie.db"),
                    Uri.parse("content://${context.packageName}.files/../../databases/clearmovie.db"),
                    Uri.parse("content://${context.packageName}/x"),
                    Uri.parse("http://example.invalid/a.jpg"),
                    Uri.parse("content:///no-authority"),
                )
            val plan = access.plan(links, MediaAccessState.Full)
            assertThat(plan.items.map { it.resolution }).containsExactlyElementsIn(links.map { Resolution.Unreadable(it) }).inOrder()
            assertThat(access.execute(project, plan).map { it.ref }).containsExactly(null, null, null, null, null)
        }

    @Test
    fun `a deleted MediaStore row shows as a missing clip`() =
        runBlocking<Unit> {
            val kept = store.addMedia("a.jpg", 10, id = 1)
            val deleted = store.addMedia("b.mp4", 10, video = true, id = 2)
            val copied = MediaRef.Copied("abcd1234_c.jpg")
            files.copiedFile(project, copied).apply { parentFile!!.mkdirs() }.writeText("x")
            val gone = MediaRef.Copied("abcd1234_gone.jpg")
            files.copiedFile(project, gone).writeText("y")
            val p =
                Fixtures.presetProject(SequentialIds("x")).copy(id = project).addToMain(
                    listOf(
                        PhotoClip(ClipId("keep"), MediaRef.Store(kept.toString()), 3000),
                        VideoClip(ClipId("del"), MediaRef.Store(deleted.toString()), sourceDurationMs = 4000),
                        PhotoClip(ClipId("copy"), copied, 3000),
                        PhotoClip(ClipId("copygone"), gone, 3000),
                    ),
                    ids = SequentialIds("n"),
                )
            assertThat(access.missing(p, MediaAccessState.Full)).isEmpty()
            store.delete(deleted)
            files.copiedFile(project, gone).delete()

            val missing = access.missing(p, MediaAccessState.Full)

            assertThat(missing).containsExactly(ClipId("del"), MediaStatus.MISSING, ClipId("copygone"), MediaStatus.MISSING)
            assertThat(access.check(p, MediaAccessState.Full)[ClipId("keep")]).isEqualTo(MediaStatus.AVAILABLE)
            // Preset files (the intro, the frame, its music, the ending, the closing message) are PresetStore's to check.
            val presetClips =
                p.tracks
                    .flatMap { it.clips }
                    .filter { it.mediaRef() is MediaRef.Preset }
                    .map { it.id }
            assertThat(presetClips).hasSize(5)
            assertThat(presetClips.map { access.check(p, MediaAccessState.Full)[it] }.toSet()).containsExactly(MediaStatus.AVAILABLE)
        }

    @Test
    fun `turning photo access off shows clips as no access, not as deleted`() =
        runBlocking<Unit> {
            val item = store.addMedia("a.jpg", 10, id = 1)
            // The real MediaProvider hides what the app may not read: an empty answer.
            store.hidden += item.toString()
            assertThat(access.status(project, MediaRef.Store(item.toString()), MediaAccessState.Denied)).isEqualTo(MediaStatus.NO_ACCESS)
            assertThat(access.status(project, MediaRef.Store(item.toString()), MediaAccessState.Partial)).isEqualTo(MediaStatus.NO_ACCESS)
            // With full access the same empty answer means it is gone.
            assertThat(access.status(project, MediaRef.Store(item.toString()), MediaAccessState.Full)).isEqualTo(MediaStatus.MISSING)
            // A picker or document link whose grant ended refuses outright.
            val picked = Uri.parse("content://media/picker/0/${MediaStoreGateway.LOCAL_PICKER}/media/7")
            store.add(FakeMediaProvider.Row(picked, "p.jpg", 1, "image/jpeg"))
            store.forbidden += picked.toString()
            assertThat(access.status(project, MediaRef.Store(picked.toString()), MediaAccessState.Full)).isEqualTo(MediaStatus.NO_ACCESS)
            assertThat(
                access.status(project, MediaRef.Store("content://com.example.uninstalled/x"), MediaAccessState.Full),
            ).isEqualTo(MediaStatus.MISSING)
        }

    @Test
    fun `checking a project never opens another app's file it holds no grant for`() =
        runBlocking<Unit> {
            val link =
                photosApp.add(
                    FakeMediaProvider.Row(Uri.parse("content://$PHOTOS/q"), "q.jpg", 5, "image/jpeg", bytes = ByteArray(5)),
                )
            photosApp.nullCursor = true
            assertThat(access.status(project, MediaRef.Store(link.toString()), MediaAccessState.Full)).isEqualTo(MediaStatus.MISSING)
            assertThat(photosApp.opens).isEqualTo(0)
            grants.persistable += link.toString()
            grants.persist(link)
            assertThat(access.status(project, MediaRef.Store(link.toString()), MediaAccessState.Full)).isEqualTo(MediaStatus.AVAILABLE)
            assertThat(photosApp.opens).isEqualTo(1)
        }

    @Test
    fun `a MediaStore collection or a cloud picker id is never linked as a photo`() =
        runBlocking<Unit> {
            store.addMedia("IMG_1.jpg", 100, id = 34)
            val collection = FakeMediaProvider.IMAGES
            val cloud = Uri.parse("content://media/picker/0/com.example.cloud/media/34")
            store.add(FakeMediaProvider.Row(cloud, "Other.jpg", 100, "image/jpeg"))
            val plan = access.plan(listOf(collection, cloud), MediaAccessState.Full)
            assertThat(plan.items[0].resolution).isEqualTo(Resolution.Unreadable(collection))
            // The cloud item's 34 is not MediaStore item 34: it is copied instead.
            assertThat(plan.items[1].resolution).isInstanceOf(Resolution.NeedsCopy::class.java)
        }

    @Test
    fun `a provider that under-reports a file's size cannot fill the phone`() =
        runBlocking<Unit> {
            val liar = Uri.parse("content://com.example.chat/liar")
            val stream = SlowZeros(50_000_000, sleepEvery = Long.MAX_VALUE)
            shadowOf(context.contentResolver).registerInputStreamSupplier(liar) { stream }
            val plan =
                IntakePlan(
                    listOf(PlannedItem(liar, Resolution.NeedsCopy(liar, SourceInfo("small.jpg", 1000, "image/jpeg", null)))),
                    CopyBudget.Verdict.Fits,
                )
            val result = access.execute(project, plan)
            assertThat(result.single().ref).isNull()
            assertThat(files.projectMediaDir(project).listFiles().orEmpty()).isEmpty()
            // The copy stopped just past the declared size plus slack, not at 50 MB.
            assertThat(stream.read).isLessThan(2_000_000L)
            assertThat(MediaCopier.limitFor(1000, 10_000_000_000)).isEqualTo(1000 + 100 + MediaCopier.SIZE_SLACK_BYTES)
            assertThat(MediaCopier.limitFor(null, CopyBudget.RESERVE_BYTES + 5)).isEqualTo(5)
            assertThat(MediaCopier.limitFor(null, 10)).isEqualTo(0)
        }

    @Test
    fun `a 100 MB copy reports progress and, cancelled, leaves no partial file`() =
        runBlocking<Unit> {
            val total = 100L * 1024 * 1024
            val big = Uri.parse("content://com.example.chat/big")
            shadowOf(context.contentResolver).registerInputStreamSupplier(big) { SlowZeros(total) }
            photosApp.add(
                FakeMediaProvider.Row(Uri.parse("content://$PHOTOS/small"), "small.jpg", 100, "image/jpeg", bytes = ByteArray(100)),
            )
            val smallFirst = Uri.parse("content://$PHOTOS/small")
            val plan =
                IntakePlan(
                    listOf(
                        PlannedItem(smallFirst, Resolution.NeedsCopy(smallFirst, SourceInfo("small.jpg", 100, "image/jpeg", null))),
                        PlannedItem(big, Resolution.NeedsCopy(big, SourceInfo("VID big.mp4", total, "video/mp4", null))),
                    ),
                    CopyBudget.Verdict.Fits,
                )
            val progress = java.util.concurrent.CopyOnWriteArrayList<Long>()
            val job = async(Dispatchers.Default) { access.execute(project, plan) { copied, _ -> progress += copied } }
            withTimeout(30_000) { while ((progress.lastOrNull() ?: 0) < total / 3 && job.isActive) delay(5) }
            assertThat(job.isActive).isTrue()
            job.cancelAndJoin()

            assertThat(job.isCancelled).isTrue()
            assertThat(progress.zipWithNext().all { (a, b) -> b >= a }).isTrue()
            assertThat(progress.last()).isAtLeast(total / 3)
            // Neither the partial big copy nor the finished small one is left.
            assertThat(
                files
                    .projectMediaDir(project)
                    .listFiles()
                    .orEmpty()
                    .map { it.name },
            ).isEmpty()
        }

    @Test
    fun `a whole 100 MB copy reports progress up to the total and ends with the file in place`() =
        runBlocking<Unit> {
            val total = 100L * 1024 * 1024
            val big = Uri.parse("content://com.example.chat/big")
            shadowOf(context.contentResolver).registerInputStreamSupplier(big) { SlowZeros(total, sleepEvery = Long.MAX_VALUE) }
            val plan =
                IntakePlan(
                    listOf(PlannedItem(big, Resolution.NeedsCopy(big, SourceInfo("big.mp4", total, "video/mp4", null)))),
                    CopyBudget.Verdict.Fits,
                )
            val progress = mutableListOf<Long>()
            val result = access.execute(project, plan) { copied, all -> progress += copied.also { assertThat(all).isEqualTo(total) } }
            assertThat(progress.size).isAtLeast(50)
            assertThat(progress.last()).isEqualTo(total)
            val file = files.copiedFile(project, result.single().ref as MediaRef.Copied)
            assertThat(file.length()).isEqualTo(total)
            assertThat(
                files
                    .projectMediaDir(project)
                    .listFiles()!!
                    .map { it.name }
                    .filter { it.endsWith(".part") },
            ).isEmpty()
        }

    @Test
    fun `cancelling an intake gives back the grants it persisted`() =
        runBlocking<Unit> {
            val doc = Uri.parse("content://$PHOTOS/doc")
            photosApp.add(FakeMediaProvider.Row(doc, "d.jpg", 10, "image/jpeg"))
            grants.persistable += doc.toString()
            val plan = access.plan(listOf(doc), MediaAccessState.Denied)
            assertThat(grants.held).containsExactly(doc.toString())

            access.abandon(plan)

            assertThat(grants.held).isEmpty()
            assertThat(db.grants().usersOf(doc.toString())).isEqualTo(0)
        }

    @Test
    fun `two intakes holding the same link, one turned down, keeps the other's grant, which it then stores`() =
        runBlocking<Unit> {
            val doc = Uri.parse("content://$PHOTOS/shared-twice")
            photosApp.add(FakeMediaProvider.Row(doc, "s.jpg", 10, "image/jpeg"))
            grants.persistable += doc.toString()
            // The photo step and a share plan the same link.
            val picker = access.plan(listOf(doc), MediaAccessState.Denied)
            val share = access.plan(listOf(doc), MediaAccessState.Denied)

            access.abandon(picker)
            access.reconcileGrants(listOf(doc.toString()))
            assertThat(grants.held).containsExactly(doc.toString())

            access.execute(project, share)
            access.reconcileGrants(listOf(doc.toString()))
            assertThat(grants.held).containsExactly(doc.toString())
            assertThat(db.grants().usersOf(doc.toString())).isEqualTo(1)
        }

    @Test
    fun `grants left over by a killed intake are released at the next launch`() =
        runBlocking<Unit> {
            db.grants().add(
                com.itsluminous.clearmovie.data.db
                    .MediaGrantEntity("content://x/used", "p1"),
            )
            access.reconcileGrants(listOf("content://x/used", "content://x/orphan"))
            assertThat(grants.released).containsExactly("content://x/orphan")
        }

    @Test
    fun `an item that vanished between planning and copying comes back without a ref`() =
        runBlocking<Unit> {
            val shared = googlePhotosLink("x.jpg", 50)
            val plan = access.plan(listOf(shared), MediaAccessState.Denied)
            photosApp.delete(shared)
            val result = access.execute(project, plan)
            assertThat(result.single().ref).isNull()
            assertThat(files.projectMediaDir(project).listFiles().orEmpty()).isEmpty()
        }

    private fun PlannedItem.info(): SourceInfo? = (resolution as? Resolution.Linked)?.info

    /** Zeros, slowly enough that a test can cancel half-way. */
    private class SlowZeros(
        private val size: Long,
        private val sleepEvery: Long = 4L * 1024 * 1024,
    ) : InputStream() {
        private var pos = 0L

        /** Bytes handed out so far. */
        val read: Long get() = pos

        override fun read(): Int = if (pos++ < size) 0 else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (pos >= size) return -1
            val n = minOf(len.toLong(), size - pos).toInt()
            java.util.Arrays.fill(b, off, off + n, 0)
            if (pos / sleepEvery != (pos + n) / sleepEvery) Thread.sleep(15)
            pos += n
            return n
        }
    }

    private companion object {
        const val PHOTOS = "com.google.android.apps.photos.contentprovider"
    }
}
