// ai-sandbox — top-level Gradle settings.
//
// The repo's primary surface remains the Bash/PowerShell + Docker
// orchestration kit. The Java management server (UC03) lives under
// `server/` as a Gradle subproject. UC04 adds `android/` — a separate
// Android application module that shares the wrapper + version catalog
// but pulls Android Gradle Plugin + Compose plugin from Google Maven.

rootProject.name = "ai-sandbox"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google {
            // Google publishes the Android Gradle Plugin and AndroidX
            // tooling here. Constrain by group to keep dependency
            // resolution narrow and predictable.
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    // Version catalog lives at `gradle/libs.versions.toml` (default).
}

include(":server")
project(":server").projectDir = file("server")

// UC04 — Android client application module. Shares the wrapper +
// version catalog with :server. Builds independently from any
// docker-compose / shell-script work in the repo root.
include(":android")
project(":android").projectDir = file("android")

// UC-21 — vendored Termux terminal libraries (Apache-2.0), used by the
// Android client to render a real ANSI/VT terminal over the WebSocket
// stream. `:terminal-view` depends on `:terminal-emulator`. See NOTICE.
include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("terminal-emulator")
include(":terminal-view")
project(":terminal-view").projectDir = file("terminal-view")
