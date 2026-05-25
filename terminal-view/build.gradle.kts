// Vendored Termux `terminal-view` (Apache-2.0) — the Android `View` that
// renders a `TerminalEmulator` screen buffer and wires IME / hardware-key /
// gesture input. UC-21 vendors this as a source module. See ../NOTICE.
//
// Deviations from upstream: Gradle Groovy build + maven-publish replaced with
// this Kotlin-DSL build, wired to the repo's version catalog. No source
// changes to the view itself — it attaches to the (JNI-free) `TerminalSession`
// drop-in from `:terminal-emulator`.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get().toInt())
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // `api` so the app sees TerminalEmulator/TerminalSession transitively.
    api(project(":terminal-emulator"))
    implementation(libs.androidx.annotation)
}
