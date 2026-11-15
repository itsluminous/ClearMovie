package com.itsluminous.clearmovie

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.testing.SourceTree
import org.junit.Test
import java.io.File

/**
 * Layering inside the single `:app` module (ARCHITECTURE section 5), enforced
 * by imports:
 * - `domain` and `render.plan` are pure Kotlin: no `android.*`,
 *   `androidx.*` or Media3, and nothing from the app's other layers, so it
 *   stays JVM-testable.
 * - `data` never reaches up into `ui`.
 */
class PackageLayeringConventionTest {
    private val root = "com.itsluminous.clearmovie"

    private data class Rule(
        val packagePrefix: String,
        val forbiddenImports: List<String>,
    )

    private val pureKotlin =
        listOf("android.", "androidx.", "com.google.android.", "$root.data", "$root.ui", "$root.di", "$root.render.media3", "$root.work")
    private val rules =
        listOf(
            Rule("$root.domain", pureKotlin),
            Rule("$root.render.plan", pureKotlin),
            Rule("$root.data", listOf("$root.ui")),
        )

    private val packageLine = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val importLine = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)

    fun violations(
        path: String,
        source: String,
    ): List<String> {
        val pkg = packageLine.find(source)?.groupValues?.get(1) ?: return emptyList()
        val imports = importLine.findAll(source).map { it.groupValues[1] }.toList()
        return rules
            .filter { pkg == it.packagePrefix || pkg.startsWith(it.packagePrefix + ".") }
            .flatMap { rule ->
                imports.filter { import -> rule.forbiddenImports.any { import.startsWith(it) } }.map { "$path ($pkg) imports $it" }
            }
    }

    @Test
    fun `no layer imports what it must not`() {
        val found = SourceTree.mainKotlinFiles().flatMap { violations(it.path, it.readText()) }.toList()
        assertWithMessage("Layering violations:\n" + found.joinToString("\n")).that(found).isEmpty()
    }

    @Test
    fun `the domain package exists and is checked`() {
        assertThat(File(SourceTree.mainKotlinDir, "com/itsluminous/clearmovie/domain").isDirectory).isTrue()
    }

    @Test
    fun `the checker catches planted violations`() {
        val domain = "package $root.domain.model\nimport android.net.Uri\nimport $root.ui.theme.MediaColors\nimport java.io.File\n"
        val data = "package $root.data.settings\nimport $root.ui.settings.SettingsScreen\nimport androidx.datastore.core.DataStore\n"
        val ui = "package $root.ui.home\nimport android.os.Build\nimport $root.data.settings.SettingsRepository\n"
        assertThat(violations("d.kt", domain)).hasSize(2)
        assertThat(violations("s.kt", data)).hasSize(1)
        assertThat(violations("h.kt", ui)).isEmpty()
    }
}
