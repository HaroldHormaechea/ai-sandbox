---
name: android-testing
description: >-
  Run Android UI and end-to-end enrollment tests for the ai-sandbox Android
  client on this host's headless emulator. Use when asked to test the Android
  app, run instrumented Compose tests, verify enrollment, connect the emulator
  (AVD) to a local ai-sandbox management server, or run the UC-85 deterministic
  functional gate (and to (re)capture / refresh its replay fixtures). Covers the
  full path from a STOPPED AVD and STOPPED server to a verified on-device
  enrollment, then running the instrumented tests. Linear runbook — follow the
  phases in order.
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
- **Deterministic functional gate (UC-85)** — the LLM-free release gate: the real
  server under the `replay` profile + an on-device instrumented Compose suite. This
  is the level the `release` skill's Phase 1 gates on. See **§ Deterministic
  functional gate** below; it is one command and supersedes the old
  blind-tap-and-eyeball drive.

> **Do NOT use the live QR camera headless.** The emulator's virtual-scene back
> camera faces a non-overridable test-card; the `wall`/`table` posters aren't in
> the default view and there's no headless way to navigate the 3D scene
> (`-virtualscene-poster` requires RGB power-of-two images and still leaves the
> QR off-camera). Instead, enroll through the **UC-83 "read QR from file" path**
> (Phase 4): generate the invite as a QR **PNG**, hand it to the production
> `QrImageDecoder`, and let the same production parse+enroll seam
> (`onQrPayload` → `EnrollmentClient` → `POST /v1/enrollment` → SPKI pin) run —
> camera-free, but exercising the real decode path a user hits, not a side
> channel. This replaces the older `E2eEnrollmentProbeTest` workaround (which
> injected an already-decoded QR string and so never covered the image decode).

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

## Phase 4 — Enroll the AVD against the server (QR-from-file, camera-free)

Issue a fresh single-use invite (token TTL ~10 min), render it as a **QR PNG**,
and run the UC-83 file-enrollment instrumented test. The test decodes the PNG
through the **production** `QrImageDecoder` and feeds the result into the same
production seam the "Read QR from file" button uses
(`OnboardingViewModel.onQrPayload` → `EnrollmentClient` → mTLS
`POST /v1/enrollment` → SPKI pin). This is the documented headless-enrollment
route: it covers the real image-decode + parse + enroll path end-to-end, no
camera and no picker UI.

```bash
# 4a. fresh single-use invite as raw JSON payload
PAYLOAD=$(java -jar "$CTL_JAR" client invite probe-vm \
  --pki-dir "$E2E/pki" --enrollment-dir "$E2E/enrollment" \
  --server-url https://10.0.2.2:18443 --json)

# 4b. render the payload to a QR PNG. Use a ZXing-based generator (do NOT assume
#     `qrencode` is installed). The one-liner below drives the same ZXing core
#     jar the app already depends on — adjust the jar path to your Gradle cache,
#     or use any ZXing QRCodeWriter snippet. It writes invite-qr.png.
ZXING_JAR=$(find ~/.gradle/caches -name 'core-3.5.4.jar' | head -1)
cat > /tmp/QrGen.java <<'JAVA'
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
public class QrGen {
  public static void main(String[] a) throws Exception {
    String payload = new String(Files.readAllBytes(new File(a[0]).toPath())).trim();
    BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 600, 600);
    BufferedImage img = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++)
      img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
    ImageIO.write(img, "png", new File(a[1]));
  }
}
JAVA
printf '%s' "$PAYLOAD" > /tmp/invite.json
(cd /tmp && "$JAVA_HOME/bin/javac" -cp "$ZXING_JAR" QrGen.java \
  && "$JAVA_HOME/bin/java" -cp ".:$ZXING_JAR" QrGen /tmp/invite.json /tmp/invite-qr.png)

# 4c. push the PNG to the device and run the file-enrollment test, which decodes
#     it via the production QrImageDecoder and enrolls through onQrPayload.
"$ADB" -s "$DEV" push /tmp/invite-qr.png /sdcard/Download/invite-qr.png
"$ADB" -s "$DEV" shell "am instrument -w \
  -e class com.aisandbox.android.net.E2eQrFileEnrollmentTest \
  -e qrImagePath /sdcard/Download/invite-qr.png \
  com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner"
#   expect: OK (1 test)

# server-side confirmation:
grep -o 'client_enroll' "$E2E/log/audit.log" && ls "$E2E/clients/"
```

The test is
`android/src/androidTest/kotlin/com/aisandbox/android/net/E2eQrFileEnrollmentTest.kt`.
It reads the pushed PNG, runs it through the production `QrImageDecoder`
(`decodeInviteFromUri` / `decodeInviteCandidates`), and drives
`OnboardingViewModel.onQrImageSelected` → `onQrPayload` so the assertion covers
the exact decode+enroll path a user exercises with the "Read QR from file"
button — not a pre-decoded string injected past the decoder.

> Alternatively, bundle a known invite-QR PNG as an **androidTest asset** under
> `android/src/androidTest/assets/` and have the test read it from the test APK
> instead of `adb push`; use that when the invite is fixed/recorded rather than
> freshly minted per run.

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

## Deterministic functional gate (UC-85)

The release functional gate (the `release` skill's Phase 1) is **deterministic and
LLM-free**. It runs the REAL server under a new Spring profile, `replay`, whose only
behavioural changes are: (a) the conversation transcript SOURCE is a committed fixture
file instead of the docker `aisandbox-conversation-tail` helper; (b) a synthetic
sessions list so the device has a card to open; (c) the device's answer is recorded and
echoed back (an `answer-echo` frame) instead of injected into a (nonexistent) tmux pane.
Everything else — mTLS, UC-83 enrollment, the WebSocket, the `ConversationEventMapper`,
the whole Compose stack — stays the real production path. The driver is an on-device
instrumented Compose suite that interacts by stable `testTag` only (defined in
`android/.../ui/testtags/GateTestTags.kt`) and asserts programmatically — **no
`adb input tap` coordinates, no screenshot eyeballing.** This supersedes the old
blind-tap-and-eyeball drive, which was non-deterministic and chronically blocked by
launcher/SystemUI ANRs.

### Run the gate

One command (from a STOPPED state — it builds, boots, enrolls, runs, tears down):

```bash
JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/android-sdk ./android/gate.sh
```

Exit 0 = gate passed. Knobs (all optional): `GATE_TEST_PACKAGE` (instrumented suite
package, default `com.aisandbox.android.gate`), `GATE_ENROLL_CLASS`, `GATE_PORT`,
`GATE_AVD`, `GATE_KEEP=1` (skip teardown for debugging). The same flow runs in CI via
the `android-gate` workflow; its `/dev/kvm` precondition is proven by the
`android-gate-smoke` job.

### What the fixtures exercise

`fixtures/replay/manifest.json` maps each synthetic session `n` to a `*.tail` fixture:
single-select / multi-select / "Other" free-text questions (UC-55/75), a multi-question
sheet (UC-43), and a conversation transcript with teammate/subagent bubbles (UC-58),
a long uncropped message (UC-80), and long-press copy (UC-81). The gate asserts the
**selected** option is the one transmitted (UC-57) and the per-question mapping (UC-43)
by observing the server's `answer-echo` frame, not the UI state.

### Scope limitation — UC-79 load-older under replay

Lazy scroll-up paging *older than the backfilled window* (UC-79 `load-older`) is **NOT
exercised under `replay`**: the server's older-page fetch (`TranscriptTailService
.fetchPageLines`) shells the docker helper's `--fetch-page`, and there is no docker in a
replay run, so it returns an empty older page. The gate therefore asserts anchor-to-bottom
(UC-78) and scroll *within* the backfilled window only. A change that specifically touches
the `load-older`/paging path needs a targeted check (or a live spot-check) on top of the gate.

## Re-capturing / refreshing replay fixtures (UC-85)

Fixtures are recorded protocol-frame streams; they can drift from the evolving real
protocol, so an occasional real-Claude re-capture keeps a green gate meaningful (the
boot-time `ReplayFixtureValidator` catches schema/version drift and malformed lines, but
not semantic staleness).

**Fixture format.** Each `*.tail` line is one helper envelope: either a transcript line
`<source>\t<raw-json>` (e.g. `main\t{"type":"assistant",…}`), a helper control line
`__ctrl__\t<kind>[\t<extra>]` (`backfill-start\t<idx>`, `backfill-end`, `pending-question\t<json>`,
…), or the replay-only directive `__replay__\tawait-answer` (consumed by the replay reader:
the tail parks there until the device's answer is recorded, then replays the recorded
post-answer frames). `manifest.json` carries `schemaVersion` (must equal
`ReplayFixtureValidator.SCHEMA_VERSION`) and a `scenarios[]` of `{n, target, title, fixture}`.

**To (re)capture from a real session** (the source of truth for the envelope bytes):

1. Run a real ai-sandbox session and open its conversation WebSocket the way the app
   does (or instrument the in-container helper directly). The helper that produces the
   envelope stream is `container-bin/aisandbox-conversation-tail`; its stdout IS the
   `<source>\t<raw-json>` / `__ctrl__\t…` line format the fixtures store.
2. Capture the helper's stdout for the scenario you want (raise the AskUserQuestion /
   drive the conversation), keeping the `backfill-start … backfill-end` window and the
   transcript lines verbatim.
3. Insert a single `__replay__\tawait-answer` line at the point the session blocked on the
   answer, and ensure at least one post-answer `turn-end` (a `system`/`turn_duration` line)
   follows it — the validator requires this so the UC-75 spinner / answer-watchdog clears.
4. Save as `fixtures/replay/<scenario>.tail`, add/update its `manifest.json` entry, and
   bump `schemaVersion` **in both** `manifest.json` and `ReplayFixtureValidator.SCHEMA_VERSION`
   if the line format itself changed (drift detection, AC-2).
5. Re-run `./android/gate.sh` — the catalog validates every fixture at boot and fails LOUD
   on any malformed line, schema mismatch, or a question fixture missing its await-gate /
   post-answer turn-end.

The committed fixtures are hand-authored against the documented format
(`server/CONVERSATION_PROTOCOL.md`) and the `ConversationEventMapper` contract; a real
re-capture is the higher-fidelity refresh when the protocol evolves.
