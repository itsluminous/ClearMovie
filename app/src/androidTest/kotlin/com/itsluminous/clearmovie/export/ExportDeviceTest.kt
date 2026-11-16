package com.itsluminous.clearmovie.export

import android.app.NotificationManager
import android.app.PendingIntent
import android.media.MediaCodecList
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.transformer.Codec
import androidx.media3.transformer.ExportException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.export.ExportFileName
import com.itsluminous.clearmovie.domain.export.ExportQuality
import com.itsluminous.clearmovie.domain.model.AudioClip
import com.itsluminous.clearmovie.domain.model.MediaRef
import com.itsluminous.clearmovie.render.media3.EncodeSpec
import com.itsluminous.clearmovie.render.media3.EncoderPreference
import com.itsluminous.clearmovie.render.media3.RenderExporter
import com.itsluminous.clearmovie.render.plan.LoudnessTable
import com.itsluminous.clearmovie.render.plan.RenderPlanBuilder
import com.itsluminous.clearmovie.work.ExportFailure
import com.itsluminous.clearmovie.work.ExportNotifications
import com.itsluminous.clearmovie.work.ExportOutcome
import com.itsluminous.clearmovie.work.ExportWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The export on a device (cm-export acceptance): each size gives its own
 * resolution and bit rate class, and the plan's length; the video lands in
 * Movies/ClearMovie under a safe name; Stop (from the app or the
 * notification) leaves no file; a failing hardware encoder falls back to
 * software; a nearly full phone writes straight into the gallery entry or
 * says how much to free; a missing photo blocks the export; the
 * notification shows progress and the result.
 */
@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class ExportDeviceTest {
    private val h = ExportHarness()

    @Before
    fun setUp() {
        h.setUp()
        h.bindPreset()
    }

    @After
    fun tearDown() = h.cleanUp()

    private fun export(
        quality: ExportQuality,
        name: String,
        pipeline: com.itsluminous.clearmovie.work.ExportPipeline = h.pipeline(),
        items: List<com.itsluminous.clearmovie.domain.model.MainClip> = h.typicalItems(),
    ): Pair<ExportOutcome, com.itsluminous.clearmovie.domain.model.Project> {
        val project = h.project(items, name)
        val outcome = runBlocking { pipeline.run(project.id, quality, "New video") {} }
        if (outcome is ExportOutcome.Done) h.exported(outcome.done.uri)
        return outcome to project
    }

    @Test
    fun eachSizeHasItsResolutionBitrateClassAndThePlannedLength() {
        val rates = mutableMapOf<ExportQuality, Int>()
        for (quality in ExportQuality.entries) {
            // The real encoders, with the bit rate the video encoder was configured with noted down.
            val configured = mutableListOf<Int>()
            val recording = { spec: EncodeSpec, preference: EncoderPreference ->
                RecordingVideoEncoders(RenderExporter.defaultEncoders(h.context, spec, preference), configured) as Codec.EncoderFactory
            }
            val (outcome, project) = export(quality, "Size ${quality.name}", h.pipeline(baseEncoders = recording))
            val done = (outcome as ExportOutcome.Done).done
            val probe = h.probe(done.uri)
            h.keep(done.uri, "export-${quality.name.lowercase()}.mp4")
            Log.i(
                ExportHarness.TAG,
                "${quality.name}: $probe, configured $configured b/s, average video ${done.averageVideoBitrate} b/s, encoder ${done.videoEncoder}",
            )
            assertThat(probe.height).isEqualTo(quality.height)
            assertThat(probe.width).isEqualTo(RenderPlanBuilder.evenWidthFor(quality.height))
            assertThat(probe.videoMime).isEqualTo("video/avc")
            assertThat(probe.audioMime).isEqualTo("audio/mp4a-latm")
            // The plan's length (overlaps included), within one frame at 30 fps.
            assertThat(probe.videoDurationUs).isWithin(FRAME_US + 1).of(h.plannedUs(project))
            // The encoder was asked for this size's rate (fallback may only lower it), and the file stays within it.
            assertThat(configured).isNotEmpty()
            assertThat(configured.last()).isIn(
                com.google.common.collect.Range
                    .closed(quality.videoBitrate / 2, quality.videoBitrate),
            )
            assertThat(done.averageVideoBitrate.toLong()).isAtMost((quality.videoBitrate * 1.25).toLong())
            rates[quality] = configured.last()
        }
        assertThat(rates.getValue(ExportQuality.SMALL)).isLessThan(rates.getValue(ExportQuality.GOOD))
        assertThat(rates.getValue(ExportQuality.GOOD)).isLessThan(rates.getValue(ExportQuality.BEST))
    }

    @Test
    fun theVideoIsInMoviesClearMovieUnderASafeName() {
        val name = "Mom/Dad's ../party: 2026?"
        val (outcome, _) = export(ExportQuality.SMALL, name)
        val done = (outcome as ExportOutcome.Done).done
        val entry = requireNotNull(h.store.describe(done.uri))
        val expected = ExportFileName.displayName(name, "x")
        assertThat(expected).isEqualTo("Mom Dad's .. party 2026.mp4")
        // MediaStore adds " (1)" when an earlier run left the same name.
        assertThat(entry.displayName).matches("""Mom Dad's \.\. party 2026( \(\d+\))?\.mp4""")
        assertThat(entry.relativePath).isEqualTo("Movies/ClearMovie/")
        assertThat(entry.pending).isFalse()
        assertThat(entry.mime).isEqualTo("video/mp4")
        assertThat(entry.sizeBytes).isGreaterThan(0)
        assertThat(done.displayName).isEqualTo(entry.displayName)
        // Nothing else of this export is left: no hidden entry, no temporary file.
        assertThat(h.store.pendingEntries()).isEmpty()
        assertThat(h.pipeline().tempFiles()).isEmpty()
    }

    @Test
    fun aFailingHardwareEncoderFallsBackToSoftware() {
        val failing = { spec: com.itsluminous.clearmovie.render.media3.EncodeSpec, preference: EncoderPreference ->
            val real = RenderExporter.defaultEncoders(h.context, spec, preference)
            if (preference == EncoderPreference.HARDWARE_FIRST) FailingVideoEncoders(real) else real
        }
        val (outcome, _) = export(ExportQuality.SMALL, "Fallback", h.pipeline(baseEncoders = failing))
        val done = (outcome as ExportOutcome.Done).done
        assertThat(done.softwareFallback).isTrue()
        assertWithMessage("encoder ${done.videoEncoder}").that(ExportHarness.isHardware(done.videoEncoder)).isFalse()
        assertThat(h.probe(done.uri).height).isEqualTo(480)
    }

    @Test
    fun encodersThatAllFailLeaveNoFile() {
        val failing = { spec: com.itsluminous.clearmovie.render.media3.EncodeSpec, preference: EncoderPreference ->
            FailingVideoEncoders(RenderExporter.defaultEncoders(h.context, spec, preference)) as Codec.EncoderFactory
        }
        val (outcome, _) = export(ExportQuality.SMALL, "Both fail", h.pipeline(baseEncoders = failing))
        assertThat(outcome).isEqualTo(ExportOutcome.Failed(ExportFailure.FAILED))
        assertThat(h.store.entriesNamed("Both fail.mp4")).isEmpty()
        assertThat(h.store.pendingEntries()).isEmpty()
        assertThat(h.pipeline().tempFiles()).isEmpty()
    }

    @Test
    fun aNearlyFullPhoneWritesStraightIntoTheGallery() {
        val items = h.typicalItems()
        val estimate = ExportQuality.SMALL.estimatedBytes(15_000_000)
        val (outcome, project) = export(ExportQuality.SMALL, "Direct", h.pipeline(freeBytes = { (estimate * 1.5).toLong() }), items)
        val done = (outcome as ExportOutcome.Done).done
        assertThat(done.direct).isTrue()
        val probe = h.probe(done.uri)
        assertThat(probe.height).isEqualTo(480)
        assertThat(probe.videoDurationUs).isWithin(FRAME_US + 1).of(h.plannedUs(project))
        h.keep(done.uri, "export-direct-small.mp4")
        assertThat(h.pipeline().tempFiles()).isEmpty()
    }

    @Test
    fun aFullPhoneSaysHowMuchToFreeAndWritesNothing() {
        val (outcome, _) = export(ExportQuality.BEST, "Full", h.pipeline(freeBytes = { 1_000_000 }))
        val failed = outcome as ExportOutcome.Failed
        assertThat(failed.reason).isEqualTo(ExportFailure.NO_SPACE)
        assertThat(failed.missingBytes).isGreaterThan(0)
        assertThat(h.store.entriesNamed("Full.mp4")).isEmpty()
    }

    @Test
    fun aMissingPhotoBlocksTheExport() {
        val items = h.typicalItems()
        val project = h.project(items, "Missing")
        val gone = (items.first() as com.itsluminous.clearmovie.domain.model.PhotoClip).media as MediaRef.Store
        h.context.contentResolver.delete(android.net.Uri.parse(gone.uri), null, null)
        val outcome = runBlocking { h.pipeline().run(project.id, ExportQuality.SMALL, "x") {} }
        assertThat(outcome).isEqualTo(ExportOutcome.Failed(ExportFailure.MISSING_MEDIA))
        assertThat(h.store.entriesNamed("Missing.mp4")).isEmpty()
    }

    @Test
    fun aSoundWithoutMeasuredLoudnessIsMeasuredAtExport() {
        val speech = h.insert("fixtures/speech.m4a", "audio/mp4")
        val project = h.project(h.typicalItems(), "Loud")
        val ref = MediaRef.Store(speech.toString())
        val withSound =
            project.copy(
                tracks =
                    project.tracks.map { t ->
                        t.copy(
                            clips =
                                t.clips.map { c ->
                                    if (c is AudioClip &&
                                        c.media == MediaRef.Preset(com.itsluminous.clearmovie.domain.model.PresetSlot.ENDING)
                                    ) {
                                        c.copy(media = ref)
                                    } else {
                                        c
                                    }
                                },
                        )
                    },
            )
        val set =
            runBlocking {
                com.itsluminous.clearmovie.data.project
                    .ProjectMedia(h.app.presetStore, h.app.appFiles)
                    .forProject(withSound)
            }
        val table: LoudnessTable = runBlocking { h.pipeline().measuredLoudness(withSound, set.loudness, set.resolver) }
        // The replaced ending now has a loudness (so it is evened out like the preset's), the preset's own keep theirs.
        assertThat(table.lufs(ref)).isNotNull()
        assertThat(
            table.lufs(ref)!!,
        ).isWithin(3.0).of(set.loudness.lufs(MediaRef.Preset(com.itsluminous.clearmovie.domain.model.PresetSlot.ENDING))!!)
    }

    @Test
    fun stopLeavesNoFileAndTheNotificationShowsProgressThenTheResult() {
        val wm = WorkManager.getInstance(h.context)
        val manager = h.context.getSystemService(NotificationManager::class.java)

        // A long one, stopped from the notification's own Stop button.
        val long = h.project(h.typicalItems(photoMs = 8000), "Stop me")
        val id = ExportWorker.enqueue(wm, long.id, ExportQuality.BEST, "x", replace = true)
        waitFor("progress") { (progressOf(wm, id) ?: 0) >= 3 }
        val shown = manager.activeNotifications.firstOrNull { it.id == ExportNotifications.PROGRESS_ID }
        assertWithMessage("progress notification").that(shown).isNotNull()
        val n = shown!!.notification
        assertThat(n.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX)).isEqualTo(100)
        val stop = n.actions.single { it.title.toString() == h.context.getString(R.string.export_stop) }
        stop.actionIntent.send()
        waitFor("cancelled") { wm.getWorkInfoById(id).get()?.state == WorkInfo.State.CANCELLED }
        waitFor("cleaned up") { h.store.pendingEntries().isEmpty() && h.pipeline().tempFiles().isEmpty() }
        assertThat(h.store.entriesNamed("Stop me.mp4")).isEmpty()

        // A short one, stopped from the app (as the sheet's Stop does), mid-way.
        val second = h.project(h.typicalItems(photoMs = 8000), "Stop me too")
        val id2 = ExportWorker.enqueue(wm, second.id, ExportQuality.BEST, "x", replace = true)
        waitFor("progress") { (progressOf(wm, id2) ?: 0) >= 3 }
        wm.cancelWorkById(id2).result.get()
        waitFor("cancelled") { wm.getWorkInfoById(id2).get()?.state == WorkInfo.State.CANCELLED }
        waitFor("cleaned up") { h.store.pendingEntries().isEmpty() && h.pipeline().tempFiles().isEmpty() }
        assertThat(h.store.entriesNamed("Stop me too.mp4")).isEmpty()

        // One that finishes: the result notification names it, and the work's output points at it.
        val done = h.project(h.typicalItems(), "Notified")
        val id3 = ExportWorker.enqueue(wm, done.id, ExportQuality.SMALL, "x", replace = true)
        waitFor("finished", 600) {
            wm
                .getWorkInfoById(id3)
                .get()
                ?.state
                ?.isFinished == true
        }
        val info = wm.getWorkInfoById(id3).get()!!
        assertThat(info.state).isEqualTo(WorkInfo.State.SUCCEEDED)
        val uri = android.net.Uri.parse(info.outputData.getString(ExportWorker.KEY_URI))
        h.exported(uri)
        waitFor("result notification") { manager.activeNotifications.any { it.id == ExportNotifications.RESULT_ID } }
        val result = manager.activeNotifications.single { it.id == ExportNotifications.RESULT_ID }.notification
        assertThat(result.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            .isEqualTo(h.context.getString(R.string.notification_export_done_title))
        assertThat(result.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()).contains("Notified")
        assertThat(manager.activeNotifications.none { it.id == ExportNotifications.PROGRESS_ID }).isTrue()
        manager.cancel(ExportNotifications.RESULT_ID)
    }

    @Test
    fun hardwareEncoderIsUsedOnAPhone() {
        assumeFalse(
            "Emulators encode in software only; this runs on a physical phone (${android.os.Build.FINGERPRINT})",
            ExportHarness.isEmulator(),
        )
        val hardware =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { t -> t.equals("video/avc", true) }
            }
        assertWithMessage("hardware H.264 encoders").that(hardware).isNotEmpty()
        val started = System.nanoTime()
        val (outcome, project) = export(ExportQuality.BEST, "Hardware")
        val done = (outcome as ExportOutcome.Done).done
        val wallMs = (System.nanoTime() - started) / 1_000_000
        Log.i(
            ExportHarness.TAG,
            "Phone ${android.os.Build.MODEL}: encoder ${done.videoEncoder}, $wallMs ms for ${h.plannedUs(project) / 1000} ms of video",
        )
        assertWithMessage("encoder ${done.videoEncoder}").that(ExportHarness.isHardware(done.videoEncoder)).isTrue()
        assertThat(done.softwareFallback).isFalse()
    }

    private fun progressOf(
        wm: WorkManager,
        id: UUID,
    ): Int? =
        wm
            .getWorkInfoById(id)
            .get()
            ?.progress
            ?.getInt(ExportWorker.KEY_PERCENT, -1)
            ?.takeIf { it >= 0 }

    private fun waitFor(
        what: String,
        seconds: Long = 300,
        check: () -> Boolean,
    ) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!check()) {
            check(System.nanoTime() < end) { "Timed out waiting for $what" }
            Thread.sleep(200)
        }
    }

    /** Video encoders that refuse to start, as a broken hardware encoder does; audio passes through. */
    private class FailingVideoEncoders(
        private val delegate: Codec.EncoderFactory,
    ) : Codec.EncoderFactory {
        override fun createForAudioEncoding(
            format: Format,
            logSessionId: android.media.metrics.LogSessionId?,
        ): Codec = delegate.createForAudioEncoding(format, logSessionId)

        override fun createForVideoEncoding(
            format: Format,
            logSessionId: android.media.metrics.LogSessionId?,
        ): Codec =
            throw ExportException.createForCodec(
                IllegalStateException("test: the hardware encoder is broken"),
                ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                ExportException.CodecInfo(format.toString(), true, false, null),
            )

        @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
        override fun isVideoFormatSupported(format: Format): Boolean = delegate.isVideoFormatSupported(format)

        override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

        override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()
    }

    /** The real encoders, noting the bit rate each video encoder was configured with. */
    private class RecordingVideoEncoders(
        private val delegate: Codec.EncoderFactory,
        private val configured: MutableList<Int>,
    ) : Codec.EncoderFactory {
        override fun createForAudioEncoding(
            format: Format,
            logSessionId: android.media.metrics.LogSessionId?,
        ): Codec = delegate.createForAudioEncoding(format, logSessionId)

        override fun createForVideoEncoding(
            format: Format,
            logSessionId: android.media.metrics.LogSessionId?,
        ): Codec = delegate.createForVideoEncoding(format, logSessionId).also { configured += it.configurationFormat.averageBitrate }

        @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
        override fun isVideoFormatSupported(format: Format): Boolean = delegate.isVideoFormatSupported(format)

        override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

        override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()
    }

    private companion object {
        const val FRAME_US = 1_000_000L / 30
    }
}
