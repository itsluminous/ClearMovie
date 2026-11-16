package com.itsluminous.clearmovie.ui.preset

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.core.os.ConfigurationCompat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.domain.model.LocalizedText
import com.itsluminous.clearmovie.domain.model.Preset
import java.util.Locale

/**
 * A preset's name as the user sees it. The user's own `preset.json` may name
 * it in each shipped language ([Preset.displayName], user data, e.g. the
 * owner's channel name); a bundled preset names a string resource
 * ([Preset.displayNameRes]). Only the bundled names listed in [BUNDLED] are
 * looked up, so a preset file can never point at an unrelated string.
 */
object PresetNames {
    private val BUNDLED: Map<String, Int> = mapOf("preset_event_frame_name" to R.string.preset_event_frame_name)

    fun name(
        preset: Preset,
        resources: Resources,
        locale: Locale,
    ): String {
        preset.displayName?.let { text -> pick(text, locale)?.let { return it } }
        val res = preset.displayNameRes?.let { BUNDLED[it] } ?: R.string.preset_unnamed
        return resources.getString(res)
    }

    /** The text in [locale]'s language and script, else the nearest one there is. */
    internal fun pick(
        text: LocalizedText,
        locale: Locale,
    ): String? {
        val hindi = locale.language == "hi"
        val latin = locale.script == "Latn"
        val order =
            when {
                hindi && latin -> listOf(text.hiLatn, text.en, text.hi)
                hindi -> listOf(text.hi, text.hiLatn, text.en)
                else -> listOf(text.en, text.hiLatn, text.hi)
            }
        return order.firstOrNull { !it.isNullOrBlank() }
    }
}

@Composable
@ReadOnlyComposable
fun presetName(preset: Preset): String {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    return PresetNames.name(preset, LocalResources.current, locale)
}
