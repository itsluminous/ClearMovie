package com.itsluminous.clearmovie.render.media3

import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the calibration keeps: the stored answer, a fresh measurement, and what it refuses to keep. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ColourCalibrationCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = ColourCalibration.key()
    private val bt601 = DeviceColour(gpuDecodesBt709AsBt601 = true, encoderWritesBt601 = true, encoderName = "OMX.google.h264.encoder")

    @After
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `a stored answer is used without measuring`() {
        val file = folder.newFile()
        file.writeText(bt601.serialize(key))
        var measured = 0
        assertThat(ColourCalibration.lookUp(file, key) { measured++.let { null } }).isEqualTo(bt601 to true)
        assertThat(measured).isEqualTo(0)
    }

    @Test
    fun `a measurement is stored and read back by the next process`() {
        val file = folder.root.resolve("render/colour-calibration.txt")
        assertThat(ColourCalibration.lookUp(file, key) { bt601 }).isEqualTo(bt601 to true)
        assertThat(DeviceColour.parse(file.readText(), key)).isEqualTo(bt601)
    }

    @Test
    fun `an answer from an older measurement is measured again`() {
        val fresh = DeviceColour(gpuDecodesBt709AsBt601 = true, encoderWritesBt601 = false, encoderName = "c2.android.avc.encoder")
        // Version 2 encoded a 320x192 clip, version 3 a clip without a colr box.
        for (older in listOf("v2|", "v3|")) {
            val file = folder.newFile()
            file.writeText(bt601.serialize(older + key.substringAfter('|')))
            assertThat(ColourCalibration.lookUp(file, key) { fresh }).isEqualTo(fresh to true)
            assertThat(DeviceColour.parse(file.readText(), key)).isEqualTo(fresh)
        }
    }

    @Test
    fun `an interrupted measurement is not kept`() {
        val file = folder.root.resolve("colour-calibration.txt")
        val (result, keep) =
            ColourCalibration.lookUp(file, key) {
                Thread.currentThread().interrupt()
                null
            }
        assertThat(result).isEqualTo(DeviceColour.TRUSTED)
        assertThat(keep).isFalse()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a failed or unclear measurement changes nothing and is not stored`() {
        val file = folder.root.resolve("colour-calibration.txt")
        assertThat(ColourCalibration.lookUp(file, key) { null }).isEqualTo(DeviceColour.TRUSTED to true)
        assertThat(ColourCalibration.lookUp(file, key) { error("boom") }).isEqualTo(DeviceColour.TRUSTED to true)
        assertThat(ColourCalibration.lookUp(file, key) { DeviceColour.TRUSTED }).isEqualTo(DeviceColour.TRUSTED to true)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a BT601 answer whose encoder name cannot be stored is not written`() {
        val file = folder.root.resolve("colour-calibration.txt")
        val unnamed = bt601.copy(encoderName = "bad name")
        assertThat(ColourCalibration.lookUp(file, key) { unnamed }).isEqualTo(unnamed to true)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a BT709 answer is stored even when its encoder name cannot be`() {
        val file = folder.root.resolve("colour-calibration.txt")
        val answer = DeviceColour(gpuDecodesBt709AsBt601 = true, encoderWritesBt601 = false, encoderName = "bad name")
        ColourCalibration.lookUp(file, key) { answer }
        assertThat(DeviceColour.parse(file.readText(), key)).isEqualTo(answer.copy(encoderName = null))
    }
}
