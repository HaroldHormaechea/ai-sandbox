// ai-sandbox/android — Android client application module (UC04).
//
// Kotlin + Jetpack Compose, Material 3 Expressive baseline, minSdk = 29.
// Two executable artifact streams:
//
//   * debug    — debug-signed APK, used by CI on every PR (android-ci.yml).
//   * release  — signed APK + AAB, emitted on `android-vX.Y.Z` tags by
//                android-release.yml. Signing config is fed by env vars
//                (KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD)
//                in CI; locally falls back to `~/.gradle/keystore.jks` if
//                present, otherwise the debug keystore.
//
// Distribution is sideload-only (no Play). Per UC04 AC29 there are zero
// telemetry / analytics / crash-reporter SDKs. The dependency block
// below carries only what the design + ACs require.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9.0+ ships built-in Kotlin support — the standalone
    // `org.jetbrains.kotlin.android` plugin is no longer required (and
    // applying it fails with "no longer required for Kotlin support").
    // See https://kotl.in/gradle/agp-built-in-kotlin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aisandbox.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.aisandbox.android"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = (project.findProperty("ai_sandbox_android_version_code") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("ai_sandbox_android_version_name") as String?) ?: "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // No Vector Drawables compat shim — minSdk 29 renders them natively.
        vectorDrawables { useSupportLibrary = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get().toInt())
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
        }
        named("test") {
            java.srcDirs("src/test/kotlin")
        }
        named("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
        }
    }

    // Signing configs are populated lazily in `buildTypes` below so that
    // `:android:tasks` works on a machine that has no keystore.
    val ciKeystoreFile = System.getenv("KEYSTORE_FILE")
        ?.takeIf { it.isNotBlank() }
        ?.let { rootProject.file(it) }
    val localKeystoreFile = File(System.getProperty("user.home"), ".gradle/keystore.jks")
    val releaseKeystore = ciKeystoreFile?.takeIf { it.exists() }
        ?: localKeystoreFile.takeIf { it.exists() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "ai-sandbox"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        named("debug") {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        named("release") {
            isMinifyEnabled = false
            // R8 + Compose stripping is a future optimisation; the AAB
            // is small enough today and shrinking introduces a class of
            // bugs (reflection on KeyStore, OkHttp interceptors) we'd
            // rather not chase during the v0.1 sideload window.
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Warnings-as-errors keeps the bar tight; CI uploads lint XML.
        warningsAsErrors = false
        abortOnError = false
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml").takeIf { it.exists() }
    }

    // QA — unit-test options. `returnDefaultValues = true` makes
    // unmocked Android API calls (e.g. android.util.Log.i) return no-op
    // defaults instead of throwing "Method ... not mocked." This lets
    // pure-JVM unit tests under src/test/** exercise classes that
    // sprinkle Log calls (StreamClient et al.) without dragging in
    // Robolectric for those tests.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// QA — JUnit 5 (Jupiter) platform binding for the :android unit tests.
// The server module uses spring-boot-starter-test which auto-configures
// useJUnitPlatform(); the Android module has no equivalent so it must
// be wired explicitly. Without this, JUnit 5 @Test methods are silently
// skipped.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    // ── Compose BOM (single pin transitively covers all Compose libs) ──
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // ── Core AndroidX ─────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.window)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // ── Compose UI ────────────────────────────────────────────────────
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.animation)

    // Adaptive layout (tablet split-pane at ≥600 dp — UC04 AC2).
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.adaptive.nav.suite)

    // ── Navigation ────────────────────────────────────────────────────
    implementation(libs.androidx.navigation.compose)

    // ── Camera + QR decode (UC04-1 onboarding) ────────────────────────
    // ZXing chosen over ML Kit per AC29 — ML Kit pulls play-services-mlkit-*
    // which depend on GMS. ZXing is a pure-Java BarcodeReader with zero
    // GMS deps, perfect for a sideload-only distribution.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // ── Networking (mTLS REST + WebSocket) ────────────────────────────
    // OkHttp ships a CertificatePinner (AC7) and KeyManager hook
    // (Android KeyStore → TLS client cert, AC5). Single client serves
    // both REST and WebSocket per the proposal.
    implementation(libs.okhttp)
    implementation(libs.okhttp.tls)

    // ── Kotlin coroutines + JSON ──────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ── Crypto (PKCS#12 import) — UC13 ────────────────────────────────
    // Android's stock "BC" provider (the stripped-down one bundled with
    // the platform) does not register a SecretKeyFactory under the bare
    // PBKDF2 OID 1.2.840.113549.1.5.12, which the JDK 21 default
    // `KeyStore.getInstance("PKCS12")` emission (PBES2 / PBKDF2-HMAC-SHA256
    // + AES-256) requires during private-key unwrap. We ship the real
    // BouncyCastle alongside Android's stock provider, register it under
    // a distinct project-specific name (`BC-ai-sandbox-client`), and
    // route ONLY the PKCS#12 unwrap through it. TLS continues through
    // Conscrypt — see identity/BouncyCastleClientProvider.kt for the
    // full rationale, including why we use `Security.addProvider` and
    // not `insertProviderAt`. Version pinned via the catalog
    // (`bouncycastle = "1.79"`) — no range, no `latest.release`.
    implementation(libs.bouncycastle.prov)

    // ── Compose tooling (debug-only) ──────────────────────────────────
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // ── Unit + instrumented tests (QA scope; deps declared here so the
    //    catalog is the SSoT, source files live under src/test + androidTest
    //    and are written by QA, not the developer). ─────────────────────
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // JUnit Platform launcher is required on the test runtime classpath
    // when useJUnitPlatform() is used; otherwise Gradle Test Executor
    // fails to start with "Failed to load JUnit Platform".
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Global per-test timeout so a hung test fails the build instead
    // of stalling android-ci until the GH Actions job timeout.
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation(libs.robolectric)
    testImplementation(libs.assertj.core)
    // kotlin-reflect is required by tests that introspect sealed-class
    // permittedSubclasses (TerminalBellDetectionTest.HapticEvent_pin).
    testImplementation(kotlin("reflect"))
    // QA-only — MockWebServer drives EnrollmentClient + StreamClient unit
    // tests against real WS / HTTP responses without spinning the server.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // Mockito inline-mock-maker is the default in 5.x; lets us mock the
    // final Kotlin class KeyStoreIdentityManager whose internals require
    // a real AndroidKeyStore. Used only by net/* unit tests.
    testImplementation(libs.mockito.core)
    // UC10 § AC8 — Robolectric-driven Compose unit tests for
    // ServerIdentityChangedScreen's three variants. The catalog aliases
    // are also wired into androidTestImplementation below; the
    // testImplementation edges here let the JVM unit-test classpath
    // resolve `androidx.compose.ui.test.junit4.createComposeRule` and
    // friends.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
