package com.itsluminous.clearmovie.resources

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.testing.SourceTree
import com.itsluminous.clearmovie.testing.StringXml
import org.junit.Test
import java.io.File

/**
 * The default-locale strings are split by TRANSLATABILITY (as in ClearSMS):
 * `strings.xml` and `strings_platform.xml` are translated, and
 * `strings_notranslate.xml` holds the product name, URLs and language tags.
 * A translator can then skip exactly one file, which only works if the split
 * stays honest.
 */
class NoTranslateStringsConventionTest {
    private val valuesDir = SourceTree.shippedValuesDirs[0]
    private val translatedFiles = listOf("strings.xml", "strings_platform.xml")

    private fun file(name: String) = File(valuesDir, name)

    @Test
    fun `every entry in strings_notranslate is marked translatable=false`() {
        val entries = StringXml.entries(file("strings_notranslate.xml"))
        assertThat(entries).isNotEmpty()
        assertWithMessage("strings_notranslate.xml entries without translatable=\"false\"")
            .that(entries.filter { it.translatable }.map { it.name })
            .isEmpty()
    }

    @Test
    fun `the product name and the language tags live only in strings_notranslate`() {
        val noTranslate = StringXml.entries(file("strings_notranslate.xml")).map { it.name }
        assertThat(noTranslate).containsAtLeast("app_name", "supported_language_tags")
        for (name in translatedFiles) {
            val names = StringXml.entries(file(name)).map { it.name }
            assertWithMessage("$name must not define app_name or supported_language_tags")
                .that(names)
                .containsNoneOf("app_name", "supported_language_tags")
        }
    }

    @Test
    fun `the translated files carry no translatable=false entries`() {
        for (name in translatedFiles) {
            assertWithMessage("$name has translatable=\"false\" entries; move them to strings_notranslate.xml")
                .that(StringXml.entries(file(name)).filter { !it.translatable }.map { it.name })
                .isEmpty()
        }
    }

    @Test
    fun `no translated string looks like a URL`() {
        val urlLike = Regex("""(https?://|mailto:|market://|tel:)""", RegexOption.IGNORE_CASE)
        for (dir in SourceTree.shippedValuesDirs) {
            val offenders =
                StringXml
                    .entriesIn(dir)
                    .filter { it.translatable && it.values.any(urlLike::containsMatchIn) }
                    .map { "${dir.name}: ${it.name}" }
            assertWithMessage("URLs belong in strings_notranslate.xml").that(offenders).isEmpty()
        }
    }

    @Test
    fun `the default locale is exactly these three string files and translations copy only two`() {
        fun stringFiles(dir: File) = dir.listFiles { f: File -> f.name.startsWith("strings") && f.extension == "xml" }!!.map { it.name }
        assertThat(stringFiles(valuesDir)).containsExactly("strings.xml", "strings_platform.xml", "strings_notranslate.xml")
        for (dir in SourceTree.shippedValuesDirs.drop(1)) {
            assertWithMessage(dir.name).that(stringFiles(dir)).containsExactly("strings.xml", "strings_platform.xml")
        }
    }
}
