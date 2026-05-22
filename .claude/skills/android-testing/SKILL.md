---
name: android-testing
description: >-
  Run Android UI and end-to-end enrollment tests for the ai-sandbox Android
  client on this host's headless emulator. Use when asked to test the Android
  app, run instrumented Compose tests, verify enrollment, or connect the
  emulator (AVD) to a local ai-sandbox management server. Covers the full path
  from a STOPPED AVD and STOPPED server to a verified on-device enrollment, then
  running the instrumented tests. Linear runbook — follow the phases in order.
---

# Android testing — ai-sandbox

End-to-end runbook to test the ai-sandbox Android client on a **headless
emulator**, starting from nothing running. There are two test levels:

- **Component UI tests** — `:android:connectedDebugAndroidTest` composes a single
  screen directly (no server, no enrollment). Needs only a booted emulator
  (Phase 1) + installed test APK (Phase 3). This is the level UC-18-style fixes
  use.
- **Full enrollment E2E** — the app talks to a real local server over mTLS.
  Needs Phases 1–4.

> **Do NOT use the live QR camera headless.** The emulator's virtual-scene back
> camera faces a non-overridable test-card; the `wall`/`table` posters aren't in
> the default view and there's no headless way to navigate the 3D scene
> (`-virtualscene-poster` requires RGB power-of-two images and still leaves the
> QR off-camera). Enrollment is driven by the on-device probe in **Phase 4**,
> which exercises the same production networking (`EnrollmentClient` →
> `POST /v1/enrollment` → SPKI pin) without a camera.

## One-time prerequisites (skip if already provisioned)

- Android SDK at `~/Android/Sdk` with `platform-tools`, `platforms;android-36`,
  `build-tools;36.0.0`, `emulator`, `system-images;android-36;google_apis;x86_64`;
  `local.properties` → `sdk.dir=~/Android/Sdk`; `/dev/kvm` accessible. (If absent,
  install via `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager` then accept
  licenses — this is the heavy one-time step.)
- Full JDK 21 (`javac`) on the host; `openssl`.
- Server + CLI jars at `/opt/ai-sandbox-server/lib/{aisandbox-server,aisandboxctl}.jar`
  (from the installed `.deb`) or built from `server/`.

## Environment (run once per shell)

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ADB=$ANDROID_HOME/platform-tools/adb
export EMU=$ANDROID_HOME/emulator/emulator
export AVDM=$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager
export SERVER_JAR=/opt/ai-sandbox-server/lib/aisandbox-server.jar
export CTL_JAR=/opt/ai-sandbox-server/lib/aisandboxctl.jar
export REPO=/home/potato-server/ai-sandbox
export E2E=/home/potato-server/ai-sandbox-e2e
export DEV=emulator-5554
mkdir -p "$E2E"/{pki,clients,enrollment,secrets,sessions,log}
```

## Phase 1 — Boot the AVD (from stopped)

```bash
# create the AVD only if missing
"$AVDM" list avd 2>/dev/null | grep -q ai_sandbox_test || \
  echo no | "$AVDM" create avd -n ai_sandbox_test \
    -k "system-images;android-36;google_apis;x86_64" -d pixel_6

# launch headless (KVM) only if not already running, then wait for full boot
pgrep -f "avd ai_sandbox_test" >/dev/null || \
  nohup "$EMU" -avd ai_sandbox_test -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -accel auto -port 5554 > "$E2E/emulator.log" 2>&1 &
"$ADB" wait-for-device
until [ "$("$ADB" -s "$DEV" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
echo "AVD booted: $DEV"
```

## Phase 2 — Stand up the local test server (from stopped, non-root, port 18443)

The production server (systemd, port 12410, root-owned `/etc`) is left untouched.
This is a throwaway user-owned instance.

```bash
# 2a. server cert+key with SANs covering every host the QR URL may use
[ -s "$E2E/pki/server.crt" ] || openssl req -x509 -newkey rsa:2048 -nodes -days 1825 \
  -keyout "$E2E/pki/server.key" -out "$E2E/pki/server.crt" -subj "/CN=ai-sandbox-server" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" \
  -addext "basicConstraints=critical,CA:FALSE" \
  -addext "keyUsage=critical,digitalSignature,keyEncipherment"
chmod 644 "$E2E/pki/server.crt"; chmod 600 "$E2E/pki/server.key"

# 2b. config.yaml (all paths user-owned; port 18443)
cat > "$E2E/config.yaml" <<YAML
ai-sandbox:
  server:
    tls: { port: 18443, bind-address: "0.0.0.0" }
    pki:        { dir: $E2E/pki }
    clients:    { dir: $E2E/clients }
    enrollment: { dir: $E2E/enrollment, default-ttl-minutes: 10, rate-limit-per-window: 5, rate-limit-window-seconds: 60 }
    hostscripts: { repo-root: $REPO }
    sessions:   { host-state-root: $E2E/sessions }
    secrets:    { dir: $E2E/secrets }
    audit:      { file: $E2E/log/audit.log, retention-days: 7 }
YAML

# 2c. launch — the -D audit override is REQUIRED (logback reads it at early init,
#     before the YAML applies; without it the server dies opening /var/log/...).
pgrep -f "aisandbox-server.jar.*ai-sandbox-e2e" >/dev/null || \
  nohup java -Xms256m -Xmx1g -Dfile.encoding=UTF-8 \
    -Dai-sandbox.server.audit.file="$E2E/log/audit.log" \
    -jar "$SERVER_JAR" --spring.config.additional-location=file:"$E2E/config.yaml" \
    > "$E2E/server.log" 2>&1 &
until grep -q "Netty started on port 18443" "$E2E/server.log" 2>/dev/null; do sleep 1; done
echo "test server up on 18443"
```

## Phase 3 — Build + install the app and test APKs

```bash
(cd "$REPO" && ./gradlew :android:assembleDebug :android:assembleDebugAndroidTest)
"$ADB" -s "$DEV" install -r "$REPO/android/build/outputs/apk/debug/android-debug.apk"
"$ADB" -s "$DEV" install -r "$REPO/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk"
```

## Phase 4 — Enroll the AVD against the server (proven, camera-free)

Issue a fresh single-use invite (token TTL ~10 min) and run the on-device probe,
which makes the real mTLS `POST /v1/enrollment` call from the emulator.

```bash
PAYLOAD=$(java -jar "$CTL_JAR" client invite probe-vm \
  --pki-dir "$E2E/pki" --enrollment-dir "$E2E/enrollment" \
  --server-url https://10.0.2.2:18443 --json)

"$ADB" -s "$DEV" shell "am instrument -w \
  -e class com.aisandbox.android.net.E2eEnrollmentProbeTest \
  -e qrPayload '$PAYLOAD' \
  com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner"
#   expect: OK (1 test)

# server-side confirmation:
grep -o 'client_enroll' "$E2E/log/audit.log" && ls "$E2E/clients/"
```

The payload is single-quoted so its JSON double-quotes survive the device shell;
the invite JSON contains no single-quote chars, so this is safe. The probe is
`android/src/androidTest/kotlin/com/aisandbox/android/net/E2eEnrollmentProbeTest.kt`.

## Phase 5 — (optional) seed a session so a card renders (REQUIRES DOCKER)

`GET /v1/sessions` enumerates real `ai-sandbox-N` `docker compose` projects; there
is no fake/seed hook. Without this the Sessions screen shows the empty-state.

```bash
AI_SANDBOX_HOST_STATE_ROOT="$E2E/sessions" "$REPO/spawn.sh"   # needs Docker + the sandbox image
```

## Phase 6 — Run the instrumented UI tests

```bash
# component-level Compose tests (no server/enrollment needed; emulator only):
(cd "$REPO" && ./gradlew :android:connectedDebugAndroidTest)
```

To target one class with a runtime arg, use the `am instrument -e class <FQN>` form
from Phase 4.

## Phase 7 — Teardown

```bash
"$ADB" -s "$DEV" emu kill 2>/dev/null                                   # stop emulator
for p in $(pgrep -x java); do grep -qa ai-sandbox-e2e /proc/$p/cmdline 2>/dev/null && kill "$p"; done   # stop test server
# rm -rf "$E2E"                                                         # discard test PKI/state (optional)
```

> Stop the test server by PID via `pgrep -x java` + a `/proc/<pid>/cmdline` match —
> NOT `pkill -f ai-sandbox-e2e`, which also matches the launching command's own
> shell and kills itself.

## Networking

`10.0.2.2:18443` (used above) is the emulator's built-in host alias — no per-boot
plumbing. Alternative: `adb reverse tcp:18443 tcp:18443` then use
`https://127.0.0.1:18443`. The cert SAN, the QR `u` host, and the pinned host must
agree; the Phase-2 cert covers `localhost`/`127.0.0.1`/`10.0.2.2` so either works.

## Troubleshooting

- **Server won't start, `audit.log (Permission denied)`** — you omitted
  `-Dai-sandbox.server.audit.file=…` (Phase 2c). The YAML key alone is too late.
- **`client invite` exits 2 ("server-url not in SAN")** — the `--server-url` host
  isn't in the cert SAN; regenerate the cert (Phase 2a) with that host.
- **Probe fails 401** — token expired (10-min TTL) or already redeemed; issue a fresh invite.
- **Probe fails 429** — per-IP rate limit; raise `rate-limit-per-window` or wait the window.
- **Emulator scene renders black** — a non-power-of-two / non-RGB camera poster; irrelevant if you use the camera-free Phase 4 (recommended).
- **No session cards** — expected without Docker; do Phase 5 or accept the empty-state.
