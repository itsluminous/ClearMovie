package com.itsluminous.clearmovie.ui.theme

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The launcher icons are adaptive icons with a monochrome layer, which is what
 * Android 13+ tints for themed icons. (The AOSP emulator's launcher has no
 * themed-icon switch, so this is checked here as well as on a real phone.)
 */
@RunWith(RobolectricTestRunner::class)
class LauncherIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `both launcher icons have background, foreground and monochrome layers`() {
        for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = ContextCompat.getDrawable(context, id)
            assertThat(icon).isInstanceOf(AdaptiveIconDrawable::class.java)
            icon as AdaptiveIconDrawable
            assertThat(icon.background).isNotNull()
            assertThat(icon.foreground).isNotNull()
            assertThat(icon.monochrome).isNotNull()
        }
    }
}
