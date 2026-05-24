# Use Case 22: Onboarding toolchain selection with full Android build/test/emulator image support

## Summary
The setup wizard (`setup.sh` / `setup.ps1`) gains a Step-3 prompt to choose which **toolchains** to bake into the sandbox image (`ai-context:latest`, from `SandboxDockerfile`). The selection is **persisted** (gitignored, survives rebuild + re-spawn; mechanism the dev-team's choice) and drives **conditional provisioning** via docker build args, extending the existing `TARGETARCH`/`gcompat` pattern already in the Dockerfile. The menu is data-driven; this use case fully specifies **"Android testing"** for **amd64** hosts. When selected, the image gains: a **glibc-capable userland** via `gcompat` + `libc6-compat` on Alpine, **auto-falling back to a glibc base** (Debian/Ubuntu, matching CI's `ubuntu-latest`) for the Android image if a required SDK binary can't load under `gcompat` (the chosen path is documented); **JDK 21**; and the **Android SDK build components baked at build** (`cmdline-tools;latest`, `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`, licenses accepted, `ANDROID_HOME` exported) so the lint/test/assemble/bundle lane works **offline**. The heavy **x86_64 system image + AVD are NOT baked** — they're provisioned **lazily on first emulator use** into **`/workspace/environment-utilities/`** (persisted via the existing workspace bind mount), accepting a one-time network need. The emulator is driven by an **in-container helper command the in-sandbox Claude Code session invokes** to start a headless emulator (`-no-window -gpu swiftshader_indirect`), wait for `sys.boot_completed`, and stop it — enabling `:android:connectedAndroidTest`. `spawn.sh` / `spawn.ps1` inject `--device /dev/kvm` when Android is enabled and the host has KVM; without KVM the build + JVM-test lane still works and the emulator degrades gracefully with a clear message. **Management-server-spawned sessions (UC03/UC05) are also in scope** — they honor the Android-enabled image, inject `/dev/kvm` when present, and resolve the same AVD cache. **arm64** hosts are a documented follow-up (x86_64 system images won't run there); non-Android operators get today's lean Alpine image unchanged.

## Acceptance Criteria
1. `setup.sh` and `setup.ps1` present a **toolchain-selection prompt** at/adjacent to Step 3 ("Container image"), listing available toolchains with **"Android testing"** selectable; multi-select, with a clear default of "none beyond the base."
2. The selection is **persisted** to a gitignored location and **survives rebuild + re-spawn**: re-running setup pre-selects prior choices and `docker compose build` / spawn honor them without re-prompting unless changed. The exact mechanism is the dev-team's choice, consistent with existing repo conventions.
3. Onboarding is **idempotent**: rebuilding with the same selection yields a functionally equivalent image; **deselecting** Android testing and rebuilding produces an image without the Android toolchain (no orphaned SDK/emulator layers).
4. When Android testing is **not** selected, `ai-context:latest` is functionally equivalent to today's Alpine image — no JDK/SDK, no size or feature regression for non-Android operators.
5. When selected (amd64), the image has a working **JDK 21** (`java -version` reports 21) usable by the Gradle wrapper.
6. The image's Android SDK native binaries execute (`aapt2 version`, `adb version`, and the emulator binary run without `ld-linux`/loader errors) — **primarily via `gcompat` + `libc6-compat` on the Alpine base; if a required binary cannot load under `gcompat`, the Android image uses a glibc base instead**, and the chosen path is recorded/documented.
7. The **Android SDK build components are baked at build** at an exported `ANDROID_HOME` with exactly `cmdline-tools;latest`, `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`, all licenses pre-accepted (no interactive license prompt during build); the lint/test/assemble/bundle lane succeeds **with no network** for these components.
8. Inside an Android-enabled sandbox, from the repo root, each of these completes successfully: `./gradlew :android:lint`, `:android:test`, `:android:assembleDebug`, `:android:bundleDebug` (the exact `android-ci.yml` lane), producing the lint XML report, the debug APK, and the debug AAB.
9. The **x86_64 system image + AVD are NOT in the built image**; on the first emulator start they are downloaded/created under **`/workspace/environment-utilities/`** (persisted via the existing workspace bind mount) and reused without re-download on later starts. The first **emulator** use requires network; the build lane (AC5–8) does not.
10. An **in-container helper command** (on `PATH`, documented/discoverable so the in-sandbox Claude Code session can find and invoke it) starts a headless emulator (`-no-window -gpu swiftshader_indirect`), waits for `sys.boot_completed`, and can stop it; once booted, `./gradlew :android:connectedAndroidTest` runs green against the emulator via `adb`.
11. `spawn.sh` / `spawn.ps1` inject `--device /dev/kvm` at run time **only when** Android testing is enabled **and** `/dev/kvm` exists on the host; non-Android and no-KVM runs are unaffected.
12. When `/dev/kvm` is **absent**: AC5–8 still pass; the emulator helper reports clearly that acceleration is unavailable (instrumented tests slow/skipped) and does **not** fail the sandbox.
13. **Management-server-spawned sessions** (UC03/UC05, via `ScriptExecutorService#composeEnv`) also: honor the Android selection baked into the shared image, inject `--device /dev/kvm` when the host has it, and resolve the AVD cache under the session's `/workspace/environment-utilities`. Their build + emulator behavior matches developer-mode sessions.
14. All Android toolchain versions match the contract in `gradle/libs.versions.toml` + `android-ci.yml` (JDK 21, build-tools 36.0.0, platforms android-36, compileSdk/targetSdk 36, minSdk 29) — no drift between the image and the version catalog.
15. On an **arm64** host, onboarding clearly reports Android testing as **not yet available (amd64-only)** and does not offer a broken option or fail the wizard — the limitation is surfaced, not hit at build time.
16. The menu is **data-driven** so future toolchains (and arm64 Android) can be added without reworking the prompt/persistence/build-arg plumbing.
17. **Docs** (README + the `android/README.md` foot-gun note + a new host-KVM prerequisite section) describe: the toolchain prompt, the in-container emulator helper, the `/dev/kvm` requirement and how to verify it, the first-use network need for the lazy system image, the no-KVM degradation behavior, the `gcompat`→glibc fallback, the amd64-only limitation, and the per-session-workspace cache caveat.

## Potential Pitfalls & Open Questions
- **Edge case (accepted)** — Per-session isolated workspaces (`AI_SANDBOX_WORKSPACE_HOST_PATH` override, e.g. `./workspace-3` or server-assigned per-session paths) don't share `/workspace/environment-utilities`, so each such session re-downloads the system image on its first emulator use. Accepted consequence of caching under the workspace mount.
- **Edge case (open)** — Server-spawned sessions run as an arbitrary `uid:0` under systemd (UC17). Opening `/dev/kvm` requires that runtime user to have device access (kvm group membership / a device-cgroup rule); the dev-team must confirm the server-managed compose path grants it.
- **Risk (accepted)** — The `gcompat`→glibc fallback means the Android image base can differ by host/build; mitigated by documenting which path was taken (AC6/AC17), but it does fork the image surface.

## Original Description
> Create a use case for the project ai-sandbox that makes it so the server onboarding set-up prompts for which technologies you want to support in your images. Oneo f them should be Android testing, and prepare the image to support all you said

(Context: the user first noted "That project contains also info about how to test android apps." The phrase "all you said" refers to the Android build/test/emulator requirements analysis produced earlier in the session while assessing the working host — glibc userland for the SDK's native binaries, JDK 21, the SDK packages from `android-ci.yml`, an x86_64 system image + AVD, headless launch, and `/dev/kvm` passthrough — now captured in the Summary and Acceptance Criteria above.)

> Note: originally drafted as UC-18, renumbered to UC-22 because `main` advanced and `use-cases/18-android-sessions-cards-untappable.md` had already shipped under number 18.

## Clarifications
- Q: Which base-image strategy should the Android-enabled sandbox use for the glibc-linked SDK binaries (aapt2/adb/emulator)?
  A: gcompat on Alpine — with a documented auto-fallback to a glibc base (Debian/Ubuntu) if a required binary cannot load under gcompat (AC6).
- Q: How complete must the Android toolchain be inside the sandbox?
  A: Build + JVM tests + best-effort emulator — full lint/test/assemble/bundle lane plus a headless emulator that uses KVM when present and degrades gracefully when `/dev/kvm` is absent (AC9–12).
- Q: Which host architectures must Android testing support?
  A: amd64 now (x86_64 system image); arm64 (Apple Silicon) is a documented follow-up / known limitation (AC15).
- Q: Where should the operator's toolchain selection be persisted?
  A: The dev-team's choice, consistent with repo conventions — must survive rebuild + re-spawn and stay gitignored (AC2).
- Q: If gcompat ultimately can't make a required SDK binary load on Alpine, what should the dev-team do?
  A: Auto-fallback to a glibc base, and document the limitation (AC6).
- Q: How should `/dev/kvm` passthrough be wired so it activates only when Android testing is enabled AND the host has KVM?
  A: `spawn.sh` / `spawn.ps1` detect `/dev/kvm` + the Android flag and inject `--device /dev/kvm` at run time (AC11).
- Q: How should the headless emulator get booted for `:android:connectedAndroidTest` inside the sandbox?
  A: It should be possible to spawn it from within the container by the LLM using it — the sandbox containers are used by Claude Code sessions, so an in-container helper command the in-sandbox agent can invoke (AC10).
- Q: When should the ~1.5 GB system image + AVD be provisioned?
  A: Lazily on first use (kept out of the Docker image; downloaded on demand into a persisted location) (AC9).
- Q: Is an offline/air-gapped emulator bootstrap a requirement?
  A: No — network on first emulator use is fine; the build + JVM-test lane stays fully offline (AC9).
- Q: How should the persisted system-image/AVD cache be mounted so it survives across sessions and rebuilds?
  A: Reuse `/workspace`, creating a new subfolder `/workspace/environment-utilities` (persisted via the existing workspace bind mount) (AC9, AC13).
- Q: Should the management-server-spawned sessions (UC03/UC05) also get the Android toolchain + `/dev/kvm` + AVD volume?
  A: Include server-spawned sessions — extend `ScriptExecutorService#composeEnv` so they honor the Android image, inject `/dev/kvm`, and resolve the AVD cache (AC13).

## Testing Limitations / Validation Status (validated on a Docker + KVM + amd64 host — 2026-05-25)

The original head-start draft (2026-05-24) was authored on a host with **no
Docker, no `/dev/kvm`, and no root**, so its behavioural acceptance criteria were
unverified (static checks only). This section has since been **reconciled with a
full validation run** on a capable host — **x86_64, Docker 29.5.0, `/dev/kvm`
present (group `kvm` gid 991), user in `docker` + `kvm`** — where every
behavioural AC below was exercised for real.

**The gcompat→glibc fallback was TAKEN (not hypothetical).** Empirically: `java`,
`aapt2`, and `adb` *do* load under Alpine's `gcompat`, but the emulator's QEMU
binary does **not** (it needs glibc's `posix_fallocate64`, which `gcompat` does
not export). So the Android image runs on a **glibc (Debian `node:20-bookworm-slim`)
base** per AC6; the lean (non-Android) image stays on Alpine.

### Validated on this host (empirical)
- **AC4** — lean image (`ANDROID_TESTING=0`) is label `android=0`, ~213 MB, has no
  `java` / `/opt/android-sdk`, ships all base tools; functionally equivalent to
  the pre-UC22 Alpine image.
- **AC5** — `java -version` → OpenJDK **21.0.11 LTS** inside the Android image.
- **AC6** — `aapt2 version` (2.20) and `adb version` (1.0.41) load with no loader
  errors on the glibc base (also on the login-shell PATH via the profile.d
  snippet).
- **AC7** — SDK baked at `$ANDROID_HOME` (`cmdline-tools/latest`, `platform-tools`,
  `platforms;android-36`, `build-tools;36.0.0`); the build lane uses them with no
  SDK download.
- **AC8** — inside the image, `:android:lint :test :assembleDebug :bundleDebug` →
  **BUILD SUCCESSFUL**, producing the debug APK + AAB + lint XML report (Gradle
  resolves AGP/Compose/Maven over the network; only the SDK side is offline).
- **AC9** — the x86_64 system image + emulator + AVD are NOT baked; first
  `aisandbox-emulator start` provisions them lazily into
  `/workspace/environment-utilities`; a second `start` re-uses them with **no
  re-download**.
- **AC10** — the in-container `aisandbox-emulator` helper boots a headless AVD
  (`-no-window -gpu swiftshader_indirect`) on KVM; `adb` sees `emulator-5554`;
  `:android:connectedAndroidTest` runs against it (**16/17 instrumented tests
  pass**). See the AC10 caveat under "Not validated here" for the 1 remaining test.
- **AC11** — `spawn.sh` injects `--device /dev/kvm` **and** `group_add` of the host
  kvm gid (991), only when the image is Android **and** `/dev/kvm` exists; the
  in-container user (uid 1000, supplementary group 991) can read+write `/dev/kvm`.
- **AC12** — without `/dev/kvm`, `aisandbox-emulator doctor` reports it absent and
  `start` prints a clear warning (names `--no-accel`, notes the build/JVM lane is
  unaffected) then refuses (exit 1) without crashing the session.
- **AC13** — a management-server-style spawn (`spawn.sh` with
  `AI_SANDBOX_COMPOSE_FILE` + `AI_SANDBOX_HOST_STATE_ROOT` +
  `AI_SANDBOX_RUN_AS_USER=<uid>:0`) layers `docker-compose.kvm.yml` (gid 991), and
  an arbitrary `<uid>:0` process with that supplementary group reads+writes
  `/dev/kvm`. The release **zip** and **.deb** ship `docker-compose.kvm.yml` (0644)
  and `container-bin/aisandbox-emulator` (0755) — verified green by
  `ReleaseBundleTest` (8) + `DebPackageTest` (5). **Delivered shell-only — no
  `server/` Java production edit was needed.**
- **AC14** — the built image's `java -version`, `build-tools;36.0.0`, and
  `platforms;android-36` match `gradle/libs.versions.toml` + `android-ci.yml`.

### Regressions found and fixed during this validation run
- **claude uid 1000 (`SandboxDockerfile`).** The `node:20-bookworm-slim` base
  occupies uid/gid 1000 with its `node` user, so `useradd claude` landed at uid
  1001 — and a fresh dev-mode session could not write its host-owned bind mounts
  (`/workspace`, `~/.claude`), so the entrypoint died on `Permission denied`.
  Fixed by freeing uid/gid 1000 (the unused `node` user is removed) and pinning
  `claude` to 1000, matching the Alpine image.
- **emulator AVD creation (`container-bin/aisandbox-emulator`).** The "unified SDK
  root" symlinked `cmdline-tools` back to the baked `/opt/android-sdk`, but
  `avdmanager` canonicalises its launcher path to find the SDK root → resolved to
  `/opt/android-sdk` (no system-images) → `avdmanager create avd` failed with
  "Valid system image paths are: null". Fixed by **copying** `cmdline-tools` into
  `$SDK_ROOT` (a real dir, persisted; an older symlinked cache is migrated in
  place) and running `sdkmanager`/`avdmanager` from there.

### Static / structural only (no behavioural run needed)
- **AC1, AC2, AC3, AC16** — the Step-3 toolchain prompt, `.ai-sandbox-toolchains`
  persistence, and the data-driven menu live in `setup.sh` + `lib.sh`. AC3's
  "deselect → no Android toolchain / no orphaned layers" is also empirically
  confirmed (building `ANDROID_TESTING=0` over the Android image yields the lean,
  SDK-free image).
- **AC15** — on this amd64 host the limitation is not hit; `setup.sh` surfaces
  "amd64-only" on non-amd64 hosts and the Dockerfile fails loud if
  `ANDROID_TESTING=1` reaches a non-amd64 build. arm64 system-image support is a
  documented follow-up.
- **AC17** — README (toolchain prompt, emulator helper, `/dev/kvm` prereq + how to
  verify, first-use network need, no-KVM degradation, gcompat→glibc fallback,
  amd64-only, and the per-session-workspace cache caveat) and `android/README.md`
  foot-gun note describe the feature.

### Not validated here
- **PowerShell mirrors** (`setup.ps1` / `spawn.ps1` / `lib.ps1` / `clean.ps1`) —
  **NOT** validated: no `pwsh` on this host. They mirror the bash logic and must be
  parsed/run on Windows before relying on them.
- **AC10 — the E2E enrollment probe.** `connectedAndroidTest` is 16/17 because
  `android/src/androidTest/.../net/E2eEnrollmentProbeTest.kt` hard-throws
  `IllegalStateException: missing instrumentation arg: -e qrPayload` when run
  without a QR invite. It is a deliberately-targeted probe for the server-connected
  flow (the `android-testing` skill supplies `-e qrPayload`); in the bare lane it
  should self-skip via a JUnit assumption rather than fail. That is a test-side
  change (QA scope, `android/src/androidTest/**`) — the in-container emulator
  toolchain that AC10 gates is validated.
