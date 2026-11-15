import com.android.build.api.variant.FilterConfiguration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is driven entirely by environment variables (as in ClearSMS):
// with all four set, release APKs come out signed; local builds, forks and PRs
// without the secrets fall back to unsigned release APKs instead of failing.
// See docs/release-signing.md.
val signingKeystoreFile: String? = System.getenv("SIGNING_KEYSTORE_FILE")
val signingKeystorePassword: String? = System.getenv("SIGNING_KEYSTORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning =
    !signingKeystoreFile.isNullOrBlank() &&
        !signingKeystorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

// Base version code. Every APK's real versionCode is `baseVersionCode * 10 + abiCode`
// (see abiVersionCodes below and docs/release-signing.md). The release job reads this
// line to find fastlane/metadata/android/en-US/changelogs/<baseVersionCode>.txt.
val baseVersionCode = 1

// ABI splits: AndroidX ships small native libraries (Compose graphics-path,
// DataStore's shared counter), so each processor type gets its own APK, plus a
// universal one. The release job's native-code guard
// (scripts/verify-release-apks.sh) fails if these splits are switched off while
// .so files still ship.
val splitAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

// abiCode per output: 0 universal, 1 armeabi-v7a, 2 arm64-v8a, 3 x86, 4 x86_64.
// Every per-ABI APK gets a higher code than the universal one, so moving from the
// universal APK to the matching per-ABI APK is always an update.
val abiVersionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

android {
    namespace = "com.itsluminous.clearmovie"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itsluminous.ClearMovie"
        minSdk = 29
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(signingKeystoreFile!!)
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                // v2 for Android 7-8, v3 (key rotation support) for Android 9+.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Signed only when all four SIGNING_* environment variables are set.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Keep the APK free of the build machine's VCS state and path.
            vcsInfo {
                include = false
            }
            // aapt2's PNG cruncher is not byte-stable across hosts (ClearSMS).
            isCrunchPngs = false
            // R8 shrinking without obfuscation (-dontobfuscate in proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*splitAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    // If an app bundle is ever built, keep every language in the base APK: the
    // in-app picker switches language without the Play Store downloading one.
    bundle {
        language {
            enableSplit = false
        }
    }

    // The Play-Store dependency block is useless outside the Play Store.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME feeds Settings -> About.
        buildConfig = true
    }

    androidResources {
        // Ship only the shipped languages' resources: AndroidX and Material carry
        // ~85 locales, whose framework strings ("Navigate up") would otherwise
        // show in the phone's language while the app's own text is English.
        localeFilters += listOf("en", "hi", "b+hi+Latn")
        // AGP generates the locale config from the values-<locale>/ folders plus
        // the default locale named in res/resources.properties, and wires
        // android:localeConfig into the merged manifest. A new translation
        // folder is all a translator has to add.
        generateLocaleConfig = true
    }

    lint {
        // Severities live in app/lint.xml. No baseline: every finding is fixed.
        abortOnError = true
        warningsAsErrors = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDir("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDir("src/androidTest/kotlin")
            // room-testing's MigrationTestHelper reads the exported schemas as test assets.
            assets.srcDir("$projectDir/schemas")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            output.versionCode.set(baseVersionCode * 10 + (abi?.let { abiVersionCodes.getValue(it) } ?: 0))
            if (variant.buildType == "release") {
                // ClearMovie-<versionName>-<abi>.apk, and -universal for the fat APK.
                // VariantOutputImpl is AGP-internal (ClearSMS uses it too); fail at
                // configuration time, not with oddly named APKs, if an AGP bump removes it.
                val impl =
                    output as? com.android.build.api.variant.impl.VariantOutputImpl
                        ?: throw GradleException("AGP no longer exposes VariantOutputImpl; update the APK naming in app/build.gradle.kts")
                impl.outputFileName.set(output.versionName.map { "ClearMovie-$it-${abi ?: "universal"}.apk" })
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Line coverage of the pure project model (ROADMAP cm-model: at least 90%).
// `./gradlew koverVerifyDebug` runs the debug unit tests and fails below the
// bound; CI runs it with the other checks. `koverHtmlReportDebug` shows which
// lines are missed.
kover {
    reports {
        filters {
            includes {
                packages("com.itsluminous.clearmovie.domain.model")
            }
        }
        variant("debug") {
            verify {
                rule("Project model line coverage") {
                    minBound(90)
                }
            }
        }
    }
}

dependencies {
    // Core AndroidX + lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // AppCompat: per-app language (setApplicationLocales) on Android 10-12.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // ProcessLifecycleOwner: autosave flushes when the whole app goes to the background.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose (versions from the BOM)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Room (projects, preset bindings, session) and DataStore (settings)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // The project model is stored as JSON (ARCHITECTURE section 6, cm-storage keeps it in Room).
    implementation(libs.kotlinx.serialization.json)

    // Media3: Transformer for the export and exact drafts, ExoPlayer for the layered preview.
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)

    // Material Components: provides the XML Theme.Material3.* parent in themes.xml.
    implementation(libs.google.material)

    // Unit tests (JVM + Robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.turbine)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
