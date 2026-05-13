plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 3
        versionName = "0.2.0"
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
