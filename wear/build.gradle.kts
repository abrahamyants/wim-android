plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wim.app.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wim.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
}

dependencies {
    // Wear OS baseline
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.wear:wear:1.3.0")

    // Coroutines for background PCM streaming
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Wearable Data Layer (ChannelClient for audio streaming)
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Complications
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    // Tests
    testImplementation("junit:junit:4.13.2")
}
