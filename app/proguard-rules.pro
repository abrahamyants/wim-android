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
