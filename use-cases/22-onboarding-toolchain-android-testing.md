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

## Testing Limitations / Validation Status (head-start draft — 2026-05-24)

This use case was **implemented as a head-start draft** on a host that **cannot
validate the behavioural acceptance criteria**: it has **no Docker, no
`/dev/kvm`, and no root** (Alpine/musl, uid 1000). The ledger row is intentionally
kept at **`in-progress`** — do not mark it `done` until the items below are
verified on a capable host. The implementation lives on branch
`feat/uc-22-android-toolchain-impl` (pushed, **not merged**).

### What WAS validated here (static only)
- `bash -n` syntax — **pass** on `setup.sh`, `spawn.sh`, `lib.sh`, `container-bin/aisandbox-emulator`.
- `shellcheck` 0.11.0 (`--severity=warning`) — **clean** on all four scripts (the only hits are pre-existing SC2034s on `lib.sh`'s legacy colour aliases, untouched by this work).
- Version-pin cross-check — the image installs `platforms;android-36` + `build-tools;36.0.0` + `openjdk21-jdk`, matching `.github/workflows/android-ci.yml` and `gradle/libs.versions.toml` (java=21, androidCompileSdk=36) — **AC14 ✓**.
- YAML hygiene — `docker-compose.yml` / `docker-compose.kvm.yml` are tab-free and structurally correct (no `docker compose config` available for a full lint).
- PowerShell (`setup.ps1` / `spawn.ps1` / `lib.ps1`) — **NOT** validated (no `pwsh` available); mirrors the bash logic and must be parsed/run on Windows.

### What could NOT be validated here (needs Docker + KVM + amd64)
| AC | Why it needs a real host |
|----|--------------------------|
| 4 | Confirm the non-Android image is byte-equivalent to today's — needs `docker compose build`. |
| 5 | `java -version` → 21 inside the built image. |
| 6 | **The key open risk:** that `aapt2` / `adb` / the QEMU emulator actually LOAD under `gcompat` on Alpine/musl. If they don't, apply the glibc-base fallback. |
| 7 | SDK components baked + licenses accepted — needs the image build to succeed. |
| 8 | `:android:lint/:test/:assembleDebug/:bundleDebug` running inside the image. |
| 9, 10, 12 | Lazy system-image pull, headless AVD boot, `connectedAndroidTest`, and no-KVM degradation — all need `/dev/kvm`. |
| 11, 13 | `spawn.sh` `--device /dev/kvm` injection + server-spawned parity — need Docker + a real session launch. |

### How to finish validation (on a Docker + KVM + amd64 host)
```bash
# 1. Build the Android-enabled image, and prove the base image is unchanged.
AI_SANDBOX_TOOLCHAIN_ANDROID=1 docker compose build      # Android image
AI_SANDBOX_TOOLCHAIN_ANDROID=0 docker compose build      # base image (AC4)

# 2. The decisive gcompat check — do the SDK binaries load? (AC5/AC6/AC7)
docker run --rm ai-context:latest sh -lc '
  java -version &&
  aapt2 version &&           # <-- gcompat-sufficiency check (AC6); the make-or-break line
  adb version &&
  ls "$ANDROID_HOME"/build-tools/36.0.0/aapt2'

# 3. Build lane, offline, from a session at the repo root (AC8):
./gradlew :android:lint :android:test :android:assembleDebug :android:bundleDebug

# 4. Emulator lane (AC9/AC10/AC12) — spawn.sh auto-passes --device /dev/kvm for
#    Android images when /dev/kvm exists; from inside the session:
aisandbox-emulator doctor
aisandbox-emulator start
./gradlew :android:connectedAndroidTest
```
If step 2's `aapt2 version` fails with an `ld-linux`/loader error under `gcompat`,
apply the **gcompat→glibc fallback** (AC6): rebase the Android image on a glibc
base (Debian/Ubuntu, matching CI's `ubuntu-latest`); the SDK install steps are
otherwise identical.

### Deliberately NOT done in this draft
- **No Java edits.** AC13 (server-spawned sessions) is delivered entirely on the
  shell side: the server already invokes `spawn.sh` (`ScriptExecutorService#composeEnv`
  sets `AI_SANDBOX_COMPOSE_FILE`), and `spawn.sh` now inspects the image label +
  `/dev/kvm` and layers `docker-compose.kvm.yml`. `ScriptExecutorService.java` was
  **left untouched on purpose** — it is `spotlessCheck`/CI-gated and could not be
  compiled or formatted here. Remaining server-side items to confirm on a real
  host: (a) the release / `.deb` bundle ships `docker-compose.kvm.yml` next to
  `docker-compose.yml`, and (b) the server's runtime user (`<secrets-owner-uid>:0`)
  can open `/dev/kvm` (kvm group membership / a device-cgroup rule).
- **arm64 Android** — AC15 surfaces the amd64-only limitation; full arm64 support
  (arm64 system images) is a separate follow-up.
