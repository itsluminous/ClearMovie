package com.itsluminous.clearmovie.testing

import java.io.File

/**
 * The app module's source tree, whether the test JVM runs from the module
 * (Gradle) or from the repo root (an IDE).
 */
object SourceTree {
    private val moduleDir: File =
        listOf(File("."), File("app"))
            .first { File(it, "src/main/AndroidManifest.xml").isFile }

    /** `app/src/main/res`. */
    val resDir: File = File(moduleDir, "src/main/res")

    /** `app/src/main/kotlin`. */
    val mainKotlinDir: File = File(moduleDir, "src/main/kotlin")

    /** The three shipped string folders: English (default), Hindi, Hinglish. */
    val shippedValuesDirs: List<File> = listOf("values", "values-hi", "values-b+hi+Latn").map { File(resDir, it) }

    /** Every main Kotlin source file. */
    fun mainKotlinFiles(): Sequence<File> = mainKotlinDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
}
