// Vendored Termux `terminal-emulator` (Apache-2.0) — the pure-Java ANSI/VT
// terminal state machine. UC-21 vendors this as a source module so the
// Android client can render real TUIs over the WebSocket stream.
//
// Deviations from upstream (see ../NOTICE):
//   * The JNI local-process module (`JNI.java`, `src/main/jni/**`) is NOT
//     vendored — the ai-sandbox client has no local shell. `TerminalSession`
//     is rewritten to be driven by external (WebSocket) bytes.
//   * Gradle Groovy build + maven-publish replaced with this Kotlin-DSL build,
//     wired to the repo's version catalog.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.terminal"
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
        // Vendored upstream code; do not fail the build on its lint findings.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
