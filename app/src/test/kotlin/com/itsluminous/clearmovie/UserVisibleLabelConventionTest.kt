package com.itsluminous.clearmovie

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.testing.SourceTree
import org.junit.Test

/**
 * No user-visible text is written as a literal in Kotlin. Android Lint's
 * HardcodedText only reads XML layouts and the manifest, so it never sees a
 * Compose `Text("Hello")`; this test closes that gap (ported from ClearSMS
 * and extended for Compose parameters).
 *
 * It scans every main source file, with comments removed, for:
 * 1. a literal handed to `Text(...)`, whatever its case;
 * 2. a literal passed as a UI text parameter: `contentDescription =`,
 *    `label =`, `title =`, `placeholder =`, `text =`, `message =`,
 *    `actionLabel =`, `supportingText =`, a Snackbar message or a Toast;
 * 3. a `when` branch that returns a capitalised word or phrase as a label;
 * 4. a label built from an enum name (`name.lowercase().replace('_', ' ')`).
 *
 * A literal with no letter (`Text("•")`) or one that is an interpolation is
 * ignored. The self-test at the bottom plants each shape, including the
 * `Text("Hello")` that cm-scaffold's acceptance asks CI to reject.
 */
class UserVisibleLabelConventionTest {
    data class Finding(
        val file: String,
        val line: Int,
        val text: String,
    ) {
        override fun toString() = "$file:$line  ${text.trim()}"
    }

    private val literal = """"((?:\\.|[^"\\])*)""""

    private val textCall = Regex("""\bText\(\s*(?:text\s*=\s*)?$literal""")

    private val namedTextParameter =
        Regex(
            """\b(?:contentDescription|label|title|placeholder|text|message|actionLabel|supportingText)\s*=\s*$literal""",
        )

    private val snackbarOrToast = Regex("""(?:\bshowSnackbar\(\s*(?:message\s*=\s*)?|\bmakeText\(\s*[^,]+,\s*)$literal""")

    /** `<condition> -> "<literal>"` on one line (the condition does not start with a quote). */
    private val branchLiteral = Regex("""^\s*([^"\s][^"]*?)\s*->\s*$literal\s*,?\s*$""")
    private val titleCasePhrase = Regex("""^[A-Z][a-z]+(?: [a-z]+)*[.!?]?$""")

    private val underscoreToSpace = Regex("""\.replace\('_',\s*' '\)""")
    private val caseChange = Regex("""\.lowercase\(\)|\.uppercase\(\)|replaceFirstChar|uppercaseChar|\.capitalize\(""")

    private val hasLetter = Regex("""\p{L}""")

    /** Comments blanked out, keeping line numbers, so KDoc examples are not findings. */
    private fun withoutComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    private fun isShownText(value: String) = '$' !in value && hasLetter.containsMatchIn(value)

    fun findings(
        path: String,
        source: String,
    ): List<Finding> {
        val code = withoutComments(source)
        val lines = code.lines()
        val found = mutableListOf<Finding>()

        // Shapes 1 and 2 may span lines (`Text(\n    "Hello",`), so they match the whole file.
        for (regex in listOf(textCall, namedTextParameter, snackbarOrToast)) {
            for (match in regex.findAll(code)) {
                if (!isShownText(match.groupValues.last())) continue
                val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                found += Finding(path, line, lines[line - 1])
            }
        }

        var index = 0
        while (index < lines.size) {
            var line = lines[index]
            val reported = index + 1
            // ktlint may wrap a branch as `X ->` / `"literal"`; judge the two as one.
            if (line.trimEnd().endsWith("->") && index + 1 < lines.size) {
                line = line.trimEnd() + " " + lines[index + 1].trim()
                index++
            }
            index++
            val branch = branchLiteral.find(line)
            if (branch != null && isShownText(branch.groupValues[2]) && titleCasePhrase.matches(branch.groupValues[2])) {
                found += Finding(path, reported, line)
            }
            if (underscoreToSpace.containsMatchIn(line) && caseChange.containsMatchIn(line)) {
                found += Finding(path, reported, line)
            }
        }
        return found.distinct().sortedBy { it.line }
    }

    @Test
    fun `no user-visible text is a literal in Kotlin`() {
        val findings = SourceTree.mainKotlinFiles().flatMap { findings(it.path, it.readText()) }.toList()
        assertWithMessage(
            "Text the user sees must be a string resource (stringResource(R.string.x)), with its Hindi and " +
                "Hinglish translations, never a literal in code:\n" + findings.joinToString("\n"),
        ).that(findings).isEmpty()
    }

    @Test
    fun `the scanner catches the planted shapes and ignores ids, symbols and comments`() {
        val planted =
            """
            Text("Hello")
            Text(text = "lower case too")
            Icon(painter, contentDescription = "Settings")
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
            Text(
                "Wrapped",
            )
            snackbarHostState.showSnackbar("Saved")
            Toast.makeText(context, "Exported", Toast.LENGTH_SHORT)
            val label = when (mode) {
                ThemeMode.DARK -> "Dark"
                ThemeMode.LIGHT ->
                    "Light"
                else -> R.string.theme_system
            }
            fun ThemeMode.label() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            const val HOME = "home"
            val mime = when (kind) { Kind.PHOTO -> "image/jpeg" }
            Text("•")
            Text("${'$'}count")
            Text(stringResource(R.string.app_name))
            // Text("Only a comment")
            /** A KDoc example: Text("Also a comment") */
            Modifier.testTag("settings_list")
            """.trimIndent()
        assertThat(findings("planted.kt", planted).map { it.line })
            .containsExactly(1, 2, 3, 5, 8, 9, 11, 12, 16)
            .inOrder()
    }
}
