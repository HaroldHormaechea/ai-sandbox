# UC-22 — Next steps (finish on a Docker + KVM + amd64 host)

This branch (`feat/uc-22-android-toolchain-impl`) holds a **head-start
implementation draft** of UC-22 that was authored on a host with **no
Docker/KVM/root**, so its behavioural acceptance criteria are **unverified**
(static checks only: `bash -n` + `shellcheck` clean, version pins + YAML
checked). See `use-cases/22-onboarding-toolchain-android-testing.md` →
"Testing Limitations / Validation Status" for the full status.

**How to use this file:** open a **project-builder root session** on a
workstation that has **Docker + `/dev/kvm` + amd64**, and paste the prompt below
verbatim. It drives the dev-team to validate the draft for real, fix what
breaks (most likely the `gcompat`→glibc-base fallback), and ship it.

> Replace `<TARGET_DIR>` with where you want the clone (e.g. `/workspace/ai-sandbox`).
> Assumes that session has SSH access to the repo and the `develop` skill available.

---

```text
We are continuing ai-sandbox use case UC-22 ("Onboarding toolchain selection
with full Android build/test/emulator image support"). A head-start
implementation already exists but was authored on a host with NO Docker/KVM, so
its behavioural acceptance criteria are UNVERIFIED. This workstation HAS Docker +
/dev/kvm + amd64, so your job is to validate it for real, fix whatever fails,
and ship it. Run this from the ROOT session (the develop/dev-team flow cannot be
nested).

REPO + STATE
- Repo: git@github.com:HaroldHormaechea/ai-sandbox.git
- main contains the UC-22 doc (merged as PR #28), USE_CASES.md row 22 = "in-progress".
- The implementation draft is on branch `feat/uc-22-android-toolchain-impl`
  (based on main, NOT merged, no PR open).

SETUP
1. Confirm this host is capable, else stop and tell me:
     docker version            # daemon reachable
     ls -l /dev/kvm            # exists, readable+writable
     uname -m                  # x86_64
2. Clone the repo to a TARGET_DIR (a sibling of this session dir, NOT inside it),
   and check out the draft branch:
     git clone git@github.com:HaroldHormaechea/ai-sandbox.git <TARGET_DIR>
     git -C <TARGET_DIR> checkout feat/uc-22-android-toolchain-impl
3. Read <TARGET_DIR>/use-cases/22-onboarding-toolchain-android-testing.md IN FULL
   — especially the "Acceptance Criteria" and the "Testing Limitations /
   Validation Status" section, which lists exactly what was static-checked vs.
   what still needs running here, with the commands.

WHAT THE DRAFT ALREADY DOES (static-checked only: bash -n + shellcheck clean,
version pins match android-ci.yml/libs.versions.toml)
- setup.sh/setup.ps1 Step 3: toolchain prompt → persists ./.ai-sandbox-toolchains
- SandboxDockerfile: ARG ANDROID_TESTING → gcompat+libc6-compat+JDK21+Android SDK
  (platform-tools, platforms;android-36, build-tools;36.0.0); image label
  com.ai-sandbox.toolchain.android
- docker-compose.yml build arg + docker-compose.kvm.yml (/dev/kvm override)
- lib.sh/lib.ps1, spawn.sh/spawn.ps1: layer the KVM override for Android images
  when /dev/kvm exists (also covers server-spawned sessions)
- container-bin/aisandbox-emulator: in-container lazy system-image/AVD + headless boot
- NO server/ Java was edited (deliberately).

THE WORK TO DO (use the develop dev-team; let QA actually run these gates)
1. Build both image variants and prove the base image is unaffected (AC4):
     AI_SANDBOX_TOOLCHAIN_ANDROID=1 docker compose build
     AI_SANDBOX_TOOLCHAIN_ANDROID=0 docker compose build
2. THE make-or-break check (AC5/6/7) — do the glibc-linked SDK binaries load
   under gcompat on Alpine/musl?
     docker run --rm ai-context:latest sh -lc 'java -version && aapt2 version && adb version && ls "$ANDROID_HOME"/build-tools/36.0.0/aapt2'
   If aapt2/adb fail with an ld-linux/loader error, apply the gcompat->glibc-base
   FALLBACK the UC documents (AC6): rebase the Android toolchain layers onto a
   glibc base (Debian/Ubuntu, matching CI's ubuntu-latest); keep the SDK install
   steps identical. Record which path you took in the UC file + README.
   Also fix any wrong package name / cmdline-tools URL / sdkmanager path you hit.
3. Build lane offline from a session (AC8):
     ./gradlew :android:lint :android:test :android:assembleDebug :android:bundleDebug
4. Emulator lane (AC9/10/12) — spawn an Android session (spawn.sh auto-passes
   --device /dev/kvm), then inside it:
     aisandbox-emulator doctor && aisandbox-emulator start && ./gradlew :android:connectedAndroidTest
   Confirm graceful behaviour when /dev/kvm is absent (AC12).
5. Server-spawned path (AC13): confirm a management-server spawn gets the KVM
   override, that docker-compose.kvm.yml is shipped alongside docker-compose.yml
   in the release/.deb bundle, and that the server runtime user (<uid>:0) can
   open /dev/kvm (kvm group / device-cgroup). Make the minimal change needed; if
   you touch server/ Java, run :server:spotlessCheck + :server:test.
6. If pwsh is available, validate setup.ps1/spawn.ps1/lib.ps1; else note it.
7. Update the UC file: move validated items out of "Testing Limitations", note
   the base-image decision, and fix docs/README if reality differed.

SHIP CHAIN (do not skip)
- Open a PR from feat/uc-22-android-toolchain-impl -> main. android-ci will run
  (the diff touches android/**). Get it GREEN.
- Only after the build + emulator gates genuinely pass: merge to main, then set
  USE_CASES.md row 22 Status -> "done" (Updated -> today). If something is
  infeasible, set it "blocked" and explain — do NOT mark done on unverified ACs.

CONSTRAINTS
- Don't mark UC-22 done until ACs are empirically verified here.
- PROJECT_BRIEF.md must not change. Keep version pins matching
  gradle/libs.versions.toml (JDK 21, build-tools 36.0.0, android-36).
```
