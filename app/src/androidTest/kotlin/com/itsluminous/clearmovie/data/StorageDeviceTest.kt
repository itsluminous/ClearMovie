package com.itsluminous.clearmovie.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.preset.AndroidMediaProbe
import com.itsluminous.clearmovie.data.preset.KeyColorCheck
import com.itsluminous.clearmovie.data.preset.PresetStore
import com.itsluminous.clearmovie.domain.model.ArgbColor
import com.itsluminous.clearmovie.domain.model.NormRect
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On a real phone: the Room schema and its migration chain (room-testing),
 * and the platform decoder behind the preset checks, on the synthetic
 * fixtures from scripts/make-storage-fixtures.sh.
 */
@RunWith(AndroidJUnit4::class)
class StorageDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ClearMovieDatabase::class.java)

    private fun fixture(name: String): File {
        val out = File(context.cacheDir, "fixture-$name")
        javaClass.classLoader!!.getResourceAsStream("fixtures/media/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    @Test
    fun theExportedSchemaMatchesTheDatabaseAndEveryMigrationRuns() {
        migrations.createDatabase(DB, 1).close()
        // Every step in MIGRATIONS up to the current version, checked against the exported schema.
        migrations.runMigrationsAndValidate(DB, ClearMovieDatabase.VERSION, true, *ClearMovieDatabase.MIGRATIONS).close()
        val db = Room.databaseBuilder(context, ClearMovieDatabase::class.java, DB).addMigrations(*ClearMovieDatabase.MIGRATIONS).build()
        runBlocking { assertThat(db.projects().summaryList()).isEmpty() }
        db.close()
    }

    @Test
    fun theReEncodedFrameDecodedOnThePhoneGetsItsKeyColourAdjusted() =
        runBlocking<Unit> {
            val probe = AndroidMediaProbe()
            val key = ArgbColor(0xFF00D922)
            val window = NormRect(0.0094, 0.0222, 0.9813, 0.7741)

            val original = probe.probe(fixture("frame-original.mp4"), PresetStore.FRAME_SAMPLES)!!
            val reencoded = probe.probe(fixture("frame-reencoded.mp4"), PresetStore.FRAME_SAMPLES)!!
            assertThat(original.frames).hasSize(3)
            assertThat(reencoded.frames).hasSize(3)
            assertThat(original.durationUs).isAtLeast(1_900_000)

            val before = KeyColorCheck.measure(original.frames, window, key)!!
            val after = KeyColorCheck.measure(reencoded.frames, window, key)!!
            android.util.Log.i(TAG, "original measured=${before.measured} deltaE=${"%.2f".format(before.deltaE)}")
            android.util.Log.i(TAG, "reencoded measured=${after.measured} deltaE=${"%.2f".format(after.deltaE)}")
            assertThat(before.needsAdjusting).isFalse()
            assertThat(after.needsAdjusting).isTrue()
            assertThat(after.deltaE).isWithin(1.5).of(9.0)
        }

    @Test
    fun theToneFixtureMeasuresMinus20Lufs() =
        runBlocking<Unit> {
            val facts = AndroidMediaProbe().probe(fixture("tone-997hz-minus20dbfs.m4a"), measureLoudness = true)!!
            android.util.Log.i(TAG, "tone loudness=${facts.loudnessLufs}")
            assertThat(facts.hasAudio).isTrue()
            assertThat(facts.hasVideo).isFalse()
            assertThat(facts.loudnessLufs!!).isWithin(0.5).of(-20.0)
        }

    @Test
    fun aFileThatIsNotMediaProbesAsNull() =
        runBlocking<Unit> {
            val junk = File(context.cacheDir, "junk.mp4").apply { writeText("not a video") }
            assertThat(AndroidMediaProbe().probe(junk, PresetStore.FRAME_SAMPLES, measureLoudness = true)).isNull()
        }

    private companion object {
        const val DB = "migration-test.db"
        const val TAG = "StorageDeviceTest"
    }
}
