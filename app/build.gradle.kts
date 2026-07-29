plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.firebase-perf")
}

android {
    namespace = "com.wim.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wimlabs.wim"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Strip out unneeded library locales (Play Services injects ~80 locales).
        // WiM ships English plus the 4 native UI locales added Session 17.
        resourceConfigurations += listOf("en", "es", "ru", "pt", "fr")

        // Only ship native code for arm64 (modern Pixel/Samsung/etc.) and
        // armv7 (older 32-bit devices still in active use). Drops x86 and
        // x86_64 which only run on emulators. Halves the sherpa-onnx .so
        // payload that's the bulk of the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Defense in depth: even zero-byte debug placeholders must never appear
    // in a distributable APK/AAB.
    androidResources {
        ignoreAssetsPattern = "api_key.txt:anthropic_key.txt"
    }

    // Release signing secrets come from ~/.gradle/gradle.properties (never the
    // repo): WIM_STORE_PASSWORD / WIM_KEY_PASSWORD. If absent (e.g. CI without
    // the secret) the release variant builds UNSIGNED instead of failing.
    val wimStorePw = project.findProperty("WIM_STORE_PASSWORD") as? String
    val wimKeyPw = (project.findProperty("WIM_KEY_PASSWORD") as? String) ?: wimStorePw
    val wimOpenAiKey = project.findProperty("WIM_OPENAI_KEY") as? String ?: ""
    val wimAnthropicKey = project.findProperty("WIM_ANTHROPIC_KEY") as? String ?: ""
    signingConfigs {
        if (wimStorePw != null) {
            create("release") {
                storeFile = file("../wim-release.jks")
                storePassword = wimStorePw
                keyAlias = "wim"
                keyPassword = wimKeyPw
            }
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "EMBEDDED_OPENAI_KEY", "\"\"")
            buildConfigField("String", "EMBEDDED_ANTHROPIC_KEY", "\"\"")
        }
        release {
            buildConfigField("String", "EMBEDDED_OPENAI_KEY", "\"\"")
            buildConfigField("String", "EMBEDDED_ANTHROPIC_KEY", "\"\"")
            if (wimStorePw != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            // Strip unused resources that R8 has proven unreachable. Pairs
            // with isMinifyEnabled — must be enabled together. Trims another
            // few MB off the release APK on top of code shrinking.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Upload native debug symbols (sherpa-onnx .so) to Crashlytics so
            // native crash stack traces are human-readable instead of bare
            // memory addresses. Build task: uploadCrashlyticsSymbolFileRelease,
            // wired automatically into the assembleRelease lifecycle.
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
            }
        }

        // Self-contained giveaway build. Ships the operator's own provider keys
        // inside BuildConfig, so anyone who installs it gets every cloud layer
        // with no sign-in, no subscription and no quota — billed to him, on
        // purpose. WhisperClient and ReconstructClient already prefer the
        // direct-to-provider path whenever a key is present; blank keys in
        // every other variant are the only thing that has been gating it.
        //
        // Deliberately a separate build type rather than a flag on release.
        // `release` keeps every guard it has — asset stripping,
        // verifyNoProviderKeysInRelease, the CI bundle grep — and CI only ever
        // builds debug and release, so none of that is weakened by this
        // existing. Declared after release so initWith copies the finished
        // signing, minify, shrink and proguard config.
        //
        // Keys are never committed. They are injected at build time:
        //   gradle assembleGiveaway -PWIM_OPENAI_KEY=... -PWIM_ANTHROPIC_KEY=...
        create("giveaway") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            // initWith does not reliably carry a conditional signingConfig
            // across AGP versions. Assign it explicitly: a differently-signed
            // sideload forces an uninstall, which re-arms Android 16's
            // restricted-settings block.
            if (wimStorePw != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField(
                "String",
                "EMBEDDED_OPENAI_KEY",
                "\"$wimOpenAiKey\"",
            )
            buildConfigField(
                "String",
                "EMBEDDED_ANTHROPIC_KEY",
                "\"$wimAnthropicKey\"",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric class graph is heavy; default ~512m OOMs at scale.
        unitTests.all {
            it.maxHeapSize = "4g"
            it.setForkEvery(50)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Jetpack Compose — onboarding is being rewritten in Compose (option 3, 2026-05-16).
    // BOM 2024.12.01 pairs with Kotlin 2.0.21 and AGP 8.7.x. Adding here keeps the
    // existing View/XML code unaffected; only the new onboarding/* package opts in.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // L2: removed lottie-compose — unused; persona uses Compose spring physics.

    debugImplementation("androidx.compose.ui:ui-tooling")

    // OkHttp for Whisper API + reconstruction API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20240303")

    // Coroutines for async API calls
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Firebase Auth + Google Sign-In
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-perf-ktx")
    implementation("com.google.firebase:firebase-config-ktx")
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // Monthly WiM Cloud subscription. The backend verifies and acknowledges
    // every completed purchase before the app grants time-bounded access.
    // Core artifact is Java API and stays compatible with this app's Kotlin
    // 2.0 toolchain; billing-ktx 9.1 is compiled with newer Kotlin metadata.
    implementation("com.android.billingclient:billing:9.1.0")

    // Credential Manager for Google Sign-In
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Sherpa-onnx for Moonshine ASR — flat-file AAR (53.9 MB, ships .so for all 4 ABIs).
    // Released April 24, 2026. Pairs with Moonshine base-en quantized model
    // (encoder_model.ort 31 MB + decoder_model_merged.ort 109 MB + tokens.txt) downloaded
    // at runtime via MoonshineModelDownloader. The Kotlin API surface ships inside
    // the AAR; the previously hand-copied wrappers under com/k2fsa/sherpa/onnx/ were
    // removed (R8 duplicate-class) and now live in .r8-displaced-sherpa-wrappers/.
    implementation(files("libs/sherpa-onnx-1.12.40.aar"))

    // Apache Commons Compress — needed to extract the tar.bz2 model archive at runtime.
    // Java/Android stdlib supports zip but not bzip2; commons-compress is ~600 KB.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Apache Commons Codec — Double Metaphone for phonetic-neighbor correction
    // (PhoneticMatcher post-hoc swaps ASR output against user vocabulary).
    implementation("commons-codec:commons-codec:1.17.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Instrumented (on-device) tests — first androidTest source set (2026-06-09).
    // Runs ProfileManager against REAL SharedPreferences on a device, where the
    // Robolectric shadow can't reproduce apply()/disk-marshal threading.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// A developer may keep direct-call credentials locally for debug work, but a
// release must stop before Android's asset merger can package either file.
val verifyNoProviderKeysInRelease by tasks.registering {
    group = "verification"
    doLast {
        val forbidden = listOf(
            file("src/main/assets/api_key.txt"),
            file("src/main/assets/anthropic_key.txt"),
        ).filter { it.exists() }
        check(forbidden.isEmpty()) {
            "Release build blocked: remove local provider-key assets: " +
                forbidden.joinToString { it.name }
        }
    }
}

val verifyGiveawayInputs by tasks.registering {
    group = "verification"
    doLast {
        check((project.findProperty("WIM_STORE_PASSWORD") as? String).isNullOrBlank().not()) {
            "Giveaway build requires the stable WiM release signing password."
        }
        check((project.findProperty("WIM_OPENAI_KEY") as? String).isNullOrBlank().not()) {
            "Giveaway build requires WIM_OPENAI_KEY."
        }
        check((project.findProperty("WIM_ANTHROPIC_KEY") as? String).isNullOrBlank().not()) {
            "Giveaway build requires WIM_ANTHROPIC_KEY."
        }
    }
}

tasks.configureEach {
    if (name == "mergeReleaseAssets") {
        dependsOn(verifyNoProviderKeysInRelease)
    }
    if (name == "generateGiveawayBuildConfig") {
        dependsOn(verifyGiveawayInputs)
    }
}

// assembleRelease auto-wires uploadCrashlyticsSymbolFileRelease, but bundleRelease
// does not — Play Store ships AABs, so without this the native stack traces from
// sherpa-onnx come back to Crashlytics as bare memory addresses.
afterEvaluate {
    tasks.findByName("bundleRelease")?.finalizedBy("uploadCrashlyticsSymbolFileRelease")
}
