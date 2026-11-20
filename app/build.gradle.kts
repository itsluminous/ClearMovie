plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is driven entirely by environment variables (as in ClearSMS):
// with all four set, the release APK comes out signed; local builds, forks and PRs
// without the secrets fall back to an unsigned release APK instead of failing.
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

// Base version code. The APK's versionCode is `baseVersionCode * 10`
// (defaultConfig below; see docs/release-signing.md). 0.0.1 shipped as per-ABI
// APKs with codes 10-14 (base * 10 + abiCode); the * 10 keeps every later
// release (base 2 and up, codes 20 and up) above all of them, so a phone with
// any 0.0.1 APK accepts the next single APK as an update. Raise this by one for
// each release. The release job reads this line to find
// fastlane/metadata/android/en-US/changelogs/<baseVersionCode>.txt.
val baseVersionCode = 2

android {
    namespace = "com.itsluminous.clearmovie"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itsluminous.ClearMovie"
        minSdk = 29
        targetSdk = 36
        versionCode = baseVersionCode * 10
        versionName = "0.0.2"

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
            if (variant.buildType == "release") {
                // One APK for every processor type: ClearMovie-<versionName>.apk.
                // VariantOutputImpl is AGP-internal (ClearSMS uses it too); fail at
                // configuration time, not with an oddly named APK, if an AGP bump removes it.
                val impl =
                    output as? com.android.build.api.variant.impl.VariantOutputImpl
                        ?: throw GradleException("AGP no longer exposes VariantOutputImpl; update the APK naming in app/build.gradle.kts")
                impl.outputFileName.set(output.versionName.map { "ClearMovie-$it.apk" })
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
    // Export runs in a Hilt-injected foreground worker (ARCHITECTURE section 12).
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

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
    // EXIF capture dates of shared photos (cm-share); already on the classpath through Media3.
    implementation(libs.androidx.exifinterface)

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
    testImplementation(libs.androidx.work.testing)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.work.testing)
}
