# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Firebase Auth + Firestore
-keep class com.google.firebase.** { *; }
-keep interface com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Google Play Services (Auth, Identity)
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
-keep class com.google.android.libraries.identity.** { *; }

# Credential Manager
-keep class androidx.credentials.** { *; }

# JSON
-keep class org.json.** { *; }

# Keep data classes used in JSON parsing
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Native methods — every JNI bridge (sherpa-onnx, anything else with native).
# Without this R8 strips the Kotlin signature and the C++ side crashes
# at "method not found" the first time it tries to call up.
-keepclasseswithmembernames class * {
    native <methods>;
}

# sherpa-onnx — Whisper-local engine (Moonshine, whisper-tiny). Class names
# referenced from JNI as well as from app code; keep both.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Apache Commons (compress + codec).
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**

# WiM's own Application / Service / Receiver / AccessibilityService classes —
# instantiated by the OS by name from AndroidManifest. AGP usually keeps them
# automatically but explicit beats sorry.
-keep class com.wim.app.WimApp { *; }
-keep class com.wim.app.BubbleService { *; }
-keep class com.wim.app.WimAccessibilityService { *; }
-keep class com.wim.app.WimTileService { *; }
-keep class com.wim.app.HotwordListener { *; }
