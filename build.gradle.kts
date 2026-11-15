// Top-level build file: plugin versions come from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint)
}

// Apply ktlint to every module so `./gradlew ktlintCheck` covers the whole project.
allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.8.0")
        // The code style comes from .editorconfig `ktlint_code_style`.
        filter {
            exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
            exclude("**/build/**")
        }
    }

    // ktlint-cli 1.8.0 ships logback 1.3.x. logback is only the lint tool's own
    // logger (never on the app classpath), so it is raised on the `ktlint`
    // configuration alone, as in ClearSMS.
    configurations.matching { it.name == "ktlint" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "ch.qos.logback") useVersion("1.5.38")
        }
    }
}

// Guard: ktlint must actually inspect the app's Kotlin sources. Under AGP 9 an
// older ktlint plugin registered no per-source-set task and `ktlintCheck`
// passed without reading a file (seen in ClearSMS). Fail at configuration time
// unless a check exists for every source set and is wired into `ktlintCheck`.
gradle.projectsEvaluated {
    val app = project(":app")
    val expected = listOf("ktlintMainSourceSetCheck", "ktlintTestSourceSetCheck", "ktlintAndroidTestSourceSetCheck")
    val missing = expected.filter { app.tasks.findByName(it) == null }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "ktlint is not inspecting the app sources: ${missing.joinToString()} not registered. " +
                "Registered ktlint tasks: ${app.tasks.names.filter { it.startsWith("ktlint") }.sorted()}",
        )
    }
    val aggregate = app.tasks.getByName("ktlintCheck")
    val wired = aggregate.taskDependencies.getDependencies(aggregate).map { it.name }
    val unwired = expected.filter { it !in wired }
    if (unwired.isNotEmpty()) {
        throw GradleException(
            "ktlintCheck no longer depends on ${unwired.joinToString()}; it would pass without " +
                "inspecting those sources. Wired: $wired",
        )
    }
}
