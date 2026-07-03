plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

// --- Release signing --------------------------------------------------------
// Keystore coordinates are read from environment variables (CI) or Gradle
// properties (local dev) — never committed. See RELEASE.md.
//   WISP_KEYSTORE_FILE / wispKeystoreFile         path to the .jks/.keystore
//   WISP_KEYSTORE_PASSWORD / wispKeystorePassword store password
//   WISP_KEY_ALIAS / wispKeyAlias                 key alias
//   WISP_KEY_PASSWORD / wispKeyPassword           key password (defaults to
//                                                     the store password)
// If none are set, the release build gracefully falls back to the debug signing
// config so `assembleRelease` still yields an installable (debug-signed) APK.
// NEVER commit a keystore or password — *.jks / *.keystore are gitignored.
fun releaseSecret(
    envName: String,
    propName: String,
): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(propName) as String?)?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSecret("WISP_KEYSTORE_FILE", "wispKeystoreFile")
val releaseStorePassword = releaseSecret("WISP_KEYSTORE_PASSWORD", "wispKeystorePassword")
val releaseKeyAlias = releaseSecret("WISP_KEY_ALIAS", "wispKeyAlias")
val releaseKeyPassword =
    releaseSecret("WISP_KEY_PASSWORD", "wispKeyPassword") ?: releaseStorePassword
val hasReleaseSigning =
    releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null

android {
    namespace = "com.wisp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wisp"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Sideload targets are arm64 only (Pixel 7 Pro + the arm64 AVD); the
        // other ABIs were ~35 MB of unused onnxruntime libs in every APK.
        ndk { abiFilters += "arm64-v8a" }

        // Injected for the opt-in real-Claude smoke test (androidTest). Reads the
        // `anthropicApiKey` Gradle property or ANTHROPIC_API_KEY env var; defaults
        // to empty so the smoke test skips cleanly when no key is present. NEVER
        // commit a key — this is resolved at build time from the environment only.
        val anthropicApiKey =
            (project.findProperty("anthropicApiKey") as String?)
                ?: System.getenv("ANTHROPIC_API_KEY")
                ?: ""
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Real release keystore when configured, else debug-signed so the
            // sideload APK still installs locally (see RELEASE.md).
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    lint {
        // Existing warnings are grandfathered via lint-baseline.xml; only *new*
        // errors abort the build. Not treating warnings as errors keeps the
        // sideload/dev loop unblocked while still gating regressions.
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        baseline = file("lint-baseline.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Unmocked framework calls (e.g. android.util.Log) return defaults
            // instead of throwing, so framework-free logic can be unit-tested
            // without Robolectric.
            isReturnDefaultValues = true
        }
    }
}

// Room exports its schema JSON here (phase-12); the committed schema documents
// the CREATE statements the numbered migrations must mirror.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// --- Static analysis / formatting -------------------------------------------
// detekt + ktlint gate *new* code via committed baselines (config/detekt/
// baseline.xml, config/detekt/detekt-baseline.xml for ktlint is generated under
// build/). Existing code is grandfathered — do not mass-reformat. Update a
// baseline with `./gradlew detektBaseline` / `./gradlew ktlintGenerateBaseline`.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    // Fast, no type-resolution pass. To enable type-aware rules later, run the
    // `detektMain` task (needs the compile classpath) instead of `detekt`.
    parallel = true
}

ktlint {
    version.set("1.3.1")
    android.set(true)
    // .editorconfig at the repo root is the source of truth for style.
    baseline.set(file("$rootDir/config/ktlint/baseline.xml"))
    filter {
        exclude { it.file.path.contains("/generated/") }
        exclude { it.file.path.contains("/build/") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // DB (declared now; used from phase-05)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Async / serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Secrets
    implementation(libs.androidx.security.crypto)

    // HTTP transport for the Claude client (phase-04)
    implementation(libs.okhttp)

    // Wake word detection (phase-09) — openWakeWord ONNX runtime port.
    // onnxruntime pinned above openwakeword's transitive 1.18.0: 1.18 ships
    // 4 KB-aligned ELF segments and fails Android's 16 KB page-size check.
    implementation(libs.openwakeword)
    implementation(libs.onnxruntime.android)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.turbine)

    // Instrumented (real-Claude smoke test lives in androidTest)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
