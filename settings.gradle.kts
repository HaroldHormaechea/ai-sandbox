// ai-sandbox — top-level Gradle settings.
//
// The repo's primary surface remains the Bash/PowerShell + Docker
// orchestration kit. The Java management server (UC03) lives under
// `server/` as a single Gradle subproject so the rest of the repo is
// untouched by Gradle.

rootProject.name = "ai-sandbox"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    // Version catalog lives at `gradle/libs.versions.toml` (default).
}

include(":server")
project(":server").projectDir = file("server")
