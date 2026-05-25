---
name: android-emulator-setup
description: >-
  Bring up a headless Android emulator inside this ai-sandbox (KVM) session and
  run the app's instrumented tests. Use when asked to set up or launch the
  Android emulator, boot an AVD, or run :android:connectedDebugAndroidTest. The
  session image bakes the Android SDK + the emulator's native libs; this skill
  drives the `aisandbox-emulator` helper (lazy install + KVM boot) and documents
  the manual fallback. Linear runbook — follow the steps in order.
---

# Android emulator setup — ai-sandbox (KVM session)

This session runs the **Android (KVM-capable) ai-sandbox image**, so most of the
setup is already done for you:

- The Android SDK is baked at **`/opt/android-sdk`** — `cmdline-tools/latest`,
  `platform-tools`, `platforms;android-36`, `build-tools;36.0.0` — plus the
  emulator's native runtime libs (X11 / GL / pulse / nss / …). JDK 21 is at
  `/usr/lib/jvm/temurin-21-jdk-amd64`.
- The **heavy, lazy bits** are NOT baked: the `emulator` package, the x86_64
  system image, and the AVD. They are provisioned on first use into the
  persisted **`/workspace/environment-utilities/android`** cache, so they
  download once (~1.5 GB) and survive container restart/rebuild.
- `/dev/kvm` is passed into the session by `spawn.sh` **only when the host
  exposes it**. With KVM the AVD boots in seconds; without it the emulator falls
  back to software emulation (unusably slow, often won't finish booting).

> The build / JVM-test lane (`:android:lint :test :assembleDebug :bundleDebug`)
> needs none of this — it runs offline against the baked SDK, with or without
> KVM. You only need an emulator for **instrumented** (`connected`) tests.

## Step 1 — Fast path: the `aisandbox-emulator` helper (recommended)

The image ships `aisandbox-emulator` on `PATH`. It performs the entire setup —
lazy-install the emulator + system image, build a unified SDK root, create the
AVD, and boot it headless on KVM:

```bash
aisandbox-emulator doctor   # check baked SDK + JDK + adb + /dev/kvm; names anything missing
aisandbox-emulator start    # provision (first run) + boot headless; waits for sys.boot_completed
```

`start` is idempotent: it reuses a running emulator and the cached image/AVD. On
success `adb` shows `emulator-5554` as `device` and `sys.boot_completed=1`.

Without `/dev/kvm` it refuses to start unless you pass `--no-accel` (software
emulation — expect it to be very slow). Stop it when done:

```bash
aisandbox-emulator stop
```

## Step 2 — Run the instrumented tests

With the emulator booted, from the Android project's repo root:

```bash
./gradlew :android:connectedDebugAndroidTest
```

This builds the debug + androidTest APKs and runs the Compose UI / instrumented
tests on the booted AVD. To target a single class, use the runtime arg form:

```bash
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.TestClass>
```

## Step 3 — Manual fallback (only if the helper is unavailable)

Set the image's real paths, then provision + boot by hand:

```bash
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDM="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
SYS_IMAGE="system-images;android-36;default;x86_64"

# 1. Install the lazy bits (first time only; needs network):
yes | "$SDKM" --licenses >/dev/null
"$SDKM" "emulator" "$SYS_IMAGE"

# 2. Create the AVD (once):
"$AVDM" list avd 2>/dev/null | grep -q 'Name: aisandbox' || \
  echo no | "$AVDM" create avd -n aisandbox -k "$SYS_IMAGE" --device pixel_6

# 3. Boot headless with KVM and wait for full boot:
nohup "$EMU" -avd aisandbox -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -accel auto > /tmp/emulator.log 2>&1 &
"$ADB" wait-for-device
until [ "$("$ADB" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
"$ADB" -s emulator-5554 shell input keyevent 82   # dismiss the lock screen
```

> **System-image variant.** This session standardises on `…;default;x86_64`
> (the same variant `aisandbox-emulator` installs). `google_apis` also works but
> is larger and is not needed for Compose UI / instrumented tests.

## Troubleshooting

- **`doctor` reports `/dev/kvm` absent / inaccessible** — the host has no KVM or
  the session was not spawned with it. Re-spawn on a KVM host (`spawn.sh`
  auto-passes `--device /dev/kvm` plus the kvm group for Android images). The
  build / JVM-test lane is unaffected.
- **Emulator won't boot or is extremely slow** — almost always missing KVM;
  confirm with `aisandbox-emulator doctor`. Software emulation (`--no-accel`)
  frequently never reaches `boot_completed`.
- **`avdmanager` fails with `Valid system image paths are: null`** — sdkmanager
  and avdmanager must run from one SDK root that holds BOTH the tools and the
  installed system image. The helper handles this for you (it materialises a
  unified root under the cache); the manual steps above keep everything under
  `/opt/android-sdk`.
- **First `start` is slow** — that's the one-time emulator + system-image
  download into `/workspace/environment-utilities`. Later starts reuse it with
  no re-download.
