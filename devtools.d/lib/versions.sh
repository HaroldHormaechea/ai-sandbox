# shellcheck shell=bash
# Shared version constants for ai-sandbox devtool capabilities (UC-27).
#
# Sourced by BOTH the capability manifests (to build version-bearing LABELs, so
# the menu text reflects exactly what will be installed — AC#9) AND the
# in-container provisioning helpers (aisandbox-java / aisandbox-android), so the
# label the operator sees and the toolchain that actually lands can never drift.
#
# Every value is overridable via the matching env var, so an operator (or CI)
# can pin a different build without editing a manifest. This file MUST stay free
# of side effects: variable assignments only, no installs, no network, no I/O.

# ── DinD (rootless Docker) ───────────────────────────────────────────────────
# Static rootless-Docker tarball version fetched by aisandbox-dind. Keep in sync
# with the upstream changelog at https://docs.docker.com/engine/security/rootless/.
AISB_DIND_DOCKER_VERSION="${AISB_DIND_DOCKER_VERSION:-27.3.1}"

# ── Java (Temurin JDK) ───────────────────────────────────────────────────────
AISB_JAVA_FLAVOR="${AISB_JAVA_FLAVOR:-Temurin JDK}"
AISB_JAVA_MAJOR="${AISB_JAVA_MAJOR:-21}"
# Exact Temurin build, used by aisandbox-java to construct the Adoptium download
# URL. The '+' is URL-encoded by the helper. Bumping the patch is a normal PR.
AISB_JAVA_BUILD="${AISB_JAVA_BUILD:-21.0.5+11}"

# ── Android SDK ──────────────────────────────────────────────────────────────
# cmdline-tools package revision (the numeric build of the commandlinetools zip).
AISB_ANDROID_CMDLINE_TOOLS_VERSION="${AISB_ANDROID_CMDLINE_TOOLS_VERSION:-11076708}"
AISB_ANDROID_BUILD_TOOLS_VERSION="${AISB_ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
AISB_ANDROID_PLATFORM="${AISB_ANDROID_PLATFORM:-android-36}"
# x86_64 system image (amd64-only — AC#11). Used both for the label and the
# `sdkmanager` install line.
AISB_ANDROID_SYSTEM_IMAGE="${AISB_ANDROID_SYSTEM_IMAGE:-system-images;android-36;google_apis;x86_64}"
