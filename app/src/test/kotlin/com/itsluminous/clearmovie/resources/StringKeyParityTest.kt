package com.itsluminous.clearmovie.resources

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.testing.SourceTree
import com.itsluminous.clearmovie.testing.StringEntry
import com.itsluminous.clearmovie.testing.StringXml
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * English, Hindi and Hinglish must stay complete and consistent: the same
 * translatable `<string>` and `<plurals>` names, the same placeholders per name,
 * and no Devanagari anywhere in the Hinglish folder.
 *
 * Lint's MissingTranslation / ExtraTranslation (errors in app/lint.xml) catch
 * a missing or extra name. This test also catches what Lint does not: a
 * translation that drops or renumbers a placeholder in a way that still
 * formats, and Devanagari in the Latin-script Hinglish files.
 */
class StringKeyParityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** `%1$s`, `%2$d`, `%d`, `%.1f`; `%%` is a literal percent sign and does not count. */
    private val placeholder = Regex("""%(?!%)(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

    private val devanagari = Regex("""[\u0900-\u097F\uA8E0-\uA8FF]""")

    /** name -> placeholders, for every translatable string and plural. */
    private fun keys(valuesDir: File): Map<String, Set<String>> =
        StringXml
            .entriesIn(valuesDir)
            .filter { it.translatable && it.tag != "string-array" }
            .associate { entry -> entry.name to entry.placeholders() }

    private fun StringEntry.placeholders(): Set<String> =
        values
            .flatMap { value ->
                // A literal %% must not hide a placeholder after it ("%1$d%% done").
                placeholder.findAll(value.replace("%%", "")).map { it.value }
            }.toSet()

    /** Every difference between [default] and [translation], as readable lines. */
    private fun parityProblems(
        default: File,
        translation: File,
    ): List<String> {
        val expected = keys(default)
        val actual = keys(translation)
        val problems = mutableListOf<String>()
        (expected.keys - actual.keys).sorted().forEach { problems += "${translation.name}: missing \"$it\"" }
        (actual.keys - expected.keys).sorted().forEach { problems += "${translation.name}: \"$it\" does not exist in ${default.name}" }
        for (name in (expected.keys intersect actual.keys).sorted()) {
            if (expected[name] != actual[name]) {
                problems += "${translation.name}: \"$name\" has placeholders ${actual[name]}, expected ${expected[name]}"
            }
        }
        return problems
    }

    /** Lines of [dir] that contain Devanagari, as `file:line`. */
    private fun devanagariLines(dir: File): List<String> =
        dir
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line -> if (devanagari.containsMatchIn(line)) "${file.name}:${i + 1}" else null }
            }.toList()

    @Test
    fun `Hindi and Hinglish have exactly the English keys and placeholders`() {
        val (english, hindi, hinglish) = SourceTree.shippedValuesDirs
        assertThat(keys(english)).isNotEmpty()
        val problems = parityProblems(english, hindi) + parityProblems(english, hinglish)
        assertWithMessage("Every string must exist in all three shipped languages:\n" + problems.joinToString("\n"))
            .that(problems)
            .isEmpty()
    }

    @Test
    fun `the Hinglish folder contains no Devanagari`() {
        val hinglish = SourceTree.shippedValuesDirs[2]
        assertWithMessage("Hinglish is Hindi in Latin letters; Devanagari found at:")
            .that(devanagariLines(hinglish))
            .isEmpty()
    }

    @Test
    fun `every plural in every shipped language has an other quantity`() {
        val missing =
            SourceTree.shippedValuesDirs.flatMap { dir ->
                StringXml.entriesIn(dir).filter { it.tag == "plurals" && "other" !in it.quantities }.map { "${dir.name}: ${it.name}" }
            }
        assertWithMessage("<plurals> without quantity=\"other\"").that(missing).isEmpty()
    }

    @Test
    fun `the checker itself catches a missing key, an extra key, a changed placeholder and Devanagari`() {
        val default =
            values(
                "values",
                """
                <string name="kept">Kept %1${'$'}s</string>
                <string name="dropped">Dropped</string>
                <string name="percent">%1${'$'}d%% done</string>
                <string name="product" translatable="false">ClearMovie</string>
                <plurals name="items"><item quantity="one">%1${'$'}d item</item><item quantity="other">%1${'$'}d items</item></plurals>
                """,
            )
        val translation =
            values(
                "values-xx",
                """
                <string name="kept">Kept %1${'$'}d</string>
                <string name="percent">%1${'$'}d%% ho gaya</string>
                <string name="extra">Extra</string>
                <plurals name="items"><item quantity="other">%1${'$'}d items, नमस्ते</item></plurals>
                """,
            )
        assertThat(parityProblems(default, translation))
            .containsExactly(
                "values-xx: missing \"dropped\"",
                "values-xx: \"extra\" does not exist in values",
                "values-xx: \"kept\" has placeholders [%1\$d], expected [%1\$s]",
            )
        assertThat(devanagariLines(translation)).hasSize(1)
    }

    private fun values(
        dirName: String,
        body: String,
    ): File {
        val dir = tmp.newFolder(dirName)
        File(dir, "strings.xml").writeText("<resources>\n${body.trimIndent()}\n</resources>\n")
        return dir
    }
}
