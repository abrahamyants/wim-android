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
        applicationId = "com.wim.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.0"

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

    signingConfigs {
        create("release") {
            storeFile = file("../wim-release.jks")
            storePassword = "wimstore2026"
            keyAlias = "wim"
            keyPassword = "wimstore2026"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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

    // Lottie for Mr. DNA persona sequences. JSON assets go in res/raw/ — not yet
    // designed, components currently fall back to Compose spring-physics primitives.
    implementation("com.airbnb.android:lottie-compose:6.6.0")

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

    // Credential Manager for Google Sign-In
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Sherpa-onnx for Moonshine ASR — flat-file AAR (53.9 MB, ships .so for all 4 ABIs).
    // Released April 24, 2026. Pairs with Moonshine base-en quantized model
    // (encoder_model.ort 31 MB + decoder_model_merged.ort 109 MB + tokens.txt) downloaded
    // at runtime via MoonshineModelDownloader. The Kotlin API surface is hand-copied
    // into app/src/main/java/com/k2fsa/sherpa/onnx/ from the sherpa-onnx repo at the
    // matching version — those classes are thin wrappers around the JNI in this AAR.
    implementation(files("libs/sherpa-onnx-1.12.40.aar"))

    // Apache Commons Compress — needed to extract the tar.bz2 model archive at runtime.
    // Java/Android stdlib supports zip but not bzip2; commons-compress is ~600 KB.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Apache Commons Codec — Double Metaphone for phonetic-neighbor correction
    // (PhoneticMatcher post-hoc swaps ASR output against user vocabulary).
    implementation("commons-codec:commons-codec:1.17.1")

    // MediaPipe tasks-genai — on-device LLM inference for local L2/L3
    // reconstruction via Gemma 3 1B IT (text-in text-out). Paired with Vosk
    // for ASR, this makes L2/L3 fully local with no cloud round-trip.
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// assembleRelease auto-wires uploadCrashlyticsSymbolFileRelease, but bundleRelease
// does not — Play Store ships AABs, so without this the native stack traces from
// sherpa-onnx come back to Crashlytics as bare memory addresses.
afterEvaluate {
    tasks.findByName("bundleRelease")?.finalizedBy("uploadCrashlyticsSymbolFileRelease")
}
