# Proguard / R8 rules for ai-sandbox-android.
#
# Note: isMinifyEnabled = false and isShrinkResources = false on the
# release build today (see android/build.gradle.kts) — these rules are
# declared up-front so that when we flip minification on (post-v0.1)
# the OkHttp + Kotlin reflection + KeyStore code paths survive.

# OkHttp ships its own proguard rules; keep its TLS extensions intact.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlin coroutines flow + structured concurrency rely on reflection.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }

# kotlinx-serialization @Serializable classes — keep their generated
# Companion + serializer.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.aisandbox.android.**$$serializer { *; }
-keepclassmembers class com.aisandbox.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.aisandbox.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose runtime intrinsics — the Compose plugin already emits keep
# rules, but recover anything stripped by aggressive minify.
-keep class androidx.compose.runtime.** { *; }

# BouncyCastle (used only by tests today; included defensively).
-dontwarn org.bouncycastle.**

# Android KeyStore wrappers in androidx.security.crypto.
-keep class androidx.security.crypto.** { *; }
