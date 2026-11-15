package com.itsluminous.clearmovie.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.itsluminous.clearmovie.R
import java.util.Locale

/** One entry of Settings -> Language: a BCP 47 tag and the language's name in its own script. */
data class LanguageOption(
    val tag: String,
    val nativeName: String,
)

/**
 * The in-app language picker, on top of per-app locales. On Android 13+
 * AppCompat hands the choice to the system's LocaleManager (it also shows in
 * Settings -> Apps -> ClearMovie -> Language); on Android 10-12 AppCompat
 * stores it itself (autoStoreLocales in the manifest) and applies it to every
 * AppCompatActivity. Either way the activity is recreated in the new language.
 */
object AppLanguage {
    /**
     * The supported languages, each named by its own folder's `language_name_native`
     * (English, हिन्दी, Hinglish), so the list reads the same in every UI language.
     */
    fun options(context: Context): List<LanguageOption> =
        context.resources.getStringArray(R.array.supported_language_tags).map { tag ->
            val localized =
                Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            LanguageOption(tag, context.createConfigurationContext(localized).getString(R.string.language_name_native))
        }

    /** The chosen tag among [supportedTags], or null for "Same as phone". */
    fun selectedTag(supportedTags: List<String>): String? {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) null else matchSupportedTag(locales[0], supportedTags)
    }

    /** Applies [tag]; null returns to the phone's language. Call on the main thread. */
    fun select(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }
}

/**
 * Maps a stored or system [locale] to one of [supportedTags]: an exact tag first,
 * then the same language and script (so `hi-IN` selects `hi` and `hi-Latn-IN`
 * selects `hi-Latn`, but `hi-Latn` never selects Devanagari `hi`). Null when none fits.
 */
internal fun matchSupportedTag(
    locale: Locale?,
    supportedTags: List<String>,
): String? {
    if (locale == null) return null
    val exact = locale.toLanguageTag()
    supportedTags.firstOrNull { it.equals(exact, ignoreCase = true) }?.let { return it }
    return supportedTags.firstOrNull { tag ->
        val candidate = Locale.forLanguageTag(tag)
        candidate.language == locale.language && candidate.script.equals(locale.script, ignoreCase = true)
    }
}
