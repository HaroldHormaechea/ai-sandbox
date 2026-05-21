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

# BouncyCastle — production dependency since UC13 (PKCS#12 import).
# `bcprov-jdk18on` is registered at process start as a JCE provider
# under the distinct name `BC-ai-sandbox-client` (see
# identity/BouncyCastleClientProvider.kt) and is consumed via
# `KeyStore.getInstance("PKCS12", "BC-ai-sandbox-client")` in
# identity/KeyStoreIdentityManager.kt. The provider's algorithm
# registrations are looked up reflectively via the JCA's
# `Provider.Service` machinery, so R8 / ProGuard must keep both the
# top-level provider class and the implementation tree intact for any
# future minified release build.
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
# Defensive — covers any reflective lookup that walks `Provider.Service`
# implementations under the BC tree (member access, not class signature).
-keepclassmembers class org.bouncycastle.** implements java.security.Provider$Service$* { *; }

# Android KeyStore wrappers in androidx.security.crypto.
-keep class androidx.security.crypto.** { *; }
